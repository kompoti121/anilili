package com.miruronative

import android.app.Application
import com.miruronative.data.AppGraph
import com.miruronative.data.auth.AuthManager
import com.miruronative.data.library.LibraryStore
import com.miruronative.data.settings.SettingsStore
import com.miruronative.data.reminder.ReminderManager

class MiruroApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        LibraryStore.init(this)
        AuthManager.init(this)
        SettingsStore.init(this)
        ReminderManager.init(this)
    }
}
