package com.narayan.mapidex.companion

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Mapidex Ask — what is the wearer looking at?
 *
 * Cascade per Capture (POST /capture) or re-ask (POST /ask):
 *   1. Gemini Nano on-device (ML Kit GenAI Prompt API → AICore), image with gaze DOT + GPS lat/lng.
 *   2. Web enrich (no key): OSM Overpass named places ≤120 m, Nominatim street, Wikipedia geosearch/summary.
 *      A Nano title that matches a nearby name/article is "confirmed" and gets a wiki layer.
 *   3. Gemini API cloud (multimodal) when Nano failed / low confidence / generic / unconfirmed —
 *      gets image + dot + GPS + Nano's guess + web hints. Skipped (needs_gemini_key=true) without a key.
 * Results: GET /ask.json (Playdate card) — schema in README. Verify (pass/fail) is the same pipe with a
 * different prompt: POST /verify {task} → GET /verify.json.
 */
object AskEngine {
    private const val NANO_MAX_SIDE = 1024
    private const val CLOUD_MAX_SIDE = 1600
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var askJob: Job? = null
    private var verifyJob: Job? = null

    private var nano: GenerativeModel? = null

    /** available | downloadable | downloading | unavailable | error: … | unknown — shown in /ask.json + app. */
    var nanoStatus by mutableStateOf("unknown")
        private set
    var uiLine by mutableStateOf("no capture yet")
        private set

    @Volatile private var askState: JSONObject = idle("ask")
    @Volatile private var verifyState: JSONObject = idle("verify")

    fun askJson(): String = askState.toString(2)
    fun verifyJson(): String = verifyState.toString(2)

