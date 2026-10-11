package com.narayan.mapidex.companion

import android.util.Base64
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/**
 * Tiny LAN server (port 8080).
 *   GET  /feed.json              route/places feed (unchanged)
 *   GET  /health                 {"ok":true,...}
 *   POST /capture                Neon frame + gaze → stored → Ask cascade starts (returns immediately; ?wait=1 blocks ≤40 s)
 *   POST /ask                    re-run Ask on the last capture, optional question (form/JSON "question" or ?q=)
 *   GET  /ask.json               Playdate Ask card
 *   POST /verify                 task verify on the last capture: "task" (form/JSON/?task=) → GET /verify.json
 *   GET  /capture/last.png       last original;  /capture/last_marked.png = model copy with gaze dot
 *   GET  /capture/last_gaze.json
 *   GET  /captures               archive list, newest first (mapidéx)
 *   GET  /capture/latest.json|png  newest archived capture (mapidéx; png = unmarked original)
 *
 * /capture accepts either
 *   multipart/form-data: image=<png/jpeg file>, gaze_x, gaze_y, [scene_w, scene_h, worn, ts, lat, lng, question, meta]
 *   application/json:    {"image_b64": "...", "gaze_x":…, "gaze_y":…, …same optional fields}
 */
class FeedServer(private val capturesDir: File, port: Int = PORT) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val res = try {
            route(session)
        } catch (e: Exception) {
            Log.e(TAG, "serve ${session.uri}", e)
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("ok", false).put("error", "${e.javaClass.simpleName}: ${e.message}").toString())
        }
        res.addHeader("Access-Control-Allow-Origin", "*")
        res.addHeader("Cache-Control", "no-store")
        return res
    }

    private fun route(s: IHTTPSession): Response {
        val post = s.method == Method.POST
        return when (s.uri) {
            "/feed.json" -> json(FeedState.feedJson())
            "/health" -> json(
                JSONObject().put("ok", true).put("version", FeedState.VERSION)
                    .put("nano_status", AskEngine.nanoStatus).put("gemini_key", GeminiCloud.hasKey).toString()
            )
            "/ask.json" -> json(AskEngine.askJson())
            "/verify.json" -> json(AskEngine.verifyJson())
            "/capture" -> if (post) capture(s) else json(Response.Status.METHOD_NOT_ALLOWED, err("POST multipart image + gaze_x/gaze_y"))
            "/ask" -> if (post) reAsk(s) else json(AskEngine.askJson())
            "/verify" -> if (post) verify(s) else json(AskEngine.verifyJson())
            "/capture/last.png" -> file(CaptureStore.last?.png, "image/png")
            "/capture/last_marked.png" -> file(CaptureStore.last?.marked, "image/png")
            "/capture/last_gaze.json" -> file(File(capturesDir, "last_gaze.json"), "application/json")
            "/captures" -> json(listCaptures().toString())
            "/capture/latest.json" -> latest("json")
            "/capture/latest.png" -> latest("png")
            "/" -> newFixedLengthResponse(
                Response.Status.OK, "text/plain",
                "Mapidex companion v${FeedState.VERSION}\nGET /feed.json\nGET /health\nPOST /capture\nPOST /ask\nGET /ask.json\nPOST /verify\nGET /verify.json\n"
            )
            else -> json(Response.Status.NOT_FOUND, err("not found"))
        }
    }

    /** Parsed body: form fields + optional image bytes. */
    private class Body(val fields: Map<String, String>, val image: ByteArray?)

    private fun readBody(s: IHTTPSession): Body {
        val files = HashMap<String, String>()
        s.parseBody(files)
        val fields = HashMap<String, String>()
        s.parameters.forEach { (k, v) -> v.firstOrNull()?.let { fields[k] = it } }
        var image: ByteArray? = files["image"]?.let { File(it).readBytes() }
        val ct = s.headers["content-type"].orEmpty()
        files["postData"]?.takeIf { ct.contains("json") }?.let { raw ->
            val j = JSONObject(raw)
            j.keys().forEach { k -> if (k != "image_b64" && !j.isNull(k)) fields[k] = j.get(k).toString() }
            j.optString("image_b64").takeIf { it.isNotBlank() }?.let {
                image = Base64.decode(it.substringAfter("base64,"), Base64.DEFAULT)
            }
        }
        return Body(fields, image)
    }

    private fun capture(s: IHTTPSession): Response {
        val b = readBody(s)
        val img = b.image ?: return json(Response.Status.BAD_REQUEST, err("missing image (multipart field 'image' or JSON 'image_b64')"))
        val f = b.fields
        fun num(k: String) = f[k]?.trim()?.takeIf { it.isNotEmpty() && it != "null" && it != "None" }?.toDoubleOrNull()
        val cap = try {
            CaptureStore.save(
                img,
                num("gaze_x")?.toFloat(), num("gaze_y")?.toFloat(),
                num("scene_w")?.toInt(), num("scene_h")?.toInt(),
                f["worn"]?.lowercase()?.let { it == "true" || it == "1" },
                f["ts"], num("lat"), num("lng") ?: num("lon"),
                f["question"]?.ifBlank { null },
                f["meta"],
            )
        } catch (e: IllegalArgumentException) {
            return json(Response.Status.BAD_REQUEST, err(e.message ?: "bad image"))
        }
        Log.i(TAG, "capture ${cap.id} ${cap.w}x${cap.h} gaze=(${cap.gx},${cap.gy}) worn=${cap.worn}")
        if (f["ask"] != "0") AskEngine.ask(cap, cap.question)
        if (s.parameters["wait"]?.firstOrNull() == "1") AskEngine.awaitAsk(40_000)
        return json(
            JSONObject().put("ok", true).put("id", cap.id).put("capture_id", cap.id)
                .put("gaze_x", cap.gx?.toDouble() ?: JSONObject.NULL).put("gaze_y", cap.gy?.toDouble() ?: JSONObject.NULL)
                .put("worn", cap.worn ?: JSONObject.NULL)
                .put("gaze", JSONObject().put("x", cap.gx?.toDouble() ?: JSONObject.NULL).put("y", cap.gy?.toDouble() ?: JSONObject.NULL))
                .put("size", JSONObject().put("w", cap.w).put("h", cap.h))
                .put("ask", JSONObject(AskEngine.askJson()))
                .toString()
        )
    }

    private fun reAsk(s: IHTTPSession): Response {
        val b = readBody(s)
        val cap = CaptureStore.last ?: return json(Response.Status.CONFLICT, err("no capture yet"))
        AskEngine.ask(cap, (b.fields["question"] ?: b.fields["q"])?.ifBlank { null })
        if (b.fields["wait"] == "1") AskEngine.awaitAsk(40_000)
        return json(AskEngine.askJson())
    }

    private fun verify(s: IHTTPSession): Response {
        val b = readBody(s)
        val cap = CaptureStore.last ?: return json(Response.Status.CONFLICT, err("no capture yet"))
        val task = b.fields["task"]?.ifBlank { null } ?: return json(Response.Status.BAD_REQUEST, err("missing 'task'"))
        AskEngine.verify(cap, task)
        if (b.fields["wait"] == "1") AskEngine.awaitVerify(40_000)
        return json(AskEngine.verifyJson())
    }

    private fun jsonFiles() = (capturesDir.listFiles { f -> f.name.startsWith("cap-") && f.name.endsWith(".json") }
        ?: emptyArray()).sortedByDescending { it.name }

    private fun listCaptures() = JSONArray().apply {
        jsonFiles().take(50).forEach { f -> runCatching { put(JSONObject(f.readText()).apply { remove("source") }) } }
    }

    private fun latest(kind: String): Response {
        val j = jsonFiles().firstOrNull() ?: return json(Response.Status.NOT_FOUND, err("no captures"))
        if (kind == "json") return json(j.readText())
        return file(File(j.parent, j.nameWithoutExtension + ".png"), "image/png")
    }

    private fun file(f: File?, mime: String): Response =
        if (f == null || !f.exists()) json(Response.Status.NOT_FOUND, err("no capture yet"))
        else newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(f), f.length())

    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg).toString()
    private fun json(body: String) = json(Response.Status.OK, body)
    private fun json(status: Response.Status, body: String) = newFixedLengthResponse(status, "application/json", body)

    companion object {
        const val PORT = 8080
        const val TAG = "MapidexCapture"
    }
}
