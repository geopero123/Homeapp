package dev.giorgi.silk

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation

/**
 * iOS-style context menu: a Liquid Glass card that springs out of the
 * touched item over a dimmed screen. Tap outside or press Back to close.
 */
class GlassMenu(private val root: FrameLayout) {

    var onShowingChanged: ((Boolean) -> Unit)? = null

    private var overlay: FrameLayout? = null
    private var card: View? = null

    val isShowing: Boolean get() = overlay != null

    fun show(anchor: View?, title: String?, entries: List<Pair<String, () -> Unit>>) {
        dismiss(immediate = true)
        val ctx = root.context

        val ov = FrameLayout(ctx)
        ov.isClickable = true
        ov.setBackgroundColor(0x40000000)
        ov.alpha = 0f
        ov.setOnClickListener { dismiss() }

        val list = LinearLayout(ctx)
        list.orientation = LinearLayout.VERTICAL
        if (title != null) {
            val t = TextView(ctx)
            t.text = title
            t.setTextColor(0x99FFFFFF.toInt())
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            t.setPadding(ctx.dp(18), ctx.dp(14), ctx.dp(18), ctx.dp(10))
            list.addView(t)
        }
        entries.forEachIndexed { i, (label, action) ->
            if (i > 0 || title != null) {
                val line = View(ctx)
                line.setBackgroundColor(0x26FFFFFF)
                list.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            }
            val row = TextView(ctx)
            row.text = label
            row.setTextColor(if (label.startsWith("Remove") || label == "Uninstall") 0xFFFF6961.toInt() else Color.WHITE)
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            row.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            row.setPadding(ctx.dp(18), ctx.dp(13), ctx.dp(18), ctx.dp(13))
            row.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(0x26FFFFFF))
            }
            row.setOnClickListener {
                dismiss()
                action()
            }
            list.addView(row)
        }

        val scroll = ScrollView(ctx)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(list)

        val c = FrameLayout(ctx)
        c.isClickable = true
        c.background = LiquidGlass(c, ctx.dpf(20f), darkness = 0.38f)
        c.clipToOutline = true
        c.addView(scroll)

        val width = ctx.dp(260)
        val maxH = (root.height * 0.6f).toInt()
        c.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)
        )
        val h = c.measuredHeight
        val margin = ctx.dp(16)

        var x: Int
        var y: Int
        var pivotY: Float
        val pivotX: Float
        if (anchor != null) {
            val a = IntArray(2)
            val r = IntArray(2)
            anchor.getLocationInWindow(a)
            root.getLocationInWindow(r)
            val ax = a[0] - r[0]
            val ay = a[1] - r[1]
            val centerX = ax + anchor.width / 2
            x = (centerX - width / 2).coerceIn(margin, root.width - width - margin)
            y = ay + anchor.height + ctx.dp(8)
            pivotY = 0f
            if (y + h > root.height - ctx.dp(24)) {
                y = ay - h - ctx.dp(8)
                pivotY = h.toFloat()
            }
            y = y.coerceAtLeast(ctx.dp(40))
            pivotX = (centerX - x).toFloat()
        } else {
            x = (root.width - width) / 2
            y = (root.height - h) / 2
            pivotX = width / 2f
            pivotY = h / 2f
        }

        val lp = FrameLayout.LayoutParams(width, h)
        lp.leftMargin = x
        lp.topMargin = y
        ov.addView(c, lp)
        root.addView(ov, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        c.pivotX = pivotX
        c.pivotY = pivotY
        c.scaleX = 0.5f
        c.scaleY = 0.5f
        c.alpha = 0f
        Motion.scale(c, 1f, 650f, 0.72f)
        Motion.spring(c, DynamicAnimation.ALPHA, 1f, 900f, 1f)
        Motion.spring(ov, DynamicAnimation.ALPHA, 1f, 600f, 1f)

        overlay = ov
        card = c
        onShowingChanged?.invoke(true)
    }

    fun dismiss(immediate: Boolean = false) {
        val ov = overlay ?: return
        val c = card
        overlay = null
        card = null
        onShowingChanged?.invoke(false)
        if (immediate || c == null) {
            root.removeView(ov)
            return
        }
        ov.isClickable = false
        Motion.scale(c, 0.7f, 900f, 1f)
        Motion.spring(c, DynamicAnimation.ALPHA, 0f, 900f, 1f)
        Motion.spring(ov, DynamicAnimation.ALPHA, 0f, 700f, 1f) { root.removeView(ov) }
    }
}