    /** Called on service start: check AICore, kick off the Nano download if needed. */
    fun prepare() {
        scope.launch {
            try {
                val m = nano ?: Generation.getClient().also { nano = it }
                when (m.checkStatus()) {
                    FeatureStatus.AVAILABLE -> { nanoStatus = "available"; runCatching { m.warmup() } }
                    FeatureStatus.DOWNLOADING -> nanoStatus = "downloading"
                    FeatureStatus.UNAVAILABLE -> nanoStatus = "unavailable"
                    FeatureStatus.DOWNLOADABLE -> {
                        nanoStatus = "downloading"
                        m.download().collect { s ->
                            when (s) {
                                is DownloadStatus.DownloadCompleted -> nanoStatus = "available"
                                is DownloadStatus.DownloadFailed -> nanoStatus = "error: download failed — ${s.e.message}"
                                else -> {}
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                nanoStatus = "error: ${describe(e)}"
            }
        }
    }

    // ---------------------------------------------------------------- entry points

    fun ask(cap: CaptureStore.Capture, question: String?) {
        askJob?.cancel()
        askState = thinking(askState, cap, question)
        uiLine = "Ask: thinking…"
        askJob = scope.launch { askState = runCascade(cap, Mode.Ask(question)).also { uiLine = "Ask: ${it.optString("title")} (${it.optString("source")})"; History.logAsk(it) } }
    }

    fun verify(cap: CaptureStore.Capture, task: String) {
        verifyJob?.cancel()
        verifyState = thinking(verifyState, cap, task)
        verifyJob = scope.launch { verifyState = runCascade(cap, Mode.Verify(task)) }
    }

    /** For ?wait=1 callers: block until the current job finishes (or timeout). */
    fun awaitAsk(ms: Long) = runBlocking { withTimeoutOrNull(ms) { askJob?.join() } }
    fun awaitVerify(ms: Long) = runBlocking { withTimeoutOrNull(ms) { verifyJob?.join() } }

    // ---------------------------------------------------------------- cascade

    private sealed class Mode {
        data class Ask(val question: String?) : Mode()
        data class Verify(val task: String) : Mode()
    }

    private data class Answer(
        val title: String, val blurb: String, val confidence: String,
        val verdict: String? = null, val raw: String = "",
    )

    private suspend fun runCascade(cap: CaptureStore.Capture, mode: Mode): JSONObject {
        val steps = JSONArray()
        val (lat, lng, hasFix) = location(cap)

        // 2 starts in parallel with 1 — it only needs GPS.
        val webJob = if (hasFix) scope.async { runCatching { WebEnrich.lookup(lat, lng) }.getOrNull() } else null

        // 1. Nano
        var nanoErr: String? = null
        val nanoAns: Answer? = try {
            val bmp = CaptureStore.loadForModel(cap.marked, NANO_MAX_SIDE) ?: throw IllegalStateException("could not decode capture")
            val prompt = prompt(mode, cap, bmp, lat, lng, hasFix, null, null)
            val raw = withTimeout(35_000) { nanoGenerate(bmp, prompt) }
            bmp.recycle()
            parse(raw, mode).also { steps.put(step("nano", true, "${it.confidence} · ${it.title}")) }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            nanoErr = describe(e)
            steps.put(step("nano", false, nanoErr))
            null
        }

        // 2. Web
        val web = webJob?.let { withTimeoutOrNull(18_000) { it.await() } }
        if (!hasFix) steps.put(step("web", false, "no GPS fix"))
        else if (web == null || web.isEmpty) steps.put(step("web", false, "no web context (offline / lookup failed)"))
        else steps.put(step("web", true, "${web.nearby.size} places · ${web.wiki.size} wiki"))

        var answer = nanoAns
        var source = if (nanoAns != null) "nano" else null
        var confirmed: Pair<String, WebEnrich.Wiki?>? = null
        if (mode is Mode.Ask && nanoAns != null && web != null) {
            confirmed = WebEnrich.match(nanoAns.title, web)
            if (confirmed != null) {
                source = "nano+web"
                answer = nanoAns.copy(confidence = if (nanoAns.confidence == "low") "medium" else nanoAns.confidence)
            }
        }

        // 3. Gemini cloud fallback
        val thin = answer == null || answer.confidence == "low" || isGeneric(answer.title) ||
            (mode is Mode.Ask && confirmed == null && answer.confidence != "high")
        var needsKey = false
        var cloudErr: String? = null
        if (thin) {
            if (!GeminiCloud.hasKey) {
                needsKey = true
                steps.put(step("gemini", false, "skipped — needs_gemini_key (add GEMINI_API_KEY to companion/local.properties)"))
            } else try {
                val bmp = CaptureStore.loadForModel(cap.marked, CLOUD_MAX_SIDE) ?: throw IllegalStateException("could not decode capture")
                val prompt = prompt(mode, cap, bmp, lat, lng, hasFix, nanoAns, web)
                val raw = GeminiCloud.generate(bmp, prompt)
                bmp.recycle()
                val g = parse(raw, mode)
                steps.put(step("gemini", true, "${GeminiCloud.model} · ${g.confidence} · ${g.title}"))
                // Prefer cloud unless it's worse than what we have.
                if (answer == null || rank(g.confidence) >= rank(answer.confidence) || isGeneric(answer.title)) {
                    answer = g; source = "gemini"
                    if (mode is Mode.Ask && web != null) WebEnrich.match(g.title, web)?.let { confirmed = it; source = "gemini+web" }
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                cloudErr = describe(e)
                steps.put(step("gemini", false, cloudErr))
            }
        } else steps.put(step("gemini", true, "not needed"))

        // Build ask.json / verify.json
        val now = Instant.now().toString()
        val out = JSONObject()
            .put("kind", if (mode is Mode.Ask) "ask" else "verify")
            .put("capture_id", cap.id)
            .put("gaze", gazeJson(cap))
            .put("location", JSONObject().put("lat", if (hasFix) lat else JSONObject.NULL).put("lng", if (hasFix) lng else JSONObject.NULL).put("has_fix", hasFix))
            .put("question", when (mode) { is Mode.Ask -> mode.question ?: JSONObject.NULL; is Mode.Verify -> mode.task })
            .put("nano_status", nanoStatus)
            .put("needs_gemini_key", needsKey)
            .put("cascade", steps)
            .put("updated_at", now)

        if (answer == null) {
            val err = listOfNotNull(nanoErr?.let { "Nano: $it" }, cloudErr?.let { "Gemini: $it" },
                if (needsKey) "Gemini: no API key (needs_gemini_key)" else null).joinToString(" | ")
            return out.put("status", "error").put("title", "Can't tell yet").put("blurb", err.take(160))
                .put("error", err).put("source", "none").put("confidence", "none")
                .put("layers", JSONArray().put(layer("error", err.take(200))))
        }

        val layers = JSONArray()
        if (mode is Mode.Verify) layers.put(layer("verdict", answer.verdict ?: "UNSURE"))
        layers.put(layer("what", answer.title)).put(layer("about", answer.blurb))
        web?.let { w ->
            val place = confirmed?.first ?: w.nearby.firstOrNull()?.let { "${it.name} (${it.distM} m)" } ?: w.address
            place?.let { layers.put(layer("place", it)) }
            confirmed?.second?.let { wk -> WebEnrich.wikiSummary(wk.title)?.let { layers.put(layer("wiki", it.take(240))) } }
        }
        out.put("status", "ok").put("title", answer.title).put("blurb", answer.blurb)
            .put("confidence", answer.confidence).put("source", source ?: "nano")
            .put("layers", layers).put("error", JSONObject.NULL)
        if (mode is Mode.Verify) out.put("pass", answer.verdict == "PASS").put("verdict", answer.verdict ?: "UNSURE")
        return out
    }

    private suspend fun nanoGenerate(bmp: Bitmap, prompt: String): String {
        val m = nano ?: Generation.getClient().also { nano = it }
        when (m.checkStatus()) {
            FeatureStatus.AVAILABLE -> nanoStatus = "available"
            FeatureStatus.DOWNLOADABLE -> { prepare(); throw IllegalStateException("Gemini Nano not downloaded yet — download started, retry in a few minutes") }
            FeatureStatus.DOWNLOADING -> { nanoStatus = "downloading"; throw IllegalStateException("Gemini Nano still downloading") }
            else -> { nanoStatus = "unavailable"; throw IllegalStateException("Gemini Nano unavailable on this device (AICore)") }
        }
        val res = m.generateContent(generateContentRequest(ImagePart(bmp), TextPart(prompt)) {
            temperature = 0.2f
            topK = 16
            maxOutputTokens = 160
        })
        return res.candidates.firstOrNull()?.text?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("Nano returned no text")
    }

    // ---------------------------------------------------------------- prompt + parse

    private fun prompt(
        mode: Mode, cap: CaptureStore.Capture, img: Bitmap,
        lat: Double, lng: Double, hasFix: Boolean,
        nanoGuess: Answer?, web: WebEnrich.Context?,
    ): String = buildString {
        val sx = img.width.toFloat() / cap.w; val sy = img.height.toFloat() / cap.h
        append("You are Mapidex, a Pokédex for the real world. This photo is from the wearer's smart glasses. ")
        if (cap.gx != null && cap.gy != null) {
            val x = (cap.gx * sx).toInt(); val y = (cap.gy * sy).toInt()
            val px = (cap.gx * 100 / cap.w).toInt(); val py = (cap.gy * 100 / cap.h).toInt()
            append("The wearer was looking EXACTLY at the small high-contrast dot (black center, white rim) at pixel ($x, $y) ")
            append("of this ${img.width}x${img.height} image — about $px% from the left and $py% from the top. ")
            append("Identify ONLY the thing directly under that dot (the specific object, sign, building, artwork, plant or place it sits on). ")
            append("Ignore the rest of the image. Never describe the dot itself.\n")
        } else {
            append("No gaze point was recorded; identify the main subject in the centre of the image.\n")
        }
        if (hasFix) append("GPS of the wearer: lat %.6f, lng %.6f. If the dot is on a building, storefront, sign or landmark, use this location to give its specific real name.\n".format(lat, lng))
        web?.takeUnless { it.isEmpty }?.let { append("Web context for this location (hints, may not be what the dot is on):\n${it.hint()}\n") }
        nanoGuess?.let { append("An on-device model guessed: \"${it.title}\" (${it.confidence}). Correct it if wrong.\n") }
        when (mode) {
            is Mode.Ask -> {
                mode.question?.takeIf { it.isNotBlank() }?.let { append("The wearer asks about the dotted thing: \"$it\". Answer that in the BLURB.\n") }
                append("Reply in exactly this format, nothing else:\n")
                append("TITLE: <2-5 words naming the thing under the dot>\n")
                append("BLURB: <one sentence, max 25 words, a Pokédex-style fact about it>\n")
                append("CONFIDENCE: <high|medium|low>")
            }
            is Mode.Verify -> {
                append("Task to verify: \"${mode.task}\". Judge from the photo, focusing on what is under the dot, whether the task is done.\n")
                append("Reply in exactly this format, nothing else:\n")
                append("VERDICT: <PASS|FAIL>\n")
                append("TITLE: <2-5 words naming what is under the dot>\n")
                append("BLURB: <one short sentence on why>\n")
                append("CONFIDENCE: <high|medium|low>")
            }
        }
    }

    private fun parse(raw: String, mode: Mode): Answer {
        fun field(name: String) = Regex("(?im)^\\s*\\**$name\\**\\s*[:：]\\s*(.+)$").find(raw)?.groupValues?.get(1)?.trim()?.trim('*', '"', ' ')
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val title = (field("TITLE") ?: lines.firstOrNull() ?: "").take(48)
        val blurb = (field("BLURB") ?: lines.drop(1).joinToString(" ")).take(200)
        val conf = field("CONFIDENCE")?.lowercase()?.let { c -> listOf("high", "medium", "low").firstOrNull { c.contains(it) } } ?: "medium"
        val verdict = if (mode is Mode.Verify) field("VERDICT")?.uppercase()?.let { if (it.contains("PASS")) "PASS" else if (it.contains("FAIL")) "FAIL" else null } else null
        return Answer(title, blurb, conf, verdict, raw)
    }

    private val GENERIC = setOf("", "unknown", "object", "thing", "image", "photo", "dot", "black dot", "white dot", "n a", "none", "something", "unclear", "blurry image")
    private fun isGeneric(t: String) = t.lowercase().replace(Regex("[^a-z ]"), " ").trim().let { it.length < 3 || it in GENERIC }
    private fun rank(c: String) = when (c) { "high" -> 2; "medium" -> 1; else -> 0 }

    // ---------------------------------------------------------------- helpers

    private fun location(cap: CaptureStore.Capture): Triple<Double, Double, Boolean> =
        if (cap.lat != null && cap.lng != null) Triple(cap.lat, cap.lng, true)
        else Triple(FeedState.lat, FeedState.lng, FeedState.hasFix)

    private fun describe(e: Throwable): String = when (e) {
        is GenAiException -> when (e.errorCode) {
            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> "AICore blocks Nano while the companion is in the background — keep the app open on screen"
            GenAiException.ErrorCode.NOT_AVAILABLE, GenAiException.ErrorCode.NOT_SUPPORTED -> "Gemini Nano not available (AICore): ${e.message}"
            GenAiException.ErrorCode.BUSY -> "AICore busy, retry"
            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> "AICore per-app quota exceeded, wait and retry"
            GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> "AICore needs a system/Play update"
            else -> "AICore error ${e.errorCode}: ${e.message}"
        }
        is kotlinx.coroutines.TimeoutCancellationException -> "timed out"
        is IllegalStateException -> e.message ?: "error"
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }.take(220)

    private fun step(name: String, ok: Boolean, note: String?) = JSONObject().put("step", name).put("ok", ok).put("note", note ?: "")
    private fun layer(name: String, text: String) = JSONObject().put("name", name).put("text", text)
    private fun gazeJson(cap: CaptureStore.Capture) = JSONObject()
        .put("x", cap.gx?.toDouble() ?: JSONObject.NULL).put("y", cap.gy?.toDouble() ?: JSONObject.NULL)
        .put("w", cap.w).put("h", cap.h)

    private fun idle(kind: String) = JSONObject()
        .put("kind", kind).put("status", "idle").put("title", "").put("blurb", "")
        .put("gaze", JSONObject().put("x", JSONObject.NULL).put("y", JSONObject.NULL))
        .put("layers", JSONArray()).put("needs_gemini_key", !GeminiCloud.hasKey)
        .put("updated_at", Instant.now().toString())

    /** Keep the previous answer visible while the new one is computed. */
    private fun thinking(prev: JSONObject, cap: CaptureStore.Capture, q: String?) = JSONObject(prev.toString())
        .put("status", "thinking").put("capture_id", cap.id).put("gaze", gazeJson(cap))
        .put("question", q ?: JSONObject.NULL).put("updated_at", Instant.now().toString())
}
