package com.miruronative.playback

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebSettings
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.miruronative.data.model.StreamItem
import com.miruronative.data.model.SubtitleItem
import com.miruronative.data.settings.DownloadQuality
import com.miruronative.diagnostics.DiagnosticsLog
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class EpisodeDownloadSubtitle(
    val url: String,
    val label: String,
    val language: String,
    val fileName: String? = null,
)

@Serializable
data class EpisodeDownloadMetadata(
    val anilistId: Int,
    val seriesTitle: String,
    val episodeNumber: String,
    val episodeTitle: String? = null,
    val artworkUrl: String? = null,
    val provider: String,
    val category: String,
    val referer: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<EpisodeDownloadSubtitle> = emptyList(),
    val quality: String? = null,
    val streamType: String? = null,
)

enum class EpisodeDownloadState {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    REMOVING,
    RESTARTING,
    STOPPED,
}

data class EpisodeDownload(
    val id: String,
    val uri: String,
    val metadata: EpisodeDownloadMetadata,
    val state: EpisodeDownloadState,
    val percent: Float?,
    val bytesDownloaded: Long,
    val contentLength: Long?,
    val updatedAtMs: Long,
) {
    val isComplete: Boolean get() = state == EpisodeDownloadState.COMPLETED
    val isActive: Boolean get() = state in setOf(
        EpisodeDownloadState.QUEUED,
        EpisodeDownloadState.DOWNLOADING,
        EpisodeDownloadState.RESTARTING,
        EpisodeDownloadState.STOPPED,
    )
}

/**
 * Owns Media3's persistent download index and non-evicting download cache.
 *
 * Download request metadata contains the HTTP profile needed by the provider. A resolving data
 * source applies that profile to the manifest and every child playlist/segment request, including
 * child URLs hosted on a different CDN. Downloads are deliberately serialized so the active
 * profile is unambiguous.
 */
@OptIn(UnstableApi::class)
object EpisodeDownloads {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _downloads = MutableStateFlow<List<EpisodeDownload>>(emptyList())
    private val _preparingIds = MutableStateFlow<Set<String>>(emptySet())
    val preparingIds: StateFlow<Set<String>> = _preparingIds.asStateFlow()

    private val metadataById = ConcurrentHashMap<String, EpisodeDownloadMetadata>()
    private val manifestIdByUri = ConcurrentHashMap<String, String>()
    private val manifestIdByHost = ConcurrentHashMap<String, String>()
    private val requestByUri = ConcurrentHashMap<String, DownloadRequest>()
    private val subtitleJobs = ConcurrentHashMap<String, Job>()

    @Volatile private var initialized = false
    @Volatile private var activeDownloadId: String? = null
    @Volatile private var preparingDownloadId: String? = null
    private var progressPoller: Job? = null

