package com.miruronative.data.library

import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** One `<anime>` entry from a MyAnimeList XML export. */
data class MalImportEntry(
    val malId: Int,
    val title: String,
    val watchedEpisodes: Int,
    val status: String,
)

/** What an import run did, for the settings screen's result line. */
data class MalImportSummary(
    val totalEntries: Int,
    val added: Int,
    val alreadySaved: Int,
    val unmatched: Int,
)

object MalImport {
    /**
     * Parses a MyAnimeList XML export. MAL serves exports gzipped (`.xml.gz`), so both raw XML
     * and gzip are accepted. Throws on files that aren't a MAL export at all; entries without a
     * usable MAL id are skipped.
     */
    fun parse(bytes: ByteArray): List<MalImportEntry> {
        val xmlBytes = if (isGzip(bytes)) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        } else {
            bytes
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            // The file comes from outside the app; never resolve external entities.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
            isNamespaceAware = false
            isValidating = false
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
        require(document.documentElement?.tagName == "myanimelist") { "Not a MyAnimeList export file" }
        val nodes = document.getElementsByTagName("anime")
        val seen = mutableSetOf<Int>()
        return buildList {
            for (i in 0 until nodes.length) {
                val anime = nodes.item(i) as? Element ?: continue
                val malId = anime.text("series_animedb_id")?.toIntOrNull()?.takeIf { it > 0 } ?: continue
                if (!seen.add(malId)) continue
                add(
                    MalImportEntry(
                        malId = malId,
                        title = anime.text("series_title").orEmpty(),
                        watchedEpisodes = anime.text("my_watched_episodes")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                        status = normalizeStatus(anime.text("my_status")),
                    ),
                )
            }
        }
    }

    /** Old exports and some third-party tools write the numeric status codes. */
    internal fun normalizeStatus(raw: String?): String =
        when (raw?.trim()?.lowercase(Locale.US)?.replace(" ", "")) {
            "watching", "1" -> "Watching"
            "completed", "2" -> "Completed"
            "on-hold", "onhold", "3" -> "On-Hold"
            "dropped", "4" -> "Dropped"
            else -> "Plan to Watch"
        }

    private fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()

    private fun Element.text(tag: String): String? =
        getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}
