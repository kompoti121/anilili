package com.miruronative.data.remote

import com.miruronative.data.auth.AuthManager
import com.miruronative.data.model.GqlMediaListResponse
import com.miruronative.data.model.GqlDiscoverOptionsResponse
import com.miruronative.data.model.GqlMediaResponse
import com.miruronative.data.model.GqlPageResponse
import com.miruronative.data.model.GqlViewerResponse
import com.miruronative.data.model.GqlViewerFavouritesResponse
import com.miruronative.data.model.GraphQLRequest
import com.miruronative.data.model.Media
import com.miruronative.data.model.DiscoverFilters
import com.miruronative.data.model.DiscoverOptions
import com.miruronative.data.model.MediaListCollection
import com.miruronative.data.model.MediaPage
import com.miruronative.data.model.Viewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
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
        idMal
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
        isAdult
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
        isAdult
        genres
        tags { name rank isMediaSpoiler isGeneralSpoiler }
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

    suspend fun search(query: String, page: Int = 1, perPage: Int = 20, hideAdult: Boolean = false): MediaPage {
        val gql = """
            query (${'$'}search: String, ${'$'}page: Int, ${'$'}perPage: Int, ${'$'}isAdult: Boolean) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { hasNextPage currentPage }
                media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH, isAdult: ${'$'}isAdult) { $mediaListFields }
              }
            }
        """.trimIndent()
        val vars = buildJsonObject {
            put("search", query)
            put("page", page)
            put("perPage", perPage)
            if (hideAdult) put("isAdult", false)
        }
        return queryPage(gql, vars, page)
    }

    /** Server-side catalog search used by Browse: every selected filter is applied by AniList. */
    suspend fun discover(filters: DiscoverFilters, page: Int = 1, perPage: Int = 30, hideAdult: Boolean = false): MediaPage {
        val gql = """
            query (
              ${'$'}search: String,
              ${'$'}page: Int,
              ${'$'}perPage: Int,
              ${'$'}genres: [String],
              ${'$'}tags: [String],
              ${'$'}year: Int,
              ${'$'}status: MediaStatus,
              ${'$'}format: MediaFormat,
              ${'$'}minimumScore: Int,
              ${'$'}sort: [MediaSort],
              ${'$'}isAdult: Boolean
            ) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { hasNextPage currentPage }
                media(
                  search: ${'$'}search,
                  type: ANIME,
                  genre_in: ${'$'}genres,
                  tag_in: ${'$'}tags,
                  seasonYear: ${'$'}year,
                  status: ${'$'}status,
                  format: ${'$'}format,
                  averageScore_greater: ${'$'}minimumScore,
                  sort: ${'$'}sort,
                  isAdult: ${'$'}isAdult
                ) { $mediaListFields }
              }
            }
        """.trimIndent()
        val vars = buildJsonObject {
            filters.query.trim().takeIf { it.isNotEmpty() }?.let { put("search", it) }
            put("page", page)
            put("perPage", perPage)
            if (hideAdult) put("isAdult", false)
            if (filters.genres.isNotEmpty()) put("genres", buildJsonArray { filters.genres.forEach(::add) })
            if (filters.tags.isNotEmpty()) put("tags", buildJsonArray { filters.tags.forEach(::add) })
            filters.year?.let { put("year", it) }
            filters.status?.let { put("status", it) }
            filters.format?.let { put("format", it) }
            filters.minimumScore?.let { put("minimumScore", it) }
            put("sort", buildJsonArray { add(filters.sort) })
        }
        return queryPage(gql, vars, page)
    }

    /** AniList-owned genre and tag catalog; adult tags are intentionally excluded in the app UI. */
    suspend fun discoverOptions(): DiscoverOptions = withContext(Dispatchers.IO) {
        val gql = """
            query {
              GenreCollection
              MediaTagCollection { name description category isAdult }
            }
        """.trimIndent()
        val text = post(gql, buildJsonObject { })
        val data = json.decodeFromString(GqlDiscoverOptionsResponse.serializer(), text).data
        DiscoverOptions(
            genres = data?.genres.orEmpty().sorted(),
            tags = data?.tags.orEmpty().filterNot { it.isAdult }.sortedBy { it.name },
        )
    }

    /** Generic collection fetch (trending, popular, etc.). [status] is an optional MediaStatus enum. */
    suspend fun collection(
        sort: String,
        status: String? = null,
        page: Int = 1,
        perPage: Int = 20,
        hideAdult: Boolean = false,
    ): MediaPage {
        val statusFilter = if (status != null) ", status: $status" else ""
        val adultFilter = if (hideAdult) ", isAdult: false" else ""
        val gql = """
            query (${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { hasNextPage currentPage }
                media(type: ANIME, sort: [$sort]$statusFilter$adultFilter) { $mediaListFields }
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
                createdAt
                statistics { anime { count episodesWatched minutesWatched meanScore } }
              }
            }
        """.trimIndent()
        val text = post(gql, buildJsonObject { })
        json.decodeFromString(GqlViewerResponse.serializer(), text).data?.viewer
    }

    /** Authenticated: every anime the current user has marked as a favourite. */
    suspend fun favouriteAnime(): List<Media> = withContext(Dispatchers.IO) {
        val all = mutableListOf<Media>()
        var page = 1
        var hasNext = true
        while (hasNext && page <= 10) {
            val gql = """
                query (${'$'}page: Int) {
                  Viewer {
                    favourites {
                      anime(page: ${'$'}page, perPage: 50) {
                        pageInfo { hasNextPage currentPage }
                        nodes { $mediaListFields }
                      }
                    }
                  }
                }
            """.trimIndent()
            val text = post(gql, buildJsonObject { put("page", page) })
            val connection = json.decodeFromString(GqlViewerFavouritesResponse.serializer(), text)
                .data?.viewer?.favourites?.anime ?: break
            all += connection.nodes
            hasNext = connection.pageInfo.hasNextPage
            page++
        }
        all.distinctBy { it.id }
    }

    /**
     * Authenticated: the viewer's notification feed, newest first. AniList has no per-item
     * read flag — the first [unreadCount] entries are the unread ones. Passing
     * [markAllRead] resets the server-side unread counter.
     */
    suspend fun notifications(markAllRead: Boolean = false): Pair<List<com.miruronative.data.model.AppNotification>, Int> =
        withContext(Dispatchers.IO) {
            val mediaFields = "media { id title { romaji english } coverImage { large } bannerImage }"
            val userFields = "user { id name avatar { large } }"
            val gql = """
                query (${'$'}reset: Boolean) {
                  Viewer { unreadNotificationCount }
                  Page(page: 1, perPage: 50) {
                    notifications(resetNotificationCount: ${'$'}reset) {
                      __typename
                      ... on AiringNotification { id createdAt episode $mediaFields }
                      ... on RelatedMediaAdditionNotification { id createdAt context $mediaFields }
                      ... on MediaDataChangeNotification { id createdAt context $mediaFields }
                      ... on MediaMergeNotification { id createdAt reason $mediaFields }
                      ... on FollowingNotification { id createdAt context $userFields }
                      ... on ActivityMessageNotification { id createdAt context $userFields }
                      ... on ActivityMentionNotification { id createdAt context $userFields }
                      ... on ActivityReplyNotification { id createdAt context $userFields }
                      ... on ActivityLikeNotification { id createdAt context $userFields }
                      ... on ActivityReplyLikeNotification { id createdAt context $userFields }
                    }
                  }
                }
            """.trimIndent()
            val text = post(gql, buildJsonObject { put("reset", markAllRead) })
            val root = json.parseToJsonElement(text).jsonObject["data"]?.jsonObject
            val unreadCount = root?.get("Viewer")?.jsonObject
                ?.get("unreadNotificationCount")?.jsonPrimitive?.intOrNull ?: 0
            val items = (root?.get("Page")?.jsonObject?.get("notifications") as? kotlinx.serialization.json.JsonArray)
                .orEmpty()
                .mapIndexedNotNull { index, element ->
                    parseNotification(element as? JsonObject ?: return@mapIndexedNotNull null, unread = index < unreadCount)
                }
            items to unreadCount
        }

    private fun parseNotification(obj: JsonObject, unread: Boolean): com.miruronative.data.model.AppNotification? {
        val typename = obj["__typename"]?.jsonPrimitive?.contentOrNull ?: return null
        val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return null
        val createdAt = obj["createdAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val media = obj["media"] as? JsonObject
        val user = obj["user"] as? JsonObject
        val mediaTitle = (media?.get("title") as? JsonObject)?.let {
            it["english"]?.jsonPrimitive?.contentOrNull ?: it["romaji"]?.jsonPrimitive?.contentOrNull
        }
        val userName = user?.get("name")?.jsonPrimitive?.contentOrNull
        val context = obj["context"]?.jsonPrimitive?.contentOrNull
            ?: obj["reason"]?.jsonPrimitive?.contentOrNull
        val kind = when (typename) {
            "AiringNotification" -> com.miruronative.data.model.AppNotification.Kind.AIRING
            "RelatedMediaAdditionNotification",
            "MediaDataChangeNotification",
            "MediaMergeNotification",
            -> com.miruronative.data.model.AppNotification.Kind.MEDIA
            else -> com.miruronative.data.model.AppNotification.Kind.SOCIAL
        }
        val badge = when (typename) {
            "AiringNotification" -> obj["episode"]?.jsonPrimitive?.intOrNull?.let { "EP $it" }
            "RelatedMediaAdditionNotification" -> "NEW RELATED"
            "MediaDataChangeNotification", "MediaMergeNotification" -> "MEDIA UPDATE"
            else -> null
        }
        return com.miruronative.data.model.AppNotification(
            id = id,
            kind = kind,
            createdAt = createdAt,
            title = mediaTitle ?: userName ?: "AniList",
            badge = badge,
            detail = if (kind == com.miruronative.data.model.AppNotification.Kind.SOCIAL) {
                listOfNotNull(userName, context?.trim()).joinToString(" ").ifBlank { null }
            } else {
                null
            },
            mediaId = media?.get("id")?.jsonPrimitive?.intOrNull,
            image = (media?.get("coverImage") as? JsonObject)?.get("large")?.jsonPrimitive?.contentOrNull
                ?: (user?.get("avatar") as? JsonObject)?.get("large")?.jsonPrimitive?.contentOrNull,
            banner = media?.get("bannerImage")?.jsonPrimitive?.contentOrNull,
            unread = unread,
        )
    }

    /** Authenticated: the user's anime lists (Watching + Planning) with per-entry progress. */
    suspend fun userAnimeList(userId: Int): MediaListCollection? = withContext(Dispatchers.IO) {
        val gql = """
            query (${'$'}userId: Int) {
              MediaListCollection(userId: ${'$'}userId, type: ANIME, status_in: [CURRENT, REPEATING, PLANNING, PAUSED, COMPLETED, DROPPED]) {
                lists {
                  name
                  status
                  entries { id progress score(format: POINT_10_DECIMAL) status media { $mediaListFields } }
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

    /**
     * Mirrors the device Save button without damaging an existing AniList status/progress.
     * New saves become PLANNING; unsaving only removes entries that are still PLANNING.
     */
    suspend fun syncSavedAnime(mediaId: Int, saved: Boolean) = withContext(Dispatchers.IO) {
        val current = mediaListEntry(mediaId)
        when {
            saved && current == null -> {
                val mutation = """
                    mutation (${'$'}mediaId: Int) {
                      SaveMediaListEntry(mediaId: ${'$'}mediaId, status: PLANNING) { id status }
                    }
                """.trimIndent()
                post(mutation, buildJsonObject { put("mediaId", mediaId) })
            }
            !saved && current?.second == "PLANNING" -> {
                val mutation = """
                    mutation (${'$'}id: Int) {
                      DeleteMediaListEntry(id: ${'$'}id) { deleted }
                    }
                """.trimIndent()
                post(mutation, buildJsonObject { put("id", current.first) })
            }
        }
    }

    private fun mediaListEntry(mediaId: Int): Pair<Int, String>? {
        val query = """
            query (${'$'}mediaId: Int) {
              Media(id: ${'$'}mediaId, type: ANIME) { mediaListEntry { id status } }
            }
        """.trimIndent()
        val text = post(query, buildJsonObject { put("mediaId", mediaId) })
        val entry = json.parseToJsonElement(text).jsonObject["data"]?.jsonObject
            ?.get("Media")?.jsonObject
            ?.get("mediaListEntry") as? JsonObject ?: return null
        val id = entry["id"]?.jsonPrimitive?.intOrNull ?: return null
        val status = entry["status"]?.jsonPrimitive?.contentOrNull ?: return null
        return id to status
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
