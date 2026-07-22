package com.miruronative.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.util.Base64Compat
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
internal class FlixcloudPlaylistDataSource(
    private val upstream: DataSource,
    private val playlistKey: () -> String?,
) : DataSource {
    private var buffered: ByteArray? = null
    private var bufferedPosition = 0
    private var upstreamOpen = false
    private var resolvedUri: Uri? = null
    private var responseHeaders: Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        buffered = null
        bufferedPosition = 0
        resolvedUri = null
        responseHeaders = emptyMap()

        val length = upstream.open(dataSpec)
        upstreamOpen = true
        val key = playlistKey()
        val path = dataSpec.uri.path.orEmpty()
        val isPlaylist = dataSpec.position == 0L && path.endsWith(".m3u8", ignoreCase = true)
        val isWrappedSegment = dataSpec.position == 0L &&
            (path.endsWith(".png", ignoreCase = true) || path.endsWith(".webp", ignoreCase = true))
        if (key.isNullOrBlank() || (!isPlaylist && !isWrappedSegment)) return length

        val raw = ByteArrayOutputStream().use { output ->
            val chunk = ByteArray(8 * 1024)
            while (true) {
                val read = upstream.read(chunk, 0, chunk.size)
                if (read == C.RESULT_END_OF_INPUT) break
                if (read > 0) output.write(chunk, 0, read)
            }
            output.toByteArray()
        }
        resolvedUri = upstream.uri
        responseHeaders = upstream.responseHeaders
        upstream.close()
        upstreamOpen = false

        val decoded = if (isPlaylist) {
            decodeFlixcloudPlaylist(raw, key) ?: raw
        } else {
            decodeFlixcloudSegment(raw) ?: raw
        }
        if (decoded !== raw) {
            if (isPlaylist || segmentLogCount.getAndIncrement() < 3) {
                DiagnosticsLog.event(
                    "Flixcloud ${if (isPlaylist) "playlist decoded" else "segment unwrapped"} " +
                        "host=${dataSpec.uri.host ?: "unknown"} bytes=${decoded.size}",
                )
            }
        }
        buffered = decoded
        return decoded.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val data = buffered ?: return upstream.read(buffer, offset, length)
        if (bufferedPosition >= data.size) return C.RESULT_END_OF_INPUT
        val count = minOf(length, data.size - bufferedPosition)
        data.copyInto(buffer, offset, bufferedPosition, bufferedPosition + count)
        bufferedPosition += count
        return count
    }

    override fun getUri(): Uri? = resolvedUri ?: upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (responseHeaders.isNotEmpty()) responseHeaders else upstream.responseHeaders

    override fun close() {
        buffered = null
        bufferedPosition = 0
        resolvedUri = null
        responseHeaders = emptyMap()
        if (upstreamOpen) {
            upstreamOpen = false
            upstream.close()
        }
    }

    private companion object {
        val segmentLogCount = AtomicInteger(0)
    }
}

internal fun decodeFlixcloudPlaylist(payload: ByteArray, keyBase64: String): ByteArray? {
    val encoded = payload.toString(Charsets.UTF_8).trim()
    if (encoded.startsWith("#EXTM3U")) return null
    return runCatching {
        val key = Base64Compat.decode(keyBase64)
        require(key.isNotEmpty())
        val encrypted = Base64Compat.decode(encoded)
        ByteArray(encrypted.size) { index ->
            (encrypted[index].toInt() xor key[index % key.size].toInt()).toByte()
        }.takeIf { it.toString(Charsets.UTF_8).startsWith("#EXTM3U") }
    }.getOrNull()
}

internal fun decodeFlixcloudSegment(payload: ByteArray): ByteArray? {
    val headerSize = when {
        payload.size >= 12 && payload.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            payload.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> 12
        payload.size >= 8 && payload.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
        ) -> 8
        else -> return null
    }
    if (payload.size == headerSize) return ByteArray(0)

    val segment = payload.copyOfRange(headerSize, payload.size)
    if (segment.first().toInt() and 0xff == 0x47) return segment
    segment.indices.forEach { index ->
        segment[index] = (segment[index].toInt() xor segmentMask[index and 15].toInt()).toByte()
    }
    return segment.takeIf { it.isNotEmpty() && (it.first().toInt() and 0xff) == 0x47 }
}

private val segmentMask = byteArrayOf(
    157.toByte(), 42, 241.toByte(), 71, 179.toByte(), 142.toByte(), 92, 112,
    166.toByte(), 25, 228.toByte(), 59, 216.toByte(), 98, 15, 197.toByte(),
)
