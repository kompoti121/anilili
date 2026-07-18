package com.miruronative.data.remote

import com.miruronative.data.model.Media
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.StreamItem
import com.miruronative.data.model.SubtitleItem
import com.miruronative.diagnostics.DiagnosticsLog
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class AllAnimeProvider(
    private val client: OkHttpClient,
    private val json: Json,
    private val protocol: AllAnimeProtocolVersion = AllAnimeProtocolConfig.active,
) {
    internal enum class SourceRoute { CURRENT, LEGACY }

    private data class CryptoBootstrap(
        val epoch: Int,
        val partB: String,
        val switchAt: Long?,
    )

    private data class SourcePayload(
        val sources: List<AllAnimeCodec.Source>,
        val subtitles: List<SubtitleItem>,
        val route: SourceRoute,
    )

    private data class ClockResult(
        val streams: List<StreamItem>,
        val subtitles: List<SubtitleItem>,
    )

    private class CurrentRouteRateLimit(message: String) : IllegalStateException(message)

    private data class Candidate(
        val id: String,
        val title: String,
        val englishTitle: String?,
        val malId: Int?,
        val availableSub: Int,
        val availableDub: Int,
    )

    private val showIds = ConcurrentHashMap<Int, String>()
    @Volatile
    internal var lastSourceRoute: SourceRoute? = null
        private set

    fun episodeAvailability(media: Media): EpisodeAvailability {
        val candidate = resolveCandidate(media, "sub")
        showIds[media.id] = candidate.id
        return EpisodeAvailability.counts(candidate.availableSub, candidate.availableDub)
    }

    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        return sources(media, audio, episode, allowLegacyFallback = true)
    }

    internal fun sources(
        media: Media,
        audio: String,
        episode: Int,
        allowLegacyFallback: Boolean,
    ): SourcesResult {
        val showId = showIds[media.id] ?: resolveShow(media, audio).also { showIds[media.id] = it }
        return sourcesForShow(showId, audio, episode, allowLegacyFallback)
    }

    internal fun sourcesForShow(
        showId: String,
        audio: String,
        episode: Int,
        allowLegacyFallback: Boolean = true,
    ): SourcesResult {
        val payload = fetchSourcePayload(showId, audio, episode, allowLegacyFallback)
        lastSourceRoute = payload.route
        val native = mutableListOf<StreamItem>()
        val embeds = mutableListOf<StreamItem>()
        val subtitles = payload.subtitles.toMutableList()

        payload.sources.sortedByDescending(AllAnimeCodec.Source::priority).forEach { source ->
            if (source.name.equals("Ss-Hls", true)) return@forEach
            val rawUrl = source.url
            if (rawUrl.startsWith("--")) {
                val path = AllAnimeCodec.decodeSourceUrl(rawUrl)
                if (path.contains("/clock")) {
                    val clock = resolveClock(path, audio, source.name)
                    native += clock.streams
                    subtitles += clock.subtitles
                }
                return@forEach
            }
            if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) return@forEach

            val isDirect = MEDIA_URL.containsMatchIn(rawUrl) ||
                (source.type.equals("player", true) && !HTML_PLAYER_URL.containsMatchIn(rawUrl)) ||
                source.fileExtension.equals("mp4", true) || source.fileExtension.equals("m3u8", true)
            if (isDirect) {
                val type = if (rawUrl.contains(".m3u8", true) || source.fileExtension.equals("m3u8", true)) "hls" else "mp4"
                native += stream(rawUrl, type, qualityLabel(source.name, source.quality), audio, protocol.playerReferer)
            } else {
                embeds += stream(rawUrl, "embed", source.name.ifBlank { "AllAnime embed" }, audio, protocol.playerReferer)
            }
        }

        val verified = native.distinctBy(StreamItem::url).filter(::isPlayable)
        val streams = (verified + embeds.distinctBy(StreamItem::url)).mapIndexed { index, item ->
            item.copy(isActive = index == 0)
        }
        if (streams.isEmpty()) error("AllAnime episode $episode has no playable sources")
        return SourcesResult(
            streams,
            subtitles.map(::absoluteSubtitle).distinctBy(SubtitleItem::url),
            null,
            null,
        )
    }

    private fun resolveShow(media: Media, audio: String): String {
        return resolveCandidate(media, audio).id
    }

    private fun resolveCandidate(media: Media, audio: String): Candidate {
        val candidates = titles(media).flatMap { title ->
            runCatching {
                retryOnce("AllAnime title search query=${title.take(80)}") {
                    search(title, audio, media.isAdult)
                }
            }.onFailure {
                logFailure("AllAnime title search failed query=${title.take(80)}", it)
            }.getOrDefault(emptyList())
        }.distinctBy(Candidate::id)
        media.idMal?.let { malId -> candidates.firstOrNull { it.malId == malId }?.let { return it } }

        val chosen = candidates.maxByOrNull { candidate ->
            titles(media).maxOfOrNull { title ->
                maxOf(
                    NativeProviderParsers.titleScore(title, candidate.title),
                    NativeProviderParsers.titleScore(title, candidate.englishTitle.orEmpty()),
                )
            } ?: 0.0
        } ?: error("AllAnime match not found")
        val score = titles(media).maxOfOrNull { title ->
            maxOf(
                NativeProviderParsers.titleScore(title, chosen.title),
                NativeProviderParsers.titleScore(title, chosen.englishTitle.orEmpty()),
            )
        } ?: 0.0
        if (score < 0.35) error("AllAnime title match was too weak")
        return chosen
    }

    private fun search(query: String, audio: String, allowAdult: Boolean): List<Candidate> {
        val variables = buildJsonObject {
            putJsonObject("search") {
                put("allowAdult", allowAdult)
                put("allowUnknown", false)
                put("query", query)
            }
            put("limit", 20)
            put("page", 1)
            put("translationType", if (audio == "dub") "dub" else "sub")
            put("countryOrigin", "ALL")
        }
        val root = postGraphQl(SEARCH_QUERY, variables)
        val edges = (((root["data"] as? JsonObject)?.get("shows") as? JsonObject)?.get("edges") as? JsonArray)
            .orEmpty()
        return edges.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.string("_id") ?: return@mapNotNull null
            Candidate(
                id = id,
                title = item.string("name") ?: id,
                englishTitle = item.string("englishName"),
                malId = item.int("malId"),
                availableSub = ((item["availableEpisodes"] as? JsonObject)?.int("sub") ?: 0),
                availableDub = ((item["availableEpisodes"] as? JsonObject)?.int("dub") ?: 0),
            )
        }
    }

    private fun postGraphQl(query: String, variables: JsonObject): JsonObject {
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(protocol.legacyApi).post(body).allAnimeHeaders().build()
        return executeText(request).let(json::parseToJsonElement).jsonObject
    }

    private fun fetchSourcePayload(
        showId: String,
        audio: String,
        episode: Int,
        allowLegacyFallback: Boolean,
    ): SourcePayload {
        val current = runCatching {
            retryOnce("AllAnime ${protocol.version} current source route") {
                fetchCurrentSourcePayload(showId, audio, episode)
            }
        }
        current.getOrNull()?.takeIf { it.sources.isNotEmpty() }?.let { return it }

        val currentFailure = current.exceptionOrNull()
            ?: IllegalStateException("AllAnime current source route returned no sources")
        logFailure(
            "AllAnime ${protocol.version} current route failed show=$showId audio=$audio episode=$episode" +
                if (allowLegacyFallback) "; trying legacy" else "; legacy disabled",
            currentFailure,
        )
        if (!allowLegacyFallback) throw currentFailure
        return fetchLegacySourcePayload(showId, audio, episode)
    }

    /**
     * MKissa/AllAnime's current episode route uses a short-lived, epoch-keyed AES-GCM request
     * envelope. Its bootstrap endpoint supplies the current epoch half; the other half is bundled
     * with the client and XOR-combined exactly as the web client does. This avoids
     * baking expiring media URLs into the app while retaining the legacy endpoint as a fallback.
     */
    private fun fetchCurrentSourcePayload(showId: String, audio: String, episode: Int): SourcePayload {
        val bootstrap = cryptoBootstrap()
        val key = AllAnimeCodec.epochKey(bootstrap.partB, protocol.cryptoMask)
        val aaReq = AllAnimeCodec.signRequest(
            key = key,
            epoch = bootstrap.epoch,
            buildId = protocol.buildId,
            queryHash = protocol.currentSourcesHash,
            nowMs = System.currentTimeMillis(),
        )
        val variables = buildJsonObject {
            put("showId", showId)
            put("translationType", if (audio == "dub") "dub" else "sub")
            put("episodeString", episode.toString())
        }
        val extensions = buildJsonObject {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", protocol.currentSourcesHash)
            }
            put("aaReq", aaReq)
        }
        val url = protocol.currentApi.toHttpUrl().newBuilder()
            .addQueryParameter("variables", variables.toString())
            .addQueryParameter("extensions", extensions.toString())
            .build()
        val request = Request.Builder().url(url).get().currentApiHeaders().build()
        val root = json.parseToJsonElement(executeText(request)) as? JsonObject
            ?: error("AllAnime returned invalid source data")
        currentRouteError(root)?.let { message ->
            if (message.contains("too many requests", true)) {
                throw CurrentRouteRateLimit(message)
            }
            error("AllAnime current route error: ${message.take(240)}")
        }
        val data = root["data"] as? JsonObject ?: error("AllAnime returned no source data")
        val encrypted = data.string("tobeparsed")
        val decoded = if (!encrypted.isNullOrBlank()) {
            AllAnimeCodec.decryptGcm(encrypted, key)
        } else {
            data["episode"] ?: error("AllAnime current source payload missing")
        }
        val sources = AllAnimeCodec.parseSources(decoded)
        if (sources.isEmpty()) {
            error(
                "AllAnime current source payload empty show=$showId audio=$audio episode=$episode " +
                    "keys=${(decoded as? JsonObject)?.keys.orEmpty().sorted()}",
            )
        }
        return SourcePayload(
            sources = sources,
            subtitles = AllAnimeCodec.parseSubtitles(decoded),
            route = SourceRoute.CURRENT,
        )
    }

    private fun cryptoBootstrap(): CryptoBootstrap {
        val bootstrapRequest = Request.Builder()
            .url(protocol.bootstrapUrl)
            .get()
            .currentApiHeaders()
            .build()
        val bootstrapJson = json.parseToJsonElement(executeText(bootstrapRequest)).jsonObject
        val bootstrap = CryptoBootstrap(
            epoch = bootstrapJson.int("epoch") ?: error("AllAnime epoch missing"),
            partB = bootstrapJson.string("partB") ?: error("AllAnime key half missing"),
            switchAt = bootstrapJson.string("switchAt")?.toLongOrNull()
                ?: (bootstrapJson["switchAt"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
        )
        if (bootstrap.switchAt != null && System.currentTimeMillis() >= bootstrap.switchAt) {
            error("AllAnime crypto bootstrap expired")
        }
        return bootstrap
    }

    private fun fetchLegacySourcePayload(showId: String, audio: String, episode: Int): SourcePayload {
        val variables = buildJsonObject {
            put("showId", showId)
            put("translationType", if (audio == "dub") "dub" else "sub")
            put("episodeString", episode.toString())
        }
        val extensions = buildJsonObject {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", protocol.legacySourcesHash)
            }
        }
        val url = protocol.legacyApi.toHttpUrl().newBuilder()
            .addQueryParameter("variables", variables.toString())
            .addQueryParameter("extensions", extensions.toString())
            .build()
        val root = json.parseToJsonElement(
            executeText(Request.Builder().url(url).get().allAnimeHeaders().build()),
        ) as? JsonObject ?: error("AllAnime returned invalid legacy source data")
        val data = root["data"] as? JsonObject ?: error("AllAnime returned no legacy source data")
        val decoded = data.string("tobeparsed")?.takeIf(String::isNotBlank)?.let(AllAnimeCodec::decrypt)
            ?: data["episode"]
        return SourcePayload(
            sources = AllAnimeCodec.parseSources(decoded),
            subtitles = AllAnimeCodec.parseSubtitles(decoded),
            route = SourceRoute.LEGACY,
        )
    }

    private fun resolveClock(path: String, audio: String, fallbackName: String): ClockResult {
        val url = NativeProviderParsers.absoluteUrl("https://allanime.day", path)
        val request = Request.Builder().url(url).get().playerHeaders().build()
        val root = runCatching { json.parseToJsonElement(executeText(request)) as? JsonObject }.getOrNull()
            ?: return ClockResult(emptyList(), emptyList())
        val streams = (root["links"] as? JsonArray).orEmpty().mapNotNull { element ->
            val link = element as? JsonObject ?: return@mapNotNull null
            val streamUrl = link.string("link") ?: link.string("url") ?: return@mapNotNull null
            val hls = link.boolean("hls") || streamUrl.contains(".m3u8", true) ||
                streamUrl.contains("repackager.wixmp", true)
            stream(
                streamUrl,
                if (hls) "hls" else "mp4",
                qualityLabel(fallbackName, link.string("resolutionStr")),
                audio,
                protocol.playerReferer,
            )
        }
        return ClockResult(streams, AllAnimeCodec.parseSubtitles(root))
    }

    internal fun isPlayable(item: StreamItem): Boolean = runCatching {
        val builder = Request.Builder()
            .url(item.url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", item.referer ?: protocol.playerReferer)
        if (!item.isHls) builder.header("Range", "bytes=0-1")
        client.newCall(builder.get().build()).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) return@use false
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            if ("text/html" in contentType) return@use false
            if (!item.isHls) return@use true
            response.body?.string().orEmpty().trimStart().startsWith("#EXTM3U")
        }
    }.onFailure {
        val host = item.url.substringAfter("://", item.url).substringBefore('/').take(120)
        logFailure("AllAnime direct stream probe failed host=$host", it)
    }.getOrDefault(false)

    private fun executeText(request: Request): String = client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) error("AllAnime HTTP ${response.code}")
        body
    }

    private fun Request.Builder.allAnimeHeaders(): Request.Builder = this
        .header("User-Agent", USER_AGENT)
        .header("Referer", protocol.apiReferer)
        .header("Origin", protocol.apiOrigin)
        .header("Accept", "application/json, */*")

    private fun Request.Builder.currentApiHeaders(): Request.Builder = allAnimeHeaders()
        .header("x-build-id", protocol.buildId)

    private fun Request.Builder.playerHeaders(): Request.Builder = this
        .header("User-Agent", USER_AGENT)
        .header("Referer", protocol.playerReferer)
        .header("Accept", "application/json, */*")

    private fun absoluteSubtitle(item: SubtitleItem): SubtitleItem = item.copy(
        url = NativeProviderParsers.absoluteUrl("https://allanime.day", item.url),
    )

    private fun logFailure(message: String, throwable: Throwable) {
        // Local JVM tests use Android stubs, so logging itself must never alter provider behavior.
        runCatching { DiagnosticsLog.throwable(message, throwable) }
    }

    private inline fun <T> retryOnce(label: String, block: () -> T): T {
        return runCatching(block).getOrElse { firstFailure ->
            // A rate-limited request already consumed the server's burst allowance. Production
            // should fall back immediately; live tests space requests before reaching this point.
            if (firstFailure is CurrentRouteRateLimit) throw firstFailure
            logFailure("$label failed; retrying once", firstFailure)
            block()
        }
    }

    private fun currentRouteError(root: JsonObject): String? =
        (root["errors"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.string("message") }
            ?.joinToString("; ")
            ?.takeIf(String::isNotBlank)

    private fun stream(
        url: String,
        type: String,
        label: String,
        audio: String,
        referer: String,
    ) = StreamItem(url, type, label, audio, referer, false, null, null)

    private fun qualityLabel(source: String, quality: String?): String = listOf(
        "AllAnime",
        quality?.takeIf(String::isNotBlank),
        source.takeIf(String::isNotBlank),
    ).filterNotNull().joinToString(" ")

    private fun titles(media: Media): List<String> = listOfNotNull(
        media.title.english,
        media.title.romaji,
        media.title.native,
    ).filter(String::isNotBlank).distinct()

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.boolean(name: String): Boolean = (this[name] as? JsonPrimitive)?.contentOrNull == "true"

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val MEDIA_URL = Regex("""\.(?:m3u8|mp4)(?:[?#]|$)""", RegexOption.IGNORE_CASE)
        private val HTML_PLAYER_URL = Regex("""(?:player|embed)[^/?]*\.html(?:[?#]|$)""", RegexOption.IGNORE_CASE)
        private const val SEARCH_QUERY =
            "query(\$search: SearchInput, \$limit: Int, \$page: Int, \$translationType: VaildTranslationTypeEnumType, \$countryOrigin: VaildCountryOriginEnumType) { shows(search: \$search, limit: \$limit, page: \$page, translationType: \$translationType, countryOrigin: \$countryOrigin) { edges { _id name englishName malId availableEpisodes } } }"
    }
}

