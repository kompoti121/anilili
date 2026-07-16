package com.miruronative.ui.profile

import com.miruronative.data.model.MediaListCollection
import com.miruronative.data.model.MediaListEntry
import com.miruronative.data.model.MediaListGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileListPolicyTest {
    @Test
    fun `custom list duplicates are retained once and classified by entry status`() {
        val entry = MediaListEntry(id = 9, status = "CURRENT")
        val collection = MediaListCollection(
            lists = listOf(
                MediaListGroup(status = "CURRENT", entries = listOf(entry)),
                MediaListGroup(name = "Favorites", isCustomList = true, entries = listOf(entry)),
            ),
        )

        assertEquals(listOf(entry), collection.allEntries())
    }
}
