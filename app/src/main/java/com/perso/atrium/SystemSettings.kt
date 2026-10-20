package com.perso.atrium

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast

object SystemSettings {
    fun open(context: Context) {
        val candidates = listOfNotNull(
            Intent(Settings.ACTION_SETTINGS),
            context.packageManager.getLeanbackLaunchIntentForPackage("com.android.tv.settings"),
            context.packageManager.getLaunchIntentForPackage("com.android.tv.settings")
        )
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // Keep empty.
            }
        }
        Toast.makeText(context, R.string.system_settings_error, Toast.LENGTH_LONG).show()
    }
}
