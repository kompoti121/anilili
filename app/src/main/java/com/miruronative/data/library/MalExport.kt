package com.miruronative.data.library

import com.miruronative.data.model.Media
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

data class MalExportEntry(
    val malId: Int,
    val title: String,
    val format: String?,
    val episodes: Int?,
    val watchedEpisodes: Int,
    val score: Int,
    val status: String,
    val rewatching: Boolean = false,
)

data class MalExportFile(
    val fileName: String,
    val xml: String,
    val exportedCount: Int,
    val skippedCount: Int,
)

object MalExport {
    fun fromEntries(username: String?, entries: List<MalExportEntry>, skippedCount: Int): MalExportFile {
        val date = LocalDate.now()
        val fileName = "Anilili-mal-export-${date.format(DateTimeFormatter.BASIC_ISO_DATE)}.xml"
        val xml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8" ?>""")
            appendLine("<myanimelist>")
            appendLine("  <myinfo>")
            appendLine("    <user_id>0</user_id>")
            appendLine("    <user_name>${xml(username ?: "Anilili")}</user_name>")
            appendLine("    <user_export_type>1</user_export_type>")
            appendLine("  </myinfo>")
            entries.forEach { entry ->
                appendLine("  <anime>")
                appendLine("    <series_animedb_id>${entry.malId}</series_animedb_id>")
                appendLine("    <series_title>${xml(entry.title)}</series_title>")
                appendLine("    <series_type>${xml(format(entry.format))}</series_type>")
                appendLine("    <series_episodes>${entry.episodes ?: 0}</series_episodes>")
                appendLine("    <my_id>0</my_id>")
                appendLine("    <my_watched_episodes>${entry.watchedEpisodes.coerceAtLeast(0)}</my_watched_episodes>")
                appendLine("    <my_start_date>0000-00-00</my_start_date>")
                appendLine("    <my_finish_date>0000-00-00</my_finish_date>")
                appendLine("    <my_score>${entry.score.coerceIn(0, 10)}</my_score>")
                appendLine("    <my_status>${xml(entry.status)}</my_status>")
                appendLine("    <my_comments></my_comments>")
                appendLine("    <my_times_watched>0</my_times_watched>")
                appendLine("    <my_rewatch_value></my_rewatch_value>")
                appendLine("    <my_tags></my_tags>")
                appendLine("    <my_rewatching>${if (entry.rewatching) 1 else 0}</my_rewatching>")
                appendLine("    <my_rewatching_ep>0</my_rewatching_ep>")
                appendLine("    <update_on_import>1</update_on_import>")
                appendLine("  </anime>")
            }
            appendLine("</myanimelist>")
        }
        return MalExportFile(fileName, xml, entries.size, skippedCount)
    }

    fun entryFromMedia(
        media: Media,
        status: String,
        progress: Int,
        score: Double = 0.0,
        rewatching: Boolean = false,
    ): MalExportEntry? {
        val malId = media.idMal ?: return null
        val episodes = media.episodes
        val watched = when {
            status == "Completed" && progress <= 0 && episodes != null -> episodes
            else -> progress
        }
        return MalExportEntry(
            malId = malId,
            title = media.title.preferred,
            format = media.format,
            episodes = episodes,
            watchedEpisodes = watched,
            score = score.takeIf { it > 0.0 }?.roundToInt() ?: 0,
            status = status,
            rewatching = rewatching,
        )
    }

    fun statusFromAniList(status: String?): Pair<String, Boolean> = when (status) {
        "CURRENT" -> "Watching" to false
        "REPEATING" -> "Watching" to true
        "COMPLETED" -> "Completed" to false
        "PAUSED" -> "On-Hold" to false
        "DROPPED" -> "Dropped" to false
        else -> "Plan to Watch" to false
    }

    fun statusFromLocal(progress: Int, episodes: Int?): String = when {
        progress <= 0 -> "Plan to Watch"
        episodes != null && episodes > 0 && progress >= episodes -> "Completed"
        else -> "Watching"
    }

    private fun format(value: String?): String = when (value?.uppercase(Locale.US)) {
        "MOVIE" -> "Movie"
        "SPECIAL" -> "Special"
        "OVA" -> "OVA"
        "ONA" -> "ONA"
        "MUSIC" -> "Music"
        "TV_SHORT" -> "TV"
        else -> "TV"
    }

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
