package com.narayan.mapidex.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private fun JSONObject.dbl(k: String): Double? = if (!has(k) || isNull(k)) null else optDouble(k).takeUnless { it.isNaN() }

// ------------------------------------------------------------------ Ask log

@Composable
fun AskLogScreen(onOpen: (JSONObject) -> Unit) {
    val v = History.askVersion
    var asks by remember { mutableStateOf<List<JSONObject>?>(null) }
    LaunchedEffect(v) { asks = withContext(Dispatchers.IO) { History.asks() } }
    val live = remember { mutableStateOf<AskView?>(null) }
    LaunchedEffect(Unit) { while (true) { live.value = readAsk(); kotlinx.coroutines.delay(POLL_MS) } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ScreenHeader("Ask log", "${asks?.size ?: 0} looks · newest first") }
        if (live.value?.status == "thinking") item {
            PdCard { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PdSpinner(18.dp); MonoText("Thinking about the latest capture…", 13.sp) } }
        }
        val list = asks.orEmpty()
        if (asks != null && list.isEmpty()) item { PdCard { QuietText("No Asks yet. Each Capture → Nano/web/Gemini answer is saved here.", 13.sp) } }
        items(list, key = { it.optString("logged_at") + it.optString("capture_id") }) { a ->
            PdCard(onClick = { onOpen(a) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonoText(a.optString("title").ifBlank { "—" }, 16.sp, bold = true, maxLines = 1, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    PxText(History.time(a.optString("updated_at").ifBlank { a.optString("logged_at") }), 11.sp)
                }
                MonoText(a.optString("blurb").ifBlank { "—" }, 12.sp, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (a.optString("status") == "error") PdTag("error")
                    a.optString("source").takeIf { it.isNotBlank() && it != "none" }?.let { PdTag(it, inverted = false) }
                    a.optString("confidence").takeIf { it.isNotBlank() && it != "none" }?.let { PdTag(it, inverted = false) }
                }
            }
        }
    }
}

@Composable
fun AskDetail(a: JSONObject, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val capId = a.optString("capture_id").ifBlank { null }
    var img by remember(capId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(capId) {
        if (capId != null) img = withContext(Dispatchers.IO) {
            val dir = capturesDir(ctx)
            (File(dir, "$capId.png").takeIf { it.exists() })?.let { decodeThumb(it, 1600)?.asImageBitmap() }
        }
    }
    val gaze = a.optJSONObject("gaze")
    val loc = a.optJSONObject("location")
    Column(Modifier.fillMaxSize().background(Pd.Paper).verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PxText("Ask", 14.sp, bold = true, modifier = Modifier.weight(1f))
            PdButton("Back", onClose)
        }
        img?.let { i ->
            GazeThumb(i, gaze?.dbl("x")?.toFloat(), gaze?.dbl("y")?.toFloat(), gaze?.optInt("w") ?: 0, gaze?.optInt("h") ?: 0,
                Modifier.fillMaxWidth().border(2.dp, Pd.Ink), minDot = 6.dp)
        }
        PdCard {
            MonoText(a.optString("title").ifBlank { "—" }, 22.sp, bold = true)
            if (a.optString("blurb").isNotBlank()) MonoText(a.optString("blurb"), 14.sp)
            KV("when", History.dateTime(a.optString("updated_at").ifBlank { a.optString("logged_at") }))
            KV("source", a.optString("source").ifBlank { "—" })
            KV("conf", a.optString("confidence").ifBlank { "—" })
            a.optString("question").takeIf { it.isNotBlank() && it != "null" }?.let { KV("asked", it) }
            val lat = loc?.dbl("lat"); val lng = loc?.dbl("lng")
            KV("gps", if (lat != null && lng != null) "%.5f, %.5f".format(lat, lng) else "—")
            if (lat != null && lng != null) PdButton("Open in Maps", { History.openMaps(ctx, lat, lng) }, Modifier.fillMaxWidth(), inverted = true)
        }
        val layers = a.optJSONArray("layers") ?: JSONArray()
        if (layers.length() > 0) PdCard("Layers") {
            for (i in 0 until layers.length()) layers.optJSONObject(i)?.let { l ->
                PxText(l.optString("name"), 11.sp, Pd.Quiet)
                MonoText(l.optString("text"), 13.sp)
            }
        }
        val steps = a.optJSONArray("cascade") ?: JSONArray()
        if (steps.length() > 0) PdCard("Cascade") {
            for (i in 0 until steps.length()) steps.optJSONObject(i)?.let { s ->
                MonoText("${if (s.optBoolean("ok")) "■" else "□"} ${s.optString("step")}  ${s.optString("note")}", 12.sp)
            }
        }
        capId?.let { QuietText(it, 11.sp) }
    }
}

// ------------------------------------------------------------------ Detour log

