package com.perso.atrium

import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PanelRow(
    val id: String,
    val type: Int,
    var title: String,
    var subtitle: String? = null,
    val iconRes: Int = 0,
    val icon: Drawable? = null,
    val iconCrop: Boolean = false,
    val trailing: Int = TRAILING_NONE,
    var checked: Boolean = false,
    var selected: Boolean = false,
    val onClick: (() -> Unit)? = null
) {
    companion object {
        const val TYPE_SECTION = 0
        const val TYPE_INFO = 1
        const val TYPE_ITEM = 2

        const val TRAILING_NONE = 0
        const val TRAILING_CHECK = 1
        const val TRAILING_CHEVRON = 2
        const val TRAILING_MOVE = 3
    }
}

class PanelAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: MutableList<PanelRow> = mutableListOf()

    fun submit(list: List<PanelRow>) {
        rows = list.toMutableList()
        notifyDataSetChanged()
    }

    fun rowAt(index: Int): PanelRow? = rows.getOrNull(index)

    fun indexOfId(id: String?): Int = if (id == null) -1 else rows.indexOfFirst { it.id == id }

    fun notifyRow(id: String) {
        val index = indexOfId(id)
        if (index >= 0) notifyItemChanged(index)
    }

    fun move(from: Int, to: Int) {
        if (from == to || from !in rows.indices || to !in rows.indices) return
        rows.add(to, rows.removeAt(from))
        notifyItemMoved(from, to)
    }

    fun ids(): List<String> = rows.map { it.id }

    fun firstFocusableIndex(skipId: String? = null): Int {
        val preferred = rows.indexOfFirst { it.onClick != null && it.id != skipId }
        if (preferred >= 0) return preferred
        return rows.indexOfFirst { it.onClick != null }
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = rows[position].type

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val density = parent.resources.displayMetrics.density
        return if (viewType == PanelRow.TYPE_ITEM) {
            val holder = ItemHolder(inflater.inflate(R.layout.item_panel_row, parent, false))
            val radius = 5f * density
            holder.icon.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radius)
                }
            }
            holder
        } else {
            val view = inflater.inflate(R.layout.item_panel_text, parent, false) as TextView
            val h = (20f * density).toInt()
            if (viewType == PanelRow.TYPE_SECTION) {
                view.setBackgroundColor(0x14FFFFFF)
                view.setTextColor(0xFF90CAF9.toInt())
                view.textSize = 12f
                view.setPadding(h, (8f * density).toInt(), h, (8f * density).toInt())
            } else {
                view.setTextColor(0xFFB0BEC5.toInt())
                view.textSize = 12f
                view.setPadding(h, (8f * density).toInt(), h, (8f * density).toInt())
            }
            TextHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        if (holder is TextHolder) {
            holder.text.text = row.title
            return
        }
        holder as ItemHolder
        val density = holder.itemView.resources.displayMetrics.density

        holder.title.text = row.title
        holder.subtitle.text = row.subtitle
        holder.subtitle.visibility = if (row.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE

        val lp = holder.icon.layoutParams
        when {
            row.icon != null -> {
                lp.width = (56f * density).toInt()
                lp.height = (32f * density).toInt()
                holder.icon.setImageDrawable(row.icon)
                holder.icon.scaleType =
                    if (row.iconCrop) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                holder.icon.clipToOutline = true
                holder.icon.visibility = View.VISIBLE
            }
            row.iconRes != 0 -> {
                lp.width = (24f * density).toInt()
                lp.height = (24f * density).toInt()
                holder.icon.setImageResource(row.iconRes)
                holder.icon.scaleType = ImageView.ScaleType.FIT_CENTER
                holder.icon.clipToOutline = false
                holder.icon.visibility = View.VISIBLE
            }
            else -> {
                holder.icon.setImageDrawable(null)
                holder.icon.visibility = View.GONE
            }
        }
        holder.icon.layoutParams = lp

        val trailing = holder.trailing
        trailing.rotation = 0f
        trailing.alpha = 1f
        trailing.background = null
        trailing.setPadding(0, 0, 0, 0)
        when (row.trailing) {
            PanelRow.TRAILING_CHECK -> {
                val pad = (3f * density).toInt()
                trailing.setPadding(pad, pad, pad, pad)
                if (row.checked) {
                    trailing.setBackgroundResource(R.drawable.check_badge)
                    trailing.setImageResource(R.drawable.ic_check)
                } else {
                    trailing.setBackgroundResource(R.drawable.check_ring)
                    trailing.setImageDrawable(null)
                }
                trailing.visibility = View.VISIBLE
            }
            PanelRow.TRAILING_CHEVRON -> {
                trailing.setImageResource(R.drawable.ic_chevron_down)
                trailing.rotation = -90f
                trailing.visibility = View.VISIBLE
            }
            PanelRow.TRAILING_MOVE -> {
                trailing.setImageResource(R.drawable.ic_swap_vert)
                trailing.alpha = if (row.selected) 1f else 0.5f
                trailing.visibility = View.VISIBLE
            }
            else -> {
                trailing.setImageDrawable(null)
                trailing.visibility = View.GONE
            }
        }

        val clickable = row.onClick != null
        holder.itemView.isSelected = row.selected
        holder.itemView.setOnClickListener(if (clickable) View.OnClickListener { row.onClick?.invoke() } else null)
        holder.itemView.isClickable = clickable
        holder.itemView.isFocusable = clickable
    }

    class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.row_icon)
        val title: TextView = view.findViewById(R.id.row_title)
        val subtitle: TextView = view.findViewById(R.id.row_subtitle)
        val trailing: ImageView = view.findViewById(R.id.row_trailing)
    }

    class TextHolder(view: TextView) : RecyclerView.ViewHolder(view) {
        val text: TextView = view
    }
}
