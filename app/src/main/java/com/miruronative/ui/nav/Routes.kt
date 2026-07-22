package com.miruronative.ui.nav

import android.net.Uri

/** Central route table + typed builders so call sites don't hand-format paths. */
object Routes {
    const val EXTRA_ROUTE = "com.miruronative.extra.ROUTE"
    const val HOME = "home"
    const val SEARCH = "search"
    const val SEARCH_DESTINATION = "$SEARCH?studioId={studioId}&studioName={studioName}"
    const val SCHEDULE = "schedule"
    const val MORE = "more"
    const val SETTINGS = "settings"

    /** Top-level destinations that show the bottom navigation bar. */
    val tabRoutes = setOf(HOME, SEARCH, SCHEDULE, MORE, SETTINGS)

    const val NOTIFICATIONS = "notifications"

    const val DETAIL = "detail/{id}"
    fun detail(id: Int) = "detail/$id"

    fun studioSearch(studioId: Int, studioName: String) =
        "$SEARCH?studioId=$studioId&studioName=${Uri.encode(studioName)}"

    /** Maps optional-argument destinations back to their top-level tab route. */
    fun tabRoute(destinationRoute: String?): String? = destinationRoute?.substringBefore('?')

    /**
     * Home is the graph start destination. Restoring state while returning to it can resurrect a
     * child route (such as a studio-filtered Search) that was saved above Home instead.
     */
    fun shouldRestoreTabState(route: String): Boolean = route != HOME

    // Watch is addressed by anilistId + provider + category + episode number; the episode list
    // (and each episode's raw pipe id) is pulled from the repository's cache on arrival.
    const val WATCH = "watch/{id}/{provider}/{category}/{episode}?showEpisodes={showEpisodes}"
    fun watch(id: Int, provider: String, category: String, episode: String) =
        "watch/$id/$provider/$category/${Uri.encode(episode)}"

    /** Season selection opens the Watch destination with its episode grid visible on TV. */
    fun episodes(id: Int, provider: String, category: String, episode: String) =
        withEpisodeList(watch(id, provider, category, episode))

    internal fun withEpisodeList(watchRoute: String): String = "$watchRoute?showEpisodes=true"

    object Arg {
        const val ID = "id"
        const val STUDIO_ID = "studioId"
        const val STUDIO_NAME = "studioName"
        const val PROVIDER = "provider"
        const val CATEGORY = "category"
        const val EPISODE = "episode"
        const val SHOW_EPISODES = "showEpisodes"
    }
}
