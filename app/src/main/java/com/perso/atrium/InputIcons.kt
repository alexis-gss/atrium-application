package com.perso.atrium

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.min

object InputIcons {

    private const val MAX_SIZE = 512
    private const val ALPHA_THRESHOLD = 200

    fun cropToCard(context: Context, source: Drawable): Drawable? {
        return try {
            crop(context, source)
        } catch (e: Throwable) {
            null
        }
    }

    private fun crop(context: Context, source: Drawable): Drawable? {
        val iw = source.intrinsicWidth.takeIf { it > 0 } ?: return null
        val ih = source.intrinsicHeight.takeIf { it > 0 } ?: return null
        val scale = min(1f, MAX_SIZE.toFloat() / max(iw, ih))
        val w = max(1, (iw * scale).toInt())
        val h = max(1, (ih * scale).toInt())

        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val copy = source.constantState?.newDrawable()?.mutate() ?: source
        copy.setBounds(0, 0, w, h)
        copy.draw(Canvas(full))

        val pixels = IntArray(w * h)
        full.getPixels(pixels, 0, w, 0, 0, w, h)

        var left = w
        var top = h
        var right = -1
        var bottom = -1
        var opaque = 0
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((pixels[row + x] ushr 24) >= ALPHA_THRESHOLD) {
                    opaque++
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < left || bottom < top) return null

        val bw = right - left + 1
        val bh = bottom - top + 1
        if (opaque.toFloat() / (bw * bh) < 0.6f) return null

        val inset = 1
        val cl = left + inset
        val ct = top + inset
        val cw = bw - 2 * inset
        val ch = bh - 2 * inset
        if (cw < 8 || ch < 8) return null

        val sampleX = cl + max(2, cw / 12)
        val sampleY = ct + ch / 2
        val background = pixels[sampleY * w + sampleX] or (0xFF shl 24)

        val result = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(background)
        canvas.drawBitmap(full, (-cl).toFloat(), (-ct).toFloat(), null)
        return BitmapDrawable(context.resources, result)
    }
}
