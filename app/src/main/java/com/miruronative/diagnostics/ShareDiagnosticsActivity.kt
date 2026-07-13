package com.miruronative.diagnostics

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/** Launcher entry that shares diagnostics without booting the normal Compose/WebView app. */
class ShareDiagnosticsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticsLog.event("ShareDiagnosticsActivity.onCreate")
        DiagnosticsLog.share(this)
            .onFailure { error ->
                Toast.makeText(
                    this,
                    error.message ?: "Couldn't share diagnostics",
                    Toast.LENGTH_LONG,
                ).show()
            }
        finish()
    }
}
