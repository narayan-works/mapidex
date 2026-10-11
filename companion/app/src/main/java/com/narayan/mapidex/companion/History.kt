package com.narayan.mapidex.companion

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Companion logs (newest first, capped), persisted as JSON files in filesDir:
 *   ask_log.json     every finished Ask (copy of /ask.json at completion + logged_at)
 *   detour_log.json  every built route (vibe · via place · candidates · built_at)
 * Write-only hooks: AskEngine.ask → logAsk, FeedState.setRoute → logDetour. Endpoints unchanged.
 */
object History {
    private const val ASK_FILE = "ask_log.json"
    private const val DETOUR_FILE = "detour_log.json"
    private const val MAX = 200

    @Volatile private var dir: File? = null
    private val lock = Any()

    /** Bumped on every write so Compose screens reload. */
    var askVersion by mutableStateOf(0)
        private set
    var detourVersion by mutableStateOf(0)
        private set

    fun init(ctx: Context) { if (dir == null) dir = ctx.applicationContext.filesDir }

    private fun read(name: String): JSONArray = synchronized(lock) {
        val d = dir ?: return JSONArray()
        runCatching { JSONArray(File(d, name).readText()) }.getOrElse { JSONArray() }
    }

    private fun prepend(name: String, obj: JSONObject) { synchronized(lock) {
        val d = dir ?: return
        val old = runCatching { JSONArray(File(d, name).readText()) }.getOrElse { JSONArray() }
        val out = JSONArray().put(obj)
        for (i in 0 until minOf(old.length(), MAX - 1)) out.put(old.get(i))
        val tmp = File(d, "$name.tmp")
        tmp.writeText(out.toString())
        tmp.renameTo(File(d, name))
    } }

    private fun list(a: JSONArray) = (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    fun asks(): List<JSONObject> = list(read(ASK_FILE))
    fun detours(): List<JSONObject> = list(read(DETOUR_FILE))

    fun logAsk(ask: JSONObject) {
        if (ask.optString("kind") != "ask") return
        runCatching {
            prepend(ASK_FILE, JSONObject(ask.toString()).put("logged_at", Instant.now().toString()))
            askVersion++
        }
    }

    fun logDetour(route: JSONObject) {
        runCatching {
            val e = JSONObject()
                .put("vibe", route.optString("vibe", FeedState.selected))
                .put("via", route.opt("via") ?: JSONObject.NULL)
                .put("place", route.opt("place") ?: JSONObject.NULL)
                .put("summary", route.optString("summary"))
                .put("distance_m", route.optInt("distance_m"))
                .put("walk_s", route.optInt("walk_s"))
                .put("candidates", route.optJSONArray("candidates") ?: JSONArray())
                .put("from", Places.get(Places.WORK).name)
                .put("to", Places.get(Places.HOME).name)
                .put("note", route.opt("note") ?: JSONObject.NULL)
                .put("built_at", route.optString("built_at").ifBlank { Instant.now().toString() })
            prepend(DETOUR_FILE, e)
            detourVersion++
        }
    }

    // ------------------------------------------------------------ helpers shared by the screens

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val dateTimeFmt = DateTimeFormatter.ofPattern("MMM d · h:mm a", Locale.US)

    fun instant(iso: String?): Instant? = iso?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
    fun isToday(iso: String?): Boolean = instant(iso)?.atZone(zone)?.toLocalDate() == LocalDate.now(zone)
    fun time(iso: String?): String = instant(iso)?.atZone(zone)?.let { timeFmt.format(it) } ?: "—"
    fun dateTime(iso: String?): String = instant(iso)?.atZone(zone)?.let {
        if (it.toLocalDate() == LocalDate.now(zone)) "today · " + timeFmt.format(it) else dateTimeFmt.format(it)
    } ?: "—"

    /** Google Maps link for a pin (opens the Maps app if installed, else the browser). */
    fun mapsUri(lat: Double, lng: Double): Uri =
        Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode("%.6f,%.6f".format(Locale.US, lat, lng)))

    fun openMaps(ctx: Context, lat: Double, lng: Double) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, mapsUri(lat, lng)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
