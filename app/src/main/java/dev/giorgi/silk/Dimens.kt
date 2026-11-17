package dev.giorgi.silk

import android.content.Context

/** Fixed sizes for Phase 1. These become user settings in a later phase. */
object Dimens {
    const val GRID_COLS = 4
    const val GRID_ROWS = 5
    const val DOCK_SLOTS = 4
    const val DRAWER_COLS = 4
    const val ICON_DP = 56
    const val DOCK_HEIGHT_DP = 96
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
