package com.narayan.mapidex.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Companion views over the capture archive + Ask state (read-only — same files/state FeedServer serves:
 * <filesDir>/captures/cap-<ms>.png|json, AskEngine.askJson()). No new data paths.
 */

const val POLL_MS = 1000L

/** Captures dir: CaptureStore's if the service already init'd it, else the same <filesDir>/captures. */
fun capturesDir(ctx: Context): File = CaptureStore.dirOrNull ?: File(ctx.filesDir, "captures")

/** Downsampled decode (hi-res stays on disk untouched). */
fun decodeThumb(f: File, maxSide: Int): Bitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    if (o.outWidth <= 0) return null
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
    BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
}.getOrNull()

private fun imageSize(f: File): IntArray {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    return intArrayOf(o.outWidth, o.outHeight)
}

/** Image + gaze dot overlay (white rim, black core — same dot the model copy gets). gx/gy in w×h space. */
@Composable
fun GazeThumb(img: ImageBitmap, gx: Float?, gy: Float?, w: Int, h: Int, modifier: Modifier = Modifier, minDot: Dp = 5.dp) {
    val ratio = if (w > 0 && h > 0) w.toFloat() / h else img.width.toFloat() / img.height
    Box(modifier.aspectRatio(ratio).clipToBounds().background(Pd.Ink)) {
        Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        if (gx != null && gy != null && w > 0 && h > 0) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(gx / w * size.width, gy / h * size.height)
                val r = maxOf(minDot.toPx(), size.width * 0.018f)
                drawCircle(Color.White, r, c)
                drawCircle(Color.Black, r * 0.66f, c)
            }
        }
    }
}

// ------------------------------------------------------------------ capture archive

data class CapItem(val id: String, val png: File, val gx: Float?, val gy: Float?,
                   val sceneW: Int, val sceneH: Int, val lat: Double?, val lng: Double?, val worn: Boolean?) {
    val ms: Long? get() = id.removePrefix("cap-").toLongOrNull()
}

fun listCaptures(dir: File, max: Int): List<CapItem> =
    (dir.listFiles { f -> f.name.startsWith("cap-") && f.name.endsWith(".json") } ?: emptyArray())
        .sortedByDescending { it.name.removePrefix("cap-").removeSuffix(".json").toLongOrNull() ?: 0L }
        .take(max)
        .mapNotNull { jf ->
            runCatching {
                val j = JSONObject(jf.readText())
                val id = j.optString("id", jf.nameWithoutExtension)
                val png = File(dir, "$id.png").takeIf { it.exists() } ?: return@runCatching null
                fun d(k: String) = if (j.isNull(k)) null else j.optDouble(k).takeUnless { it.isNaN() }
                CapItem(id, png, d("gaze_x")?.toFloat(), d("gaze_y")?.toFloat(),
                    j.optInt("scene_w", 0), j.optInt("scene_h", 0), d("lat"), d("lng"),
                    if (j.has("worn") && !j.isNull("worn")) j.optBoolean("worn") else null)
            }.getOrNull()
        }

/** Archive gaze is raw scene px; rescale space = scene size if known, else the PNG's own size. */
private fun gazeSpace(c: CapItem, wh: IntArray) =
    (if (c.sceneW > 0) c.sceneW else wh[0]) to (if (c.sceneH > 0) c.sceneH else wh[1])

fun fmtMs(ms: Long?, pattern: String = "MMM d · h:mm a"): String =
    ms?.let { java.text.SimpleDateFormat(pattern, java.util.Locale.US).format(java.util.Date(it)) } ?: "—"

// ------------------------------------------------------------------ Last Ask (Home)

data class AskView(val status: String, val title: String, val blurb: String, val source: String,
                   val confidence: String, val captureId: String?, val updatedAt: String)

fun readAsk(): AskView? = runCatching { JSONObject(AskEngine.askJson()) }.getOrNull()?.let {
    AskView(it.optString("status", "idle"), it.optString("title"), it.optString("blurb"),
        it.optString("source"), it.optString("confidence"),
        it.optString("capture_id").ifBlank { null }, it.optString("updated_at"))
}

@Composable
fun LastAskCard(onOpenLog: () -> Unit) {
    var ask by remember { mutableStateOf<AskView?>(null) }
    var cap by remember { mutableStateOf<CaptureStore.Capture?>(null) }
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
    var thumbFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            ask = readAsk()
            val c = CaptureStore.last
            cap = c
            if (c != null && c.id != thumbFor) {
                thumb = withContext(Dispatchers.IO) { decodeThumb(c.png, 900)?.asImageBitmap() }
                thumbFor = c.id
            }
            delay(POLL_MS)
        }
    }

    val a = ask
    val status = a?.status ?: "idle"
    PdCard("Recent Ask", onClick = onOpenLog, trailing = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (status == "thinking") PdSpinner(11.dp, Pd.Ink)
            PxText(when (status) { "thinking" -> "thinking"; "ok" -> "ready"; "error" -> "error"; else -> "idle" }, 10.sp)
        }
    }) {
        when {
            a == null || (status == "idle" && a.title.isBlank()) ->
                QuietText("No Ask yet. Capture on the Playdate sends the look here.", 13.sp)
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (status == "thinking") PdSpinner(20.dp)
                    MonoText(if (status == "thinking" && a.title.isBlank()) "Looking…" else a.title.ifBlank { "…" },
                        18.sp, bold = true, maxLines = 2, modifier = Modifier.weight(1f))
                }
                if (a.blurb.isNotBlank()) MonoText(a.blurb, 13.sp, maxLines = 2)
                val meta = listOfNotNull(a.source.ifBlank { null }, a.confidence.ifBlank { null }?.let { "conf $it" },
                    a.updatedAt.ifBlank { null }?.let { History.time(it) })
                if (meta.isNotEmpty()) QuietText(meta.joinToString(" · "))
            }
        }
        val c = cap; val t = thumb
        if (c != null && t != null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            GazeThumb(t, c.gx, c.gy, c.w, c.h, Modifier.width(120.dp).border(2.dp, Pd.Ink), minDot = 4.dp)
            Spacer(Modifier.weight(1f))
            PxText("ask log ›", 9.sp, Pd.Quiet)
        } else PxText("ask log ›", 9.sp, Pd.Quiet)
    }
}

