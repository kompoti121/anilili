package com.miruronative.data.remote

import com.miruronative.data.auth.AuthManager
import com.miruronative.data.model.GqlMediaListResponse
import com.miruronative.data.model.GqlDiscoverOptionsResponse
import com.miruronative.data.model.GqlHomeCollectionsResponse
import com.miruronative.data.model.GqlMediaResponse
import com.miruronative.data.model.GqlPageResponse
import com.miruronative.data.model.GqlStudioPageResponse
import com.miruronative.data.model.GqlViewerResponse
import com.miruronative.data.model.GqlViewerFavouritesResponse
import com.miruronative.data.model.GraphQLRequest
import com.miruronative.data.model.Media
import com.miruronative.data.model.HomeCollections
import com.miruronative.data.model.DiscoverFilters
import com.miruronative.data.model.DiscoverOptions
import com.miruronative.data.model.MediaListCollection
import com.miruronative.data.model.MediaPage
import com.miruronative.data.model.StudioNode
import com.miruronative.data.model.Viewer
import com.miruronative.diagnostics.DiagnosticsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * AniList GraphQL metadata client. Ports the query set from MiruroAPI's anilist.js.
 * These endpoints are public and work from any IP (unlike the streaming pipe).
 */
class AniListClient(
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // Client-side pacing so bursts (the home screen's parallel loads, list sync, the related-
    // seasons walk) don't hit AniList's degraded ~30/min limit and earn a 1-minute 429 timeout.
    // We read X-RateLimit-Remaining/Reset from every response and glide toward the window reset as
    // the budget runs low, instead of only reacting to a 429 after the fact.
    private val rateGate = Mutex()
    private var nextSlotMs = 0L // guarded by rateGate
    @Volatile private var rateRemaining = Int.MAX_VALUE
    @Volatile private var rateResetMs = 0L
    @Volatile private var rateLimit = DEFAULT_RATE_LIMIT
    private val studioMediaIdCache = ConcurrentHashMap<Int, List<Int>>()

    /** Reserve the next request slot, spacing bursts and backing off as the budget nears zero. */
    private suspend fun awaitRateSlot() {
        val slot = rateGate.withLock {
            val (start, next) = nextRateSlot(
                now = System.currentTimeMillis(),
                remaining = rateRemaining,
                reset = rateResetMs,
                nextSlot = nextSlotMs,
                limit = rateLimit,
            )
            nextSlotMs = next
            start
        }
        val wait = slot - System.currentTimeMillis()
        if (wait > 0) {
            if (wait >= SLOW_WAIT_LOG_MS) {
                DiagnosticsLog.event("AniList throttle waiting ${wait}ms (remaining=$rateRemaining)")
            }
            delay(wait)
        }
    }

    private fun recordRateHeaders(limitHeader: String?, remainingHeader: String?, resetHeader: String?) {
        limitHeader?.toIntOrNull()?.takeIf { it > 0 }?.let { rateLimit = it }
        remainingHeader?.toIntOrNull()?.let { rateRemaining = it }
        resetHeader?.toLongOrNull()?.let { rateResetMs = it * 1_000 }
    }

    private val mediaListFields = """
        id
        idMal
        title { romaji english native userPreferred }
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
        studios(isMain: true) { nodes { id name isAnimationStudio } }
        nextAiringEpisode { episode airingAt timeUntilAiring }
        startDate { year month day }
    """.trimIndent()

    private val mediaFullFields = """
        id
        idMal
        title { romaji english native userPreferred }
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
        studios { nodes { id name isAnimationStudio } }
        trailer { id site thumbnail }
        nextAiringEpisode { episode airingAt timeUntilAiring }
        startDate { year month day }
        endDate { year month day }
        relations {
          edges {
            relationType
            node { $mediaListFields }
          }
        }
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

    /** The five Home collections in one GraphQL operation, avoiding a burst on every cold start. */
    suspend fun homeCollections(hideAdult: Boolean = false): HomeCollections = withContext(Dispatchers.IO) {
        val gql = """
            query (${'$'}isAdult: Boolean, ${'$'}airedBefore: Int) {
              spotlight: Page(page: 1, perPage: 30) {
                media(type: ANIME, sort: [TRENDING_DESC], isAdult: ${'$'}isAdult) { $mediaListFields }
              }
              newest: Page(page: 1, perPage: 50) {
                airingSchedules(airingAt_lesser: ${'$'}airedBefore, sort: TIME_DESC) {
                  media { $mediaListFields }
                }
              }
              popular: Page(page: 1, perPage: 30) {
                media(type: ANIME, sort: [POPULARITY_DESC], isAdult: ${'$'}isAdult) { $mediaListFields }
              }
              movies: Page(page: 1, perPage: 30) {
                media(type: ANIME, format: MOVIE, sort: [POPULARITY_DESC], isAdult: ${'$'}isAdult) { $mediaListFields }
              }
              topRated: Page(page: 1, perPage: 30) {
                media(type: ANIME, sort: [SCORE_DESC], isAdult: ${'$'}isAdult) { $mediaListFields }
              }
            }
        """.trimIndent()
        val variables = buildJsonObject {
            if (hideAdult) put("isAdult", false)
            // airingAt is the broadcast start; allow ~25 min runtime plus an hour for
            // streaming sites to pick the episode up, so every entry is actually watchable.
            put("airedBefore", System.currentTimeMillis() / 1000 - NEWEST_AIRED_BUFFER_SEC)
        }
        val data = json.decodeFromString(GqlHomeCollectionsResponse.serializer(), post(gql, variables)).data
        HomeCollections(
            spotlight = data?.spotlight?.media.orEmpty(),
            // Recently aired episodes, newest first; a show airing several times in the
            // window appears once. isAdult can't be filtered inside airingSchedules.
            newest = data?.newest?.airingSchedules.orEmpty()
                .mapNotNull { it.media }
                .distinctBy { it.id }
                .filterNot { hideAdult && it.isAdult },
            popular = data?.popular?.media.orEmpty(),
            movies = data?.movies?.media.orEmpty(),
            topRated = data?.topRated?.media.orEmpty(),
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
              ${'$'}mediaIds: [Int],
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
                  id_in: ${'$'}mediaIds,
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
        val studioMediaIds = filters.studioId?.takeIf { it > 0 }?.let { mediaIdsForStudio(it) }
        if (filters.studioId != null && studioMediaIds.isNullOrEmpty()) {
            return MediaPage(items = emptyList(), hasNextPage = false, page = page)
        }
        val vars = discoverVariables(filters, page, perPage, hideAdult, studioMediaIds)
        return queryPage(gql, vars, page)
    }

    /**
     * AniList has no studio argument on Page.media. Resolve a studio's media IDs through its
     * connection once, then use Page.media's id_in so every other Browse filter remains server-side.
     * Twenty 25-item aliases match AniList's public 500-result pagination ceiling in one request.
     */
    private suspend fun mediaIdsForStudio(studioId: Int): List<Int> {
        studioMediaIdCache[studioId]?.let { return it }
        val pageFields = (1..MAX_STUDIO_MEDIA_PAGES).joinToString("\n") { page ->
            "p$page: media(page: $page, perPage: $STUDIO_MEDIA_PAGE_SIZE) { nodes { id } }"
        }
        val gql = """
            query (${'$'}studioId: Int) {
              Studio(id: ${'$'}studioId) {
                $pageFields
              }
            }
        """.trimIndent()
        val vars = buildJsonObject { put("studioId", studioId) }
        val root = json.parseToJsonElement(post(gql, vars)).jsonObject
        val data = root["data"] as? JsonObject
        val studio = data?.get("Studio") as? JsonObject
        val ids = (1..MAX_STUDIO_MEDIA_PAGES)
            .flatMap { page ->
                val connection = studio?.get("p$page") as? JsonObject
                val nodes = connection?.get("nodes") as? JsonArray
                nodes.orEmpty().mapNotNull { node ->
                    (node as? JsonObject)?.get("id")?.jsonPrimitive?.intOrNull
                }
            }
            .distinct()
        studioMediaIdCache.putIfAbsent(studioId, ids)
        return studioMediaIdCache[studioId].orEmpty()
    }

    /** Studio suggestions for Browse. AniList media filtering requires the selected studio ID. */
    suspend fun searchStudios(query: String, perPage: Int = 12): List<StudioNode> = withContext(Dispatchers.IO) {
        val term = query.trim()
        if (term.isEmpty()) return@withContext emptyList()
        val gql = """
            query (${'$'}search: String, ${'$'}perPage: Int) {
              Page(page: 1, perPage: ${'$'}perPage) {
                studios(search: ${'$'}search, sort: [SEARCH_MATCH]) {
                  id
                  name
                  isAnimationStudio
                }
              }
            }
        """.trimIndent()
        val vars = buildJsonObject {
            put("search", term)
            put("perPage", perPage.coerceIn(1, 25))
        }
        json.decodeFromString(GqlStudioPageResponse.serializer(), post(gql, vars))
            .data?.page?.studios.orEmpty()
            .filter { it.id > 0 && it.isAnimationStudio && !it.name.isNullOrBlank() }
    }

    /** AniList-owned genre and tag catalog, filtered using the user's adult-content setting. */
    suspend fun discoverOptions(hideAdult: Boolean): DiscoverOptions = withContext(Dispatchers.IO) {
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
            tags = data?.tags.orEmpty()
                .let { tags -> if (hideAdult) tags.filterNot { it.isAdult } else tags }
                .sortedBy { it.name },
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

    /** Episodes airing in the half-open range [startSec, endExclusiveSec), sorted by time. */
    suspend fun airingSchedule(startSec: Long, endExclusiveSec: Long): List<com.miruronative.data.model.AiringSchedule> =
        withContext(Dispatchers.IO) {
            val gql = """
                query (${'$'}start: Int, ${'$'}end: Int, ${'$'}page: Int) {
                  Page(page: ${'$'}page, perPage: 50) {
                    pageInfo { hasNextPage currentPage }
                    airingSchedules(airingAt_greater: ${'$'}start, airingAt_lesser: ${'$'}end, sort: TIME) {
                      episode
                      airingAt
                      media { $mediaListFields }
                    }
                  }
                }
            """.trimIndent()
            val all = mutableListOf<com.miruronative.data.model.AiringSchedule>()
            var page = 1
            var hasNext = true
            while (hasNext && page <= MAX_SCHEDULE_PAGES) {
                val vars = buildJsonObject {
                    // AniList uses strict comparisons, so move both bounds out by one second.
                    put("start", startSec - 1)
                    put("end", endExclusiveSec)
                    put("page", page)
                }
                val parsed = json.decodeFromString(
                    com.miruronative.data.model.GqlScheduleResponse.serializer(),
                    post(gql, vars),
                ).data?.page ?: break
                all += parsed.airingSchedules
                hasNext = parsed.pageInfo.hasNextPage
                page++
            }
            all.distinctBy { it.airingAt to it.media?.id }
        }

    /** AniList media for the given MAL ids (one page per 50); unknown ids are simply absent. */
    suspend fun mediaByMalIds(malIds: List<Int>): List<Media> = withContext(Dispatchers.IO) {
        val gql = """
            query (${'$'}ids: [Int]) {
              Page(page: 1, perPage: 50) {
                media(type: ANIME, idMal_in: ${'$'}ids) { $mediaListFields }
              }
            }
        """.trimIndent()
        malIds.distinct().chunked(50).flatMap { chunk ->
            val vars = buildJsonObject {
                put("ids", kotlinx.serialization.json.JsonArray(chunk.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }
            json.decodeFromString(GqlPageResponse.serializer(), post(gql, vars)).data?.page?.media.orEmpty()
        }
    }

    /** AniList media for the given AniList ids (one page per 50); unknown ids are simply absent. */
    suspend fun mediaByIds(ids: List<Int>): List<Media> = withContext(Dispatchers.IO) {
        val gql = """
            query (${'$'}ids: [Int]) {
              Page(page: 1, perPage: 50) {
                media(type: ANIME, id_in: ${'$'}ids) { $mediaListFields }
              }
            }
        """.trimIndent()
        ids.distinct().chunked(50).flatMap { chunk ->
            val vars = buildJsonObject {
                put("ids", kotlinx.serialization.json.JsonArray(chunk.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }
            json.decodeFromString(GqlPageResponse.serializer(), post(gql, vars)).data?.page?.media.orEmpty()
        }
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
        val text = post(gql, buildJsonObject { }, authenticated = true)
        json.decodeFromString(GqlViewerResponse.serializer(), text).data?.viewer
    }

    /** Authenticated: every anime the current user has marked as a favourite. */
    suspend fun favouriteAnime(): List<Media> = withContext(Dispatchers.IO) {
        val all = mutableListOf<Media>()
        var page = 1
        var hasNext = true
        while (hasNext && page <= MAX_FAVOURITE_PAGES) {
            val gql = """
                query (${'$'}page: Int) {
                  Viewer {
                    favourites {
                      anime(page: ${'$'}page, perPage: 25) {
                        pageInfo { hasNextPage currentPage }
                        nodes { $mediaListFields }
                      }
                    }
                  }
                }
            """.trimIndent()
            val text = post(gql, buildJsonObject { put("page", page) }, authenticated = true)
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
            val mediaFields = "media { id title { romaji english userPreferred } coverImage { large } bannerImage }"
            val userFields = "user { id name avatar { large } }"
            val gql = """
                query {
                  Viewer { unreadNotificationCount }
                  Page(page: 1, perPage: 50) {
                    notifications(resetNotificationCount: false) {
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
            val text = post(gql, buildJsonObject { }, authenticated = true)
            val root = json.parseToJsonElement(text).jsonObject["data"]?.jsonObject
            val unreadCount = root?.get("Viewer")?.jsonObject
                ?.get("unreadNotificationCount")?.jsonPrimitive?.intOrNull ?: 0
            val items = (root?.get("Page")?.jsonObject?.get("notifications") as? kotlinx.serialization.json.JsonArray)
                .orEmpty()
                .mapIndexedNotNull { index, element ->
                    parseNotification(element as? JsonObject ?: return@mapIndexedNotNull null, unread = index < unreadCount)
                }
            // Keep the original count long enough to mark the correct rows in this response, then
            // reset it in a separate operation; GraphQL sibling root fields have no ordering.
            if (markAllRead && unreadCount > 0) markNotificationsRead()
            items to unreadCount
        }

    private suspend fun markNotificationsRead() {
        val gql = """
            query {
              Page(page: 1, perPage: 1) {
                notifications(resetNotificationCount: true) { __typename }
              }
            }
        """.trimIndent()
        post(gql, buildJsonObject { }, authenticated = true)
    }

    private fun parseNotification(obj: JsonObject, unread: Boolean): com.miruronative.data.model.AppNotification? {
        val typename = obj["__typename"]?.jsonPrimitive?.contentOrNull ?: return null
        val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return null
        val createdAt = obj["createdAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val media = obj["media"] as? JsonObject
        val user = obj["user"] as? JsonObject
        val mediaTitle = (media?.get("title") as? JsonObject)?.let {
            it["english"]?.jsonPrimitive?.contentOrNull
                ?: it["userPreferred"]?.jsonPrimitive?.contentOrNull
                ?: it["romaji"]?.jsonPrimitive?.contentOrNull
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
                  isCustomList
                  entries { id progress score(format: POINT_10_DECIMAL) status media { $mediaListFields } }
                }
              }
            }
        """.trimIndent()
        val vars = buildJsonObject { put("userId", userId) }
        val text = post(gql, vars, authenticated = true)
        json.decodeFromString(GqlMediaListResponse.serializer(), text).data?.collection
    }

    /** Update watched progress without regressing progress or overwriting user-owned list states. */
    suspend fun syncMediaListProgress(
        mediaId: Int,
        progress: Int,
        totalEpisodes: Int?,
    ): MediaListProgressUpdate? = withContext(Dispatchers.IO) {
        val update = planMediaListProgressUpdate(mediaListEntry(mediaId), progress, totalEpisodes)
            ?: return@withContext null
        val statusDeclaration = if (update.status != null) ", ${'$'}status: MediaListStatus" else ""
        val statusArgument = if (update.status != null) ", status: ${'$'}status" else ""
        val gql = """
            mutation (${'$'}mediaId: Int, ${'$'}progress: Int$statusDeclaration) {
              SaveMediaListEntry(mediaId: ${'$'}mediaId, progress: ${'$'}progress$statusArgument) {
                id progress status
              }
            }
        """.trimIndent()
        val vars = buildJsonObject {
            put("mediaId", mediaId)
            put("progress", update.progress)
            update.status?.let { put("status", it) }
        }
        post(gql, vars, authenticated = true)
        update
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
                post(mutation, buildJsonObject { put("mediaId", mediaId) }, authenticated = true)
            }
            !saved && current?.status == "PLANNING" -> {
                val mutation = """
                    mutation (${'$'}id: Int) {
                      DeleteMediaListEntry(id: ${'$'}id) { deleted }
                    }
                """.trimIndent()
                post(mutation, buildJsonObject { put("id", current.id) }, authenticated = true)
            }
        }
    }

    /** Reconcile all device saves in one list read plus small mutation batches. */
    suspend fun syncSavedAnime(mediaIds: Collection<Int>, viewerId: Int) = withContext(Dispatchers.IO) {
        val requested = mediaIds.filter { it > 0 }.distinct()
        if (requested.isEmpty()) return@withContext
        val existingIds = userAnimeList(viewerId)?.lists.orEmpty()
            .flatMap { it.entries }
            .mapNotNull { it.media?.id }
            .toHashSet()
        requested.filterNot(existingIds::contains).chunked(SAVED_SYNC_BATCH_SIZE).forEach { batch ->
            val declarations = batch.indices.joinToString { index -> "${'$'}id$index: Int" }
            val fields = batch.indices.joinToString("\n") { index ->
                "save$index: SaveMediaListEntry(mediaId: ${'$'}id$index, status: PLANNING) { id status }"
            }
            val mutation = "mutation ($declarations) {\n$fields\n}"
            val variables = buildJsonObject { batch.forEachIndexed { index, id -> put("id$index", id) } }
            post(mutation, variables, authenticated = true)
        }
    }

    private suspend fun mediaListEntry(mediaId: Int): MediaListProgressSnapshot? {
        val query = """
            query (${'$'}mediaId: Int) {
              Media(id: ${'$'}mediaId, type: ANIME) { mediaListEntry { id status progress } }
            }
        """.trimIndent()
        val text = post(query, buildJsonObject { put("mediaId", mediaId) }, authenticated = true)
        val entry = json.parseToJsonElement(text).jsonObject["data"]?.jsonObject
            ?.get("Media")?.jsonObject
            ?.get("mediaListEntry") as? JsonObject ?: return null
        val id = entry["id"]?.jsonPrimitive?.intOrNull ?: return null
        val status = entry["status"]?.jsonPrimitive?.contentOrNull ?: return null
        val progress = entry["progress"]?.jsonPrimitive?.intOrNull ?: 0
        return MediaListProgressSnapshot(id = id, status = status, progress = progress)
    }

    /**
     * AniList rate-limits the whole API per user/IP (degraded to ~30 req/min). On 429 we honor
     * the Retry-After header and retry instead of surfacing the error, so bursts (list sync,
     * fast browsing) and interactive saves recover on their own.
     */
    private suspend fun post(query: String, variables: JsonObject, authenticated: Boolean = false): String {
        val payload = json.encodeToString(GraphQLRequest.serializer(), GraphQLRequest(query, variables))
        var attempt = 0
        while (true) {
            awaitRateSlot()
            val builder = Request.Builder()
                .url(ANILIST_URL)
                .post(payload.toRequestBody(jsonMedia))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
            if (authenticated) {
                val token = AuthManager.current() ?: throw IOException("Sign in to AniList again to continue")
                builder.header("Authorization", "Bearer $token")
            }
            var retryAfterMs = -1L
            val response = try {
                client.newCall(builder.build()).awaitResponse()
            } catch (e: java.io.InterruptedIOException) {
                currentCoroutineContext().ensureActive()
                // ISP-level blocks of anilist.co (common in some regions) surface as timeouts
                // while the rest of the app's hosts work fine. Name the fix for the user.
                throw IOException(
                    "AniList is unreachable (timeout). Your network may be blocking anilist.co — " +
                        "try private DNS (dns.google) or a VPN.",
                    e,
                )
            } catch (e: java.net.UnknownHostException) {
                currentCoroutineContext().ensureActive()
                throw IOException(
                    "AniList is unreachable (DNS). Your network may be blocking anilist.co — " +
                        "try private DNS (dns.google) or a VPN.",
                    e,
                )
            }
            response.use { resp ->
                recordRateHeaders(
                    resp.header("X-RateLimit-Limit"),
                    resp.header("X-RateLimit-Remaining"),
                    resp.header("X-RateLimit-Reset"),
                )
                if (resp.code == 429 && attempt < MAX_RATE_LIMIT_RETRIES) {
                    val seconds = (resp.header("Retry-After")?.toLongOrNull() ?: DEFAULT_RETRY_AFTER_SECONDS)
                        .coerceAtLeast(1)
                    retryAfterMs = seconds.coerceAtMost(Long.MAX_VALUE / 1_000) * 1_000
                    rateRemaining = 0
                    rateResetMs = maxOf(rateResetMs, System.currentTimeMillis() + retryAfterMs)
                } else {
                    val body = resp.body?.string().orEmpty()
                    val graphQlErrors = graphQlErrorMessages(body)
                    if (resp.code == 401) {
                        throw IOException("AniList sign-in expired or was revoked. Sign in again.")
                    }
                    if (!resp.isSuccessful) {
                        val detail = graphQlErrors.takeIf { it.isNotEmpty() }?.joinToString("; ")?.let { ": $it" }.orEmpty()
                        val guidance = if (resp.code == 403) {
                            " AniList may be unavailable or this network may be blocked; try again or switch networks."
                        } else {
                            ""
                        }
                        throw IOException("AniList HTTP ${resp.code}$detail.$guidance".trim())
                    }
                    if (graphQlErrors.isNotEmpty()) {
                        throw IOException("AniList GraphQL error: ${graphQlErrors.joinToString("; ")}")
                    }
                    return body
                }
            }
            attempt++
            delay(retryAfterMs)
        }
    }

    companion object {
        const val ANILIST_URL = "https://graphql.anilist.co"
        private const val MAX_STUDIO_MEDIA_PAGES = 20
        private const val STUDIO_MEDIA_PAGE_SIZE = 25
        private const val MAX_RATE_LIMIT_RETRIES = 2
        private const val DEFAULT_RETRY_AFTER_SECONDS = 10L
        private const val MAX_FAVOURITE_PAGES = 40
        private const val MAX_SCHEDULE_PAGES = 10

        /** Episode start must be at least this old (~25 min runtime + 1 h for links to appear). */
        private const val NEWEST_AIRED_BUFFER_SEC = 90L * 60
        private const val SAVED_SYNC_BATCH_SIZE = 10
        private const val USER_AGENT = "Anilili/0.1.14 Android (AniList client 45552)"
    }
}

private const val DEFAULT_RATE_LIMIT = 30
private const val RATE_WINDOW_MS = 60_000L
private const val RATE_SAFETY_MS = 100L
private const val MIN_SPACING_MS = 100L
// Cap on the per-request gap while spreading a low budget across the rest of the window.
private const val MAX_SPACING_MS = 3_000L
// Cap on how long to hold when the budget is fully spent and we're awaiting a reset.
private const val MAX_BACKOFF_MS = 60_000L
// Start spreading requests once this few remain in the window.
private const val RATE_LOW_WATERMARK = 5
internal const val SLOW_WAIT_LOG_MS = 750L

/**
 * Pure slot scheduler for AniList pacing. Given [now], the last-seen budget ([remaining] and its
 * window [reset], both ms epoch) and the previously reserved [nextSlot], returns
 * (thisRequestStartMs, newNextSlotMs). Normally spaces requests by [MIN_SPACING_MS]; holds until
 * the window reset when the budget is spent (bounded by [MAX_BACKOFF_MS]); and spreads the last
 * few calls across the remaining window so we glide to the reset instead of triggering a 429.
 */
internal fun nextRateSlot(
    now: Long,
    remaining: Int,
    reset: Long,
    nextSlot: Long,
    limit: Int = DEFAULT_RATE_LIMIT,
): Pair<Long, Long> {
    val earliest = if (remaining <= 0 && now < reset) minOf(reset, now + MAX_BACKOFF_MS) else now
    val quotaSpacing = (RATE_WINDOW_MS / limit.coerceAtLeast(1) + RATE_SAFETY_MS).coerceAtLeast(MIN_SPACING_MS)
    val interval = if (remaining in 1..RATE_LOW_WATERMARK && now < reset) {
        maxOf(quotaSpacing, ((reset - now) / remaining).coerceAtMost(MAX_SPACING_MS))
    } else {
        quotaSpacing
    }
    val start = maxOf(earliest, nextSlot)
    return start to (start + interval)
}

internal data class MediaListProgressSnapshot(val id: Int, val status: String, val progress: Int)
data class MediaListProgressUpdate(val progress: Int, val status: String?)

internal fun discoverVariables(
    filters: DiscoverFilters,
    page: Int,
    perPage: Int,
    hideAdult: Boolean,
    studioMediaIds: List<Int>? = null,
): JsonObject = buildJsonObject {
    filters.query.trim().takeIf { it.isNotEmpty() }?.let { put("search", it) }
    put("page", page)
    put("perPage", perPage)
    if (hideAdult) put("isAdult", false)
    if (filters.genres.isNotEmpty()) put("genres", buildJsonArray { filters.genres.forEach(::add) })
    if (filters.tags.isNotEmpty()) put("tags", buildJsonArray { filters.tags.forEach(::add) })
    studioMediaIds?.takeIf { it.isNotEmpty() }?.let { ids ->
        put("mediaIds", buildJsonArray { ids.forEach(::add) })
    }
    filters.year?.let { put("year", it) }
    filters.status?.let { put("status", it) }
    filters.format?.let { put("format", it) }
    // AniList's *_greater filter is exclusive; the UI labels this value as inclusive.
    filters.minimumScore?.let { put("minimumScore", (it - 1).coerceAtLeast(0)) }
    put("sort", buildJsonArray { add(filters.sort) })
}

/** Pure policy used by playback sync; null means the user's AniList entry should not be changed. */
internal fun planMediaListProgressUpdate(
    current: MediaListProgressSnapshot?,
    watchedProgress: Int,
    totalEpisodes: Int?,
): MediaListProgressUpdate? {
    if (watchedProgress < 1 || (current != null && watchedProgress <= current.progress)) return null
    if (current?.status == "COMPLETED") return null
    val completed = totalEpisodes?.takeIf { it > 0 }?.let { watchedProgress >= it } == true
    val status = when {
        current == null -> if (completed) "COMPLETED" else "CURRENT"
        current.status == "PLANNING" -> if (completed) "COMPLETED" else "CURRENT"
        current.status == "CURRENT" && completed -> "COMPLETED"
        else -> null // Preserve REPEATING, PAUSED, DROPPED, and any future AniList states.
    }
    return MediaListProgressUpdate(progress = watchedProgress, status = status)
}

internal fun graphQlErrorMessages(body: String): List<String> = runCatching {
    val root = Json.parseToJsonElement(body).jsonObject
    (root["errors"] as? JsonArray).orEmpty().mapNotNull { element ->
        val error = element as? JsonObject ?: return@mapNotNull null
        val message = error["message"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val status = (error["extensions"] as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull
        if (status == null) message else "$message ($status)"
    }
}.getOrDefault(emptyList())

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    var deliveredResponse: Response? = null
    continuation.invokeOnCancellation {
        cancel()
        deliveredResponse?.close()
    }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            deliveredResponse = response
            if (continuation.isActive) {
                continuation.resumeWith(Result.success(response))
            } else {
                response.close()
            }
        }
    })
}
