package com.miruronative.data.remote

import com.miruronative.data.model.DiscoverFilters
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class StudioFilterTest {

    @Test
    fun `studio is counted as one active catalog filter`() {
        val filters = DiscoverFilters(
            studioId = 569,
            studioName = "MAPPA",
            year = 2025,
        )

        assertEquals(2, filters.activeCount)
    }

    @Test
    fun `discover variables send studio id to AniList`() {
        val variables = discoverVariables(
            filters = DiscoverFilters(
                query = "  action  ",
                studioId = 569,
                studioName = "MAPPA",
            ),
            page = 2,
            perPage = 30,
            hideAdult = false,
            studioMediaIds = listOf(113415, 110277),
        )

        assertEquals(JsonPrimitive("action"), variables["search"])
        assertEquals(
            listOf(JsonPrimitive(113415), JsonPrimitive(110277)),
            variables["mediaIds"]?.jsonArray,
        )
        assertEquals(JsonPrimitive(2), variables["page"])
        assertNull(variables["studioName"])
        assertFalse("isAdult" in variables)
    }
}
