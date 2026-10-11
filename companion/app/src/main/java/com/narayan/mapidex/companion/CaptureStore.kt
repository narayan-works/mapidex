package com.narayan.mapidex.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Last Capture from the Neon glasses (pushed by the Mac Neon bridge, /tmp/neon-web/server.py → POST /capture).
 *
 * Files under <app files>/captures/:
 *   last.png          hi-res scene frame exactly as received (unmarked original)
 *   last_marked.png   copy with a high-contrast gaze DOT (white rim, black core) — this is what the model sees
 *   last_gaze.json    {x, y, worn, ts, scene_w, scene_h, lat, lng, question, received_at, meta}
 *   cap-<ms>.png/.json  archive of every capture (mapidéx's original /capture format; GET /captures lists them)
 * Gaze x/y are scene-camera pixels, origin top-left (Neon convention). If scene_w differs from the
 * PNG width, x/y are rescaled to the PNG.
 */
object CaptureStore {
    private lateinit var dir: File

    data class Capture(
        val id: String,
        val png: File,
        val marked: File,
        val gx: Float?,
        val gy: Float?,
        val w: Int,
        val h: Int,
        val worn: Boolean?,
        val lat: Double?,
        val lng: Double?,
        val question: String?,
        val receivedAt: String,
    )

    @Volatile var last: Capture? = null
        private set

    val dirOrNull: File? get() = if (::dir.isInitialized) dir else null

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "captures").apply { mkdirs() }
        // Restore the last capture after a service restart.
        runCatching {
            val j = JSONObject(File(dir, "last_gaze.json").readText())
            val png = File(dir, "last.png"); val marked = File(dir, "last_marked.png")
            if (png.exists() && marked.exists()) last = Capture(
                j.optString("id", "restored"), png, marked,
                j.optDouble("x").takeUnless { it.isNaN() }?.toFloat(),
                j.optDouble("y").takeUnless { it.isNaN() }?.toFloat(),
                j.optInt("w"), j.optInt("h"),
                if (j.has("worn") && !j.isNull("worn")) j.optBoolean("worn") else null,
                j.optDouble("lat").takeUnless { it.isNaN() },
                j.optDouble("lng").takeUnless { it.isNaN() },
                j.optString("question").ifBlank { null },
                j.optString("received_at"),
            )
        }
    }

    /** Persist image bytes + gaze, draw the dot copy. Throws IllegalArgumentException on a bad image. */
    @Synchronized
    fun save(
        bytes: ByteArray,
        gazeX: Float?, gazeY: Float?,
        sceneW: Int?, sceneH: Int?,
        worn: Boolean?, ts: String?,
        lat: Double?, lng: Double?,
        question: String?,
        meta: String?,
    ): Capture {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("image is not a decodable PNG/JPEG (${bytes.size} bytes)")
        val w = bmp.width; val h = bmp.height
        var gx = gazeX; var gy = gazeY
        if (gx != null && gy != null && sceneW != null && sceneH != null && sceneW > 0 && sceneH > 0 &&
            (sceneW != w || sceneH != h)) {
            gx = gx * w / sceneW; gy = gy * h / sceneH
        }
        if (gx != null && gy != null) { gx = gx.coerceIn(0f, (w - 1).toFloat()); gy = gy.coerceIn(0f, (h - 1).toFloat()) }

        val png = File(dir, "last.png").apply { writeBytes(bytes) }
        val markedBmp = bmp.copy(Bitmap.Config.ARGB_8888, true)
        bmp.recycle()
        if (gx != null && gy != null) drawDot(markedBmp, gx, gy)
        val marked = File(dir, "last_marked.png")
        marked.outputStream().use { markedBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        markedBmp.recycle()

        val tsSec = ts?.toDoubleOrNull() ?: (System.currentTimeMillis() / 1000.0)
        val id = "cap-" + (tsSec * 1000).toLong()
        val now = Instant.now().toString()
        // Archive (same shape mapidéx's v0.5 receiver wrote, so /captures + /capture/latest.* keep working).
        runCatching {
            val arch = File(dir, "$id.png").apply { writeBytes(bytes) }
            File(dir, "$id.json").writeText(JSONObject()
                .put("id", id).put("image", arch.absolutePath).put("bytes", arch.length())
                .put("gaze_x", gazeX?.toDouble() ?: JSONObject.NULL).put("gaze_y", gazeY?.toDouble() ?: JSONObject.NULL)
                .put("worn", worn ?: JSONObject.NULL).put("ts", tsSec)
                .put("scene_w", sceneW ?: JSONObject.NULL).put("scene_h", sceneH ?: JSONObject.NULL)
                .put("lat", lat ?: JSONObject.NULL).put("lng", lng ?: JSONObject.NULL)
                .put("received", System.currentTimeMillis() / 1000.0)
                .apply { meta?.let { m -> runCatching { put("source", JSONObject(m)) } } }
                .toString(2))
        }
        val j = JSONObject()
            .put("id", id)
            .put("x", gx?.toDouble() ?: JSONObject.NULL).put("y", gy?.toDouble() ?: JSONObject.NULL)
            .put("w", w).put("h", h)
            .put("worn", worn ?: JSONObject.NULL)
            .put("ts", ts ?: JSONObject.NULL)
            .put("scene_w", sceneW ?: JSONObject.NULL).put("scene_h", sceneH ?: JSONObject.NULL)
            .put("lat", lat ?: JSONObject.NULL).put("lng", lng ?: JSONObject.NULL)
            .put("question", question ?: JSONObject.NULL)
            .put("received_at", now)
            .put("meta", meta?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject.NULL)
        File(dir, "last_gaze.json").writeText(j.toString(2))

        return Capture(id, png, marked, gx, gy, w, h, worn, lat, lng, question, now).also { last = it }
    }

    /** v0.5.1 "Clear captures": delete archive + last.* files on the phone. Returns files removed. Ask log text is kept. */
    @Synchronized
    fun clearAll(fallbackDir: File? = null): Int {
        val d = dirOrNull ?: fallbackDir ?: return 0
        var n = 0
        d.listFiles()?.forEach { f ->
            if ((f.name.startsWith("cap-") || f.name.startsWith("last")) && f.isFile && f.delete()) n++
        }
        last = null
        return n
    }

    /**
     * Filled DOT, not a ring: white disc + black core. Sized to cover the red/white dot the Mac bridge
     * already burns in (r≈14 px @1600 wide), so either way the model sees one black-on-white dot.
     */
    fun drawDot(b: Bitmap, x: Float, y: Float) {
        val outer = maxOf(16f, b.width * 0.0115f)
        val inner = outer * 0.68f
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        p.color = 0xFFFFFFFF.toInt(); c.drawCircle(x, y, outer, p)
        p.color = 0xFF000000.toInt(); c.drawCircle(x, y, inner, p)
    }

    /** Decode a capture image for a model, long side capped (Nano downsizes internally anyway). */
    fun loadForModel(f: File, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val long = maxOf(b.width, b.height)
        if (long <= maxSide) return b
        val s = maxSide.toFloat() / long
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true).also { if (it !== b) b.recycle() }
    }
}
