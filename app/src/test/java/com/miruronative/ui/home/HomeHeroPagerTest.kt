package com.miruronative.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeHeroPagerTest {
    @Test
    fun nextPageAdvancesAndWraps() {
        assertEquals(1, nextHeroPage(currentPage = 0, pageCount = 4))
        assertEquals(0, nextHeroPage(currentPage = 3, pageCount = 4))
    }

    @Test
    fun nextPageHandlesEmptyAndSingleItemLists() {
        assertEquals(0, nextHeroPage(currentPage = 0, pageCount = 0))
        assertEquals(0, nextHeroPage(currentPage = 0, pageCount = 1))
    }

    @Test
    fun nextPageClampsAStalePageAfterItemsChange() {
        assertEquals(0, nextHeroPage(currentPage = 10, pageCount = 3))
    }
}
