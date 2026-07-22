package com.miruronative.data.cache

import com.miruronative.util.Base64Compat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Keeps large JSON cache values below Android's per-row CursorWindow limit. */
internal object CachePayloadCodec {
    private const val GZIP_PREFIX = "gzip:"
    private const val COMPRESSION_THRESHOLD_BYTES = 128 * 1024
    private const val MAX_STORED_PART_CHARS = 384 * 1024

    fun encode(raw: String): String {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        if (bytes.size < COMPRESSION_THRESHOLD_BYTES) return raw

        val compressed = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(bytes) }
            output.toByteArray()
        }
        return GZIP_PREFIX + Base64Compat.encode(compressed)
    }

    fun decode(stored: String): String {
        if (!stored.startsWith(GZIP_PREFIX)) return stored

        val compressed = Base64Compat.decode(stored.removePrefix(GZIP_PREFIX))
        return GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    fun split(stored: String): List<String> = stored.chunked(MAX_STORED_PART_CHARS)
}
