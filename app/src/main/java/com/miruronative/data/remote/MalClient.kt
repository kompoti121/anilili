package com.miruronative.data.remote

import com.miruronative.data.auth.MalAuthManager
import com.miruronative.diagnostics.DiagnosticsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

// ---- DTOs (MAL API v2 shapes) ----

@Serializable
data class MalUser(
    val id: Int,
    val name: String,
    val picture: String? = null,
    @SerialName("joined_at") val joinedAt: String? = null,
    @SerialName("anime_statistics") val animeStatistics: MalAnimeStatistics? = null,
)

@Serializable
data class MalAnimeStatistics(
    @SerialName("num_items") val numItems: Int = 0,
    @SerialName("num_days_watched") val numDaysWatched: Double = 0.0,
    @SerialName("mean_score") val meanScore: Double = 0.0,
)

@Serializable
data class MalListStatus(
    val status: String? = null,
    val score: Int = 0,
    @SerialName("num_episodes_watched") val numEpisodesWatched: Int = 0,
    @SerialName("is_rewatching") val isRewatching: Boolean = false,
)

@Serializable
data class MalAnimeNode(
    val id: Int,
    val title: String = "",
    @SerialName("num_episodes") val numEpisodes: Int? = null,
)

@Serializable
private data class MalListItem(val node: MalAnimeNode, @SerialName("list_status") val listStatus: MalListStatus? = null)

@Serializable
private data class MalPaging(val next: String? = null)

@Serializable
private data class MalListResponse(val data: List<MalListItem> = emptyList(), val paging: MalPaging? = null)

@Serializable
private data class MalAnimeWithStatus(@SerialName("my_list_status") val myListStatus: MalListStatus? = null)

/** One entry of the viewer's MAL anime list, statuses translated to AniList vocabulary. */
data class MalListEntry(
    val malId: Int,
    val title: String,
    val totalEpisodes: Int?,
    /** AniList-style status: CURRENT/REPEATING/PLANNING/PAUSED/COMPLETED/DROPPED. */
    val status: String,
    val progress: Int,
    val score: Double,
)

/**
 * Minimal MyAnimeList v2 client for the logged-in account: profile, anime list, and the two
 * non-destructive list mutations the app performs. Every call refreshes the OAuth token as
 * needed via [MalAuthManager.freshAccessToken]. All ids here are MAL ids.
 */
class MalClient(
    private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun viewer(): MalUser = withContext(Dispatchers.IO) {
        json.decodeFromString(
            MalUser.serializer(),
            get("$API/users/@me?fields=anime_statistics"),
        )
    }

    /** The whole list in one call when possible (MAL allows limit up to 1000), else paged. */
    suspend fun animeList(): List<MalListEntry> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<MalListEntry>()
        var url: String? = "$API/users/@me/animelist" +
            "?fields=list_status,num_episodes&limit=1000&nsfw=true"
        var pages = 0
        while (url != null && pages < MAX_LIST_PAGES) {
            val parsed = json.decodeFromString(MalListResponse.serializer(), get(url))
            parsed.data.forEach { item ->
                val list = item.listStatus ?: return@forEach
                entries += MalListEntry(
                    malId = item.node.id,
                    title = item.node.title,
                    totalEpisodes = item.node.numEpisodes?.takeIf { it > 0 },
                    status = anilistStatus(list),
                    progress = list.numEpisodesWatched,
                    score = list.score.toDouble(),
                )
            }
            url = parsed.paging?.next
            pages++
        }
        entries
    }

    /** The viewer's list entry for one anime, or null when it isn't on their list. */
    suspend fun listStatus(malId: Int): MalListStatus? = withContext(Dispatchers.IO) {
        val parsed = json.decodeFromString(
            MalAnimeWithStatus.serializer(),
            get("$API/anime/$malId?fields=my_list_status"),
        )
        parsed.myListStatus?.takeIf { it.status != null }
    }

    /** Upsert progress/status. [status] uses MAL vocabulary (watching/completed/…); null keeps it. */
    suspend fun updateListStatus(malId: Int, progress: Int? = null, status: String? = null) =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder().apply {
                progress?.let { add("num_watched_episodes", it.toString()) }
                status?.let { add("status", it) }
            }.build()
            val request = authed("$API/anime/$malId/my_list_status").patch(body).build()
            execute(request)
        }

    suspend fun deleteListEntry(malId: Int) = withContext(Dispatchers.IO) {
        execute(authed("$API/anime/$malId/my_list_status").delete().build())
    }

    // ---- helpers ----

    private suspend fun get(url: String): String = execute(authed(url).get().build())

    private suspend fun authed(url: String): Request.Builder {
        val token = MalAuthManager.freshAccessToken() ?: error("Not logged in to MyAnimeList")
        return Request.Builder().url(url.toHttpUrl()).header("Authorization", "Bearer $token")
    }

    private fun execute(request: Request): String =
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                DiagnosticsLog.event("MAL HTTP ${response.code} ${request.method} ${request.url.encodedPath}")
                error("MyAnimeList HTTP ${response.code}")
            }
            text
        }

    companion object {
        private const val API = "https://api.myanimelist.net/v2"
        private const val MAX_LIST_PAGES = 5

        internal fun anilistStatus(list: MalListStatus): String = when {
            list.isRewatching -> "REPEATING"
            list.status == "watching" -> "CURRENT"
            list.status == "completed" -> "COMPLETED"
            list.status == "on_hold" -> "PAUSED"
            list.status == "dropped" -> "DROPPED"
            else -> "PLANNING"
        }

        internal fun malStatus(anilistStatus: String): String = when (anilistStatus) {
            "CURRENT", "REPEATING" -> "watching"
            "COMPLETED" -> "completed"
            "PAUSED" -> "on_hold"
            "DROPPED" -> "dropped"
            else -> "plan_to_watch"
        }
    }
}
