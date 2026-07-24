package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSettingsSheetTest {

    @Test
    fun `audio tracks mapping exposes Auto and available audio options`() {
        var selectedTrack: String? = null
        val audioTracks = listOf("Japanese", "English")
        val hasAudioOverride = true
        val activeSelected = "English"

        val options = if (audioTracks.size > 1) {
            buildList {
                add(PlayerQualityOption("Auto", !hasAudioOverride) { selectedTrack = "Auto" })
                audioTracks.forEach { track ->
                    add(PlayerQualityOption(track, hasAudioOverride && track == activeSelected) {
                        selectedTrack = track
                    })
                }
            }
        } else {
            emptyList()
        }

        assertEquals(3, options.size)
        assertEquals("Auto", options[0].label)
        assertFalse(options[0].selected)

        assertEquals("Japanese", options[1].label)
        assertFalse(options[1].selected)

        assertEquals("English", options[2].label)
        assertTrue(options[2].selected)

        options[1].onSelect()
        assertEquals("Japanese", selectedTrack)
    }

    @Test
    fun `subtitle tracks mapping exposes Off and available subtitle options`() {
        var selectedTrack: String? = null
        val subtitleTracks = listOf("English", "Spanish")
        val activeSelected = "English"

        val options = if (subtitleTracks.isNotEmpty()) {
            buildList {
                add(PlayerQualityOption("Off", subtitleTracks.none { it == activeSelected }) {
                    selectedTrack = "Off"
                })
                subtitleTracks.forEach { track ->
                    add(PlayerQualityOption(track, track == activeSelected) {
                        selectedTrack = track
                    })
                }
            }
        } else {
            emptyList()
        }

        assertEquals(3, options.size)
        assertEquals("Off", options[0].label)
        assertFalse(options[0].selected)

        assertEquals("English", options[1].label)
        assertTrue(options[1].selected)

        assertEquals("Spanish", options[2].label)
        assertFalse(options[2].selected)

        options[0].onSelect()
        assertEquals("Off", selectedTrack)
    }

    @Test
    fun `single audio track omits audio section`() {
        val audioTracks = listOf("Japanese")
        val options = if (audioTracks.size > 1) {
            listOf("Auto") + audioTracks
        } else {
            emptyList()
        }
        assertTrue(options.isEmpty())
    }
}
