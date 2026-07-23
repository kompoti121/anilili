package com.miruronative.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class PipeDownloadUrlTest {
    @Test
    fun `legacy AnimePahe download pages use Miruro compatibility worker`() {
        assertEquals(
            "https://orange-leaf-cefa.asd-968.workers.dev/d/episode-id",
            normalizePipeDownloadUrl("https://pahe.win/d/episode-id"),
        )
    }

    @Test
    fun `other provider download pages are unchanged`() {
        assertEquals(
            "https://otakuvid.online/d/episode-id",
            normalizePipeDownloadUrl("https://otakuvid.online/d/episode-id"),
        )
    }
}
