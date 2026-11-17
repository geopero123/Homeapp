package dev.giorgi.silk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import androidx.core.content.ContextCompat
import kotlin.math.hypot

/**
 * Wraps any widget (Silk's own or an Android one). Widgets eat their own
 * touches, so long-press is detected here, before the widget sees the event.
 */
class WidgetFrame(context: Context) : FrameLayout(context) {

    var onLongPress: (() -> Unit)? = null

    private val timeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var fired = false
    private val trigger = Runnable {
        fired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        // Children must stop tracking this gesture.
        val cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        for (i in 0 until childCount) getChildAt(i).dispatchTouchEvent(cancel)
        cancel.recycle()
        onLongPress?.invoke()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fired = false
                downX = ev.x
                downY = ev.y
                postDelayed(trigger, timeout)
            }
            MotionEvent.ACTION_MOVE ->
                if (hypot(ev.x - downX, ev.y - downY) > slop) removeCallbacks(trigger)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(trigger)
        }
        return fired
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(trigger)
        }
        return fired || super.onTouchEvent(ev)
    }
}

/** Silk's built-in widgets. Each sits on a glass panel. */
object SilkWidgets {

    const val CLOCK = "clock"
    const val CALENDAR = "calendar"
    const val BATTERY = "battery"

    /** key, title, spanX, spanY */
    val all = listOf(
        Triple(CLOCK, "Clock", 4 to 2),
        Triple(CALENDAR, "Calendar", 2 to 2),
        Triple(BATTERY, "Battery", 2 to 2),
    )

    fun create(context: Context, key: String): View? = when (key) {
        CLOCK -> clock(context)
        CALENDAR -> calendar(context)
        BATTERY -> BatteryWidget(context).also { it.background = Glass.panel(context, 26) }
        else -> null
    }

    private fun clockText(context: Context, pattern: String, sp: Float, light: Boolean): TextClock {
        val t = TextClock(context)
        t.format12Hour = pattern
        t.format24Hour = pattern
        t.setTextColor(Color.WHITE)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.typeface = Typeface.create("sans-serif", if (light) Typeface.NORMAL else Typeface.BOLD)
        if (light) t.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        t.includeFontPadding = false
        return t
    }

    private fun clock(context: Context): View {
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.background = Glass.panel(context, 26)
        box.setPadding(context.dp(22), context.dp(16), context.dp(22), context.dp(16))
        val time = clockText(context, "H:mm", 68f, light = true)
        time.format12Hour = "h:mm"
        val date = clockText(context, "EEEE, d MMMM", 17f, light = false)
        date.alpha = 0.85f
        box.addView(time)
        box.addView(date)
        return box
    }

    private fun calendar(context: Context): View {
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.background = Glass.panel(context, 26)
        box.setPadding(context.dp(18), context.dp(14), context.dp(18), context.dp(14))
        val day = clockText(context, "EEEE", 14f, light = false)
        day.setTextColor(0xFFFF453A.toInt())
        day.isAllCaps = true
        val num = clockText(context, "d", 56f, light = true)
        val month = clockText(context, "MMMM", 15f, light = false)
        month.alpha = 0.75f
        box.addView(day)
        box.addView(num)
        box.addView(month)
        return box
    }
}

/** Battery level as a ring, updated from the system's sticky battery broadcast. */
class BatteryWidget(context: Context) : View(context) {

    private var level = -1
    private var charging = false

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0x33FFFFFF
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xBFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val oval = RectF()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = update(intent)
    }

    private fun update(intent: Intent) {
        val l = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        level = if (l >= 0 && scale > 0) l * 100 / scale else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val sticky = ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (sticky != null) update(sticky)
    }

    override fun onDetachedFromWindow() {
        context.unregisterReceiver(receiver)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height) * 0.62f
        val stroke = size * 0.11f
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        val cx = width / 2f
        val cy = height / 2f - size * 0.06f
        oval.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        canvas.drawArc(oval, 0f, 360f, false, track)
        if (level >= 0) {
            arc.color = when {
                charging -> 0xFF30D158.toInt()
                level <= 20 -> 0xFFFF453A.toInt()
                else -> 0xFF30D158.toInt()
            }
            canvas.drawArc(oval, -90f, 360f * level / 100f, false, arc)
        }
        big.textSize = size * 0.30f
        canvas.drawText(if (level >= 0) "$level%" else "–", cx, cy + big.textSize * 0.35f, big)
        small.textSize = size * 0.15f
        canvas.drawText(if (charging) "Charging" else "Battery", cx, oval.bottom + small.textSize * 1.9f, small)
    }
}
