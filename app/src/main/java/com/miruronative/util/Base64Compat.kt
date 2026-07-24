package com.miruronative.util

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/** Base64 and Base64URL support that also runs on Fire OS 5 (API 22). */
internal object Base64Compat {
    fun decode(value: String): ByteArray =
        value.decodeBase64()?.toByteArray() ?: throw IllegalArgumentException("Invalid Base64")

    fun encode(value: ByteArray): String = value.toByteString().base64()

    fun encodeUrlSafe(value: ByteArray): String = value.toByteString().base64Url().replace("=", "")
}