@Composable
fun DetourLogScreen() {
    val ctx = LocalContext.current
    val v = History.detourVersion
    var list by remember { mutableStateOf<List<JSONObject>?>(null) }
    LaunchedEffect(v) { list = withContext(Dispatchers.IO) { History.detours() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ScreenHeader("Detour log", "${list?.size ?: 0} walks built · newest first") }
        val l = list.orEmpty()
        if (list != null && l.isEmpty()) item { PdCard { QuietText("No detours yet. Every route the Playdate (or route debug) builds lands here.", 13.sp) } }
        items(l, key = { it.optString("built_at") }) { d ->
            val via = d.optJSONObject("via")
            val place = via ?: d.optJSONObject("place")
            val lat = place?.dbl("lat"); val lng = place?.dbl("lng")
            PdCard(onClick = if (lat != null && lng != null) ({ History.openMaps(ctx, lat, lng) }) else null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PdTag(d.optString("vibe").ifBlank { "—" })
                    Spacer(Modifier.weight(1f))
                    PxText(History.dateTime(d.optString("built_at")), 11.sp)
                }
                MonoText(via?.optString("name")?.ifBlank { null } ?: "no detour", 16.sp, bold = true, maxLines = 1)
                via?.optString("tag")?.takeIf { it.isNotBlank() }?.let { QuietText(it, 12.sp) }
                QuietText("${d.optString("from")} → ${d.optString("to")} · ${d.optString("summary")}", 12.sp, maxLines = 2)
            }
        }
    }
}

// ------------------------------------------------------------------ Daily detours

private data class Stop(val name: String, val vibe: String, val tag: String, val lat: Double, val lng: Double,
                        val chosen: Boolean, val at: String?)

private fun stopOf(p: JSONObject?, vibe: String, chosen: Boolean, at: String?): Stop? {
    p ?: return null
    val lat = p.dbl("lat") ?: return null; val lng = p.dbl("lng") ?: return null
    val name = p.optString("name").ifBlank { return null }
    if (name == "no detour") return null
    return Stop(name, p.optString("category").ifBlank { vibe }, p.optString("tag"), lat, lng, chosen, at)
}

private fun dailyStops(): Pair<List<Stop>, List<Stop>> {
    val chosen = LinkedHashMap<String, Stop>()
    val nearby = LinkedHashMap<String, Stop>()
    fun key(s: Stop) = s.name.lowercase() + "@%.4f,%.4f".format(s.lat, s.lng)
    // Current feed route first (what the Playdate is walking now) …
    FeedState.route?.let { r ->
        val at = r.optString("built_at")
        if (History.isToday(at) || at.isBlank()) {
            val vibe = r.optString("vibe")
            stopOf(r.optJSONObject("via"), vibe, true, at)?.let { chosen.putIfAbsent(key(it), it) }
            r.optJSONArray("candidates")?.let { a -> for (i in 0 until a.length()) stopOf(a.optJSONObject(i), vibe, false, at)?.let { nearby.putIfAbsent(key(it), it) } }
        }
    }
    // … then every detour saved today.
    History.detours().filter { History.isToday(it.optString("built_at")) }.forEach { d ->
        val vibe = d.optString("vibe"); val at = d.optString("built_at")
        stopOf(d.optJSONObject("via"), vibe, true, at)?.let { chosen.putIfAbsent(key(it), it) }
        d.optJSONArray("candidates")?.let { a -> for (i in 0 until a.length()) stopOf(a.optJSONObject(i), vibe, false, at)?.let { nearby.putIfAbsent(key(it), it) } }
    }
    chosen.keys.forEach { nearby.remove(it) }
    return chosen.values.toList() to nearby.values.toList()
}

@Composable
fun DailyDetoursScreen() {
    val ctx = LocalContext.current
    val v = History.detourVersion
    var data by remember { mutableStateOf<Pair<List<Stop>, List<Stop>>?>(null) }
    LaunchedEffect(v) { data = withContext(Dispatchers.IO) { dailyStops() } }
    val (chosen, nearby) = data ?: (emptyList<Stop>() to emptyList())
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ScreenHeader("Daily detours", "today's OSM stops · tap to open in Google Maps") }
        if (data != null && chosen.isEmpty() && nearby.isEmpty()) item {
            PdCard { QuietText("Nothing today yet. Build a detour on the Playdate and its stops show up here.", 13.sp) }
        }
        if (chosen.isNotEmpty()) item { PxText("Stops you got · ${chosen.size}", 10.sp, Pd.Quiet, bold = true) }
        items(chosen, key = { "c" + it.name + it.lat }) { s -> StopCard(s) { History.openMaps(ctx, s.lat, s.lng) } }
        if (nearby.isNotEmpty()) item { Spacer(Modifier.height(4.dp)); PxText("Also on the way · ${nearby.size}", 10.sp, Pd.Quiet, bold = true) }
        items(nearby, key = { "n" + it.name + it.lat }) { s -> StopCard(s) { History.openMaps(ctx, s.lat, s.lng) } }
    }
}

@Composable
private fun StopCard(s: Stop, open: () -> Unit) {
    PdCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MonoText(s.name, 15.sp, bold = true, maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    PdTag(s.vibe.ifBlank { "—" }, inverted = s.chosen)
                    if (s.tag.isNotBlank()) QuietText(s.tag, 11.sp, maxLines = 1)
                }
                QuietText("%.5f, %.5f".format(s.lat, s.lng) + (s.at?.let { " · " + History.time(it) } ?: ""), 11.sp)
            }
            PdButton("Maps", open, inverted = true)
        }
    }
}
