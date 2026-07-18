package com.miruronative.ui.watch

import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerAudioPolicyTest {
    @Test
    fun categoryAudioRankingPrefersEnglishForDubAndJapaneseForSub() {
        assertTrue(categoryAudioRank("English eng", wantsDub = true) < categoryAudioRank("Japanese jpn", wantsDub = true))
        assertTrue(categoryAudioRank("Japanese jpn", wantsDub = false) < categoryAudioRank("English eng", wantsDub = false))
    }
}
