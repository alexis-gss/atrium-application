package com.perso.atrium

import android.graphics.Outline
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * @param appStyle
 * @param showLabel
 * @param iconHeightPx
 */
class TileAdapter(
    private val fixedItemWidthPx: Int = 0,
    private val appStyle: Boolean = false,
    private val iconHeightPx: Int = 0,
    private val showLabel: Boolean = !appStyle
) : RecyclerView.Adapter<TileAdapter.Holder>() {

    private var tiles: List<Tile> = emptyList()

    var selectionProvider: ((String) -> Boolean)? = null

    fun currentKeys(): List<String> = tiles.map { it.key }

    fun submit(newTiles: List<Tile>) {
        tiles = newTiles
        notifyDataSetChanged()
    }

    fun indexOfKey(key: String): Int = tiles.indexOfFirst { it.key == key }

    fun itemCount(): Int = tiles.size

    fun move(from: Int, to: Int) {
        if (from == to || from !in tiles.indices || to !in tiles.indices) return
        val mutable = tiles.toMutableList()
        mutable.add(to, mutable.removeAt(from))
        tiles = mutable
        notifyItemMoved(from, to)
    }

    fun notifyKeyChanged(key: String) {
        val index = tiles.indexOfFirst { it.key == key }
        if (index >= 0) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tile, parent, false)
        if (fixedItemWidthPx > 0) {
            view.layoutParams = RecyclerView.LayoutParams(
                fixedItemWidthPx,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val holder = Holder(view)
        if (appStyle) {
            val density = view.resources.displayMetrics.density
            val radius = 10f * density
            val padding = (4f * density).toInt()
            holder.card.setBackgroundResource(R.drawable.tile_background_plain)
            holder.card.setPadding(padding, padding, padding, padding)
            holder.icon.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radius)
                }
            }
            holder.icon.clipToOutline = true
        }
        holder.label.visibility = if (showLabel) View.VISIBLE else View.GONE
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val tile = tiles[position]
        holder.label.text = tile.label
        holder.icon.setImageDrawable(tile.icon)

        if (appStyle || iconHeightPx > 0) {
            val density = holder.itemView.resources.displayMetrics.density
            val lp = holder.icon.layoutParams as LinearLayout.LayoutParams
            lp.height = if (iconHeightPx > 0) iconHeightPx else (90f * density).toInt()
            if (appStyle && !tile.isBanner) {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                holder.icon.adjustViewBounds = true
            } else {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                holder.icon.adjustViewBounds = false
            }
            holder.icon.layoutParams = lp
        }
        holder.icon.scaleType =
            if (tile.isBanner) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        holder.check.visibility =
            if (selectionProvider?.invoke(tile.key) == true) View.VISIBLE else View.GONE

        holder.card.scaleX = 1f
        holder.card.scaleY = 1f
        holder.card.setOnClickListener { tile.onClick() }
        holder.card.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.08f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        }
    }

    override fun getItemCount(): Int = tiles.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val card: View = view.findViewById(R.id.tile_card)
        val icon: ImageView = view.findViewById(R.id.tile_icon)
        val label: TextView = view.findViewById(R.id.tile_label)
        val check: View = view.findViewById(R.id.tile_check)
    }
}
