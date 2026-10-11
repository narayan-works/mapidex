package com.narayan.mapidex.companion

import android.content.Context
import org.json.JSONObject
import java.time.Instant

/** A saved place (home / work). isDefault = still the seeded value, not set by the user. */
data class Place(
    val key: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val isDefault: Boolean,
    val savedAt: String?
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("lat", lat)
        .put("lng", lng)
        .put("is_default", isDefault)
        .put("saved_at", savedAt ?: JSONObject.NULL)
}

/** Home / work persisted in SharedPreferences, seeded with demo defaults. */
object Places {
    const val WORK = "work"
    const val HOME = "home"

    private const val PREFS = "mapidex_places"

    private val defaults = mapOf(
        WORK to Place(WORK, "Founders Inc", 37.8067218, -122.4318093, true, null),
        HOME to Place(HOME, "2143 Greenwich", 37.7989871, -122.4349331, true, null)
    )

    @Volatile private var cache: Map<String, Place> = defaults

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cache = defaults.mapValues { (key, def) ->
            if (!p.contains("${key}_lat")) def
            else Place(
                key = key,
                name = p.getString("${key}_name", null) ?: def.name,
                lat = java.lang.Double.longBitsToDouble(p.getLong("${key}_lat", 0L)),
                lng = java.lang.Double.longBitsToDouble(p.getLong("${key}_lng", 0L)),
                isDefault = false,
                savedAt = p.getString("${key}_saved_at", null)
            )
        }
    }

    fun get(key: String): Place = cache[key] ?: defaults.getValue(key)

    fun save(ctx: Context, key: String, name: String, lat: Double, lng: Double): Place {
        val place = Place(key, name.ifBlank { get(key).name }, lat, lng, false, Instant.now().toString())
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("${key}_name", place.name)
            .putLong("${key}_lat", java.lang.Double.doubleToRawLongBits(lat))
            .putLong("${key}_lng", java.lang.Double.doubleToRawLongBits(lng))
            .putString("${key}_saved_at", place.savedAt)
            .apply()
        cache = cache + (key to place)
        return place
    }

    /** Rename without moving the pin. */
    fun rename(ctx: Context, key: String, name: String): Place {
        val cur = get(key)
        return save(ctx, key, name, cur.lat, cur.lng)
    }

    fun reset(ctx: Context, key: String): Place {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("${key}_name").remove("${key}_lat").remove("${key}_lng").remove("${key}_saved_at")
            .apply()
        val def = defaults.getValue(key)
        cache = cache + (key to def)
        return def
    }
}
