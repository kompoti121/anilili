package com.miruronative.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Minimal Jikan (MyAnimeList) client. Only used for per-episode filler flags,
 * which AniList doesn't track.
 */
class JikanClient(
    private val client: OkHttpClient,
    private val json: Json,
) {
    /** Episode numbers marked as filler on MAL. Empty when unknown or none. */
    fun fillerEpisodes(malId: Int): Set<Int> {
        val fillers = mutableSetOf<Int>()
        var page = 1
        while (page <= MAX_PAGES) {
            val root = runCatching {
                val request = Request.Builder()
                    .url("https://api.jikan.moe/v4/anime/$malId/episodes?page=$page")
                    .header("Accept", "application/json")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Jikan HTTP ${response.code}")
                    json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                }
            }.getOrNull() ?: break
            val episodes = root["data"]?.jsonArray ?: break
            episodes.forEach { element ->
                val episode = element.jsonObject
                if (episode["filler"]?.jsonPrimitive?.booleanOrNull == true) {
                    episode["mal_id"]?.jsonPrimitive?.intOrNull?.let(fillers::add)
                }
            }
            val hasNext = root["pagination"]?.jsonObject
                ?.get("has_next_page")?.jsonPrimitive?.booleanOrNull ?: false
            if (!hasNext) break
            page++
        }
        return fillers
    }

    private companion object {
        // 100 episodes per page; 15 pages covers the longest running series that matter.
        const val MAX_PAGES = 15
    }
}
