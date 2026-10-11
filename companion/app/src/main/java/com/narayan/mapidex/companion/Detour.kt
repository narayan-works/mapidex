package com.narayan.mapidex.companion

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import kotlin.math.roundToInt
import kotlin.math.max

/**
 * Live OSM detour — Kotlin port of mapidex/pipe/commute_detour.py.
 * OSRM route office→(via)→home + Overpass POIs bucketed into relax/play/social/essentials.
 * Blocking network calls: run off the main thread.
 */
object Detour {
    const val USER_AGENT = "mapidex-companion/0.3"
    const val OSRM = "https://router.project-osrm.org"
    val OVERPASS_MIRRORS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.openstreetmap.ru/api/interpreter",
    )

    const val NONE = "none"
    val VIBES = listOf("relax", "play", "social", "essentials", NONE)

    private const val CARD_W = 180
    private const val CARD_H = 100
    private const val CARD_PTS = 24
    private const val LATLNG_PTS = 64
    private const val MAX_CANDIDATES = 3
    /** Total time across Overpass mirrors before falling back to a direct route (Playdate waits ≤60 s). */
    private const val OVERPASS_BUDGET_MS = 35_000L
    private const val WALK_MPS = 1.4 // ~3.1 mph; public OSRM demo ignores the foot profile

    // Same rules as classify() in the Python pipe, in priority order.
    private val BUCKET_RULES: List<Pair<String, Map<String, Set<String>>>> = listOf(
        "relax" to mapOf("leisure" to setOf("park", "garden"), "amenity" to setOf("cafe"), "tourism" to setOf("viewpoint")),
        "play" to mapOf("leisure" to setOf("playground", "pitch", "sports_centre"), "tourism" to setOf("museum")),
        "social" to mapOf("amenity" to setOf("bar", "pub", "restaurant", "cinema")),
        "essentials" to mapOf("shop" to setOf("supermarket", "convenience", "bakery"), "amenity" to setOf("pharmacy")),
    )

    data class LatLng(val lat: Double, val lng: Double)
    data class Poi(val name: String, val lat: Double, val lng: Double, val bucket: String, val tag: String)

    class DetourException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

    /** Build the route section for feed.json. [progress] gets short human status lines. */
    fun build(office: Place, home: Place, vibe: String, progress: (String) -> Unit): JSONObject {
        val o = LatLng(office.lat, office.lng)
        val h = LatLng(home.lat, home.lng)
        var via: Poi? = null
        var candidates: List<Poi> = emptyList()
        var note: String? = null

        if (vibe != NONE) {
            progress("1/3 OSRM: direct route for corridor…")
            val direct = osrmRoute(listOf(o, h), steps = false)
            val line = direct.coords
            progress("2/3 Overpass: $vibe places along corridor…")
            // Overpass is flaky (504s): degrade to the direct route with a note instead of failing the build.
            val elements = try { overpassPois(bbox(line)) } catch (e: DetourException) {
                note = "places lookup busy — direct route (retry vibe)"
                null
            }
            val buckets = if (elements != null) bucketPlaces(elements, line) else emptyMap()
            candidates = buckets[vibe].orEmpty()
            val mid = line[line.size / 2]
            via = candidates.minByOrNull { d2(it.lat, it.lng, mid.lat, mid.lng) }
            if (via == null && note == null) note = "no named $vibe place near route — direct route instead"
            candidates = candidates.sortedBy { d2(it.lat, it.lng, mid.lat, mid.lng) }.take(MAX_CANDIDATES)
        }

        progress(if (via != null) "3/3 OSRM: office → ${via.name} → home…" else "OSRM: office → home…")
        val pts = if (via != null) listOf(o, LatLng(via.lat, via.lng), h) else listOf(o, h)
        val route = osrmRoute(pts, steps = true)

        val instructions = instructions(route.legs, via)
        val viaStep = if (via != null) instructions.indexOfFirst { it.startsWith("Via ") } + 1 else 999
        val card = simplify(route.coords, CARD_PTS)
        val detourIndex = if (via != null)
            card.indices.minBy { d2(card[it].lat, card[it].lng, via.lat, via.lng) } + 1 else 1

        val miles = route.meters / 1609.34
        val walkMin = max(1, (route.meters / WALK_MPS / 60).roundToInt())

        val placeJson = if (via != null) poiJson(via)
        else JSONObject().put("name", "no detour").put("lat", h.lat).put("lng", h.lng)

        return JSONObject()
            .put("from", "office")
            .put("to", "home")
            .put("vibe", vibe)
            .put("summary", "foot - %.1f mi - %d min".format(miles, walkMin))
            .put("distance_m", route.meters.roundToInt())
            .put("walk_s", (route.meters / WALK_MPS).roundToInt())
            .put("osrm_duration_s", route.seconds.roundToInt())
            .put("path", fitPath(card))
            .put("path_latlng", JSONArray().apply {
                simplify(route.coords, LATLNG_PTS).forEach { put(JSONArray().put(round6(it.lat)).put(round6(it.lng))) }
            })
            .put("instructions", JSONArray(instructions))
            .put("steps", JSONArray().apply { route.steps.forEach { put(it) } })
            .put("place", placeJson)
            .put("via", if (via != null) poiJson(via) else JSONObject.NULL)
            .put("candidates", JSONArray().apply { candidates.forEach { put(poiJson(it)) } })
            .put("detour_index", detourIndex)
            .put("via_step", viaStep)
            .put("note", note ?: JSONObject.NULL)
            .put("built_at", Instant.now().toString())
            .put("source", "OSRM + Overpass (OSM)")
    }

    // ---------- OSRM ----------

    private class OsrmRoute(
        val coords: List<LatLng>, val meters: Double, val seconds: Double,
        val legs: JSONArray, val steps: List<JSONObject>
    )

    private fun osrmRoute(points: List<LatLng>, steps: Boolean): OsrmRoute {
        val coords = points.joinToString(";") { "${it.lng},${it.lat}" }
        val url = "$OSRM/route/v1/foot/$coords?overview=full&geometries=geojson&steps=$steps"
        val data = try { JSONObject(httpGet(url, readTimeoutMs = 30_000)) }
        catch (e: Exception) { throw DetourException("OSRM unreachable: ${e.message}", e) }
        if (data.optString("code") != "Ok" || data.optJSONArray("routes")?.length() ?: 0 == 0)
            throw DetourException("OSRM failed: ${data.optString("code")} ${data.optString("message")}")
        val r = data.getJSONArray("routes").getJSONObject(0)
        val line = r.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = (0 until line.length()).map { val c = line.getJSONArray(it); LatLng(c.getDouble(1), c.getDouble(0)) }
        val legs = r.optJSONArray("legs") ?: JSONArray()
        val flat = mutableListOf<JSONObject>()
        for (li in 0 until legs.length()) {
            val ss = legs.getJSONObject(li).optJSONArray("steps") ?: continue
            for (si in 0 until ss.length()) {
                val s = ss.getJSONObject(si)
                val m = s.getJSONObject("maneuver")
                val loc = m.optJSONArray("location")
                flat += JSONObject()
                    .put("leg", li)
                    .put("type", m.optString("type"))
                    .put("modifier", m.optString("modifier", ""))
                    .put("name", s.optString("name", ""))
                    .put("distance_m", s.optDouble("distance", 0.0).roundToInt())
                    .put("lat", loc?.let { round6(it.getDouble(1)) } ?: JSONObject.NULL)
                    .put("lng", loc?.let { round6(it.getDouble(0)) } ?: JSONObject.NULL)
            }
        }
        return OsrmRoute(pts, r.getDouble("distance"), r.getDouble("duration"), legs, flat)
    }

    /** Text like the baked Playdate feed: "Turn Left onto Buchanan Street - 147 ft", "Via X", "Arrive - home". */
    private fun instructions(legs: JSONArray, via: Poi?): List<String> {
        val out = mutableListOf<String>()
        val lastLeg = legs.length() - 1
        for (li in 0..lastLeg) {
            val ss = legs.getJSONObject(li).optJSONArray("steps") ?: continue
            for (si in 0 until ss.length()) {
                val s = ss.getJSONObject(si)
                val m = s.getJSONObject("maneuver")
                val type = m.optString("type")
                val mod = title(m.optString("modifier", ""))
                val name = s.optString("name", "")
                val ft = (s.optDouble("distance", 0.0) * 3.28084).roundToInt()
                if (type == "arrive") {
                    out += if (li < lastLeg && via != null) "Via ${via.name}" else "Arrive - home"
                    continue
                }
                val verb = when (type) {
                    "depart" -> "Head out"
                    "turn" -> "Turn $mod"
                    "new name" -> "Continue"
                    "continue" -> if (mod.isBlank()) "Continue" else "Continue $mod"
                    "end of road" -> "End Of Road $mod"
                    "fork" -> "Fork $mod"
                    "merge" -> "Merge $mod"
                    "roundabout", "rotary" -> "Roundabout"
                    else -> listOf(title(type), mod).filter { it.isNotBlank() }.joinToString(" ")
                }.trim()
                val onto = when {
                    name.isBlank() -> ""
                    type == "depart" -> " on $name"
                    else -> " onto $name"
                }
                out += "$verb$onto - $ft ft"
            }
        }
        return out
    }

    // ---------- Overpass ----------

    private data class BBox(val s: Double, val w: Double, val n: Double, val e: Double)

    private fun bbox(line: List<LatLng>, pad: Double = 0.003) = BBox(
        line.minOf { it.lat } - pad, line.minOf { it.lng } - pad,
        line.maxOf { it.lat } + pad, line.maxOf { it.lng } + pad
    )

    private fun overpassPois(b: BBox): JSONArray {
        val bb = "${b.s},${b.w},${b.n},${b.e}"
        val query = """[out:json][timeout:20];
(
  node["amenity"~"cafe|bar|pub|restaurant|cinema|pharmacy"]($bb);
  node["leisure"~"park|garden|playground|pitch|sports_centre"]($bb);
  node["shop"~"supermarket|convenience|bakery"]($bb);
  node["tourism"~"viewpoint|museum"]($bb);
);
out body;"""
        val body = "data=" + URLEncoder.encode(query, "UTF-8")
        val errors = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + OVERPASS_BUDGET_MS
        for (url in OVERPASS_MIRRORS) {
            val left = deadline - System.currentTimeMillis()
            if (left < 3_000) { errors += "${URL(url).host}: skipped (time budget)"; continue }
            try {
                return JSONObject(request(url, body, readTimeoutMs = minOf(22_000L, left).toInt(), connectTimeoutMs = 6_000))
                    .optJSONArray("elements") ?: JSONArray()
            } catch (e: Exception) {
                errors += "${URL(url).host}: ${e.message}"
            }
        }
        throw DetourException("Overpass failed on all mirrors — ${errors.joinToString("; ")}")
    }

    private fun classify(tags: JSONObject): Pair<String, String>? {
        for ((bucket, rules) in BUCKET_RULES) {
            for ((key, values) in rules) {
                val v = tags.optString(key, "")
                if (v in values) return bucket to "$key=$v"
            }
        }
        return null
    }

    private fun nearCorridor(lat: Double, lng: Double, line: List<LatLng>, maxDeg: Double = 0.0015): Boolean {
        val step = max(1, line.size / 40)
        for (i in line.indices step step) {
            if (d2(lat, lng, line[i].lat, line[i].lng) <= maxDeg * maxDeg) return true
        }
        return false
    }

    private fun bucketPlaces(elements: JSONArray, line: List<LatLng>): Map<String, List<Poi>> {
        val out = BUCKET_RULES.associate { it.first to mutableListOf<Poi>() }
        val seen = HashSet<String>()
        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            if (!el.has("lat") || !el.has("lon")) continue
            val tags = el.optJSONObject("tags") ?: continue
            val name = tags.optString("name", "").ifBlank { null } ?: continue
            val (bucket, tag) = classify(tags) ?: continue
            val lat = el.getDouble("lat"); val lng = el.getDouble("lon")
            if (!nearCorridor(lat, lng, line)) continue
            if (!seen.add("$bucket:${name.lowercase()}")) continue
            out.getValue(bucket) += Poi(name, lat, lng, bucket, tag)
        }
        return out
    }

    // ---------- geometry ----------

    private fun d2(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val dLat = aLat - bLat; val dLng = aLng - bLng
        return dLat * dLat + dLng * dLng
    }

    private fun simplify(pts: List<LatLng>, maxPts: Int): List<LatLng> {
        if (pts.size <= maxPts) return pts
        val step = (pts.size - 1).toDouble() / (maxPts - 1)
        return (0 until maxPts).map { pts[(it * step).roundToInt()] }
    }

    /** Same as fit_path(): project to a 180×100 card, north up, 8px pad. */
    private fun fitPath(pts: List<LatLng>, w: Int = CARD_W, h: Int = CARD_H): JSONObject {
        val minLng = pts.minOf { it.lng }; val maxLng = pts.maxOf { it.lng }
        val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }
        val dx = max(maxLng - minLng, 1e-9); val dy = max(maxLat - minLat, 1e-9)
        val pad = 8
        val arr = JSONArray()
        for (p in pts) {
            val x = (pad + (p.lng - minLng) / dx * (w - 2 * pad)).toInt()
            val y = (pad + (1 - (p.lat - minLat) / dy) * (h - 2 * pad)).toInt()
            arr.put(JSONArray().put(x).put(y))
        }
        return JSONObject().put("w", w).put("h", h).put("pts", arr)
    }

    private fun poiJson(p: Poi) = JSONObject()
        .put("name", p.name).put("lat", round6(p.lat)).put("lng", round6(p.lng))
        .put("category", p.bucket).put("tag", p.tag)

    private fun round6(v: Double) = Math.round(v * 1e6) / 1e6
    private fun title(s: String) = s.split(" ").joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }

    // ---------- HTTP ----------

    private fun httpGet(url: String, readTimeoutMs: Int): String = request(url, null, readTimeoutMs)
    private fun httpPost(url: String, body: String, readTimeoutMs: Int): String = request(url, body, readTimeoutMs)

    private fun request(url: String, body: String?, readTimeoutMs: Int, connectTimeoutMs: Int = 15_000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code ${text.take(120)}")
            return text
        } finally {
            c.disconnect()
        }
    }
}
