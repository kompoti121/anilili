package com.miruronative.diagnostics

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import android.webkit.WebView
import androidx.core.content.FileProvider
import com.miruronative.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small rolling diagnostic log for user-reported "black screen" and startup hangs where no crash
 * is thrown. Keep this local-only: users explicitly share a snapshot from Settings.
 */
object DiagnosticsLog {
    private const val LOG_DIR = "diagnostics"
    private const val LOG_FILE = "diagnostics.txt"
    private const val SHARE_FILE = "anilili-diagnostics.txt"
    private const val MAX_BYTES = 900_000L
    private const val TRIM_TO_BYTES = 650_000

    private val lock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var file: File? = null
    @Volatile private var lifecycleCallbacksInstalled = false
    @Volatile private var watchdogStarted = false
    @Volatile private var lastMainBlockLogAt = 0L

    fun init(context: Context) {
        appContext = context.applicationContext
        file = File(context.filesDir, LOG_DIR).resolve(LOG_FILE)
        event(
            "process start app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                "${BuildConfig.BUILD_TYPE}; device=${Build.MANUFACTURER} ${Build.MODEL}; " +
                "android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}",
        )
    }

    fun installLifecycleCallbacks(application: Application) {
        if (lifecycleCallbacksInstalled) return
        lifecycleCallbacksInstalled = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                event("lifecycle ${activity.javaClass.simpleName}.created saved=${savedInstanceState != null}")
            }

            override fun onActivityStarted(activity: Activity) {
                event("lifecycle ${activity.javaClass.simpleName}.started")
            }

            override fun onActivityResumed(activity: Activity) {
                event("lifecycle ${activity.javaClass.simpleName}.resumed")
            }

            override fun onActivityPaused(activity: Activity) {
                event("lifecycle ${activity.javaClass.simpleName}.paused")
            }

            override fun onActivityStopped(activity: Activity) {
                event("lifecycle ${activity.javaClass.simpleName}.stopped")
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
                event("lifecycle ${activity.javaClass.simpleName}.saveInstanceState")
            }

