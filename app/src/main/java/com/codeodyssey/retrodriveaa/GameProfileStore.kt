package com.codeodyssey.retrodriveaa

import android.content.Context
import org.json.JSONObject

object GameProfileStore {
    private const val PREFS_NAME = "retrodrive_game_profiles"
    private const val KEY_PROFILES = "profiles_json"
    private val legacyDefaultOverrides = mapOf(
        "sdl.fullresolution" to "desktop",
        "sdl.output" to "surface",
        "sdl.usescancodes" to "true",
        "mixer.rate" to "22050",
        "mixer.blocksize" to "1024",
        "sblaster.oplrate" to "22050",
        "gus.gusrate" to "22050",
        "speaker.pcrate" to "22050",
        "speaker.tandyrate" to "22050"
    )

    fun load(context: Context, gameId: String): GameProfile {
        val all = loadAll(context)
        return all[gameId] ?: GameProfile(gameId = gameId)
    }

    fun save(context: Context, profile: GameProfile) {
        val all = loadAll(context).toMutableMap()
        all[profile.gameId] = profile
        persistAll(context, all)
    }

    fun delete(context: Context, gameId: String) {
        val all = loadAll(context).toMutableMap()
        all.remove(gameId)
        persistAll(context, all)
    }

    private fun loadAll(context: Context): Map<String, GameProfile> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILES, "{}") ?: "{}"
        val root = try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }

        val result = linkedMapOf<String, GameProfile>()
        var mutated = false
        val keys = root.keys()
        while (keys.hasNext()) {
            val gameId = keys.next()
            val profileObj = root.optJSONObject(gameId) ?: JSONObject()
            val overridesObj = profileObj.optJSONObject("configOverrides") ?: JSONObject()
            val overrides = linkedMapOf<String, String>()
            val overrideKeys = overridesObj.keys()
            while (overrideKeys.hasNext()) {
                val key = overrideKeys.next()
                overrides[key] = overridesObj.optString(key, "")
            }
            val normalizedOverrides = normalizeLegacyOverrides(overrides)
            if (normalizedOverrides != overrides) {
                mutated = true
            }
            result[gameId] = GameProfile(gameId = gameId, configOverrides = normalizedOverrides)
        }

        if (mutated) {
            persistAll(context, result)
        }

        return result
    }

    private fun normalizeLegacyOverrides(overrides: Map<String, String>): Map<String, String> {
        if (overrides.isEmpty()) {
            return overrides
        }

        val containsLegacyBundle = legacyDefaultOverrides.all { (key, value) -> overrides[key] == value }
        if (!containsLegacyBundle) {
            return overrides
        }

        return overrides.toMutableMap().apply {
            legacyDefaultOverrides.keys.forEach(::remove)
        }
    }

    private fun persistAll(context: Context, profiles: Map<String, GameProfile>) {
        val root = JSONObject()
        profiles.forEach { (gameId, profile) ->
            val profileObj = JSONObject()
            val overridesObj = JSONObject()
            profile.configOverrides.forEach { (key, value) ->
                overridesObj.put(key, value)
            }
            profileObj.put("configOverrides", overridesObj)
            root.put(gameId, profileObj)
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES, root.toString())
            .apply()
    }
}
