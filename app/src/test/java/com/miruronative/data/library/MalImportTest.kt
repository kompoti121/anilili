package com.miruronative.data.library

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MalImportTest {
    private val sampleXml = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <myanimelist>
          <myinfo>
            <user_id>0</user_id>
            <user_name>tester</user_name>
            <user_export_type>1</user_export_type>
          </myinfo>
          <anime>
            <series_animedb_id>5114</series_animedb_id>
            <series_title>Fullmetal Alchemist: Brotherhood &amp; more</series_title>
            <series_episodes>64</series_episodes>
            <my_watched_episodes>64</my_watched_episodes>
            <my_status>Completed</my_status>
          </anime>
          <anime>
            <series_animedb_id>21</series_animedb_id>
            <series_title>One Piece</series_title>
            <my_watched_episodes>250</my_watched_episodes>
            <my_status>1</my_status>
          </anime>
          <anime>
            <series_animedb_id>21</series_animedb_id>
            <series_title>One Piece duplicate</series_title>
            <my_status>Watching</my_status>
          </anime>
          <anime>
            <series_animedb_id>0</series_animedb_id>
            <series_title>Broken entry without id</series_title>
          </anime>
        </myanimelist>
    """.trimIndent()

    @Test
    fun parsesEntriesSkippingDuplicatesAndMissingIds() {
        val entries = MalImport.parse(sampleXml.toByteArray())
        assertEquals(2, entries.size)
        assertEquals(
            MalImportEntry(5114, "Fullmetal Alchemist: Brotherhood & more", 64, "Completed"),
            entries[0],
        )
        // Numeric status codes from old exports resolve too.
        assertEquals(MalImportEntry(21, "One Piece", 250, "Watching"), entries[1])
    }

    @Test
    fun parsesGzippedExportAsMalServesIt() {
        val gzipped = ByteArrayOutputStream().also { buffer ->
            GZIPOutputStream(buffer).use { it.write(sampleXml.toByteArray()) }
        }.toByteArray()
        assertEquals(2, MalImport.parse(gzipped).size)
    }

    @Test
    fun rejectsNonMalXml() {
        assertThrows(Exception::class.java) {
            MalImport.parse("<html><body>not a list</body></html>".toByteArray())
        }
    }

    @Test
    fun normalizesEveryStatusSpelling() {
        assertEquals("Watching", MalImport.normalizeStatus("watching"))
        assertEquals("On-Hold", MalImport.normalizeStatus("3"))
        assertEquals("On-Hold", MalImport.normalizeStatus("On-Hold"))
        assertEquals("Dropped", MalImport.normalizeStatus("4"))
        assertEquals("Plan to Watch", MalImport.normalizeStatus("Plan to Watch"))
        assertEquals("Plan to Watch", MalImport.normalizeStatus(null))
    }
}
