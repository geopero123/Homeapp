package dev.giorgi.silk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.View

/** The App Library background: the wallpaper, deeply blurred and dimmed. */
class Backdrop(context: Context) : View(context) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()

    init {
        WallpaperSource.addListener { invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        val b = WallpaperSource.heavyBitmap
        if (b != null) {
            m.setScale(width / b.width.toFloat(), height / b.height.toFloat())
            canvas.drawBitmap(b, m, paint)
            canvas.drawColor(0x59000000)
        } else {
            canvas.drawColor(0xE6101012.toInt())
        }
    }
}
