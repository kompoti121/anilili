package com.miruronative.data.remote

import com.miruronative.data.auth.AuthManager
import com.miruronative.data.model.GqlMediaListResponse
import com.miruronative.data.model.GqlMediaResponse
import com.miruronative.data.model.GqlPageResponse
import com.miruronative.data.model.GqlViewerResponse
import com.miruronative.data.model.GraphQLRequest
import com.miruronative.data.model.Media
import com.miruronative.data.model.MediaListCollection
import com.miruronative.data.model.MediaPage
import com.miruronative.data.model.Viewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AniList GraphQL metadata client. Ports the query set from MiruroAPI's anilist.js.
 * These endpoints are public and work from any IP (unlike the streaming pipe).
 */
class AniListClient(
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val mediaListFields = """
        id
        title { romaji english native }
        coverImage { large extraLarge color }
        bannerImage
        format
        season
        seasonYear
        episodes
        duration
        status
        averageScore
        popularity
        genres
        studios(isMain: true) { nodes { name isAnimationStudio } }
        nextAiringEpisode { episode airingAt timeUntilAiring }
        startDate { year month day }
    """.trimIndent()

    private val mediaFullFields = """
        id
        idMal
        title { romaji english native }
        description(asHtml: false)
        coverImage { large extraLarge color }
        bannerImage
        format
        season
        seasonYear
        episodes
        duration
        status
        averageScore
        meanScore
        popularity
        favourites
        genres
        studios { nodes { name isAnimationStudio } }
        trailer { id site thumbnail }
        nextAiringEpisode { episode airingAt timeUntilAiring }
        startDate { year month day }
        endDate { year month day }
    """.trimIndent()

    private suspend fun queryPage(query: String, variables: JsonObject, page: Int): MediaPage =
        withContext(Dispatchers.IO) {
            val text = post(query, variables)
            val parsed = json.decodeFromString(GqlPageResponse.serializer(), text).data?.page
            MediaPage(
                items = parsed?.media ?: emptyList(),
                hasNextPage = parsed?.pageInfo?.hasNextPage ?: false,
                page = page,
            )
        }

    suspend fun search(query: String, page: Int = 1, perPage: Int = 20): MediaPage {
        val gql = """
            query (${'$'}search: String, ${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { hasNextPage currentPage }
                media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH) { $mediaListFields }
              }
            }
        """.trimIndent()
        val vars = buildJsonObject {
            put("search", query)
            put("page", page)
            put("perPage", perPage)
        }
        return queryPage(gql, vars, page)
    }

    /** Generic collection fetch (trending, popular, etc.). [status] is an optional MediaStatus enum. */
    suspend fun collection(
        sort: String,
        status: String? = null,
        page: Int = 1,
        perPage: Int = 20,
    ): MediaPage {
        val statusFilter = if (status != null) ", status: $status" else ""
        val gql = """
            query (${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { hasNextPage currentPage }
                media(type: ANIME, sort: [$sort]$statusFilter) { $mediaListFields }
              }
            }
        """.trimIndent()
        val vars = buildJsonObject {
            put("page", page)
            put("perPage", perPage)
        }
        return queryPage(gql, vars, page)
    }

    /** Episodes airing within [startSec, endSec] (unix seconds), sorted by time. */
    suspend fun airingSchedule(startSec: Long, endSec: Long): List<com.miruronative.data.model.AiringSchedule> =
        withContext(Dispatchers.IO) {
            val gql = """
                query (${'$'}start: Int, ${'$'}end: Int) {
                  Page(page: 1, perPage: 50) {
                    airingSchedules(airingAt_greater: ${'$'}start, airingAt_lesser: ${'$'}end, sort: TIME) {
                      episode
                      airingAt
                      media { $mediaListFields }
                    }
                  }
                }
            """.trimIndent()
            val vars = buildJsonObject {
                put("start", startSec)
                put("end", endSec)
            }
            val text = post(gql, vars)
            json.decodeFromString(com.miruronative.data.model.GqlScheduleResponse.serializer(), text)
                .data?.page?.airingSchedules ?: emptyList()
        }

    suspend fun animeInfo(id: Int): Media? = withContext(Dispatchers.IO) {
        val gql = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) { $mediaFullFields }
            }
        """.trimIndent()
        val vars = buildJsonObject { put("id", id) }
        val text = post(gql, vars)
        json.decodeFromString(GqlMediaResponse.serializer(), text).data?.media
    }

    /** Authenticated: the logged-in user's profile + stats. */
    suspend fun viewer(): Viewer? = withContext(Dispatchers.IO) {
        val gql = """
            query {
              Viewer {
                id
                name
                avatar { large }
                bannerImage
                statistics { anime { count episodesWatched minutesWatched meanScore } }
              }
            }
        """.trimIndent()
        val text = post(gql, buildJsonObject { })
        json.decodeFromString(GqlViewerResponse.serializer(), text).data?.viewer
    }

    /** Authenticated: the user's anime lists (Watching + Planning) with per-entry progress. */
    suspend fun userAnimeList(userId: Int): MediaListCollection? = withContext(Dispatchers.IO) {
        val gql = """
            query (${'$'}userId: Int) {
              MediaListCollection(userId: ${'$'}userId, type: ANIME, status_in: [CURRENT, PLANNING, PAUSED, COMPLETED]) {
                lists {
                  name
                  status
                  entries { id progress status media { $mediaListFields } }
                }
              }
            }
        """.trimIndent()
        val vars = buildJsonObject { put("userId", userId) }
        val text = post(gql, vars)
        json.decodeFromString(GqlMediaListResponse.serializer(), text).data?.collection
    }

    /** Authenticated: creates or updates one anime list entry and its watched progress. */
    suspend fun saveMediaListEntry(mediaId: Int, status: String, progress: Int) = withContext(Dispatchers.IO) {
        val gql = """
            mutation (${'$'}mediaId: Int, ${'$'}status: MediaListStatus, ${'$'}progress: Int) {
              SaveMediaListEntry(mediaId: ${'$'}mediaId, status: ${'$'}status, progress: ${'$'}progress) {
                id progress status
              }
            }
        """.trimIndent()
        val vars = buildJsonObject {
            put("mediaId", mediaId)
            put("status", status)
            put("progress", progress)
        }
        post(gql, vars)
    }

    private fun post(query: String, variables: JsonObject): String {
        val payload = json.encodeToString(GraphQLRequest.serializer(), GraphQLRequest(query, variables))
        val builder = Request.Builder()
            .url(ANILIST_URL)
            .post(payload.toRequestBody(jsonMedia))
            .header("Accept", "application/json")
        AuthManager.current()?.let { builder.header("Authorization", "Bearer $it") }
        val request = builder.build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("AniList HTTP ${resp.code}")
            return body
        }
    }

    companion object {
        const val ANILIST_URL = "https://graphql.anilist.co"
    }
}
