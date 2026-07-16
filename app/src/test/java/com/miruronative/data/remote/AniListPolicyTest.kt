package com.miruronative.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AniListPolicyTest {
    @Test
    fun `new watched entry starts current`() {
        assertEquals(MediaListProgressUpdate(3, "CURRENT"), planMediaListProgressUpdate(null, 3, 12))
    }

    @Test
    fun `last episode completes current entry`() {
        val current = MediaListProgressSnapshot(7, "CURRENT", 11)
        assertEquals(MediaListProgressUpdate(12, "COMPLETED"), planMediaListProgressUpdate(current, 12, 12))
    }

    @Test
    fun `paused and repeating status are preserved`() {
        assertEquals(
            MediaListProgressUpdate(5, null),
            planMediaListProgressUpdate(MediaListProgressSnapshot(7, "PAUSED", 4), 5, 12),
        )
        assertEquals(
            MediaListProgressUpdate(12, null),
            planMediaListProgressUpdate(MediaListProgressSnapshot(7, "REPEATING", 11), 12, 12),
        )
    }

    @Test
    fun `completed and regressive progress are not changed`() {
        assertNull(planMediaListProgressUpdate(MediaListProgressSnapshot(7, "COMPLETED", 12), 13, 13))
        assertNull(planMediaListProgressUpdate(MediaListProgressSnapshot(7, "CURRENT", 8), 7, 12))
    }

    @Test
    fun `graphql errors are extracted from successful http envelopes`() {
        val errors = graphQlErrorMessages(
            """{"data":null,"errors":[{"message":"Invalid token","extensions":{"status":401}}]}""",
        )
        assertEquals(listOf("Invalid token (401)"), errors)
        assertTrue(graphQlErrorMessages("""{"data":{"Page":{}}}""").isEmpty())
    }
}