internal object AllAnimeCodec {
    data class Source(
        val name: String,
        val url: String,
        val type: String,
        val quality: String?,
        val priority: Double,
        val fileExtension: String? = null,
    )

    private val hexMap = mapOf(
        "79" to 'A', "7a" to 'B', "7b" to 'C', "7c" to 'D', "7d" to 'E', "7e" to 'F', "7f" to 'G',
        "70" to 'H', "71" to 'I', "72" to 'J', "73" to 'K', "74" to 'L', "75" to 'M', "76" to 'N',
        "77" to 'O', "68" to 'P', "69" to 'Q', "6a" to 'R', "6b" to 'S', "6c" to 'T', "6d" to 'U',
        "6e" to 'V', "6f" to 'W', "60" to 'X', "61" to 'Y', "62" to 'Z', "59" to 'a', "5a" to 'b',
        "5b" to 'c', "5c" to 'd', "5d" to 'e', "5e" to 'f', "5f" to 'g', "50" to 'h', "51" to 'i',
        "52" to 'j', "53" to 'k', "54" to 'l', "55" to 'm', "56" to 'n', "57" to 'o', "48" to 'p',
        "49" to 'q', "4a" to 'r', "4b" to 's', "4c" to 't', "4d" to 'u', "4e" to 'v', "4f" to 'w',
        "40" to 'x', "41" to 'y', "42" to 'z', "08" to '0', "09" to '1', "0a" to '2', "0b" to '3',
        "0c" to '4', "0d" to '5', "0e" to '6', "0f" to '7', "00" to '8', "01" to '9', "15" to '-',
        "16" to '.', "67" to '_', "46" to '~', "02" to ':', "17" to '/', "07" to '?', "1b" to '#',
        "63" to '[', "65" to ']', "78" to '@', "19" to '!', "1c" to '$', "1e" to '&', "10" to '(',
        "11" to ')', "12" to '*', "13" to '+', "14" to ',', "03" to ';', "05" to '=', "1d" to '%',
    )

