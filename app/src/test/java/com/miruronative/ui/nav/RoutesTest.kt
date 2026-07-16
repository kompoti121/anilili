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
}