            override fun onActivityDestroyed(activity: Activity) {
                event("lifecycle ${activity.javaClass.simpleName}.destroyed finishing=${activity.isFinishing}")
            }
        })
    }

    fun startMainThreadWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        val main = Handler(Looper.getMainLooper())
        Thread {
            while (true) {
                val postedAt = SystemClock.elapsedRealtime()
                val responded = AtomicBoolean(false)
                main.post {
                    responded.set(true)
                    val delayMs = SystemClock.elapsedRealtime() - postedAt
                    if (delayMs > 5_000) {
                        event("main thread recovered after ${delayMs}ms")
                    }
                }
                runCatching { Thread.sleep(6_000) }
                if (!responded.get()) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastMainBlockLogAt > 15_000) {
                        lastMainBlockLogAt = now
                        append(
                            buildString {
                                append(timestamp())
                                append("  MAIN THREAD BLOCKED >6000ms\n")
                                append(stackTrace(Looper.getMainLooper().thread))
                                append('\n')
                            },
                        )
                    }
                }
                runCatching { Thread.sleep(2_000) }
            }
        }.apply {
            name = "anilili-diagnostics-watchdog"
            isDaemon = true
            start()
        }
        event("main thread watchdog started")
    }

    fun snapshot(context: Context, label: String) {
        val app = context.applicationContext
        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also { info ->
            runCatching { activityManager?.getMemoryInfo(info) }
        }
        val runtime = Runtime.getRuntime()
        val configuration = app.resources.configuration
        event(
            "$label snapshot " +
                "orientation=${orientation(configuration)} uiMode=${uiMode(configuration)} " +
                "fontScale=${configuration.fontScale} " +
                "screenDp=${configuration.screenWidthDp}x${configuration.screenHeightDp} " +
                "smallestDp=${configuration.smallestScreenWidthDp} " +
                "memAvailMb=${memoryInfo.availMem / 1024 / 1024} " +
                "memLow=${memoryInfo.lowMemory} " +
                "heapUsedMb=${(runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024} " +
                "heapMaxMb=${runtime.maxMemory() / 1024 / 1024}",
        )
    }

    fun webViewPackage(label: String) {
        val pkg = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        event(
            "$label webviewPackage=" +
                if (pkg == null) "none" else "${pkg.packageName}/${pkg.versionName} (${pkg.longVersionCodeCompat()})",
        )
    }

    fun watchFirstDraw(view: View, label: String, timeoutMs: Long = 5_000) {
        val startedAt = SystemClock.elapsedRealtime()
        val drawn = AtomicBoolean(false)
        view.post {
            event(
                "$label decor posted attached=${view.isAttachedToWindow} " +
                    "shown=${view.isShown} size=${view.width}x${view.height} visibility=${view.visibility}",
            )
        }
        Choreographer.getInstance().postFrameCallback {
            event("$label first choreographer frame after ${SystemClock.elapsedRealtime() - startedAt}ms")
        }
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (drawn.compareAndSet(false, true)) {
                    if (view.viewTreeObserver.isAlive) {
                        view.viewTreeObserver.removeOnPreDrawListener(this)
                    }
                    event(
                        "$label first pre-draw after ${SystemClock.elapsedRealtime() - startedAt}ms " +
                            "attached=${view.isAttachedToWindow} shown=${view.isShown} size=${view.width}x${view.height}",
                    )
                }
                return true
            }
        }
        view.viewTreeObserver.addOnPreDrawListener(listener)
        view.postDelayed({
            if (!drawn.get()) {
                event(
                    "$label NO pre-draw after ${timeoutMs}ms " +
                        "attached=${view.isAttachedToWindow} shown=${view.isShown} size=${view.width}x${view.height} " +
                        "visibility=${view.visibility} windowFocus=${view.hasWindowFocus()}",
                )
            }
        }, timeoutMs)
    }

    fun event(message: String) {
        append("${timestamp()} +${SystemClock.elapsedRealtime()}ms  $message\n")
    }

    fun throwable(message: String, throwable: Throwable) {
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        append(
            buildString {
                append(timestamp())
                append(" +")
                append(SystemClock.elapsedRealtime())
                append("ms")
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
        val snapshot = createShareSnapshot(context.applicationContext)
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

    fun threadStack(message: String, thread: Thread) {
        append(
            buildString {
                append(timestamp())
                append(" +")
                append(SystemClock.elapsedRealtime())
                append("ms  ")
                append(message)
                append('\n')
                append(stackTrace(thread))
                append('\n')
            },
        )
    }

    /**
     * TV path C: copies the snapshot into the device's public Downloads so it can also be pulled
     * with a file manager or over USB. Timestamped so repeated shares don't shadow each other.
     */
    fun saveToDownloads(context: Context, snapshot: File): Result<String> = runCatching {
        val name = "anilili-diagnostics-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            }
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Couldn't create a Downloads entry")
            resolver.openOutputStream(uri)?.use { out ->
                snapshot.inputStream().use { it.copyTo(out) }
            } ?: error("Couldn't write to Downloads")
        } else {
            @Suppress("DEPRECATION")
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS,
            )
            dir.mkdirs()
            snapshot.copyTo(File(dir, name), overwrite = true)
        }
        event("diagnostics saved to Downloads/$name")
        "Downloads/$name"
    }.onFailure { throwable("diagnostics downloads save failed", it) }

    fun createShareSnapshot(context: Context): File {
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

    private fun stackTrace(thread: Thread): String =
        thread.stackTrace.joinToString("\n") { "    at $it" }

    private fun orientation(configuration: Configuration): String = when (configuration.orientation) {
        Configuration.ORIENTATION_LANDSCAPE -> "landscape"
        Configuration.ORIENTATION_PORTRAIT -> "portrait"
        else -> "undefined"
    }

    private fun uiMode(configuration: Configuration): String = when (
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    ) {
        Configuration.UI_MODE_TYPE_TELEVISION -> "tv"
        Configuration.UI_MODE_TYPE_CAR -> "car"
        Configuration.UI_MODE_TYPE_WATCH -> "watch"
        Configuration.UI_MODE_TYPE_NORMAL -> "normal"
        else -> "unknown"
    }

    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
}
