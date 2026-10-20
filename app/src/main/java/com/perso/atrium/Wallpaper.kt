package com.perso.atrium

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.max

object Wallpaper {
    const val MODE_DEFAULT = "default"
    const val MODE_COLOR = "color"
    const val MODE_URL = "url"
    const val MODE_LOCAL = "local"
    const val MODE_SLIDESHOW = "slideshow"

    const val SLIDE_INTERVAL_MS = 60_000L
    const val SLIDE_FADE_MS = 300L

    val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

    private const val PREFS = "atrium"
    private const val MAX_BYTES = 20L * 1024 * 1024
    private const val REFRESH_MS = 12L * 60 * 60 * 1000
    private const val RETRY_MS = 5L * 60 * 1000

    @Volatile
    private var lastAttempt = 0L

    @Volatile
    private var slideIndex = 0

    class Slide(val bitmap: Bitmap, val count: Int)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(c: Context): String = prefs(c).getString("wp_mode", MODE_DEFAULT) ?: MODE_DEFAULT
    fun color(c: Context): Int = prefs(c).getInt("wp_color", 0xFF0B1220.toInt())
    fun url(c: Context): String = prefs(c).getString("wp_url", "") ?: ""
    fun folder(c: Context): String = prefs(c).getString("wp_folder", "") ?: ""

    private fun urlFile(c: Context) = File(c.filesDir, "wallpaper_url.img")
    private fun localFile(c: Context) = File(c.filesDir, "wallpaper_local.img")

    fun setDefault(c: Context) {
        prefs(c).edit().putString("wp_mode", MODE_DEFAULT).apply()
    }

    fun setColor(c: Context, color: Int) {
        prefs(c).edit().putString("wp_mode", MODE_COLOR).putInt("wp_color", color or 0xFF000000.toInt()).apply()
    }

    fun setUrl(c: Context, url: String) {
        prefs(c).edit().putString("wp_mode", MODE_URL).putString("wp_url", url).apply()
    }

    fun setLocal(c: Context) {
        prefs(c).edit().putString("wp_mode", MODE_LOCAL).apply()
    }

    fun setSlideshow(c: Context, path: String) {
        slideIndex = 0
        prefs(c).edit().putString("wp_mode", MODE_SLIDESHOW).putString("wp_folder", path).apply()
    }

    fun imagesIn(dir: File?): List<File> =
        dir?.listFiles { f -> f.isFile && f.extension.lowercase() in IMAGE_EXTENSIONS }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    fun nextSlide(c: Context, width: Int, height: Int): Slide? {
        val path = folder(c)
        if (path.isBlank()) return null
        val images = imagesIn(File(path))
        if (images.isEmpty()) return null
        val start = slideIndex
        for (i in images.indices) {
            val idx = (start + i) % images.size
            val bitmap = decodeBitmap(images[idx], width, height) ?: continue
            slideIndex = (idx + 1) % images.size
            return Slide(bitmap, images.size)
        }
        return null
    }

    fun signature(c: Context): String = when (mode(c)) {
        MODE_COLOR -> "c${color(c)}"
        MODE_URL -> "u${url(c)}${urlFile(c).lastModified()}"
        MODE_LOCAL -> "l${localFile(c).lastModified()}"
        MODE_SLIDESHOW -> "s${folder(c)}"
        else -> "d"
    }

