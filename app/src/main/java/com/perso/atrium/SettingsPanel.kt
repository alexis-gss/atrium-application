package com.perso.atrium

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class SettingsPanel(
    private val activity: Activity,
    private val root: View,
    private val content: ViewGroup,
    private val onClosed: (changed: Boolean) -> Unit,
    private val onWallpaperChanged: () -> Unit
) {
    private enum class Page { MAIN, FAVORITES, ORDER, WALLPAPER, ABOUT }

    private val scrim: View = root.findViewById(R.id.panel_scrim)
    private val panel: View = root.findViewById(R.id.panel)
    private val dateView: TextView = root.findViewById(R.id.panel_date)
    private val breadcrumb: TextView = root.findViewById(R.id.panel_breadcrumb)
    private val list: RecyclerView = root.findViewById(R.id.panel_list)
    private val adapter = PanelAdapter()

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val density = activity.resources.displayMetrics.density

    private val stack = ArrayList<Page>()
    private var entries: List<AppEntry> = emptyList()
    private var entriesLoaded = false
    private val selected = HashSet<String>()
    private var movingId: String? = null
    private var dirty = false
    private var closing = false
    private var statusOverride: String? = null
    private var mainFocusId: String? = null

    val isOpen: Boolean get() = root.visibility == View.VISIBLE && !closing

    init {
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        list.itemAnimator = null
        scrim.setOnClickListener { close() }
        root.findViewById<View>(R.id.panel_close).setOnClickListener { close() }

        val radius = activity.resources.getDimension(R.dimen.panel_radius)
        panel.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        panel.clipToOutline = true
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    fun open() {
        if (isOpen) return
        closing = false
        selected.clear()
        selected.addAll(Favorites.list(activity))
        dirty = false
        entriesLoaded = false
        movingId = null
        dateView.text = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(Date())

        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        content.clearFocus()

        val offset = panel.resources.getDimension(R.dimen.panel_width) +
            panel.resources.getDimension(R.dimen.panel_margin) + 8f * density
        root.visibility = View.VISIBLE
        scrim.animate().cancel()
        panel.animate().cancel()
        scrim.alpha = 0f
        panel.translationX = offset
        scrim.animate().alpha(1f).setDuration(200L).start()
        panel.animate().translationX(0f).setDuration(240L)
            .setInterpolator(DecelerateInterpolator()).start()

        stack.clear()
        stack.add(Page.MAIN)
        mainFocusId = null
        render(Page.MAIN, null)
        loadEntries()
    }

    fun close(animated: Boolean = true) {
        if (root.visibility != View.VISIBLE || closing) return
        movingId = null
        val changed = dirty
        val finish = {
            root.visibility = View.GONE
            closing = false
            scrim.alpha = 1f
            panel.translationX = 0f
            content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            onClosed(changed)
        }
        if (!animated) {
            scrim.animate().cancel()
            panel.animate().cancel()
            finish()
            return
        }
        closing = true
        val offset = panel.resources.getDimension(R.dimen.panel_width) +
            panel.resources.getDimension(R.dimen.panel_margin) + 8f * density
        scrim.animate().alpha(0f).setDuration(180L).start()
        panel.animate().translationX(offset).setDuration(200L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { finish() }
            .start()
    }

    fun handleKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN

        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (down && event.repeatCount == 0) goBack()
            return true
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            if (down && event.repeatCount == 0) close()
            return true
        }

        if (movingId != null) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (down) moveBy(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (down) moveBy(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (down && event.repeatCount == 0) stopMove()
                    return true
                }
            }
        }
        return false
    }

    private fun goBack() {
        if (movingId != null) {
            stopMove()
            return
        }
        if (stack.size > 1) {
            val leaving = stack.removeAt(stack.size - 1)
            val target = stack.last()
            render(target, if (target == Page.MAIN) mainFocusId else null)
            if (leaving == Page.WALLPAPER) statusOverride = null
        } else {
            close()
        }
    }

    private fun navigate(page: Page, fromId: String) {
        if (stack.last() == Page.MAIN) mainFocusId = fromId
        stack.add(page)
        render(page, null)
    }

    private fun render(page: Page, focusId: String?) {
        movingId = null
        val rows = when (page) {
            Page.MAIN -> mainRows()
            Page.FAVORITES -> favoritesRows()
            Page.ORDER -> orderRows()
            Page.WALLPAPER -> wallpaperRows()
            Page.ABOUT -> aboutRows()
        }
        adapter.submit(rows)
        breadcrumb.text = stack.joinToString("  ›  ") { pageTitle(it) }
        focusRow(focusId)
    }

    private fun pageTitle(page: Page): String = activity.getString(
        when (page) {
            Page.MAIN -> R.string.panel_settings_section
            Page.FAVORITES -> R.string.menu_favorites
            Page.ORDER -> R.string.panel_order
            Page.WALLPAPER -> R.string.menu_wallpaper
            Page.ABOUT -> R.string.menu_about
        }
    )

    private fun renderCurrent() {
        val page = stack.lastOrNull() ?: return
        if (page == Page.FAVORITES || page == Page.ORDER) render(page, null)
    }

    private fun focusRow(id: String?, attempt: Int = 0) {
        list.post {
            var index = adapter.indexOfId(id)
            if (index < 0 || adapter.rowAt(index)?.onClick == null) {
                index = adapter.firstFocusableIndex(skipId = "back")
            }
            if (index < 0) return@post
            list.scrollToPosition(index)
            list.post {
                val view = list.findViewHolderForAdapterPosition(index)?.itemView
                if (view != null) {
                    view.requestFocus()
                } else if (attempt < 5) {
                    handler.postDelayed({ focusRow(id, attempt + 1) }, 40L)
                }
            }
        }
    }

    private fun info(id: String, text: String) = PanelRow(id, PanelRow.TYPE_INFO, text)

    private fun backRow() = PanelRow(
        id = "back",
        type = PanelRow.TYPE_ITEM,
        title = activity.getString(R.string.settings_back),
        iconRes = R.drawable.ic_menu_back,
        onClick = { goBack() }
    )

    private fun mainRows(): List<PanelRow> = listOf(
        PanelRow(
            "favorites", PanelRow.TYPE_ITEM, activity.getString(R.string.menu_favorites),
            subtitle = activity.getString(R.string.panel_favorites_count, Favorites.list(activity).size),
            iconRes = R.drawable.ic_menu_favorites,
            trailing = PanelRow.TRAILING_CHEVRON,
            onClick = { navigate(Page.FAVORITES, "favorites") }
        ),
        PanelRow(
            "order", PanelRow.TYPE_ITEM, activity.getString(R.string.panel_order),
            subtitle = activity.getString(R.string.panel_order_subtitle),
            iconRes = R.drawable.ic_swap_vert,
            trailing = PanelRow.TRAILING_CHEVRON,
            onClick = { navigate(Page.ORDER, "order") }
        ),
        PanelRow(
            "wallpaper", PanelRow.TYPE_ITEM, activity.getString(R.string.menu_wallpaper),
            subtitle = activity.getString(R.string.panel_background),
            iconRes = R.drawable.ic_menu_wallpaper,
            trailing = PanelRow.TRAILING_CHEVRON,
            onClick = { navigate(Page.WALLPAPER, "wallpaper") }
        ),
        PanelRow(
            "about", PanelRow.TYPE_ITEM, activity.getString(R.string.menu_about),
            subtitle = activity.getString(R.string.panel_about),
            iconRes = R.drawable.ic_menu_about,
            trailing = PanelRow.TRAILING_CHEVRON,
            onClick = { navigate(Page.ABOUT, "about") }
        )
    )

    private fun loadEntries() {
        if (entriesLoaded) return
        executor.execute {
            val loaded = AppLoader.load(activity)
            handler.post {
                if (activity.isDestroyed || activity.isFinishing) return@post
                entries = loaded
                entriesLoaded = true
                if (isOpen) renderCurrent()
            }
        }
    }

    private fun copyOf(d: Drawable?): Drawable? = d?.constantState?.newDrawable()?.mutate() ?: d

    private fun favoritesRows(): List<PanelRow> {
        val rows = ArrayList<PanelRow>()
        rows.add(backRow())
        if (!entriesLoaded) {
            rows.add(info("loading", activity.getString(R.string.panel_loading)))
            return rows
        }
        rows.add(info("hint", activity.getString(R.string.favorites_hint)))
        for (e in entries) {
            rows.add(
                PanelRow(
                    id = "app:" + e.pkg,
                    type = PanelRow.TYPE_ITEM,
                    title = e.label,
                    icon = copyOf(e.icon),
                    iconCrop = e.isBanner,
                    trailing = PanelRow.TRAILING_CHECK,
                    checked = e.pkg in selected,
                    onClick = { toggleFavorite(e.pkg) }
                )
            )
        }
        return rows
    }

    private fun toggleFavorite(pkg: String) {
        val added = Favorites.toggle(activity, pkg)
        if (added) selected.add(pkg) else selected.remove(pkg)
        dirty = true
        val id = "app:$pkg"
        val index = adapter.indexOfId(id)
        adapter.rowAt(index)?.checked = added
        adapter.notifyRow(id)
    }

    private fun orderRows(): List<PanelRow> {
        val rows = ArrayList<PanelRow>()
        rows.add(backRow())
        if (!entriesLoaded) {
            rows.add(info("loading", activity.getString(R.string.panel_loading)))
            return rows
        }
        val byPkg = entries.associateBy { it.pkg }
        val favorites = Favorites.list(activity).mapNotNull { byPkg[it] }
        if (favorites.isEmpty()) {
            rows.add(info("hint", activity.getString(R.string.order_empty)))
            return rows
        }
        rows.add(info("hint", activity.getString(R.string.order_hint)))
        for (e in favorites) {
            val id = "app:" + e.pkg
            rows.add(
                PanelRow(
                    id = id,
                    type = PanelRow.TYPE_ITEM,
                    title = e.label,
                    icon = copyOf(e.icon),
                    iconCrop = e.isBanner,
                    trailing = PanelRow.TRAILING_MOVE,
                    onClick = { toggleMove(id) }
                )
            )
        }
        return rows
    }

    private fun toggleMove(id: String) {
        if (movingId == id) stopMove() else startMove(id)
    }

    private fun setHint(text: String) {
        adapter.rowAt(adapter.indexOfId("hint"))?.title = text
        adapter.notifyRow("hint")
    }

    private fun startMove(id: String) {
        movingId = id
        adapter.rowAt(adapter.indexOfId(id))?.selected = true
        adapter.notifyRow(id)
        setHint(activity.getString(R.string.order_hint_moving))
    }

    private fun stopMove() {
        val id = movingId ?: return
        movingId = null
        adapter.rowAt(adapter.indexOfId(id))?.selected = false
        adapter.notifyRow(id)
        setHint(activity.getString(R.string.order_hint))
    }

    private fun moveBy(delta: Int) {
        val id = movingId ?: return
        val from = adapter.indexOfId(id)
        val to = from + delta
        val target = adapter.rowAt(to) ?: return
        if (from < 0 || !target.id.startsWith("app:")) return
        adapter.move(from, to)
        saveOrder()
        list.postDelayed({
            list.scrollToPosition(to)
            list.findViewHolderForAdapterPosition(to)?.itemView?.requestFocus()
        }, 50L)
    }

    private fun saveOrder() {
        val visible = adapter.ids().filter { it.startsWith("app:") }.map { it.removePrefix("app:") }
        val hidden = Favorites.list(activity).filter { it !in visible }
        Favorites.setOrder(activity, visible + hidden)
        dirty = true
    }

    private fun wallpaperStatus(): String = statusOverride ?: when (Wallpaper.mode(activity)) {
        Wallpaper.MODE_COLOR -> activity.getString(
            R.string.wallpaper_status_color,
            String.format("#%06X", Wallpaper.color(activity) and 0xFFFFFF)
        )
        Wallpaper.MODE_URL -> activity.getString(R.string.wallpaper_status_url, Wallpaper.url(activity))
        Wallpaper.MODE_LOCAL -> activity.getString(R.string.wallpaper_status_local)
        Wallpaper.MODE_SLIDESHOW -> activity.getString(
            R.string.wallpaper_status_slideshow,
            File(Wallpaper.folder(activity)).name
        )
        else -> activity.getString(R.string.wallpaper_status_default)
    }

    private fun wallpaperRows(): List<PanelRow> {
        fun item(id: String, title: Int, action: () -> Unit) =
            PanelRow(id, PanelRow.TYPE_ITEM, activity.getString(title), onClick = action)
        return listOf(
            backRow(),
            info("wp_status", wallpaperStatus()),
            item("wp_default", R.string.wallpaper_default) {
                Wallpaper.setDefault(activity)
                wallpaperChanged()
            },
            item("wp_color", R.string.wallpaper_color) { showColorDialog() },
            item("wp_url", R.string.wallpaper_url) { showUrlDialog() },
            item("wp_local_folder", R.string.wallpaper_local_folder) { showFolderDialog() },
            item("wp_slideshow", R.string.wallpaper_slideshow) { showSlideshowDialog() }
        )
    }

    private fun wallpaperChanged() {
        statusOverride = null
        refreshWallpaperStatus()
        onWallpaperChanged()
    }

    private fun refreshWallpaperStatus() {
        val row = adapter.rowAt(adapter.indexOfId("wp_status")) ?: return
        row.title = wallpaperStatus()
        adapter.notifyRow("wp_status")
    }

    private fun showColorDialog() {
        val names = activity.resources.getStringArray(R.array.wallpaper_color_names).toMutableList()
        names.add(activity.getString(R.string.wallpaper_color_hex))
        AlertDialog.Builder(activity)
            .setTitle(R.string.wallpaper_color_title)
            .setItems(names.toTypedArray()) { _, which ->
                if (which < PRESET_COLORS.size) {
                    Wallpaper.setColor(activity, PRESET_COLORS[which])
                    wallpaperChanged()
                } else {
                    showHexDialog()
                }
            }
            .show()
    }

    private fun showHexDialog() {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setText("#")
            setSelection(text.length)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.wallpaper_hex_title)
            .setView(input)
            .setPositiveButton(R.string.wallpaper_ok) { _, _ ->
                var text = input.text.toString().trim()
                if (!text.startsWith("#")) text = "#$text"
                try {
                    Wallpaper.setColor(activity, Color.parseColor(text))
                    wallpaperChanged()
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(activity, R.string.wallpaper_err_color, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.wallpaper_cancel, null)
            .show()
    }

    private fun showUrlDialog() {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setText(Wallpaper.url(activity).ifBlank { "https://" })
            setSelection(text.length)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.wallpaper_url_title)
            .setView(input)
            .setPositiveButton(R.string.wallpaper_ok) { _, _ -> applyUrl(input.text.toString().trim()) }
            .setNegativeButton(R.string.wallpaper_cancel, null)
            .show()
    }

    private fun applyUrl(url: String) {
        statusOverride = activity.getString(R.string.wallpaper_checking)
        refreshWallpaperStatus()
        executor.execute {
            val error = Wallpaper.downloadUrl(activity, url)
            if (error == null) Wallpaper.setUrl(activity, url)
            handler.post {
                if (activity.isDestroyed || activity.isFinishing) return@post
                Toast.makeText(activity, error ?: R.string.wallpaper_applied, Toast.LENGTH_LONG).show()
                wallpaperChanged()
            }
        }
    }

    private fun appImagesDir(): File? = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES)

    private fun showFolderDialog() {
        val dir = appImagesDir()
        val images = Wallpaper.imagesIn(dir)
        if (images.isEmpty()) {
            Toast.makeText(
                activity,
                activity.getString(R.string.wallpaper_folder_empty, dir?.absolutePath ?: "?"),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.wallpaper_folder_title)
            .setItems(images.map { it.name }.toTypedArray()) { _, which ->
                val file = images[which]
                importLocal { FileInputStream(file) }
            }
            .show()
    }

    private fun showSlideshowDialog() {
        val root = appImagesDir()
        val candidates = ArrayList<File>()
        if (root != null) {
            candidates.add(root)
            root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                ?.let { candidates.addAll(it) }
        }
        val folders = candidates
            .map { it to Wallpaper.imagesIn(it).size }
            .filter { it.second > 0 }
        if (folders.isEmpty()) {
            Toast.makeText(
                activity,
                activity.getString(R.string.wallpaper_slideshow_empty, root?.absolutePath ?: "?"),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val labels = folders.map { (dir, count) ->
            val name = if (dir == root) activity.getString(R.string.wallpaper_slideshow_root) else dir.name
            activity.getString(R.string.wallpaper_slideshow_item, name, count)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.wallpaper_slideshow_title)
            .setItems(labels.toTypedArray()) { _, which ->
                Wallpaper.setSlideshow(activity, folders[which].first.absolutePath)
                Toast.makeText(activity, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show()
                wallpaperChanged()
            }
            .show()
    }

    private fun importLocal(open: () -> java.io.InputStream?) {
        statusOverride = activity.getString(R.string.wallpaper_checking)
        refreshWallpaperStatus()
        executor.execute {
            val error = try {
                val stream = open()
                if (stream == null) R.string.wallpaper_err_read else Wallpaper.importLocal(activity, stream)
            } catch (e: Exception) {
                R.string.wallpaper_err_read
            }
            if (error == null) Wallpaper.setLocal(activity)
            handler.post {
                if (activity.isDestroyed || activity.isFinishing) return@post
                Toast.makeText(activity, error ?: R.string.wallpaper_applied, Toast.LENGTH_LONG).show()
                wallpaperChanged()
            }
        }
    }

    private fun aboutRows(): List<PanelRow> = listOf(
        backRow(),
        PanelRow("a_name", PanelRow.TYPE_ITEM, activity.getString(R.string.about_name_label),
            subtitle = activity.getString(R.string.app_name)),
        PanelRow("a_author", PanelRow.TYPE_ITEM, activity.getString(R.string.about_author_label),
            subtitle = activity.getString(R.string.about_author_value)),
        PanelRow("a_version", PanelRow.TYPE_ITEM, activity.getString(R.string.about_version_label),
            subtitle = versionName()),
        PanelRow("a_build", PanelRow.TYPE_ITEM, activity.getString(R.string.about_build_date_label),
            subtitle = BuildConfig.BUILD_DATE)
    )

    @Suppress("DEPRECATION")
    private fun versionName(): String {
        return try {
            val pm = activity.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(activity.packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                pm.getPackageInfo(activity.packageName, 0)
            }
            info.versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }

    companion object {
        private val PRESET_COLORS = intArrayOf(
            0xFF0B1220.toInt(), 0xFF000000.toInt(), 0xFF263238.toInt(), 0xFF0D47A1.toInt(),
            0xFF4A148C.toInt(), 0xFF1B5E20.toInt(), 0xFF7F1D1D.toInt()
        )
    }
}
