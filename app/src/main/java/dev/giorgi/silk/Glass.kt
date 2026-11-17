package dev.giorgi.silk

import android.content.Context
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * The iOS-style look: translucent "glass" panels and squircle icon shapes.
 *
 * Android can't blur what is behind a single view (only whole windows), so
 * the glass here is a light translucent fill, a soft top sheen and a thin
 * bright edge. On dark wallpapers that reads as glass and costs nothing to draw.
 */
object Glass {

    fun panel(context: Context, radiusDp: Int): Drawable {
        val r = context.dpf(radiusDp.toFloat())
        val base = GradientDrawable().apply {
            cornerRadius = r
            setColor(0x2EFFFFFF)
            setStroke(context.dp(1).coerceAtLeast(1), 0x33FFFFFF)
        }
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x22FFFFFF, 0x00FFFFFF, 0x00FFFFFF)
        ).apply { cornerRadius = r }
        return LayerDrawable(arrayOf(base, sheen))
    }

    /**
     * A superellipse ("squircle"), the continuous-corner shape iOS uses for
     * icons. Exponent 5 is close to Apple's curve.
     */
    fun squircle(size: Float): Path {
        val path = Path()
        val half = size / 2f
        val n = 5.0
        val steps = 96
        for (i in 0..steps) {
            val t = 2 * Math.PI * i / steps
            val c = cos(t)
            val s = sin(t)
            val x = half + half * (sign(c) * abs(c).pow(2 / n)).toFloat()
            val y = half + half * (sign(s) * abs(s).pow(2 / n)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }
}
