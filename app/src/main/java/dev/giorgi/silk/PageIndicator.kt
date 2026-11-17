package dev.giorgi.silk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.abs

/** iOS-style page dots on a small glass capsule; the current dot follows the swipe. */
class PageIndicator(context: Context) : View(context) {

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val capsule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF }
    private val radius = context.dpf(3.5f)
    private val gap = context.dpf(15f)
    private val padH = context.dpf(12f)
    private val capsuleH = context.dpf(24f)
    private val rect = RectF()

    var count = 1
        set(value) {
            field = value
            invalidate()
        }

    var position = 0f
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        if (count <= 1) return
        val dotsW = gap * (count - 1)
        val cy = height / 2f
        rect.set(width / 2f - dotsW / 2 - padH, cy - capsuleH / 2, width / 2f + dotsW / 2 + padH, cy + capsuleH / 2)
        canvas.drawRoundRect(rect, capsuleH / 2, capsuleH / 2, capsule)
        val startX = width / 2f - dotsW / 2f
        for (i in 0 until count) {
            val closeness = (1f - abs(position - i)).coerceIn(0f, 1f)
            dot.alpha = (100 + 155 * closeness).toInt()
            canvas.drawCircle(startX + i * gap, cy, radius, dot)
        }
    }
}
