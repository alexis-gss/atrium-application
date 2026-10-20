package com.perso.atrium

import android.graphics.drawable.Drawable

data class Tile(
    val key: String,
    val label: String,
    val icon: Drawable?,
    val isBanner: Boolean,
    val onClick: () -> Unit
)
