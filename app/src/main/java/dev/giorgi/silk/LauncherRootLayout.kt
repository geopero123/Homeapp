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
 * swipe-up / swipe-down gesture between them.
 *
 * The drawer's translationY is the single source of truth:
 *   translationY == height  -> closed (drawer parked below the screen)
 *   translationY == 0       -> open
 * While a finger is down we set translationY directly (1:1 tracking). On
 * release a spring carries it to open or closed, starting from the finger's
 * real velocity. Touching the screen mid-animation cancels the spring and the
 * drawer is under the finger again; that is what "interruptible" means.
 */
class LauncherRootLayout(
    context: Context,
    private val home: View,
    private val drawer: DrawerView,
) : FrameLayout(context) {

    /** Where the drawer is heading (or resting). */
    var isOpen = false
        private set

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingVelocity = context.dp(350).toFloat() // px per second

    private var tracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var dragOffset = 0f

    private val settle = SpringAnimation(drawer, DynamicAnimation.TRANSLATION_Y)

    init {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        addView(home, FrameLayout.LayoutParams(match, match))
        addView(drawer, FrameLayout.LayoutParams(match, match))
        drawer.visibility = View.INVISIBLE

        val force = SpringForce()
        force.setStiffness(420f)
        force.setDampingRatio(0.9f)
        settle.setSpring(force)
        settle.setMinValue(0f) // never overshoot past fully open
        settle.addUpdateListener { _, _, _ -> applyProgress() }
        settle.addEndListener { _, canceled, _, _ -> if (!canceled) onSettled() }
    }

    fun open() = animateTo(true, 0f)

    fun close() = animateTo(false, 0f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!isOpen && !dragging && !settle.isRunning) {
            drawer.translationY = h.toFloat()
            applyProgress()
        }
    }

    /** Everything that depends on how far open the drawer is hangs off this one number. */
    private fun applyProgress() {
        val h = height.toFloat()
        if (h <= 0f) return
        val progress = (1f - drawer.translationY / h).coerceIn(0f, 1f)
        home.alpha = 1f - progress
        val scale = 1f - 0.06f * progress
        home.scaleX = scale
        home.scaleY = scale
        drawer.alpha = (progress * 2f).coerceAtMost(1f)
    }

    private fun animateTo(open: Boolean, velocity: Float) {
        if (height == 0) return
        isOpen = open
        drawer.visibility = View.VISIBLE
        settle.setStartVelocity(velocity)
        settle.animateToFinalPosition(if (open) 0f else height.toFloat())
    }

    private fun onSettled() {
        if (!isOpen) {
            drawer.visibility = View.INVISIBLE
            drawer.reset()
        }
    }

    // ---- touch handling -------------------------------------------------

    /**
     * Android asks the parent first, on every event, "do you want to steal this
     * gesture from your children?". We watch quietly, and once the finger has
     * clearly moved vertically we return true: the child (an icon, the pager,
     * the list) gets a cancel and the rest of the gesture comes to onTouchEvent.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                tracker?.addMovement(ev)
                // Finger landed while the drawer is still moving: catch it.
                if (settle.isRunning) {
                    settle.cancel()
                    beginDrag(ev)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                if (!dragging && shouldStartDrag(ev)) beginDrag(ev)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) releaseTracker()
            }
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                if (!dragging && shouldStartDrag(ev)) beginDrag(ev)
                if (dragging) {
                    drawer.translationY = (ev.y + dragOffset).coerceIn(0f, height.toFloat())
                    applyProgress()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    tracker?.addMovement(ev)
                    tracker?.computeCurrentVelocity(1000)
                    val velocity = tracker?.yVelocity ?: 0f
                    dragging = false
                    // A flick decides by direction; a slow release decides by position.
                    val open = when {
                        velocity < -flingVelocity -> true
                        velocity > flingVelocity -> false
                        else -> drawer.translationY < height / 2f
                    }
                    animateTo(open, velocity)
                }
                releaseTracker()
            }
        }
        return true
    }

    private fun shouldStartDrag(ev: MotionEvent): Boolean {
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dy) < touchSlop || abs(dy) < abs(dx) * 1.5f) return false
        return if (isOpen) dy > 0 && drawer.isAtTop() else dy < 0
    }

    private fun beginDrag(ev: MotionEvent) {
        dragging = true
        dragOffset = drawer.translationY - ev.y
        drawer.visibility = View.VISIBLE
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun releaseTracker() {
        tracker?.recycle()
        tracker = null
    }
}
