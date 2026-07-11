package com.miruronative.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ---- Request envelope ----
@Serializable
data class GraphQLRequest(val query: String, val variables: JsonObject)

// ---- Media fields (superset of AniList MEDIA_LIST_FIELDS + info extras we render) ----
@Serializable
data class MediaTitle(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
) {
    /** Best display title, mirroring the site's english-first preference. */
    val preferred: String get() = english ?: romaji ?: native ?: "Untitled"
}

@Serializable
data class CoverImage(
    val large: String? = null,
    val extraLarge: String? = null,
    val color: String? = null,
) {
    val best: String? get() = extraLarge ?: large
}

@Serializable
data class FuzzyDate(val year: Int? = null, val month: Int? = null, val day: Int? = null)

@Serializable
data class NextAiringEpisode(
    val episode: Int? = null,
    val airingAt: Long? = null,
    val timeUntilAiring: Long? = null,
)

@Serializable
data class StudioNode(val name: String? = null, val isAnimationStudio: Boolean = false)

@Serializable
data class StudioConnection(val nodes: List<StudioNode> = emptyList())

@Serializable
data class Trailer(val id: String? = null, val site: String? = null, val thumbnail: String? = null)

@Serializable
data class Media(
    val id: Int,
    val idMal: Int? = null,
    val title: MediaTitle = MediaTitle(),
    val description: String? = null,
    val coverImage: CoverImage = CoverImage(),
    val bannerImage: String? = null,
    val format: String? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val episodes: Int? = null,
    val duration: Int? = null,
    val status: String? = null,
    val averageScore: Int? = null,
    val meanScore: Int? = null,
    val popularity: Int? = null,
    val favourites: Int? = null,
    val genres: List<String> = emptyList(),
    val studios: StudioConnection = StudioConnection(),
    val nextAiringEpisode: NextAiringEpisode? = null,
    val startDate: FuzzyDate? = null,
    val endDate: FuzzyDate? = null,
    val trailer: Trailer? = null,
)

// ---- Response envelopes ----
@Serializable
data class PageInfo(
    val total: Int = 0,
    val currentPage: Int = 1,
    val lastPage: Int = 1,
    val hasNextPage: Boolean = false,
    val perPage: Int = 20,
)

@Serializable
data class Page(
    val pageInfo: PageInfo = PageInfo(),
    val media: List<Media> = emptyList(),
)

@Serializable
data class PageData(@SerialName("Page") val page: Page? = null)

@Serializable
data class MediaData(@SerialName("Media") val media: Media? = null)

@Serializable
data class GqlPageResponse(val data: PageData? = null)

@Serializable
data class GqlMediaResponse(val data: MediaData? = null)

// ---- Airing schedule ----
@Serializable
data class AiringSchedule(
    val episode: Int = 0,
    val airingAt: Long = 0,
    val media: Media? = null,
)

@Serializable
data class SchedulePage(val airingSchedules: List<AiringSchedule> = emptyList())

@Serializable
data class SchedulePageData(@SerialName("Page") val page: SchedulePage? = null)

@Serializable
data class GqlScheduleResponse(val data: SchedulePageData? = null)

// ---- Authenticated user (AniList login) ----
@Serializable
data class UserAvatar(val large: String? = null)

@Serializable
data class AnimeStat(
    val count: Int = 0,
    val episodesWatched: Int = 0,
    val minutesWatched: Long = 0,
    val meanScore: Double = 0.0,
)

@Serializable
data class ViewerStatistics(val anime: AnimeStat? = null)

@Serializable
data class Viewer(
    val id: Int,
    val name: String,
    val avatar: UserAvatar? = null,
    val bannerImage: String? = null,
    val statistics: ViewerStatistics? = null,
)

@Serializable
data class ViewerData(@SerialName("Viewer") val viewer: Viewer? = null)

@Serializable
data class GqlViewerResponse(val data: ViewerData? = null)

@Serializable
data class MediaListEntry(val id: Int = 0, val progress: Int = 0, val status: String? = null, val media: Media? = null)

@Serializable
data class MediaListGroup(val name: String? = null, val status: String? = null, val entries: List<MediaListEntry> = emptyList())

@Serializable
data class MediaListCollection(val lists: List<MediaListGroup> = emptyList())

@Serializable
data class MediaListCollectionData(@SerialName("MediaListCollection") val collection: MediaListCollection? = null)

@Serializable
data class GqlMediaListResponse(val data: MediaListCollectionData? = null)

/** A page of results plus whether more exist — used to drive infinite scroll. */
data class MediaPage(val items: List<Media>, val hasNextPage: Boolean, val page: Int)
