package com.miruronative.data.auth

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthTokenTest {
    @Test
    fun `jwt subject and expiration are decoded without trusting the signature`() {
        val token = jwt("""{"sub":1234,"exp":2000}""")
        assertEquals(1234, jwtSubject(token))
        assertFalse(isJwtExpired(token, nowEpochSeconds = 1_000))
        assertTrue(isJwtExpired(token, nowEpochSeconds = 1_950))
    }

    @Test
    fun `opaque token remains usable`() {
        assertEquals(null, jwtSubject("opaque-token"))
        assertFalse(isJwtExpired("opaque-token", nowEpochSeconds = Long.MAX_VALUE))
    }

    private fun jwt(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        fun encode(value: String) = encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        return "${encode("{}")}.${encode(payload)}.signature"
    }
}
