package dev.giorgi.silk

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.TextView

/** An app icon with its label underneath. Used on home pages, the dock and the drawer. */
class IconView(context: Context) : TextView(context) {

    var app: AppInfo? = null
        private set

    private val iconPx = context.dp(Dimens.ICON_DP)

    init {
        gravity = Gravity.CENTER_HORIZONTAL
        defaultFocusHighlightEnabled = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        typeface = LABEL_FACE
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(context.dpf(2f), 0f, context.dpf(0.5f), 0x80000000.toInt())
        compoundDrawablePadding = context.dp(5)
        setPadding(context.dp(2), context.dp(4), context.dp(2), context.dp(4))
    }

    /**
     * Press feedback hangs off the pressed state, not raw touches: inside a
     * scrolling parent Android delays "pressed" until it's clearly a tap and
     * drops it when a swipe takes over, so swipes don't buzz or wobble icons.
     */
    override fun setPressed(pressed: Boolean) {
        if (pressed != isPressed) {
            if (pressed) {
                Motion.scale(this, 0.88f, 1200f, 1f)
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            } else {
                Motion.scale(this, 1f, 500f, 0.5f)
            }
        }
        super.setPressed(pressed)
    }

    /** Once the long-press menu is up, the pager must not page under the finger. */
    override fun performLongClick(): Boolean {
        val handled = super.performLongClick()
        if (handled) parent?.requestDisallowInterceptTouchEvent(true)
        return handled
    }

    fun bind(app: AppInfo, showLabel: Boolean = true) {
        this.app = app
        val drawable = BitmapDrawable(resources, app.icon)
        drawable.setBounds(0, 0, iconPx, iconPx)
        setCompoundDrawables(null, drawable, null, null)
        text = if (showLabel) app.label else ""
        contentDescription = app.label
    }

    private companion object {
        val LABEL_FACE: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
