package com.narayan.mapidex.companion

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Holds the latest GPS fix + built route and renders the Mapidex feed.json. Thread-safe. */
object FeedState {
    const val VERSION = "0.5"

    @Volatile var lat: Double = 0.0
        private set
    @Volatile var lng: Double = 0.0
        private set
    @Volatile var updatedAt: String = Instant.now().toString()
        private set

    @Volatile var hasFix: Boolean = false
        private set

    /** Last successfully built route (Detour.build output), or null = demo stub. */
    @Volatile var route: JSONObject? = null
        private set
    @Volatile var selected: String = "play"
    /** idle | building | ok | error */
    @Volatile var detourStatus: String = "idle"
        private set
    @Volatile var detourMessage: String? = null
        private set

    fun update(lat: Double, lng: Double) {
        this.hasFix = true
        this.lat = lat
        this.lng = lng
        this.updatedAt = Instant.now().toString()
    }

    fun setBuilding(vibe: String, msg: String) {
        selected = vibe
        detourStatus = "building"
        detourMessage = msg
    }

    fun setRoute(vibe: String, r: JSONObject) {
        route = r
        selected = vibe
        detourStatus = "ok"
        detourMessage = r.optString("note").takeIf { r.has("note") && !r.isNull("note") }
        History.logDetour(r)   // v0.5.1: Detour log + Daily detours on the companion
    }

    /** Keeps the previous good route in the feed; only status flips to error. */
    fun setError(msg: String) {
        detourStatus = "error"
        detourMessage = msg
    }

    private fun stubRoute(): JSONObject {
        val pts = JSONArray()
            .put(JSONArray().put(90).put(90))
            .put(JSONArray().put(90).put(50))
            .put(JSONArray().put(90).put(10))
        return JSONObject()
            .put("from", "office")
            .put("to", "home")
            .put("summary", "demo · tap Build route on phone")
            .put("path", JSONObject().put("w", 180).put("h", 100).put("pts", pts))
    }

    fun feedJson(): String {
        val r = route
        return JSONObject()
            .put("office", Places.get(Places.WORK).toJson())
            .put("home", Places.get(Places.HOME).toJson())
            .put("selected", r?.optString("vibe", selected) ?: selected)
            .put(
                "you_are_here", JSONObject()
                    .put("lat", lat)
                    .put("lng", lng)
                    .put("has_fix", hasFix)
                    .put("updated_at", updatedAt)
            )
            .put("route", r ?: stubRoute())
            .put(
                "detour", JSONObject()
                    .put("status", detourStatus)
                    .put("message", detourMessage ?: JSONObject.NULL)
                    .put("live", r != null)
            )
            .put("meta", JSONObject().put("source", "mapidex-companion-v$VERSION"))
            .toString(2)
    }
}
