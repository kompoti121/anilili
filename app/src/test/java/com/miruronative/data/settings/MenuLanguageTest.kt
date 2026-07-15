package com.miruronative.data.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuLanguageTest {
    @Test
    fun `explicit menu language wins over system locale`() {
        assertTrue(MenuLanguage.SPANISH.usesSpanish("en"))
        assertFalse(MenuLanguage.ENGLISH.usesSpanish("es"))
    }

    @Test
    fun `system menu language follows Spanish locale`() {
        assertTrue(MenuLanguage.SYSTEM.usesSpanish("es"))
        assertFalse(MenuLanguage.SYSTEM.usesSpanish("en"))
    }
}
