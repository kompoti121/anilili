package com.miruronative.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the AniList bearer token encrypted by a non-exportable Android Keystore key. */
internal class SecureTokenStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): String? {
        migrateLegacyToken()
        val encoded = prefs.getString(KEY_TOKEN_ENCRYPTED, null) ?: return null
        return runCatching { decrypt(encoded) }
            .onFailure { prefs.edit().remove(KEY_TOKEN_ENCRYPTED).apply() }
            .getOrNull()
    }

    fun save(token: String) {
        val encrypted = encrypt(token)
        prefs.edit()
            .putString(KEY_TOKEN_ENCRYPTED, encrypted)
            .remove(KEY_TOKEN_LEGACY)
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_TOKEN_ENCRYPTED).remove(KEY_TOKEN_LEGACY).apply()
    }

    private fun migrateLegacyToken() {
        val legacy = prefs.getString(KEY_TOKEN_LEGACY, null) ?: return
        runCatching { save(legacy) }
            .onFailure { prefs.edit().remove(KEY_TOKEN_LEGACY).apply() }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val parts = encoded.split(':', limit = 2)
        require(parts.size == 2) { "Invalid encrypted token" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(AAD)
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFS_NAME = "miruro_auth"
        const val KEY_TOKEN_LEGACY = "anilist_token"
        const val KEY_TOKEN_ENCRYPTED = "anilist_token_v2"
        const val KEY_ALIAS = "miruro_anilist_token_key_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        val AAD: ByteArray = KEY_TOKEN_ENCRYPTED.toByteArray(StandardCharsets.UTF_8)
    }
}
