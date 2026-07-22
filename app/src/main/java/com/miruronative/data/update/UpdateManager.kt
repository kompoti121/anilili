package com.miruronative.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.miruronative.BuildConfig
import com.miruronative.data.AppGraph
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Request

/**
 * In-app updates for a sideloaded install: polls the rolling GitHub release
 * (`kompoti121/anilili`, tag `APK-release`), downloads the APK asset, and hands it
 * to the system package installer. Android rejects the install unless the new APK
 * is signed with the same key, so a hijacked release can't replace the app.
 */
object UpdateManager {
    data class UpdateInfo(
        val version: String,
        val changelog: String,
        val apkUrl: String,
        val sizeBytes: Long,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        /** Manual check found nothing newer. */
        data object UpToDate : State
        data class Available(val update: UpdateInfo) : State
        data class Downloading(val update: UpdateInfo, val progress: Float) : State
        data class ReadyToInstall(val update: UpdateInfo, val file: File) : State
        data class Failed(val message: String) : State
    }

    private const val RELEASES_LATEST = "https://api.github.com/repos/kompoti121/anilili/releases/latest"
    private const val PREFS = "anilili_updates"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private val CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(12)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Throttled startup check; only surfaces a prompt when an update exists. */
    fun autoCheckIfDue(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        check(context, manual = false)
    }

    fun check(context: Context, manual: Boolean) {
        val current = _state.value
        if (current is State.Checking || current is State.Downloading) return
        _state.value = State.Checking
        val appContext = context.applicationContext
        scope.launch {
            runCatching { fetchLatest() }
                .onSuccess { info ->
                    appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                    _state.value = when {
                        info != null && isNewer(info.version) -> State.Available(info)
                        manual -> State.UpToDate
                        else -> State.Idle
                    }
                }
                .onFailure { error ->
                    _state.value = if (manual) State.Failed(error.message ?: "Update check failed") else State.Idle
                }
        }
    }

    fun download(context: Context) {
        val info = (_state.value as? State.Available)?.update ?: return
        _state.value = State.Downloading(info, 0f)
        val appContext = context.applicationContext
        scope.launch {
            runCatching { downloadApk(appContext, info) }
                .onSuccess { file ->
                    _state.value = State.ReadyToInstall(info, file)
                    install(appContext)
                }
                .onFailure { error ->
                    _state.value = State.Failed(error.message ?: "Download failed")
                }
        }
    }

    /**
     * Launches the system installer for the downloaded APK. If the app can't request
     * installs yet, opens the "install unknown apps" settings screen instead; the
     * ReadyToInstall state is kept so the user can retry after granting.
     */
    fun install(context: Context) {
        val ready = _state.value as? State.ReadyToInstall ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            // Some TV builds don't resolve the per-app screen; fall back to the general list.
            val perApp = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val generic = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(perApp) }
                .recoverCatching { context.startActivity(generic) }
                .onFailure {
                    _state.value = State.Failed(
                        "Android blocked the install. Allow \"install unknown apps\" for Anilili in system settings, then try again.",
                    )
                }
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", ready.file)
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { error ->
            _state.value = State.Failed(error.message ?: "Couldn't launch the installer")
        }
    }

    fun dismiss() {
        if (_state.value !is State.Downloading) _state.value = State.Idle
    }

    private fun fetchLatest(): UpdateInfo? {
        val request = Request.Builder()
            .url(RELEASES_LATEST)
            .header("Accept", "application/vnd.github+json")
            .build()
        AppGraph.httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Update check failed (HTTP ${response.code})")
            val release = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            val name = release["name"]?.jsonPrimitive?.content.orEmpty()
            val tag = release["tag_name"]?.jsonPrimitive?.content.orEmpty()
            val body = release["body"]?.jsonPrimitive?.content.orEmpty()
            val version = parseVersion(name) ?: parseVersion(tag) ?: parseVersion(body) ?: return null
            val apks = release["assets"]?.jsonArray
                ?.map { it.jsonObject }
                ?.filter { it["name"]?.jsonPrimitive?.content.orEmpty().endsWith(".apk", ignoreCase = true) }
                .orEmpty()
            val preferredName = preferredReleaseApkName(
                apks.map { it["name"]?.jsonPrimitive?.content.orEmpty() },
                Build.SUPPORTED_ABIS.toList(),
            ) ?: return null
            val apk = apks.firstOrNull {
                it["name"]?.jsonPrimitive?.content.orEmpty() == preferredName
            } ?: return null
            return UpdateInfo(
                version = version,
                changelog = body,
                apkUrl = apk["browser_download_url"]?.jsonPrimitive?.content ?: return null,
                sizeBytes = apk["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: -1L,
            )
        }
    }

    private fun downloadApk(context: Context, info: UpdateInfo): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "anilili-${info.version}.apk")
        // No call timeout: the release APK takes longer than the API client's 45s cap on slow links.
        val client = AppGraph.httpClient.newBuilder()
            .cache(null)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
        client.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) error("Download failed (HTTP ${response.code})")
            val responseBody = response.body ?: error("Download failed (empty response)")
            val total = responseBody.contentLength().takeIf { it > 0 } ?: info.sizeBytes
            responseBody.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            _state.value = State.Downloading(info, (written.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                    if (total > 0 && written < total) error("Download incomplete ($written of $total bytes)")
                }
            }
        }
        return file
    }

    private fun parseVersion(text: String): String? =
        Regex("""v?(\d+(?:\.\d+)+)""").find(text)?.groupValues?.get(1)

    private fun isNewer(remote: String): Boolean {
        val remoteParts = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val currentParts = currentVersion.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(remoteParts.size, currentParts.size)) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return false
    }
}

/** Chooses the smallest compatible split while retaining the universal APK as a safe fallback. */
internal fun preferredReleaseApkName(names: List<String>, supportedAbis: List<String>): String? {
    val apks = names.filter { it.endsWith(".apk", ignoreCase = true) }
    if (apks.isEmpty()) return null
    val preferredAbi = when {
        supportedAbis.any { it.equals("arm64-v8a", ignoreCase = true) } -> "arm64-v8a"
        supportedAbis.any { it.equals("armeabi-v7a", ignoreCase = true) } -> "armeabi-v7a"
        else -> null
    }
    return preferredAbi?.let { abi ->
        apks.firstOrNull { it.contains(abi, ignoreCase = true) }
    } ?: apks.firstOrNull { it.equals("anilili.apk", ignoreCase = true) }
        ?: apks.firstOrNull { it.contains("universal", ignoreCase = true) }
        ?: apks.first()
}
