package com.miruronative.ui.nav

import android.net.Uri

/** Central route table + typed builders so call sites don't hand-format paths. */
object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val SCHEDULE = "schedule"
    const val MORE = "more"

    /** Top-level destinations that show the bottom navigation bar. */
    val tabRoutes = setOf(HOME, SEARCH, SCHEDULE, MORE)

    const val DETAIL = "detail/{id}"
    fun detail(id: Int) = "detail/$id"

    // Watch is addressed by anilistId + provider + category + episode number; the episode list
    // (and each episode's raw pipe id) is pulled from the repository's cache on arrival.
    const val WATCH = "watch/{id}/{provider}/{category}/{episode}"
    fun watch(id: Int, provider: String, category: String, episode: String) =
        "watch/$id/$provider/$category/${Uri.encode(episode)}"

    object Arg {
        const val ID = "id"
        const val PROVIDER = "provider"
        const val CATEGORY = "category"
        const val EPISODE = "episode"
    }
}