    fun isOnline(c: Context): Boolean {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return try {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    fun refreshIfNeeded(c: Context) {
        if (mode(c) != MODE_URL) return
        val url = url(c)
        if (url.isBlank()) return
        val file = urlFile(c)
        val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > REFRESH_MS
        if (!stale) return
        val now = SystemClock.elapsedRealtime()
        if (lastAttempt != 0L && now - lastAttempt < RETRY_MS) return
        if (!isOnline(c)) return
        lastAttempt = now
        downloadUrl(c, url)
    }

    fun load(c: Context, width: Int, height: Int): Drawable? = when (mode(c)) {
        MODE_COLOR -> ColorDrawable(color(c))
        MODE_URL -> decode(urlFile(c), width, height)
        MODE_LOCAL -> decode(localFile(c), width, height)
        MODE_SLIDESHOW -> nextSlide(c, width, height)?.let { SlideshowDrawable(it.bitmap) }
        else -> null
    }

    fun downloadUrl(c: Context, urlString: String): Int? {
        try {
            var current = URL(urlString.trim())
            if (current.protocol != "http" && current.protocol != "https") {
                return R.string.wallpaper_err_url
            }
            for (i in 0..5) {
                val conn = current.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 8000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) Atrium")
                conn.setRequestProperty("Accept", "image/*")
                try {
                    val code = conn.responseCode
                    if (code in listOf(301, 302, 303, 307, 308)) {
                        val location = conn.getHeaderField("Location") ?: return R.string.wallpaper_err_http
                        current = URL(current, location)
                        if (current.protocol != "http" && current.protocol != "https") {
                            return R.string.wallpaper_err_url
                        }
                        continue
                    }
                    if (code != 200) return R.string.wallpaper_err_http
                    val type = conn.contentType?.lowercase()
                    if (type != null && !type.startsWith("image/") && !type.startsWith("application/octet-stream")) {
                        return R.string.wallpaper_err_not_image
                    }
                    if (conn.contentLengthLong > MAX_BYTES) return R.string.wallpaper_err_too_big
                    return saveValidated(conn.inputStream, urlFile(c))
                } finally {
                    conn.disconnect()
                }
            }
            return R.string.wallpaper_err_http
        } catch (e: java.net.MalformedURLException) {
            return R.string.wallpaper_err_url
        } catch (e: IOException) {
            return R.string.wallpaper_err_network
        } catch (e: Exception) {
            return R.string.wallpaper_err_network
        }
    }

    fun importLocal(c: Context, input: InputStream): Int? = try {
        saveValidated(input, localFile(c))
    } catch (e: Exception) {
        R.string.wallpaper_err_read
    }

    private fun saveValidated(input: InputStream, dest: File): Int? {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        try {
            var total = 0L
            input.use { ins ->
                FileOutputStream(tmp).use { out ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = ins.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) {
                            tmp.delete()
                            return R.string.wallpaper_err_too_big
                        }
                        out.write(buffer, 0, n)
                    }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, bounds)
            val isImage = bounds.outWidth > 0 && bounds.outHeight > 0 &&
                bounds.outMimeType?.startsWith("image/") == true
            if (!isImage) {
                tmp.delete()
                return R.string.wallpaper_err_not_image
            }
            dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return R.string.wallpaper_err_read
            }
            return null
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    private fun decode(file: File, width: Int, height: Int): Drawable? =
        decodeBitmap(file, width, height)?.let { WallpaperDrawable(it) }

    private fun decodeBitmap(file: File, width: Int, height: Int): Bitmap? {
        if (!file.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) {
                sample *= 2
            }
            BitmapFactory.decodeFile(
                file.path,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        } catch (e: Throwable) {
            null
        }
    }
}

private fun drawCover(canvas: Canvas, bitmap: Bitmap, b: Rect, paint: Paint, dst: RectF) {
    val scale = max(b.width().toFloat() / bitmap.width, b.height().toFloat() / bitmap.height)
    val w = bitmap.width * scale
    val h = bitmap.height * scale
    val left = b.left + (b.width() - w) / 2f
    val top = b.top + (b.height() - h) / 2f
    dst.set(left, top, left + w, top + h)
    canvas.drawBitmap(bitmap, null, dst, paint)
}

class WallpaperDrawable(private val bitmap: Bitmap) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scrim = Paint().apply { color = 0x66000000 }
    private val dst = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.save()
        canvas.clipRect(b)
        drawCover(canvas, bitmap, b, paint, dst)
        canvas.restore()
        canvas.drawRect(b, scrim)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * Fond du diaporama : affiche une image et permet de passer à la suivante avec un
 * fondu enchaîné. Le voile sombre est dessiné une seule fois par-dessus les deux
 * images pour éviter tout scintillement pendant la transition.
 */
class SlideshowDrawable(initial: Bitmap) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scrim = Paint().apply { color = 0x66000000 }
    private val dst = RectF()

    private var base: Bitmap = initial
    private var incoming: Bitmap? = null
    private var progress = 0f
    private var animator: ValueAnimator? = null