    fun decodeSourceUrl(value: String): String {
        if (!value.startsWith("--")) return value
        return value.drop(2).chunked(2).mapNotNull(hexMap::get).joinToString("")
            .replace("/clock", "/clock.json")
    }

    fun decrypt(encoded: String): JsonElement {
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size > 29) { "AllAnime encrypted payload is too short" }
        val iv = bytes.copyOfRange(1, 13)
        val counter = iv + byteArrayOf(0, 0, 0, 2)
        val ciphertext = bytes.copyOfRange(13, bytes.size - 16)
        val key = MessageDigest.getInstance("SHA-256").digest(KEY_SEED.toByteArray(StandardCharsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(counter))
        val plaintext = cipher.doFinal(ciphertext).toString(StandardCharsets.UTF_8)
        return Json.parseToJsonElement(plaintext)
    }

    fun epochKey(partB: String, maskHex: String): ByteArray {
        val mask = maskHex.chunked(2).map { it.toInt(16).toByte() }
        val second = Base64.getDecoder().decode(partB)
        require(mask.size >= 16 && second.size >= 32) { "AllAnime epoch key is invalid" }
        return ByteArray(32) { index -> (second[index].toInt() xor mask[index % mask.size].toInt()).toByte() }
    }

    fun signRequest(
        key: ByteArray,
        epoch: Int,
        buildId: String,
        queryHash: String,
        nowMs: Long,
    ): String {
        val timestamp = nowMs / REQUEST_WINDOW_MS * REQUEST_WINDOW_MS
        val iv = MessageDigest.getInstance("SHA-256")
            .digest("$epoch:$buildId:$queryHash:$timestamp".toByteArray(StandardCharsets.UTF_8))
            .copyOfRange(0, 12)
        val plaintext = """{"v":1,"ts":$timestamp,"epoch":$epoch,"buildId":"$buildId","qh":"$queryHash"}"""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(byteArrayOf(1) + iv + encrypted)
    }

