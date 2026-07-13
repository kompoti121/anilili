package com.miruronative.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.miruronative.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small rolling diagnostic log for user-reported "black screen" and startup hangs where no crash
 * is thrown. Keep this local-only: users explicitly share a snapshot from Settings.
 */
object DiagnosticsLog {
    private const val LOG_DIR = "diagnostics"
    private const val LOG_FILE = "diagnostics.txt"
    private const val SHARE_FILE = "anilili-diagnostics.txt"
    private const val MAX_BYTES = 260_000L
    private const val TRIM_TO_BYTES = 180_000

    private val lock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var file: File? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        file = File(context.filesDir, LOG_DIR).resolve(LOG_FILE)
        event(
            "process start app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                "${BuildConfig.BUILD_TYPE}; device=${Build.MANUFACTURER} ${Build.MODEL}; " +
                "android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}",
        )
    }

    fun event(message: String) {
        append("${timestamp()}  $message\n")
    }

    fun throwable(message: String, throwable: Throwable) {
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        append(
            buildString {
                append(timestamp())
                append("  ")
                append(message)
                append(": ")
                append(throwable.javaClass.name)
                throwable.message?.let { append(": ").append(it) }
                append('\n')
                append(trace)
                append('\n')
            },
        )
    }

    fun share(context: Context): Result<Unit> = runCatching {
        event("diagnostics share requested")
        val snapshot = writeShareSnapshot(context.applicationContext)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            snapshot,
        )
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Anilili diagnostics")
            .putExtra(Intent.EXTRA_TEXT, "Anilili diagnostics are attached.")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newUri(context.contentResolver, "Anilili diagnostics", uri)
        val chooser = Intent.createChooser(send, "Share diagnostics")
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }.onFailure { throwable("diagnostics share failed", it) }

    private fun append(text: String) {
        val target = file ?: appContext?.let {
            File(it.filesDir, LOG_DIR).resolve(LOG_FILE).also { resolved -> file = resolved }
        } ?: return
        runCatching {
            synchronized(lock) {
                target.parentFile?.mkdirs()
                trimIfNeeded(target)
                target.appendText(text)
            }
        }
    }

    private fun writeShareSnapshot(context: Context): File {
        val dir = File(context.cacheDir, LOG_DIR).apply { mkdirs() }
        val snapshot = File(dir, SHARE_FILE)
        synchronized(lock) {
            snapshot.writeText(
                buildString {
                    appendLine("Anilili diagnostics")
                    appendLine("generated: ${timestamp()}")
                    appendLine("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
                    appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    appendLine()
                    appendLine("== rolling log ==")
                    append(activeFile()?.takeIf { it.exists() }?.readText().orEmpty())
                    appendLine()
                    appendLine("== last crash dialog report ==")
                    append(CrashReporter.pendingReport().orEmpty())
                },
            )
        }
        return snapshot
    }

    private fun trimIfNeeded(target: File) {
        if (!target.exists() || target.length() <= MAX_BYTES) return
        val bytes = target.readBytes()
        val start = (bytes.size - TRIM_TO_BYTES).coerceAtLeast(0)
        target.writeBytes(bytes.copyOfRange(start, bytes.size))
        target.appendText("\n${timestamp()}  log trimmed to last ${TRIM_TO_BYTES / 1000}KB\n")
    }

    private fun activeFile(): File? = file ?: appContext?.let {
        File(it.filesDir, LOG_DIR).resolve(LOG_FILE).also { resolved -> file = resolved }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
