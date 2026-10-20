package com.perso.atrium

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Build

data class AppEntry(
    val pkg: String,
    val label: String,
    val icon: Drawable?,
    val isBanner: Boolean,
    val component: ComponentName,
    val category: String
)

object AppLoader {

    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val chosen = LinkedHashMap<String, Pair<ResolveInfo, String>>()

        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            for (info in query(context, category)) {
                val pkg = info.activityInfo.packageName
                if (pkg == context.packageName || chosen.containsKey(pkg)) continue
                chosen[pkg] = info to category
            }
        }

        return chosen.map { (pkg, entry) ->
            val (info, category) = entry
            val banner = if (category == Intent.CATEGORY_LEANBACK_LAUNCHER) {
                runCatching { info.activityInfo.loadBanner(pm) }.getOrNull()
            } else {
                null
            }
            val icon = banner ?: runCatching { info.loadIcon(pm) }.getOrNull()
            AppEntry(
                pkg = pkg,
                label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg),
                icon = icon,
                isBanner = banner != null,
                component = ComponentName(pkg, info.activityInfo.name),
                category = category
            )
        }.sortedBy { it.label.lowercase() }
    }

    @Suppress("DEPRECATION")
    private fun query(context: Context, category: String): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0L)
                )
            } else {
                context.packageManager.queryIntentActivities(intent, 0)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
