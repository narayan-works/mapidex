package com.narayan.mapidex.companion

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ask cascade step 3 — Gemini API (Generative Language REST, multimodal image+text) cloud fallback.
 * Key: GEMINI_API_KEY in companion/local.properties (git-ignored) → BuildConfig. Missing key = step skipped.
 */
object GeminiCloud {
    private const val DEFAULT_MODEL = "gemini-flash-latest"
    // Read via reflection so a stale/partial BuildConfig never breaks compilation.
    private fun cfg(name: String): String? =
        runCatching { BuildConfig::class.java.getField(name).get(null) as? String }.getOrNull()?.takeIf { it.isNotBlank() }
    val key: String get() = cfg("GEMINI_API_KEY") ?: ""
    val model: String get() = cfg("GEMINI_MODEL") ?: DEFAULT_MODEL
    val hasKey get() = key.isNotBlank()

    /** Returns raw model text. Throws on HTTP / parse failure. */
    fun generate(image: Bitmap, prompt: String): String {
        val jpeg = ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 88, it); it.toByteArray() }
        val parts = JSONArray()
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
            .put(JSONObject().put("text", prompt))
        fun body(thinking: Boolean): String {
            val gen = JSONObject().put("temperature", 0.2).put("maxOutputTokens", 400)
            if (thinking) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
            return JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", gen)
                .toString()
        }
        val (code, text) = post(body(thinking = true))
        // Older/other models may reject thinkingLevel — retry once plain.
        val (c2, t2) = if (code == 400) post(body(thinking = false)) else code to text
        if (c2 !in 200..299) throw IllegalStateException("Gemini HTTP $c2: ${t2.take(240)}")
        val cand = JSONObject(t2).optJSONArray("candidates")?.optJSONObject(0)
            ?: throw IllegalStateException("Gemini: no candidates (${t2.take(200)})")
        val ps = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val out = (0 until ps.length()).mapNotNull { i ->
            ps.optJSONObject(i)?.takeUnless { it.optBoolean("thought") }?.optString("text")?.ifBlank { null }
        }.joinToString("\n")
        if (out.isBlank()) throw IllegalStateException("Gemini: empty text (finish=${cand.optString("finishReason")})")
        return out
    }

    private fun post(body: String): Pair<Int, String> {
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6000; c.readTimeout = 30000
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val s = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return code to s
        } finally { c.disconnect() }
    }
}
