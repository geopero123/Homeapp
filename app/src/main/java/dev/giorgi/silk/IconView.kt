package dev.giorgi.silk

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.TextView

/** An app icon with its label underneath. Used on home pages, the dock and the drawer. */
class IconView(context: Context) : TextView(context) {

    var app: AppInfo? = null
        private set

    private val iconPx = context.dp(Dimens.ICON_DP)

    init {
        gravity = Gravity.CENTER_HORIZONTAL
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(5f, 0f, 1f, 0x80000000.toInt())
        compoundDrawablePadding = context.dp(5)
        setPadding(context.dp(2), context.dp(4), context.dp(2), context.dp(4))
    }

    /** Press feedback: sink to 90% on touch, spring back with a small bounce. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                Motion.scale(this, 0.88f, 1200f, 1f)
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Motion.scale(this, 1f, 500f, 0.5f)
        }
        return super.onTouchEvent(event)
    }

    fun bind(app: AppInfo, showLabel: Boolean = true) {
        this.app = app
        val drawable = BitmapDrawable(resources, app.icon)
        drawable.setBounds(0, 0, iconPx, iconPx)
        setCompoundDrawables(null, drawable, null, null)
        text = if (showLabel) app.label else ""
        contentDescription = app.label
    }
}