    fun crossfadeTo(next: Bitmap, durationMs: Long) {
        finishFade()
        incoming = next
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidateSelf()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    finishFade()
                }
            })
            start()
        }
    }

    private fun finishFade() {
        animator?.let {
            it.removeAllListeners()
            it.cancel()
        }
        animator = null
        incoming?.let { base = it }
        incoming = null
        progress = 0f
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.save()
        canvas.clipRect(b)
        paint.alpha = 255
        drawCover(canvas, base, b, paint, dst)
        incoming?.let {
            paint.alpha = (progress * 255f).toInt().coerceIn(0, 255)
            drawCover(canvas, it, b, paint, dst)
        }
        canvas.restore()
        canvas.drawRect(b, scrim)
    }

    override fun setAlpha(alpha: Int) {
        // Le fond est toujours opaque.
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

class WallpaperBinder(private val activity: Activity) {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var appliedSig: String? = null
    private var current: Drawable? = null
    private var paused = false
    private var tickScheduled = false

    private val tick = Runnable { onTick() }

    fun apply() {
        paused = false
        syncTicker()
        val sig0 = Wallpaper.signature(activity)
        if (sharedSig == sig0 && appliedSig != sig0) {
            appliedSig = sig0
            show(sharedDrawable)
        }
        val metrics = activity.resources.displayMetrics
        try {
            executor.execute {
                Wallpaper.refreshIfNeeded(activity)
                val sig = Wallpaper.signature(activity)
                if (sig == appliedSig) return@execute
                val drawable = Wallpaper.load(activity, metrics.widthPixels, metrics.heightPixels)
                handler.post {
                    if (activity.isDestroyed || activity.isFinishing) return@post
                    sharedSig = sig
                    sharedDrawable = drawable
                    appliedSig = sig
                    show(drawable)
                }
            }
        } catch (e: Exception) {
            // Keep empty.
        }
    }

    fun reapply() {
        current?.let { activity.window.setBackgroundDrawable(it) }
    }

    /** Arrête le minuteur du diaporama quand l'accueil n'est plus au premier plan. */
    fun pause() {
        paused = true
        cancelTick()
    }

    fun shutdown() {
        cancelTick()
        executor.shutdownNow()
    }

    private fun syncTicker() {
        if (Wallpaper.mode(activity) == Wallpaper.MODE_SLIDESHOW) scheduleTick() else cancelTick()
    }

    private fun scheduleTick() {
        if (paused || tickScheduled) return
        tickScheduled = true
        handler.postDelayed(tick, Wallpaper.SLIDE_INTERVAL_MS)
    }

    private fun cancelTick() {
        handler.removeCallbacks(tick)
        tickScheduled = false
    }

    private fun onTick() {
        tickScheduled = false
        if (paused || activity.isDestroyed || activity.isFinishing) return
        if (Wallpaper.mode(activity) != Wallpaper.MODE_SLIDESHOW) return
        scheduleTick()
        val metrics = activity.resources.displayMetrics
        try {
            executor.execute {
                val slide = Wallpaper.nextSlide(activity, metrics.widthPixels, metrics.heightPixels)
                    ?: return@execute
                handler.post {
                    if (activity.isDestroyed || activity.isFinishing) return@post
                    if (Wallpaper.mode(activity) != Wallpaper.MODE_SLIDESHOW) return@post
                    val slideshow = current as? SlideshowDrawable
                    if (slideshow != null) {
                        if (slide.count > 1) slideshow.crossfadeTo(slide.bitmap, Wallpaper.SLIDE_FADE_MS)
                    } else {
                        val drawable = SlideshowDrawable(slide.bitmap)
                        val sig = Wallpaper.signature(activity)
                        sharedSig = sig
                        sharedDrawable = drawable
                        appliedSig = sig
                        show(drawable)
                    }
                }
            }
        } catch (e: Exception) {
            // Keep empty.
        }
    }

    private fun show(drawable: Drawable?) {
        val bg = drawable ?: activity.getDrawable(R.drawable.app_background)
        current = bg
        activity.window.setBackgroundDrawable(bg)
    }

    companion object {
        @Volatile
        private var sharedSig: String? = null

        @Volatile
        private var sharedDrawable: Drawable? = null
    }
}
