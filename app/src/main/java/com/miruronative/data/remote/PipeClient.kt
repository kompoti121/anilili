package com.miruronative.data.remote

import android.util.Base64
import com.miruronative.data.ProviderCatalog
import com.miruronative.data.model.Category
import com.miruronative.data.model.EpisodeItem
import com.miruronative.data.model.EpisodesResult
import com.miruronative.data.model.ProviderData
import com.miruronative.data.model.SkipTimes
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.StreamItem
import com.miruronative.data.model.SubtitleItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * Miruro pipe client. Transport is the hidden WebView ([PipeBridge]) — a plain HTTP client gets a
 * Cloudflare 403, but a same-origin fetch inside a loaded miruro.to page is allowed. Decoding
 * (base64url → optional XOR → gunzip → JSON) is unchanged and runs on-device. See docs/PIPE_PROTOCOL.md.
 */
class PipeClient(
    private val json: Json,
) {
    // ---- request via the WebView bridge ----

    private suspend fun pipeGet(path: String, query: JsonObject): JsonElement {
        val envelope = buildJsonObject {
            put("path", path)
            put("method", "GET")
            put("query", query)
            put("body", JsonNull)
            // The web frontend stamps its protocol version on every pipe request; matching it
            // keeps us indistinguishable from the site if the server starts enforcing it.
            put("version", "0.1.0")
        }
        val e = base64UrlEncode(envelope.toString().toByteArray(Charsets.UTF_8))
        val rawJson = PipeBridge.fetch(e)

        return withContext(Dispatchers.Default) {
            val obj = json.parseToJsonElement(rawJson).jsonObject
            val ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull ?: false
            if (!ok) {
                val status = (obj["status"] as? JsonPrimitive)?.contentOrNull
                val err = (obj["error"] as? JsonPrimitive)?.contentOrNull ?: "HTTP $status"
                throw IOException("pipe request failed ($err)")
            }
            val body = (obj["body"] as? JsonPrimitive)?.contentOrNull
                ?: throw IOException("empty pipe body")
            val obf = (obj["obf"] as? JsonPrimitive)?.contentOrNull
            decodeToJson(body, if (obf.isNullOrEmpty()) null else obf)
        }
    }

    // ---- response decode: base64url -> optional XOR -> gunzip -> JSON ----

    private fun decodeToJson(bodyText: String, obfHeader: String?): JsonElement {
        val jsonStr = if (obfHeader.isNullOrEmpty()) {
            bodyText
        } else {
            var bytes = base64UrlDecode(bodyText.trim())
            if (obfHeader == "2") bytes = xor(bytes, PIPE_OBF_KEY)
            String(gunzip(bytes), Charsets.UTF_8)
        }
        return json.parseToJsonElement(jsonStr)
    }

    // ---- episodes ----

    suspend fun getEpisodes(anilistId: Int): EpisodesResult {
        val root = pipeGet("episodes", buildJsonObject { put("anilistId", anilistId) })
        val providersObj = (root as? JsonObject)?.get("providers") as? JsonObject
            ?: return EpisodesResult(emptyList())

        val providers = providersObj.mapNotNull { (name, provEl) ->
            val provObj = provEl as? JsonObject ?: return@mapNotNull null
            val (sub, dub) = parseBuckets(provObj["episodes"])
            if (sub.isEmpty() && dub.isEmpty()) null else ProviderData(name, sub, dub)
        }.sortedBy { ProviderCatalog.sortKey(it.name) }

        return EpisodesResult(providers)
    }

    private fun parseBuckets(el: JsonElement?): Pair<List<EpisodeItem>, List<EpisodeItem>> = when (el) {
        is JsonArray -> parseEpisodeList(el) to emptyList()
        is JsonObject -> {
            val sub = (el["sub"] as? JsonArray)?.let(::parseEpisodeList) ?: emptyList()
            val dub = (el["dub"] as? JsonArray)?.let(::parseEpisodeList) ?: emptyList()
            sub to dub
        }
        else -> emptyList<EpisodeItem>() to emptyList()
    }

    private fun parseEpisodeList(arr: JsonArray): List<EpisodeItem> = arr.mapNotNull { item ->
        val o = item as? JsonObject ?: return@mapNotNull null
        val id = o.str("id") ?: return@mapNotNull null
        EpisodeItem(
            pipeId = id,
            number = o.num("number") ?: 0.0,
            title = o.str("title"),
            image = o.str("image") ?: o.str("thumbnail"),
            filler = o.bool("filler") ?: false,
        )
    }

    // ---- sources ----

    suspend fun getSources(
        pipeId: String,
        provider: String,
        category: Category,
        anilistId: Int,
    ): SourcesResult {
        val episodeIdParam = base64UrlEncode(translateId(pipeId).toByteArray(Charsets.UTF_8))
        val root = pipeGet(
            "sources",
            buildJsonObject {
                put("episodeId", episodeIdParam)
                put("provider", provider)
                put("category", category.api)
                put("anilistId", anilistId)
            },
        ) as? JsonObject ?: return SourcesResult(emptyList(), emptyList(), null, null)
        return parseSources(root)
    }

    private fun parseSources(root: JsonObject): SourcesResult {
        val streams = (root["streams"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val url = o.str("url") ?: return@mapNotNull null
            val res = o["resolution"] as? JsonObject
            StreamItem(
                url = url,
                type = o.str("type") ?: if (url.contains(".m3u8")) "hls" else "",
                quality = o.str("quality") ?: o.str("label"),
                audio = o.str("audio"),
                referer = o.str("referer"),
                isActive = o.bool("isActive") ?: false,
                width = res?.int("width"),
                height = res?.int("height"),
            )
        }.orEmpty()

        val subsArr = (root["subtitles"] as? JsonArray) ?: (root["captions"] as? JsonArray)
        val subtitles = subsArr?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val kind = o.str("kind")
            if (kind != null && !kind.equals("captions", true) && !kind.equals("subtitles", true)) {
                return@mapNotNull null
            }
            val url = o.str("url") ?: o.str("file") ?: return@mapNotNull null
            SubtitleItem(
                url = url,
                label = o.str("label") ?: o.str("name") ?: o.str("language") ?: "Subtitle",
                language = o.str("language") ?: o.str("lang") ?: "en",
            )
        }.orEmpty()

        val skipObj = (root["skipTimes"] as? JsonObject) ?: (root["skip"] as? JsonObject)
        val skip = skipObj?.let { s ->
            val intro = (s["intro"] as? JsonObject) ?: (s["op"] as? JsonObject)
            val outro = (s["outro"] as? JsonObject) ?: (s["ed"] as? JsonObject)
            SkipTimes(
                introStart = intro?.num("start"),
                introEnd = intro?.num("end"),
                outroStart = outro?.num("start"),
                outroEnd = outro?.num("end"),
            )
        }

        val download = root.str("download")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(::normalizePipeDownloadUrl)
        return SourcesResult(streams, subtitles, skip, download)
    }

    /** Mirrors MiruroAPI's translateId: base64url ids that decode to `prov:realId` are decoded. */
    private fun translateId(encoded: String): String = try {
        val decoded = String(base64UrlDecode(encoded), Charsets.UTF_8)
        if (decoded.contains(":")) decoded else encoded
    } catch (e: Exception) {
        encoded
    }

    // ---- byte helpers ----

    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun base64UrlDecode(s: String): ByteArray {
        val norm = s.replace('-', '+').replace('_', '/')
        val padded = when (norm.length % 4) {
            2 -> "$norm=="
            3 -> "$norm="
            else -> norm
        }
        return Base64.decode(padded, Base64.NO_WRAP)
    }

    private fun xor(data: ByteArray, key: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        for (i in data.indices) out[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        return out
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    // ---- JsonObject accessors ----

    private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
    private fun JsonObject.str(key: String): String? = prim(key)?.contentOrNull
    private fun JsonObject.num(key: String): Double? = prim(key)?.contentOrNull?.toDoubleOrNull()
    private fun JsonObject.int(key: String): Int? = prim(key)?.contentOrNull?.toDoubleOrNull()?.toInt()
    private fun JsonObject.bool(key: String): Boolean? =
        prim(key)?.booleanOrNull ?: prim(key)?.contentOrNull?.toBooleanStrictOrNull()

    companion object {
        // VITE_PIPE_OBF_KEY from the site's env2.js — public, not a secret.
        private val PIPE_OBF_KEY = hexToBytes("71951034f8fbcf53d89db52ceb3dc22c")

        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { i ->
                ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
            }
    }
}

/**
 * Miruro's web client rewrites legacy AnimePahe download pages through this public compatibility
 * worker. Keeping the same rewrite prevents users from being sent to the retired pahe.win host.
 */
internal fun normalizePipeDownloadUrl(url: String): String =
    if (url.startsWith(PAHE_DOWNLOAD_ORIGIN)) {
        PAHE_DOWNLOAD_WORKER + url.removePrefix(PAHE_DOWNLOAD_ORIGIN)
    } else {
        url
    }

private const val PAHE_DOWNLOAD_ORIGIN = "https://pahe.win/"
private const val PAHE_DOWNLOAD_WORKER = "https://orange-leaf-cefa.asd-968.workers.dev/"
