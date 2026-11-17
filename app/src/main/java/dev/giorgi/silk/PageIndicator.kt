package dev.giorgi.silk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.abs

/** Row of dots; the dot for the current page is brighter and follows the swipe. */
class PageIndicator(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val radius = context.dp(3).toFloat()
    private val gap = context.dp(14).toFloat()

    var count = 1
        set(value) {
            field = value
            invalidate()
        }

    /** Page index plus swipe fraction, e.g. 1.5 is halfway between pages 1 and 2. */
    var position = 0f
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        if (count <= 1) return
        val startX = width / 2f - gap * (count - 1) / 2f
        val cy = height / 2f
        for (i in 0 until count) {
            val closeness = (1f - abs(position - i)).coerceIn(0f, 1f)
            paint.alpha = (90 + 165 * closeness).toInt()
            canvas.drawCircle(startX + i * gap, cy, radius * (1f + 0.3f * closeness), paint)
        }
    }
}
