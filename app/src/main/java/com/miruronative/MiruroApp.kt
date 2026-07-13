package com.miruronative

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import android.os.StrictMode
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.miruronative.data.AppGraph
import com.miruronative.data.auth.AuthManager
import com.miruronative.diagnostics.CrashReporter
import com.miruronative.data.library.LibraryStore
import com.miruronative.data.settings.SettingsStore
import com.miruronative.data.reminder.ReminderManager
import com.miruronative.data.reminder.AutomaticReleaseManager
import com.miruronative.data.reminder.ReleaseSyncScheduler
import com.miruronative.diagnostics.DiagnosticsLog

class MiruroApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        DiagnosticsLog.init(this)
        DiagnosticsLog.event("MiruroApp.onCreate start")
        if (isDiagnosticsProcess()) {
            DiagnosticsLog.event("MiruroApp diagnostics process; skipping normal app init")
            return
        }
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build(),
            )
        }
        DiagnosticsLog.event("CrashReporter.init start")
        CrashReporter.init(this)
        DiagnosticsLog.event("AppGraph.init start")
        AppGraph.init(this)
        DiagnosticsLog.event("LibraryStore.init start")
        LibraryStore.init(this)
        DiagnosticsLog.event("AuthManager.init start")
        AuthManager.init(this)
        DiagnosticsLog.event("SettingsStore.init start")
        SettingsStore.init(this)
        DiagnosticsLog.event("ReminderManager.init start")
        ReminderManager.init(this)
        DiagnosticsLog.event("AutomaticReleaseManager.init start")
        AutomaticReleaseManager.init(this)
        DiagnosticsLog.event("ReleaseSyncScheduler.schedule start")
        ReleaseSyncScheduler.schedule(this)
        DiagnosticsLog.event("MiruroApp.onCreate complete")
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("images"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .build()

    private fun isDiagnosticsProcess(): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val pid = Process.myPid()
            @Suppress("DEPRECATION")
            (getSystemService(ACTIVITY_SERVICE) as? ActivityManager)
                ?.runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
        }
        return processName == "$packageName:diagnostics"
    }
}
