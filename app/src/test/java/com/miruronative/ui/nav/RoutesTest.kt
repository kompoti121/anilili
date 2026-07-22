package com.miruronative.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {

    @Test
    fun `watch destination keeps episode list flag optional`() {
        assertEquals(
            "watch/{id}/{provider}/{category}/{episode}?showEpisodes={showEpisodes}",
            Routes.WATCH,
        )
        assertFalse("watch/20/reanime/sub/1.5".contains("showEpisodes"))
    }

    @Test
    fun `season route explicitly opens episode list`() {
        val route = Routes.withEpisodeList("watch/21/auto/dub/1")

        assertEquals("watch/21/auto/dub/1?showEpisodes=true", route)
        assertTrue(route.endsWith("showEpisodes=true"))
    }

    @Test
    fun `studio search destination remains part of search tab`() {
        assertEquals(
            "search?studioId={studioId}&studioName={studioName}",
            Routes.SEARCH_DESTINATION,
        )
        assertEquals(Routes.SEARCH, Routes.tabRoute(Routes.SEARCH_DESTINATION))
    }

    @Test
    fun `home clears child routes while secondary tabs restore their state`() {
        assertFalse(Routes.shouldRestoreTabState(Routes.HOME))
        assertTrue(Routes.shouldRestoreTabState(Routes.SEARCH))
        assertTrue(Routes.shouldRestoreTabState(Routes.SCHEDULE))
        assertTrue(Routes.shouldRestoreTabState(Routes.MORE))
        assertTrue(Routes.shouldRestoreTabState(Routes.SETTINGS))
    }
}
