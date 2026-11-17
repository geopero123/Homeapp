package dev.giorgi.silk

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

/** An app icon with its label underneath. Used on home pages, the dock and the drawer. */
class IconView(context: Context) : TextView(context) {

    var app: AppInfo? = null
        private set

    private val iconPx = context.dp(Dimens.ICON_DP)

    init {
        gravity = Gravity.CENTER_HORIZONTAL
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        // Soft shadow keeps labels readable on bright wallpapers.
        setShadowLayer(6f, 0f, 2f, 0x99000000.toInt())
        compoundDrawablePadding = context.dp(6)
        setPadding(context.dp(4), context.dp(8), context.dp(4), context.dp(8))
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
