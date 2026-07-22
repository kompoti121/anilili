package com.miruronative.data.auth

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Stores a bearer token (or token bundle) encrypted by a non-exportable Android Keystore key.
 * [slotKey] names the SharedPreferences entry; the default is the original AniList slot, whose
 * key and AAD must never change or existing logins would be dropped on update.
 */
internal class SecureTokenStore(context: Context, private val slotKey: String = KEY_TOKEN_ENCRYPTED) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val aad = slotKey.toByteArray(StandardCharsets.UTF_8)

    fun load(): String? {
        migrateLegacyToken()
        val encoded = prefs.getString(slotKey, null) ?: return null
        return runCatching { decrypt(encoded) }
            .onFailure { prefs.edit().remove(slotKey).apply() }
            .getOrNull()
    }

    fun save(token: String) {
        val encrypted = encrypt(token)
        prefs.edit()
            .putString(slotKey, encrypted)
            .remove(KEY_TOKEN_LEGACY)
            .apply()
    }

    fun clear() {
        prefs.edit().remove(slotKey).remove(KEY_TOKEN_LEGACY).apply()
    }

    private fun migrateLegacyToken() {
        if (slotKey != KEY_TOKEN_ENCRYPTED) return
        val legacy = prefs.getString(KEY_TOKEN_LEGACY, null) ?: return
        runCatching { save(legacy) }
            .onFailure { prefs.edit().remove(KEY_TOKEN_LEGACY).apply() }
    }

    private fun encrypt(value: String): String {
        val softwareKey = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(softwareKey))
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val prefix = if (softwareKey) "$SOFTWARE_KEY_PREFIX:" else ""
        return prefix + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val parts = encoded.split(':')
        val softwareKey = parts.size == 3 && parts[0] == SOFTWARE_KEY_PREFIX
        val valueOffset = if (softwareKey) 1 else 0
        require(parts.size == valueOffset + 2) { "Invalid encrypted token" }
        val iv = Base64.decode(parts[valueOffset], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[valueOffset + 1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(softwareKey), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun secretKey(softwareKey: Boolean): SecretKey =
        if (softwareKey || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            softwareSecretKey()
        } else {
            hardwareSecretKey()
        }

    private fun softwareSecretKey(): SecretKey {
        val stored = prefs.getString(KEY_SOFTWARE_KEY, null)
        val bytes = if (stored != null) {
            Base64.decode(stored, Base64.NO_WRAP)
        } else {
            ByteArray(32).also(SecureRandom()::nextBytes).also { generated ->
                prefs.edit().putString(KEY_SOFTWARE_KEY, Base64.encodeToString(generated, Base64.NO_WRAP)).apply()
            }
        }
        return SecretKeySpec(bytes, "AES")
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun hardwareSecretKey(): SecretKey {
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

    internal companion object {
        const val PREFS_NAME = "miruro_auth"
        const val KEY_TOKEN_LEGACY = "anilist_token"
        const val KEY_TOKEN_ENCRYPTED = "anilist_token_v2"
        const val KEY_MAL_TOKENS = "mal_tokens_v1"
        const val KEY_ALIAS = "miruro_anilist_token_key_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        private const val SOFTWARE_KEY_PREFIX = "s"
        private const val KEY_SOFTWARE_KEY = "legacy_api22_software_key"
    }
}
