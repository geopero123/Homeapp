package dev.giorgi.silk

import android.content.Context
import android.graphics.Canvas
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.view.View

/** The App Library background: the wallpaper, deeply blurred and dimmed, in one draw. */
class Backdrop(context: Context) : View(context) {

    // Multiplying by 0xA6 is exactly the same as drawing 0x59000000 on top, but one pass.
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = LightingColorFilter(0xA6A6A6, 0)
    }
    private val m = Matrix()
    private val onWallpaper: () -> Unit = { invalidate() }

    override fun hasOverlappingRendering(): Boolean = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        WallpaperSource.addListener(onWallpaper)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        WallpaperSource.removeListener(onWallpaper)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val b = WallpaperSource.heavyBitmap
        if (b != null && width > 0 && height > 0) {
            val s = maxOf(width / b.width.toFloat(), height / b.height.toFloat())
            m.setScale(s, s)
            m.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
            canvas.drawBitmap(b, m, paint)
        } else {
            canvas.drawColor(0xE6101012.toInt())
        }
    }
}
