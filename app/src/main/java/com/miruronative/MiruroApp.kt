package com.miruronative

import android.app.Application
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

class MiruroApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build(),
            )
        }
        CrashReporter.init(this)
        AppGraph.init(this)
        LibraryStore.init(this)
        AuthManager.init(this)
        SettingsStore.init(this)
        ReminderManager.init(this)
        AutomaticReleaseManager.init(this)
        ReleaseSyncScheduler.schedule(this)
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
}
