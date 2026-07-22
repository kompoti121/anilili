package com.miruronative.ui.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvWebFieldTest {
    @Test
    fun emailPlaceholderUsesEmailKeyboardEvenWhenWebsiteDeclaresTextInput() {
        val field = TvWebField(
            id = "1",
            label = "Email",
            type = "text",
            value = "",
            hasNext = true,
        )

        assertTrue(field.isEmail)
        assertFalse(field.isPassword)
    }

    @Test
    fun passwordInputUsesMaskedEditor() {
        val field = TvWebField(
            id = "2",
            label = "Password",
            type = "password",
            value = "secret",
            hasNext = false,
        )

        assertTrue(field.isPassword)
        assertFalse(field.isEmail)
    }
}
