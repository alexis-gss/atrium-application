package com.perso.atrium

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var appsSection: View
    private lateinit var inputsSection: View
    private lateinit var appsEmpty: TextView
    private lateinit var appsGrid: RecyclerView
    private lateinit var inputsTitle: TextView
    private lateinit var settingsButton: View
    private lateinit var systemSettingsButton: View
    private lateinit var panel: SettingsPanel
    private var focusBeforePanel: View? = null
    private lateinit var appsHint: View
    private lateinit var inputsHint: View
    private lateinit var inputsList: RecyclerView
    private lateinit var wifiIcon: ImageView
    private lateinit var appsAdapter: TileAdapter
    private lateinit var inputsAdapter: TileAdapter

    private val executor = Executors.newSingleThreadExecutor()
    private val wallpaper = WallpaperBinder(this)
    private var rowAnimating = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var connectivityManager: ConnectivityManager? = null
    private var initialFocusDone = false
    private var showingInputs = false

    private val updateWifiRunnable = Runnable { updateWifiIcon() }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleWifiUpdate()
        override fun onLost(network: Network) = scheduleWifiUpdate()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
            scheduleWifiUpdate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        appsSection = findViewById(R.id.apps_section)
        inputsSection = findViewById(R.id.inputs_section)
        appsEmpty = findViewById(R.id.apps_empty)
        appsGrid = findViewById(R.id.apps_grid)
        inputsTitle = findViewById(R.id.inputs_title)
        appsHint = findViewById(R.id.apps_hint)
        inputsHint = findViewById(R.id.inputs_hint)
        inputsList = findViewById(R.id.inputs_list)
        wifiIcon = findViewById(R.id.wifi_icon)

        settingsButton = findViewById(R.id.settings_button)
        systemSettingsButton = findViewById(R.id.system_settings_button)
        settingsButton.setOnClickListener { openPanel() }
        systemSettingsButton.setOnClickListener { SystemSettings.open(this) }

        panel = SettingsPanel(
            activity = this,
            root = findViewById(R.id.panel_root),
            content = findViewById(R.id.home_content),
            onClosed = { changed -> onPanelClosed(changed) },
            onWallpaperChanged = { wallpaper.apply() }
        )

        val density = resources.displayMetrics.density
        val tileWidthPx = (176f * density).toInt()

        appsAdapter = TileAdapter(fixedItemWidthPx = tileWidthPx, appStyle = true)
        val extraPx = resources.displayMetrics.widthPixels * 4
        appsGrid.layoutManager =
            PreloadLayoutManager(this, LinearLayoutManager.HORIZONTAL, false, extraPx)
        appsGrid.setItemViewCacheSize(100)
        appsGrid.adapter = appsAdapter

        inputsAdapter = TileAdapter(fixedItemWidthPx = tileWidthPx, appStyle = true, showLabel = true)
        inputsList.layoutManager =
            PreloadLayoutManager(this, LinearLayoutManager.HORIZONTAL, false, extraPx)
        inputsList.setItemViewCacheSize(100)
        inputsList.adapter = inputsAdapter
    }

    override fun onStart() {
        super.onStart()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        connectivityManager = cm
        try {
            cm?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback
            )
        } catch (e: Exception) {
            // Keep empty.
        }
        updateWifiIcon()
    }

    override fun onStop() {
        mainHandler.removeCallbacks(updateWifiRunnable)
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Keep empty.
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        forceRedraw()
        refresh()
        wallpaper.apply()
    }

    override fun onPause() {
        foreground = false
        wallpaper.pause()
        resetRowAnimation()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resetRowAnimation()
        panel.close(animated = false)
        showingInputs = false
        updateRows()
        focusFirstVisible()
        forceRedraw()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) forceRedraw()
    }

    private fun forceRedraw() {
        wallpaper.reapply()
        window.decorView.invalidate()
        window.decorView.requestLayout()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (panel.isOpen) {
            return panel.handleKey(event) || super.dispatchKeyEvent(event)
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) openPanel()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focus = currentFocus
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !showingInputs &&
                inputsAdapter.itemCount() > 0 && isInside(focus, appsGrid)
            ) {
                switchRow(toInputs = true)
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && showingInputs &&
                appsAdapter.itemCount() > 0 && isInside(focus, inputsList)
            ) {
                switchRow(toInputs = false)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun openPanel() {
        if (panel.isOpen) return
        focusBeforePanel = currentFocus ?: settingsButton
        panel.open()
    }

    private fun onPanelClosed(changed: Boolean) {
        if (changed) {
            refresh(refocus = true)
        } else {
            val target = focusBeforePanel?.takeIf { it.isAttachedToWindow && it.isShown } ?: settingsButton
            target.requestFocus()
        }
        focusBeforePanel = null
    }

    private fun setTopButtonsFocusable(focusable: Boolean) {
        settingsButton.isFocusable = focusable
        systemSettingsButton.isFocusable = focusable
    }

    private fun isInside(view: View?, container: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === container) return true
            v = v.parent as? View
        }
        return false
    }

    private fun switchRow(toInputs: Boolean) {
        if (rowAnimating || toInputs == showingInputs) return

        window.decorView.playSoundEffect(
            if (toInputs) SoundEffectConstants.NAVIGATION_DOWN
            else SoundEffectConstants.NAVIGATION_UP
        )

        val outgoing = if (toInputs) appsSection else inputsSection
        val incoming = if (toInputs) inputsSection else appsSection
        val offset = 32f * resources.displayMetrics.density
        val dir = if (toInputs) -1f else 1f
        rowAnimating = true
        outgoing.animate()
            .alpha(0f)
            .translationY(dir * offset)
            .setDuration(140L)
            .withEndAction {
                setTopButtonsFocusable(false)
                showingInputs = toInputs
                updateRows()
                outgoing.alpha = 1f
                outgoing.translationY = 0f
                incoming.alpha = 0f
                incoming.translationY = -dir * offset
                focusFirstVisible()
                incoming.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180L)
                    .withEndAction { rowAnimating = false }
                    .start()
            }
            .start()
    }

    private fun resetRowAnimation() {
        for (section in listOf(appsSection, inputsSection)) {
            section.animate().cancel()
            section.alpha = 1f
            section.translationY = 0f
        }
        rowAnimating = false
        setTopButtonsFocusable(true)
    }

    private fun updateRows() {
        val hasApps = appsAdapter.itemCount() > 0
        val hasInputs = inputsAdapter.itemCount() > 0
        if (!hasInputs) showingInputs = false else if (!hasApps) showingInputs = true
        appsSection.visibility = if (showingInputs) View.GONE else View.VISIBLE
        inputsSection.visibility = if (showingInputs) View.VISIBLE else View.GONE
        appsGrid.visibility = if (hasApps) View.VISIBLE else View.GONE
        appsEmpty.visibility = if (!hasApps && !hasInputs) View.VISIBLE else View.GONE
        appsHint.visibility = if (hasInputs) View.VISIBLE else View.INVISIBLE
        inputsHint.visibility = if (hasApps) View.VISIBLE else View.INVISIBLE
    }

    private fun focusFirstVisible() {
        val list = if (showingInputs) inputsList else appsGrid
        if (list.visibility != View.VISIBLE) {
            setTopButtonsFocusable(true)
            return
        }
        list.post {
            val first = list.getChildAt(0)
            if (first != null) {
                first.requestFocus()
                setTopButtonsFocusable(true)
            } else {
                list.viewTreeObserver.addOnGlobalLayoutListener(
                    object : ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            val target = list.getChildAt(0) ?: return
                            if (list.viewTreeObserver.isAlive) {
                                list.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            }
                            target.requestFocus()
                            setTopButtonsFocusable(true)
                        }
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        panel.shutdown()
        wallpaper.shutdown()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun scheduleWifiUpdate() {
        mainHandler.post(updateWifiRunnable)
        mainHandler.postDelayed(updateWifiRunnable, 1500L)
    }

    private fun updateWifiIcon() {
        wifiIcon.visibility = if (isOnWifi()) View.VISIBLE else View.GONE
    }

    private fun isOnWifi(): Boolean {
        val cm = connectivityManager ?: return false
        return try {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            false
        }
    }

    private fun refresh(refocus: Boolean = false) {
        executor.execute {
            val inputs = loadInputs()
            val apps = loadFavoriteApps()
            mainHandler.post {
                if (isDestroyed || isFinishing) return@post
                showInputs(inputs)
                showApps(apps)
                updateRows()
                if (!initialFocusDone || refocus) {
                    initialFocusDone = true
                    focusFirstVisible()
                }
            }
        }
    }

    private fun showApps(apps: List<Tile>) {
        if (apps.map { it.key } != appsAdapter.currentKeys()) {
            appsAdapter.submit(apps)
        }
    }

    private fun showInputs(inputs: List<Tile>) {
        if (inputs.map { it.key } != inputsAdapter.currentKeys()) {
            inputsAdapter.submit(inputs)
        }
    }

    private fun loadInputs(): List<Tile> {
        val manager = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            ?: return emptyList()
        val infos = try {
            manager.tvInputList
        } catch (e: Exception) {
            return emptyList()
        }

        return infos
            .filter { it.isPassthroughInput }
            .map { info ->
                val label = runCatching { info.loadCustomLabel(this)?.toString() }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: runCatching { info.loadLabel(this)?.toString() }.getOrNull()
                        ?.takeIf { it.isNotBlank() }
                    ?: info.id
                val raw = runCatching { info.loadIcon(this) }.getOrNull()
                val cropped = raw?.let { InputIcons.cropToCard(this, it) }
                val icon = cropped ?: raw ?: getDrawable(R.drawable.ic_input)
                val inputId = info.id
                Tile(
                    key = inputId,
                    label = label,
                    icon = icon,
                    isBanner = cropped != null,
                    onClick = { openInput(inputId) }
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun openInput(inputId: String) {
        val intent = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_launch_input, Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadFavoriteApps(): List<Tile> {
        val installed = AppLoader.load(this).associateBy { it.pkg }
        return Favorites.list(this).mapNotNull { installed[it] }.map { entry ->
            Tile(
                key = entry.pkg,
                label = entry.label,
                icon = entry.icon,
                isBanner = entry.isBanner,
                onClick = { launchApp(entry.component, entry.category) }
            )
        }
    }

    private fun launchApp(component: ComponentName, category: String) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(category)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_launch_app, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        @Volatile
        var foreground = false
    }
}