// ------------------------------------------------------------------ Captures tab (4-across grid)

@Composable
fun CapturesScreen(onOpen: (CapItem) -> Unit) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<CapItem>?>(null) }
    val thumbs = remember { mutableStateMapOf<String, Pair<ImageBitmap, IntArray>>() }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            val list = withContext(Dispatchers.IO) { listCaptures(capturesDir(ctx), 120) }
            items = list
            list.filter { it.id !in thumbs }.forEach { c ->
                withContext(Dispatchers.IO) {
                    val wh = imageSize(c.png)
                    decodeThumb(c.png, 320)?.let { thumbs[c.id] = it.asImageBitmap() to wh }
                }
            }
            thumbs.keys.retainAll(list.map { it.id }.toSet())
            delay(POLL_MS * 2)
        }
    }

    val list = items.orEmpty()
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(span = { GridItemSpan(4) }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { ScreenHeader("Captures", "${list.size} · hi-res stays on this phone") }
                    if (list.isNotEmpty() && !confirmClear) PdLink("clear all", { confirmClear = true })
                }
                if (confirmClear) PdCard("Clear captures") {
                    MonoText("Delete all ${list.size} captures from this phone? Ask log text stays.", 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PdButton("Delete", {
                            CaptureStore.clearAll(capturesDir(ctx)); items = emptyList(); thumbs.clear(); confirmClear = false
                        }, inverted = true)
                        PdButton("Cancel", { confirmClear = false })
                    }
                }
            }
        }
        if (items != null && list.isEmpty()) item(span = { GridItemSpan(4) }) {
            PdCard { QuietText("None yet. Press Capture on the Playdate — frames land here with the gaze dot.", 13.sp) }
        }
        items(list, key = { it.id }) { c ->
            Box(Modifier.fillMaxWidth().border(2.dp, Pd.Ink).clickable { onOpen(c) }) {
                val t = thumbs[c.id]
                if (t != null) {
                    val (img, wh) = t
                    val (sw, sh) = gazeSpace(c, wh)
                    GazeThumb(img, c.gx, c.gy, sw, sh, Modifier.fillMaxWidth(), minDot = 3.dp)
                } else Dither(height = 60.dp)
            }
        }
    }
}

/** Full-screen hi-res viewer for one capture. */
@Composable
fun CaptureDetail(c: CapItem, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var img by remember(c.id) { mutableStateOf<Pair<ImageBitmap, IntArray>?>(null) }
    LaunchedEffect(c.id) {
        img = withContext(Dispatchers.IO) {
            val wh = imageSize(c.png)
            decodeThumb(c.png, 2400)?.asImageBitmap()?.let { it to wh }
        }
    }
    val ask = remember(c.id, History.askVersion) { History.asks().firstOrNull { it.optString("capture_id") == c.id } }
    Column(Modifier.fillMaxSize().background(Pd.Paper).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PxText("Capture", 14.sp, Pd.Ink, bold = true, modifier = Modifier.weight(1f))
            PdButton("Back", onClose)
        }
        val p = img
        if (p != null) {
            val (sw, sh) = gazeSpace(c, p.second)
            GazeThumb(p.first, c.gx, c.gy, sw, sh, Modifier.fillMaxWidth().border(2.dp, Pd.Ink), minDot = 7.dp)
        } else Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f), contentAlignment = Alignment.Center) { PdSpinner(28.dp, Pd.Ink) }
        Column(Modifier.padding(12.dp)) {
            PdCard {
                if (ask != null) {
                    MonoText(ask.optString("title").ifBlank { "—" }, 18.sp, bold = true)
                    if (ask.optString("blurb").isNotBlank()) MonoText(ask.optString("blurb"), 13.sp)
                }
                KV("when", fmtMs(c.ms))
                p?.second?.let { KV("size", "${it[0]}×${it[1]}") }
                KV("gaze", if (c.gx != null && c.gy != null) "%.0f, %.0f".format(c.gx, c.gy) else "—")
                KV("worn", c.worn?.let { if (it) "yes" else "no" } ?: "—")
                KV("gps", if (c.lat != null && c.lng != null) "%.5f, %.5f".format(c.lat, c.lng) else "—")
                QuietText(c.id, 11.sp)
                if (c.lat != null && c.lng != null) PdButton("Open in Maps", { History.openMaps(ctx, c.lat, c.lng) }, Modifier.fillMaxWidth(), inverted = true)
            }
        }
    }
}

/** Tab header: pixel title + quiet subtitle + short yellow underline. */
@Composable
fun ScreenHeader(title: String, sub: String? = null) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        PxText(title, 18.sp, bold = true)
        Box(Modifier.width(36.dp).height(4.dp).background(Pd.Accent))
        sub?.let { QuietText(it, 11.sp) }
    }
}
