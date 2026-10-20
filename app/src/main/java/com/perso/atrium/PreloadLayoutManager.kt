package com.perso.atrium

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class PreloadLayoutManager(
    context: Context,
    orientation: Int,
    reverseLayout: Boolean,
    private val extraPx: Int
) : LinearLayoutManager(context, orientation, reverseLayout) {

    override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
        extraLayoutSpace[0] = extraPx
        extraLayoutSpace[1] = extraPx
    }
}
