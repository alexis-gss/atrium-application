package com.perso.atrium

import android.content.Context

object Favorites {
    private const val PREFS = "atrium"
    private const val KEY = "favorites"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(context: Context): List<String> =
        (prefs(context).getString(KEY, "") ?: "")
            .split('\n')
            .filter { it.isNotBlank() }

    fun toggle(context: Context, pkg: String): Boolean {
        val current = list(context).toMutableList()
        val added = if (current.remove(pkg)) {
            false
        } else {
            current.add(pkg)
            true
        }
        prefs(context).edit().putString(KEY, current.joinToString("\n")).apply()
        return added
    }

    fun setOrder(context: Context, keys: List<String>) {
        prefs(context).edit().putString(KEY, keys.joinToString("\n")).apply()
    }
}
