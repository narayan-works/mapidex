package com.narayan.mapidex.companion

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Ask cascade step 2 — free web context around the GPS fix (no keys):
 *  - OSM Overpass: named features within ~120 m (amenity/tourism/historic/shop/leisure/building/…)
 *  - OSM Nominatim reverse: street / neighbourhood
 *  - Wikipedia geosearch (≤300 m) + REST summary for the best match
 * All calls are best-effort; any failure just yields fewer facts.
 */
object WebEnrich {
    private const val UA = "MapidexCompanion/0.5 (Playdate walking companion; personal use)"
    private val OVERPASS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )

    data class Place(val name: String, val kind: String, val distM: Int)
    data class Wiki(val title: String, val extract: String, val distM: Int, val url: String)
    data class Context(val nearby: List<Place>, val address: String?, val wiki: List<Wiki>) {
        val isEmpty get() = nearby.isEmpty() && address == null && wiki.isEmpty()

        /** Compact lines to hand a model as hints. */
        fun hint(max: Int = 8): String = buildString {
            address?.let { append("Address area: $it\n") }
            if (nearby.isNotEmpty()) {
                append("Named OSM places nearby (name · type · metres):\n")
                nearby.take(max).forEach { append("- ${it.name} · ${it.kind} · ${it.distM} m\n") }
            }
            if (wiki.isNotEmpty()) {
                append("Wikipedia articles nearby:\n")
                wiki.take(4).forEach { append("- ${it.title} (${it.distM} m)\n") }
            }
        }.trim()
    }

    fun lookup(lat: Double, lng: Double): Context {
        // Three sources in parallel; each is best-effort.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        try {
            val n = pool.submit<List<Place>> { runCatching { overpass(lat, lng) }.getOrDefault(emptyList()) }
            val a = pool.submit<String?> { runCatching { reverse(lat, lng) }.getOrNull() }
            val w = pool.submit<List<Wiki>> { runCatching { wikiNear(lat, lng) }.getOrDefault(emptyList()) }
            fun <T> java.util.concurrent.Future<T>.within(d: T): T = runCatching { get(15, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(d)
            return Context(n.within(emptyList()), a.within(null), w.within(emptyList()))
        } finally { pool.shutdownNow() }
    }

    /** Fuzzy match a model's title against nearby names / wiki titles. */
    fun match(title: String, ctx: Context): Pair<String, Wiki?>? {
        val t = norm(title)
        if (t.length < 3) return null
        fun hit(n: String): Boolean { val x = norm(n); return x.length >= 3 && (x.contains(t) || t.contains(x) || overlap(x, t) >= 0.6) }
        ctx.wiki.firstOrNull { hit(it.title) }?.let { return it.title to it }
        ctx.nearby.firstOrNull { hit(it.name) }?.let { p ->
            return p.name to ctx.wiki.firstOrNull { hit(p.name) && norm(it.title).contains(norm(p.name)) }
        }
        return null
    }

    fun wikiSummary(title: String): String? = runCatching {
        val u = "https://en.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
        JSONObject(get(u, 6000)).optString("extract").ifBlank { null }
    }.getOrNull()

    private fun overpass(lat: Double, lng: Double): List<Place> {
        val q = """
            [out:json][timeout:8];
            (
              nwr(around:120,$lat,$lng)["name"]["amenity"];
              nwr(around:120,$lat,$lng)["name"]["tourism"];
              nwr(around:120,$lat,$lng)["name"]["historic"];
              nwr(around:120,$lat,$lng)["name"]["shop"];
              nwr(around:120,$lat,$lng)["name"]["leisure"];
              nwr(around:120,$lat,$lng)["name"]["building"];
              nwr(around:120,$lat,$lng)["name"]["man_made"];
              nwr(around:120,$lat,$lng)["name"]["natural"];
            );
            out center 40;
        """.trimIndent()
        var last: Exception? = null
        for (ep in OVERPASS) {
            try {
                val els = JSONObject(post(ep, "data=" + URLEncoder.encode(q, "UTF-8"), 10000)).optJSONArray("elements") ?: JSONArray()
                val out = mutableListOf<Place>()
                for (i in 0 until els.length()) {
                    val e = els.getJSONObject(i)
                    val tags = e.optJSONObject("tags") ?: continue
                    val name = tags.optString("name").ifBlank { continue }
                    val elat = if (e.has("lat")) e.optDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: continue
                    val elng = if (e.has("lon")) e.optDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: continue
                    val kind = listOf("tourism", "historic", "amenity", "shop", "leisure", "man_made", "natural", "building")
                        .firstOrNull { tags.has(it) }?.let { "$it=${tags.optString(it)}" } ?: "place"
                    out += Place(name, kind, distM(lat, lng, elat, elng))
                }
                return out.distinctBy { it.name }.sortedBy { it.distM }
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("overpass failed")
    }

    private fun reverse(lat: Double, lng: Double): String? {
        val j = JSONObject(get("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&lat=$lat&lon=$lng", 6000))
        val a = j.optJSONObject("address") ?: return j.optString("display_name").ifBlank { null }
        return listOf("road", "neighbourhood", "suburb", "city", "town")
            .mapNotNull { a.optString(it).ifBlank { null } }.distinct().joinToString(", ").ifBlank { null }
    }

    private fun wikiNear(lat: Double, lng: Double): List<Wiki> {
        val j = JSONObject(get("https://en.wikipedia.org/w/api.php?action=query&list=geosearch&gscoord=$lat%7C$lng&gsradius=300&gslimit=6&format=json", 6000))
        val arr = j.optJSONObject("query")?.optJSONArray("geosearch") ?: return emptyList()
        return (0 until arr.length()).map {
            val g = arr.getJSONObject(it)
            val t = g.optString("title")
            Wiki(t, "", g.optDouble("dist").toInt(), "https://en.wikipedia.org/wiki/" + t.replace(' ', '_'))
        }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\b(the|a|an|of)\\b"), " ").replace(Regex("\\s+"), " ").trim()
    private fun overlap(a: String, b: String): Double {
        val x = a.split(' ').filter { it.length > 2 }.toSet(); val y = b.split(' ').filter { it.length > 2 }.toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / minOf(x.size, y.size)
    }

    fun distM(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Int {
        val dy = (bLat - aLat) * 111_320.0
        val dx = (bLng - aLng) * 111_320.0 * cos(Math.toRadians(aLat))
        return sqrt(dx * dx + dy * dy).toInt()
    }

    private fun get(url: String, timeout: Int) = request(url, null, timeout)
    private fun post(url: String, body: String, timeout: Int) = request(url, body, timeout)

    private fun request(url: String, body: String?, timeout: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000; c.readTimeout = timeout
            c.setRequestProperty("User-Agent", UA)
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode} $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}