    fun decryptGcm(encoded: String, key: ByteArray): JsonElement {
        val envelope = Base64.getDecoder().decode(encoded)
        require(envelope.size > 29 && envelope[0].toInt() == 1) { "AllAnime encrypted payload is invalid" }
        val iv = envelope.copyOfRange(1, 13)
        val encrypted = envelope.copyOfRange(13, envelope.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return Json.parseToJsonElement(cipher.doFinal(encrypted).toString(StandardCharsets.UTF_8))
    }

    fun parseSources(value: JsonElement?): List<Source> {
        val root = value as? JsonObject ?: return emptyList()
        val sourceUrls = ((root["episode"] as? JsonObject)?.get("sourceUrls") as? JsonArray)
            ?: (root["sourceUrls"] as? JsonArray)
            ?: return emptyList()
        return sourceUrls.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val url = item.primitive("sourceUrl") ?: return@mapNotNull null
            Source(
                name = item.primitive("sourceName").orEmpty(),
                url = url,
                type = item.primitive("type").orEmpty(),
                quality = item.primitive("resolutionStr"),
                priority = (item["priority"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                fileExtension = item.primitive("fileExtenstion") ?: item.primitive("fallBack"),
            )
        }
    }

    /** Finds soft-caption rows in both decrypted episode payloads and /clock responses. */
    fun parseSubtitles(value: JsonElement?): List<SubtitleItem> {
        val result = mutableListOf<SubtitleItem>()

        fun visit(element: JsonElement?, subtitleCollection: Boolean = false) {
            when (element) {
                is JsonArray -> element.forEach { visit(it, subtitleCollection) }
                is JsonObject -> {
                    val url = SUBTITLE_URL_KEYS.firstNotNullOfOrNull { element.primitive(it) }
                    val kind = element.primitive("kind")
                        ?: element.primitive("type")
                        ?: element.primitive("trackType")
                    val extensionMatches = url?.let(SUBTITLE_URL::containsMatchIn) == true
                    val kindMatches = kind?.contains(Regex("subtitle|caption", RegexOption.IGNORE_CASE)) == true
                    if (!url.isNullOrBlank() && (subtitleCollection || extensionMatches || kindMatches)) {
                        val rawLanguage = SUBTITLE_LANGUAGE_KEYS.firstNotNullOfOrNull { element.primitive(it) }
                        val rawLabel = SUBTITLE_LABEL_KEYS.firstNotNullOfOrNull { element.primitive(it) }
                        val language = subtitleLanguage(rawLanguage ?: rawLabel)
                        result += SubtitleItem(
                            url = NativeProviderParsers.decodeEntities(url),
                            label = rawLabel?.takeIf(String::isNotBlank)
                                ?: rawLanguage?.takeIf(String::isNotBlank)
                                ?: "Subtitle",
                            language = language,
                        )
                    }
                    element.forEach { (key, child) ->
                        val normalized = key.lowercase().replace(Regex("[^a-z]"), "")
                        val childIsSubtitleCollection = normalized in SUBTITLE_COLLECTION_KEYS
                        visit(child, childIsSubtitleCollection)
                    }
                }
                else -> Unit
            }
        }

        visit(value)
        return result.distinctBy(SubtitleItem::url)
    }

    private fun subtitleLanguage(value: String?): String {
        val normalized = value.orEmpty().trim().lowercase().substringBefore('-').substringBefore('_')
        return when (normalized) {
            "english", "eng", "en" -> "en"
            "japanese", "jpn", "ja" -> "ja"
            "french", "fra", "fre", "fr" -> "fr"
            "german", "deu", "ger", "de" -> "de"
            "spanish", "spa", "es" -> "es"
            "portuguese", "por", "pt" -> "pt"
            "arabic", "ara", "ar" -> "ar"
            "italian", "ita", "it" -> "it"
            else -> normalized.takeIf { it.length in 2..3 } ?: "und"
        }
    }

    private fun JsonObject.primitive(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private val SUBTITLE_URL_KEYS = listOf("url", "src", "file", "link")
    private val SUBTITLE_LABEL_KEYS = listOf("label", "name", "title")
    private val SUBTITLE_LANGUAGE_KEYS = listOf("language", "lang", "srclang", "languageCode")
    private val SUBTITLE_COLLECTION_KEYS = setOf("subtitles", "subtitle", "captions", "caption")
    private val SUBTITLE_URL = Regex("\\.(?:vtt|srt|ass|ssa)(?:[?#]|$)", RegexOption.IGNORE_CASE)
    private const val KEY_SEED = "Xot36i3lK3:v1"
    private const val REQUEST_WINDOW_MS = 5 * 60 * 1000L
}