    private lateinit var appContext: Context
    private lateinit var databaseProvider: StandaloneDatabaseProvider
    private lateinit var downloadCache: SimpleCache
    private lateinit var upstreamFactory: DataSource.Factory
    private lateinit var manager: DownloadManager
    private lateinit var userAgent: String
    private val subtitleClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun downloads(context: Context): StateFlow<List<EpisodeDownload>> {
        initialize(context)
        return _downloads.asStateFlow()
    }

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            databaseProvider = StandaloneDatabaseProvider(appContext)
            downloadCache = SimpleCache(
                File(appContext.filesDir, DOWNLOAD_DIRECTORY),
                NoOpCacheEvictor(),
                databaseProvider,
            )
            userAgent = runCatching {
                WebSettings.getDefaultUserAgent(appContext).replace("; wv", "")
            }.getOrDefault(FALLBACK_USER_AGENT)
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setAllowCrossProtocolRedirects(true)
            upstreamFactory = ResolvingDataSource.Factory(
                httpFactory,
                ResolvingDataSource.Resolver { dataSpec ->
                    val headers = requestHeadersFor(dataSpec.uri)
                    if (headers.isEmpty()) dataSpec else dataSpec.withAdditionalHeaders(headers)
                },
            )
            manager = DownloadManager(
                appContext,
                databaseProvider,
                downloadCache,
                upstreamFactory,
                Runnable::run,
            ).apply {
                maxParallelDownloads = 1
                addListener(downloadListener)
            }
            initialized = true
            refreshDownloads()
            DiagnosticsLog.event("EpisodeDownloads initialized")
        }
    }

    fun getDownloadManager(context: Context): DownloadManager {
        initialize(context)
        return manager
    }

    fun downloadCache(context: Context): Cache {
        initialize(context)
        return downloadCache
    }

    /**
     * Adds the download cache in front of the normal streaming source without writing new playback
     * traffic into it. Media3 will therefore play a completed download offline while ordinary
     * streams continue to use the app's bounded playback cache.
     */
    fun readOnlyPlaybackFactory(
        context: Context,
        upstream: DataSource.Factory,
    ): DataSource.Factory = CacheDataSource.Factory()
        .setCache(downloadCache(context))
        .setUpstreamDataSourceFactory(upstream)
        .setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    fun idFor(anilistId: Int, category: String, episodeNumber: String): String =
        "episode:$anilistId:${category.trim().lowercase()}:${episodeNumber.trim()}"

    fun canDownload(stream: StreamItem?): Boolean =
        stream != null &&
            !stream.isEmbed &&
            stream.playlistKey == null &&
            (stream.isHls || stream.isDirectFile())

    fun canSaveToDevice(stream: StreamItem?): Boolean =
        stream != null && stream.playlistKey == null && stream.isDirectFile()

    /**
     * Prepares the adaptive manifest first so Media3 records the selected HLS stream keys instead
     * of blindly downloading every rendition in a multivariant playlist.
     */
    fun enqueue(
        context: Context,
        metadata: EpisodeDownloadMetadata,
        stream: StreamItem,
        quality: DownloadQuality = DownloadQuality.BEST,
        onResult: (Result<Unit>) -> Unit = {},
    ) {
        initialize(context)
        if (!canDownload(stream)) {
            onResult(Result.failure(IllegalArgumentException("This stream cannot be downloaded")))
            return
        }
        val id = idFor(metadata.anilistId, metadata.category, metadata.episodeNumber)
        val preparedMetadata = metadata.copy(
            subtitles = metadata.subtitles.mapIndexed { index, subtitle ->
                subtitle.copy(fileName = subtitle.fileName ?: subtitleFileName(id, index, subtitle.url))
            },
            quality = if (stream.isHls) quality.label else stream.label,
            streamType = if (stream.isHls) "hls" else "direct",
        )
        metadataById[id] = preparedMetadata
        manifestIdByUri[stream.url] = id
        Uri.parse(stream.url).host?.let { manifestIdByHost[it] = id }
        preparingDownloadId = id
        _preparingIds.value += id

        if (!stream.isHls) {
            val result = runCatching {
                val data = json.encodeToString(preparedMetadata).encodeToByteArray()
                val request = DownloadRequest.Builder(id, Uri.parse(stream.url))
                    .setMimeType(directFileFormat(stream).mimeType)
                    .setData(data)
                    .build()
                requestByUri[request.uri.toString()] = request
                DownloadService.sendAddDownload(
                    appContext,
                    EpisodeDownloadService::class.java,
                    request,
                    false,
                )
                DiagnosticsLog.event(
                    "Direct episode download queued id=$id provider=${preparedMetadata.provider} " +
                        "host=${request.uri.host ?: "unknown"} quality=${preparedMetadata.quality}",
                )
                downloadSubtitleFiles(id, preparedMetadata)
            }
            finishPreparing(id)
            onResult(result)
            return
        }

        val mediaItem = MediaItem.Builder()
            .setMediaId(id)
            .setUri(stream.url)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        val helper = DownloadHelper.Factory()
            .setDataSourceFactory(upstreamFactory)
            .setRenderersFactory(DefaultRenderersFactory(appContext))
            .create(mediaItem)
        helper.prepare(object : DownloadHelper.Callback {
            override fun onPrepared(helper: DownloadHelper, tracksInfoAvailable: Boolean) {
                val result = runCatching {
                    val trackParameters = TrackSelectionParameters.Builder()
                        .setForceHighestSupportedBitrate(true)
                        .apply {
                            quality.maxHeight?.let { height ->
                                setMaxVideoSize(Int.MAX_VALUE, height)
                            }
                        }
                        .build()
                    for (periodIndex in 0 until helper.periodCount) {
                        helper.replaceTrackSelections(periodIndex, trackParameters)
                    }
                    val data = json.encodeToString(preparedMetadata).encodeToByteArray()
                    val request = helper.getDownloadRequest(id, data)
                    requestByUri[request.uri.toString()] = request
                    DownloadService.sendAddDownload(
                        appContext,
                        EpisodeDownloadService::class.java,
                        request,
                        false,
                    )
                    DiagnosticsLog.event(
                        "Episode download queued id=$id provider=${preparedMetadata.provider} " +
                            "host=${request.uri.host ?: "unknown"} tracks=${request.streamKeys.size} " +
                            "subtitles=${preparedMetadata.subtitles.size} quality=${quality.label}",
                    )
                    downloadSubtitleFiles(id, preparedMetadata)
                }
                helper.release()
                finishPreparing(id)
                onResult(result)
            }

            override fun onPrepareError(helper: DownloadHelper, e: IOException) {
                helper.release()
                finishPreparing(id)
                DiagnosticsLog.throwable("Episode download prepare failed id=$id", e)
                onResult(Result.failure(e))
            }
        })
    }

    /**
     * Sends a direct video file to Android's public Downloads collection. Adaptive HLS is excluded:
     * it is a bundle of manifests and segments rather than one file another player can open.
     */
    @android.annotation.SuppressLint("NewApi")
    fun enqueueDeviceDownload(
        context: Context,
        metadata: EpisodeDownloadMetadata,
        stream: StreamItem,
    ): Result<Long> = runCatching {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Device Downloads requires Android 10 or newer"
        }
        require(canSaveToDevice(stream)) {
            "Only direct video files can be saved to Device Downloads"
        }
        initialize(context)
        val app = context.applicationContext
        val format = directFileFormat(stream)
        val fileName = uniqueDeviceFileName(
            context = app,
            stem = buildString {
                append(safeFilePart(metadata.seriesTitle))
                append(" - Episode ")
                append(safeFilePart(metadata.episodeNumber))
                metadata.episodeTitle
                    ?.takeIf(String::isNotBlank)
                    ?.let { append(" - ").append(safeFilePart(it)) }
                stream.label.takeIf { it.isNotBlank() && !it.equals("auto", true) }
                    ?.let { append(" [").append(safeFilePart(it)).append(']') }
            }.take(180).trim(),
            extension = format.extension,
        )
        val request = android.app.DownloadManager.Request(Uri.parse(stream.url))
            .setTitle("${metadata.seriesTitle} · Episode ${metadata.episodeNumber}")
            .setDescription("${metadata.provider} · ${stream.label}")
            .setMimeType(format.mimeType)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setNotificationVisibility(
                android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "$PUBLIC_DOWNLOAD_SUBDIRECTORY/$fileName",
            )
        (requestHeaders(metadata) + ("User-Agent" to userAgent)).forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank() && '\n' !in value && '\r' !in value) {
                request.addRequestHeader(name, value)
            }
        }
        val systemManager = app.getSystemService(Context.DOWNLOAD_SERVICE)
            as? android.app.DownloadManager
            ?: error("Android Download Manager is unavailable")
        systemManager.enqueue(request).also { systemId ->
            DiagnosticsLog.event(
                "Device episode download queued systemId=$systemId provider=${metadata.provider} " +
                    "file=$fileName host=${Uri.parse(stream.url).host ?: "unknown"}",
            )
        }
    }

    fun remove(context: Context, id: String) {
        initialize(context)
        DownloadService.sendRemoveDownload(
            appContext,
            EpisodeDownloadService::class.java,
            id,
            false,
        )
        DiagnosticsLog.event("Episode download remove requested id=$id")
    }

    fun localSubtitles(
        context: Context,
        metadata: EpisodeDownloadMetadata,
    ): List<SubtitleItem> {
        initialize(context)
        val directory = subtitleDirectory()
        return metadata.subtitles.mapNotNull { subtitle ->
            val fileName = subtitle.fileName ?: return@mapNotNull null
            val file = File(directory, fileName).takeIf(File::isFile) ?: return@mapNotNull null
            SubtitleItem(
                url = Uri.fromFile(file).toString(),
                label = subtitle.label,
                language = subtitle.language,
            )
        }
    }

    /**
     * Reuses the persisted adaptive stream keys when the normal player opens a downloaded URL.
     * This prevents the player from requesting an undownloaded rendition while offline.
     */
    fun buildMediaItem(
        context: Context,
        uri: String,
        builder: MediaItem.Builder,
    ): MediaItem {
        initialize(context)
        val request = requestByUri[uri]
        return request
            ?.toMediaItem(builder)
            ?.buildUpon()
            ?.setMediaId(uri)
            ?.build()
            ?: builder.build()
    }

    private fun finishPreparing(id: String) {
        _preparingIds.value -= id
        if (preparingDownloadId == id) preparingDownloadId = null
    }

    private fun requestHeadersFor(uri: Uri): Map<String, String> {
        val exactId = manifestIdByUri[uri.toString()]
        val sameHostId = uri.host?.let(manifestIdByHost::get)
        val metadata = metadataById[exactId ?: activeDownloadId ?: preparingDownloadId ?: sameHostId]
            ?: return emptyMap()
        val referer = metadata.referer?.takeIf { it.isNotBlank() } ?: return metadata.headers
        val refererUri = Uri.parse(referer)
        val origin = if (refererUri.scheme != null && refererUri.host != null) {
            "${refererUri.scheme}://${refererUri.host}"
        } else {
            referer
        }
        return buildMap {
            put("Referer", referer)
            put("Origin", origin)
            putAll(metadata.headers)
        }
    }

    private val downloadListener = object : DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            register(download)
            if (download.state == Download.STATE_DOWNLOADING) {
                activeDownloadId = download.request.id
            } else if (activeDownloadId == download.request.id) {
                activeDownloadId = null
            }
            finalException?.let {
                DiagnosticsLog.throwable("Episode download failed id=${download.request.id}", it)
            }
            refreshDownloads()
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            subtitleJobs.remove(download.request.id)?.cancel()
            val metadata = metadataById[download.request.id] ?: decodeMetadata(download.request)
            metadata?.let(::deleteSubtitleFiles)
            metadataById.remove(download.request.id)
            manifestIdByUri.entries.removeAll { it.value == download.request.id }
            manifestIdByHost.entries.removeAll { it.value == download.request.id }
            requestByUri.remove(download.request.uri.toString())
            if (activeDownloadId == download.request.id) activeDownloadId = null
            refreshDownloads()
        }
    }

    private fun refreshDownloads() {
        if (!initialized) return
        scope.launch {
            val loaded = runCatching {
                buildList {
                    manager.downloadIndex.getDownloads().use { cursor ->
                        while (cursor.moveToNext()) {
                            val download = cursor.download
                            register(download)
                            download.toEpisodeDownload()?.let(::add)
                        }
                    }
                }.sortedByDescending(EpisodeDownload::updatedAtMs)
            }.onFailure {
                DiagnosticsLog.throwable("Episode download index query failed", it)
            }.getOrDefault(_downloads.value)
            _downloads.value = loaded
            if (loaded.any { it.state == EpisodeDownloadState.DOWNLOADING }) {
                startProgressPoller()
            } else {
                stopProgressPoller()
            }
        }
    }

    private fun register(download: Download) {
        val metadata = decodeMetadata(download.request) ?: return
        metadataById[download.request.id] = metadata
        manifestIdByUri[download.request.uri.toString()] = download.request.id
        download.request.uri.host?.let { manifestIdByHost[it] = download.request.id }
        requestByUri[download.request.uri.toString()] = download.request
    }

    private fun Download.toEpisodeDownload(): EpisodeDownload? {
        val metadata = decodeMetadata(request) ?: return null
        val rawPercent = percentDownloaded
        return EpisodeDownload(
            id = request.id,
            uri = request.uri.toString(),
            metadata = metadata,
            state = state.toEpisodeDownloadState(),
            percent = when {
                state == Download.STATE_COMPLETED -> 100f
                rawPercent.isFinite() && rawPercent >= 0f -> rawPercent.coerceIn(0f, 100f)
                else -> null
            },
            bytesDownloaded = bytesDownloaded,
            contentLength = contentLength.takeIf { it > 0 },
            updatedAtMs = updateTimeMs,
        )
    }

    private fun decodeMetadata(request: DownloadRequest): EpisodeDownloadMetadata? =
        runCatching {
            json.decodeFromString<EpisodeDownloadMetadata>(request.data.decodeToString())
        }.onFailure {
            DiagnosticsLog.throwable("Episode download metadata invalid id=${request.id}", it)
        }.getOrNull()

    private fun downloadSubtitleFiles(id: String, metadata: EpisodeDownloadMetadata) {
        if (metadata.subtitles.isEmpty()) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val directory = subtitleDirectory().apply { mkdirs() }
                val headers = requestHeaders(metadata) + ("User-Agent" to userAgent)
                metadata.subtitles.forEach { subtitle ->
                    val fileName = subtitle.fileName ?: return@forEach
                    val target = File(directory, fileName)
                    val temporary = File(directory, "$fileName.part")
                    runCatching {
                        val request = Request.Builder()
                            .url(subtitle.url)
                            .apply { headers.forEach { (name, value) -> header(name, value) } }
                            .build()
                        subtitleClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                throw IOException("Subtitle HTTP ${response.code}")
                            }
                            val body = response.body ?: throw IOException("Subtitle response was empty")
                            temporary.outputStream().use { output ->
                                body.byteStream().use { input -> input.copyTo(output) }
                            }
                        }
                        kotlin.coroutines.coroutineContext.ensureActive()
                        if (target.exists() && !target.delete()) {
                            throw IOException("Could not replace subtitle file")
                        }
                        if (!temporary.renameTo(target)) {
                            temporary.copyTo(target, overwrite = true)
                            temporary.delete()
                        }
                        DiagnosticsLog.event(
                            "Episode subtitle downloaded id=$id language=${subtitle.language} " +
                                "label=${subtitle.label.take(48)}",
                        )
                    }.onFailure {
                        temporary.delete()
                        if (it is CancellationException) throw it
                        DiagnosticsLog.throwable(
                            "Episode subtitle download failed id=$id language=${subtitle.language}",
                            it,
                        )
                    }
                }
            } finally {
                subtitleJobs.remove(id)
            }
        }
        subtitleJobs.put(id, job)?.cancel()
        job.start()
    }

    private fun requestHeaders(metadata: EpisodeDownloadMetadata): Map<String, String> {
        val referer = metadata.referer?.takeIf { it.isNotBlank() } ?: return metadata.headers
        val refererUri = Uri.parse(referer)
        val origin = if (refererUri.scheme != null && refererUri.host != null) {
            "${refererUri.scheme}://${refererUri.host}"
        } else {
            referer
        }
        return buildMap {
            put("Referer", referer)
            put("Origin", origin)
            putAll(metadata.headers)
        }
    }

    private fun StreamItem.isDirectFile(): Boolean {
        if (isHls || isEmbed) return false
        val normalizedType = type.trim().lowercase()
        if (normalizedType in setOf("dash", "mpd", "webrtc")) return false
        val path = runCatching { Uri.parse(url).path.orEmpty().lowercase() }.getOrDefault("")
        return !path.endsWith(".mpd")
    }

    private data class DirectFileFormat(val extension: String, val mimeType: String)

    private fun directFileFormat(stream: StreamItem): DirectFileFormat {
        val path = runCatching { Uri.parse(stream.url).path.orEmpty().lowercase() }.getOrDefault("")
        return when {
            path.endsWith(".webm") || stream.type.equals("webm", true) ->
                DirectFileFormat("webm", "video/webm")
            path.endsWith(".mkv") || stream.type.equals("mkv", true) ->
                DirectFileFormat("mkv", "video/x-matroska")
            else -> DirectFileFormat("mp4", MimeTypes.VIDEO_MP4)
        }
    }

    private fun safeFilePart(value: String): String =
        value.trim()
            .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim('.', ' ')
            .ifBlank { "Episode" }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private fun uniqueDeviceFileName(
        context: Context,
        stem: String,
        extension: String,
    ): String {
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_DOWNLOAD_SUBDIRECTORY/"
        var copy = 1
        while (copy <= 999) {
            val suffix = if (copy == 1) "" else " ($copy)"
            val candidate = "$stem$suffix.$extension"
            val exists = context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(candidate, relativePath),
                null,
            )?.use { it.moveToFirst() } == true
            if (!exists) return candidate
            copy += 1
        }
        return "$stem-${System.currentTimeMillis()}.$extension"
    }

    private fun deleteSubtitleFiles(metadata: EpisodeDownloadMetadata) {
        val directory = subtitleDirectory()
        metadata.subtitles.forEach { subtitle ->
            subtitle.fileName?.let { fileName ->
                File(directory, fileName).delete()
                File(directory, "$fileName.part").delete()
            }
        }
    }

    private fun subtitleDirectory(): File =
        File(appContext.filesDir, SUBTITLE_DIRECTORY)

    private fun subtitleFileName(
        id: String,
        index: Int,
        url: String,
    ): String {
        val extension = Uri.parse(url).lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it in SUPPORTED_SUBTITLE_EXTENSIONS }
            ?: "vtt"
        return "${id.hashCode().toUInt().toString(16)}-$index.$extension"
    }

    private fun startProgressPoller() {
        if (progressPoller?.isActive == true) return
        progressPoller = scope.launch {
            while (isActive) {
                delay(PROGRESS_REFRESH_MS)
                refreshDownloads()
                if (_downloads.value.none { it.state == EpisodeDownloadState.DOWNLOADING }) break
            }
        }
    }

    private fun stopProgressPoller() {
        progressPoller?.cancel()
        progressPoller = null
    }

    private fun Int.toEpisodeDownloadState(): EpisodeDownloadState = when (this) {
        Download.STATE_DOWNLOADING -> EpisodeDownloadState.DOWNLOADING
        Download.STATE_COMPLETED -> EpisodeDownloadState.COMPLETED
        Download.STATE_FAILED -> EpisodeDownloadState.FAILED
        Download.STATE_REMOVING -> EpisodeDownloadState.REMOVING
        Download.STATE_RESTARTING -> EpisodeDownloadState.RESTARTING
        Download.STATE_STOPPED -> EpisodeDownloadState.STOPPED
        else -> EpisodeDownloadState.QUEUED
    }

    private const val DOWNLOAD_DIRECTORY = "episode-downloads"
    private const val SUBTITLE_DIRECTORY = "episode-download-subtitles"
    private const val PUBLIC_DOWNLOAD_SUBDIRECTORY = "Anilili"
    private const val PROGRESS_REFRESH_MS = 1_000L
    private val SUPPORTED_SUBTITLE_EXTENSIONS = setOf("vtt", "srt", "ass", "ssa", "ttml", "xml")
    private const val FALLBACK_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
}
