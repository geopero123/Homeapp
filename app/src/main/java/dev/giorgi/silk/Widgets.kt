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
import android.os.SystemClock
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
 * Wraps any widget (Silk's own or an Android one) and detects long-press
 * itself, because widgets eat their own touches.
 *
 * It always claims the touch (so plain Silk widgets still get MOVE/UP and
 * never stay pressed), presses in only once it is clearly a tap, and gives up
 * the long-press as soon as the finger moves or a child list starts scrolling.
 */
class WidgetFrame(context: Context) : FrameLayout(context) {

    var onLongPress: (() -> Unit)? = null

    private val timeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val tapTimeout = ViewConfiguration.getTapTimeout().toLong()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var fired = false
    private var pressed = false

    private val pressIn = Runnable {
        pressed = true
        Motion.scale(this, 0.96f, 900f, 1f)
    }

    private val trigger = Runnable {
        fired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        // The menu is up: the pager and root must not steal this finger now.
        parent?.requestDisallowInterceptTouchEvent(true)
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0)
        for (i in 0 until childCount) getChildAt(i).dispatchTouchEvent(cancel)
        cancel.recycle()
        onLongPress?.invoke()
    }

    private fun track(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fired = false
                downX = ev.x
                downY = ev.y
                removeCallbacks(trigger)
                removeCallbacks(pressIn)
                postDelayed(pressIn, tapTimeout)
                postDelayed(trigger, timeout)
            }
            MotionEvent.ACTION_MOVE ->
                if (!fired && hypot(ev.x - downX, ev.y - downY) > slop) release()
            MotionEvent.ACTION_POINTER_DOWN -> if (!fired) release()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
        }
    }

    private fun release() {
        removeCallbacks(trigger)
        removeCallbacks(pressIn)
        if (pressed) {
            pressed = false
            Motion.scale(this, 1f, 500f, 0.55f)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        return fired
    }

    /** Reached when no child took the touch (Silk widgets) or after we intercepted. */
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked != MotionEvent.ACTION_DOWN) track(ev)
        return true
    }

    /** A child (e.g. a list widget) started scrolling: not a long-press. */
    override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
        if (disallow && !fired) release()
        super.requestDisallowInterceptTouchEvent(disallow)
    }

    override fun onDetachedFromWindow() {
        release()
        fired = false
        Motion.cancel(this)
        scaleX = 1f
        scaleY = 1f
        super.onDetachedFromWindow()
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
        BATTERY -> BatteryWidget(context).also { it.background = LiquidGlass(it, context.dpf(26f), 0.12f) }
        else -> null
    }

    private fun clockText(context: Context, pattern: String, sp: Float, light: Boolean): TextClock {
        val t = TextClock(context)
        t.format12Hour = pattern
        t.format24Hour = pattern
        t.setTextColor(Color.WHITE)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.typeface = if (light) Typeface.create("sans-serif-light", Typeface.NORMAL)
            else Typeface.create("sans-serif", Typeface.BOLD)
        t.includeFontPadding = false
        return t
    }

    private fun clock(context: Context): View {
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.background = LiquidGlass(box, context.dpf(26f), 0.12f)
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
        box.background = LiquidGlass(box, context.dpf(26f), 0.12f)
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
        val newLevel = if (l >= 0 && scale > 0) l * 100 / scale else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val newCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        // The broadcast also fires for temperature/voltage changes: only redraw when it shows.
        if (newLevel == level && newCharging == charging) return
        level = newLevel
        charging = newCharging
        label = if (level >= 0) "$level%" else "–"
        invalidate()
    }

    private var label = "–"
    private var registered = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (registered) return
        val sticky = ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        registered = true
        if (sticky != null) update(sticky)
    }

    override fun onDetachedFromWindow() {
        if (registered) {
            registered = false
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Already unregistered.
            }
        }
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
        canvas.drawText(label, cx, cy + big.textSize * 0.35f, big)
        small.textSize = size * 0.15f
        canvas.drawText(if (charging) "Charging" else "Battery", cx, oval.bottom + small.textSize * 1.9f, small)
    }
}
