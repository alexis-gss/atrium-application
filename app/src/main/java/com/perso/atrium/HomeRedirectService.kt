package com.perso.atrium

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

class HomeRedirectService : AccessibilityService() {

    private var lastRedirect = 0L
    private val handler = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in STOCK_LAUNCHERS) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastRedirect < 1500L) return
        lastRedirect = now

        launchHome()

        handler.postDelayed({
            if (!MainActivity.foreground) launchHome()
        }, 1000L)
    }

    private fun launchHome() {
        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        try {
            startActivity(intent, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
        } catch (e: Exception) {
            // Keep empty.
        }
    }

    override fun onInterrupt() {
        // Keep empty.
    }

    companion object {
        private val STOCK_LAUNCHERS = setOf(
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher"
        )
    }
}
