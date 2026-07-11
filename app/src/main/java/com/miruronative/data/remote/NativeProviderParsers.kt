package com.miruronative.data.remote

import java.net.URI

internal data class NativeEpisodeRequest(
    val provider: String,
    val anilistId: Int,
    val audio: String,
    val episode: Int,
)

internal object NativeProviderParsers {
    private val episodePath = Regex(
        "^watch/([a-z0-9]+)/(\\d+)/(sub|dub)/[a-z0-9]+-(\\d+)$",
        RegexOption.IGNORE_CASE,
    )

    fun episodeRequest(path: String): NativeEpisodeRequest? {
        val match = episodePath.matchEntire(path.trim('/')) ?: return null
        return NativeEpisodeRequest(
            provider = match.groupValues[1].lowercase(),
            anilistId = match.groupValues[2].toIntOrNull() ?: return null,
            audio = match.groupValues[3].lowercase(),
            episode = match.groupValues[4].toIntOrNull() ?: return null,
        )
    }

    fun attr(tag: String, name: String): String {
        val escaped = Regex.escape(name)
        return Regex("\\b$escaped\\s*=\\s*([\"'])(.*?)\\1", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(tag)
            ?.groupValues
            ?.get(2)
            ?.let(::decodeEntities)
            .orEmpty()
    }

    fun stripTags(html: String): String = decodeEntities(
        html.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim(),
    )

    fun decodeEntities(value: String): String {
        var decoded = value
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
        decoded = Regex("&#(\\d+);").replace(decoded) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        }
        decoded = Regex("&#x([0-9a-f]+);", RegexOption.IGNORE_CASE).replace(decoded) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
        }
        return decoded.replace("\\/", "/")
    }

    fun normalize(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]"), "")

    fun titleScore(query: String, candidate: String): Double {
        val a = normalize(query)
        val b = normalize(candidate)
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        if (a in b || b in a) return 0.86
        if (a.length < 2 || b.length < 2) return 0.0
        val counts = HashMap<String, Int>()
        for (i in 0 until a.length - 1) {
            val pair = a.substring(i, i + 2)
            counts[pair] = (counts[pair] ?: 0) + 1
        }
        var hits = 0
        for (i in 0 until b.length - 1) {
            val pair = b.substring(i, i + 2)
            val remaining = counts[pair] ?: 0
            if (remaining > 0) {
                hits++
                counts[pair] = remaining - 1
            }
        }
        return (2.0 * hits) / ((a.length - 1) + (b.length - 1))
    }

    fun absoluteUrl(base: String, value: String): String {
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return runCatching { URI(base).resolve(value).toString() }.getOrDefault(value)
    }

    fun hlsUrls(html: String): List<String> = Regex(
        "(?:https?:)?(?:\\\\/|/)[^\"'\\s<>]+?\\.m3u8[^\"'\\s<>]*",
        RegexOption.IGNORE_CASE,
    ).findAll(html)
        .map { decodeEntities(it.value).replace("\\/", "/") }
        .map { if (it.startsWith("//")) "https:$it" else it }
        .distinct()
        .toList()
}
