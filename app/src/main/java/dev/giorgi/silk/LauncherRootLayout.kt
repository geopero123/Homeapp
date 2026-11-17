package dev.giorgi.silk

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs

/**
 * Holds the home screen and, on top of it, the app drawer, and owns the
 * vertical gestures:
 *   swipe up on home      -> drawer follows the finger, spring settles it
 *   swipe down on home    -> notification shade (One UI default)
 *   swipe down in drawer  -> drawer follows the finger back down
 *
 * The drawer's translationY is the single source of truth:
 *   translationY == height  -> closed,  translationY == 0 -> open.
 *
 * The drawer is never INVISIBLE; when closed it sits off screen at alpha 0.
 * That way its pages are already measured and laid out, and the first frame
 * of the opening gesture has nothing to build.
 */
class LauncherRootLayout(
    context: Context,
    private val home: View,
    private val drawer: DrawerView,
) : FrameLayout(context) {

    var isOpen = false
        private set

    /** Called once per downward swipe on the home screen. */
    var onSwipeDown: (() -> Unit)? = null

    private enum class Mode { NONE, DRAWER, SWALLOW }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingVelocity = context.dp(350).toFloat()

    private var tracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var mode = Mode.NONE
    private var dragOffset = 0f

    private val settle = SpringAnimation(drawer, DynamicAnimation.TRANSLATION_Y)

    init {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        addView(home, FrameLayout.LayoutParams(match, match))
        addView(drawer, FrameLayout.LayoutParams(match, match))
        drawer.alpha = 0f
        drawer.translationY = 100_000f // replaced by the real height on first layout

        val force = SpringForce()
        force.setStiffness(500f)
        force.setDampingRatio(0.9f)
        settle.setSpring(force)
        settle.setMinValue(0f)
        settle.addUpdateListener { _, _, _ -> applyProgress() }
        settle.addEndListener { _, canceled, _, _ -> if (!canceled) onSettled() }
    }

    fun open() = animateTo(true, 0f)

    fun close() = animateTo(false, 0f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!isOpen && mode != Mode.DRAWER && !settle.isRunning) {
            drawer.translationY = h.toFloat()
            applyProgress()
        }
    }

    private fun applyProgress() {
        val h = height.toFloat()
        if (h <= 0f) return
        val progress = (1f - drawer.translationY / h).coerceIn(0f, 1f)
        home.alpha = 1f - progress
        val scale = 1f - 0.06f * progress
        home.scaleX = scale
        home.scaleY = scale
        drawer.alpha = (progress * 2.5f).coerceAtMost(1f)
    }

    private fun animateTo(open: Boolean, velocity: Float) {
        if (height == 0) return
        isOpen = open
        settle.setStartVelocity(velocity)
        settle.animateToFinalPosition(if (open) 0f else height.toFloat())
    }

    private fun onSettled() {
        if (!isOpen) drawer.reset()
    }

    // ---- touch handling -------------------------------------------------

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                mode = Mode.NONE
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                tracker?.addMovement(ev)
                if (settle.isRunning) {
                    settle.cancel()
                    beginDrag(ev)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                if (mode == Mode.NONE) decide(ev)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.NONE) releaseTracker()
            }
        }
        return mode != Mode.NONE
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                if (mode == Mode.NONE) decide(ev)
                if (mode == Mode.DRAWER) {
                    drawer.translationY = (ev.y + dragOffset).coerceIn(0f, height.toFloat())
                    applyProgress()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.DRAWER) {
                    tracker?.addMovement(ev)
                    tracker?.computeCurrentVelocity(1000)
                    val velocity = tracker?.yVelocity ?: 0f
                    val open = when {
                        velocity < -flingVelocity -> true
                        velocity > flingVelocity -> false
                        else -> drawer.translationY < height / 2f
                    }
                    animateTo(open, velocity)
                }
                mode = Mode.NONE
                releaseTracker()
            }
        }
        return true
    }

    /** Decides, once the finger has moved far enough vertically, what this gesture is. */
    private fun decide(ev: MotionEvent) {
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dy) < touchSlop || abs(dy) < abs(dx) * 1.5f) return
        when {
            isOpen && dy > 0 && drawer.isAtTop() -> beginDrag(ev)
            !isOpen && dy < 0 -> beginDrag(ev)
            !isOpen && dy > 0 -> {
                mode = Mode.SWALLOW
                onSwipeDown?.invoke()
            }
        }
    }

    private fun beginDrag(ev: MotionEvent) {
        mode = Mode.DRAWER
        dragOffset = drawer.translationY - ev.y
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun releaseTracker() {
        tracker?.recycle()
        tracker = null
    }
}
