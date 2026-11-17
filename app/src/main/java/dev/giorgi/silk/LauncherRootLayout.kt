package dev.giorgi.silk

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs

/**
 * Holds, bottom to top: the home screen, the blurred-wallpaper backdrop, and
 * the App Library. Owns the vertical gestures:
 *   swipe up on home      -> App Library follows the finger, spring settles it
 *   swipe down on home    -> notification shade
 *   swipe down in library -> library follows the finger back down
 *
 * Everything hangs off one number, the library's translationY:
 *   height -> closed, 0 -> open. While the finger is down it's set directly
 *   (1:1); on release a spring takes over from the finger's velocity; a touch
 *   while it is visibly moving catches it again.
 */
class LauncherRootLayout(
    context: Context,
    private val home: View,
    private val backdrop: View,
    private val drawer: DrawerView,
) : FrameLayout(context) {

    var isOpen = false
        private set

    var onSwipeDown: (() -> Unit)? = null

    /** False while a menu is showing: the menu gets every touch. */
    var gesturesEnabled = true

    /** True while the home pager is still animating to a page. */
    var homeSettling: () -> Boolean = { false }

    private enum class Mode { NONE, DRAWER, SWALLOW }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingVelocity = context.dp(350).toFloat()
    private val catchDistance = context.dp(40).toFloat()
    private val maxBlur = context.dpf(18f)
    private val blurStep = context.dpf(2f)
    private var lastBlur = 0f
    private var homeHidden = false

    private var tracker: VelocityTracker? = null
    private var activeId = -1
    private var downX = 0f
    private var downY = 0f
    private var mode = Mode.NONE
    private var dragOffset = 0f
    private var inBottomGestureZone = false
    private var ignoreDisallow = false

    private val settle = SpringAnimation(drawer, DynamicAnimation.TRANSLATION_Y)

    init {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        addView(home, FrameLayout.LayoutParams(match, match))
        addView(backdrop, FrameLayout.LayoutParams(match, match))
        addView(drawer, FrameLayout.LayoutParams(match, match))
        backdrop.alpha = 0f
        drawer.alpha = 0f
        backdrop.visibility = View.INVISIBLE
        drawer.visibility = View.INVISIBLE
        drawer.translationY = 100_000f

        val force = SpringForce()
        force.setStiffness(450f)
        force.setDampingRatio(0.86f)
        settle.setSpring(force)
        settle.setMinValue(0f)
        settle.addUpdateListener { _, _, _ -> applyProgress() }
        settle.addEndListener { _, canceled, _, _ ->
            if (!canceled) {
                setMoving(false)
                if (!isOpen) drawer.reset()
            }
        }
    }

    fun open() = animateTo(true, 0f)

    fun close() = animateTo(false, 0f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (mode != Mode.DRAWER && !settle.isRunning) {
            drawer.translationY = if (isOpen) 0f else h.toFloat()
            applyProgress()
        }
    }

    private fun applyProgress() {
        val h = height.toFloat()
        if (h <= 0f) return
        val progress = (1f - drawer.translationY / h).coerceIn(0f, 1f)

        // Fully closed: library and backdrop cost nothing. (INVISIBLE keeps them laid out.)
        val shown = if (progress > 0f) View.VISIBLE else View.INVISIBLE
        if (drawer.visibility != shown) {
            drawer.visibility = shown
            backdrop.visibility = shown
        }
        // Fully under the opaque backdrop: don't draw home at all.
        val hide = progress >= 0.999f
        if (hide != homeHidden) {
            homeHidden = hide
            home.visibility = if (hide) View.INVISIBLE else View.VISIBLE
        }

        // Home recedes: shrinks, blurs and fades under the library.
        val scale = 1f - 0.08f * progress
        home.scaleX = scale
        home.scaleY = scale
        home.alpha = 1f - 0.7f * progress
        // Full blur by 40% of travel, in 2dp steps: HWUI only re-blurs when the
        // effect changes and reuses the cached result in between.
        val target = maxBlur * (progress * 2.5f).coerceAtMost(1f)
        val blur = (target / blurStep).toInt() * blurStep
        if (blur != lastBlur) {
            home.setRenderEffect(
                if (blur <= 0f) null else RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
            )
            lastBlur = blur
        }

        backdrop.alpha = progress
        // Library content arrives in stages: it fades in over the first 40% of travel.
        drawer.alpha = (progress * 2.5f).coerceAtMost(1f)
        val contentScale = 0.94f + 0.06f * progress
        drawer.scaleX = contentScale
        drawer.scaleY = contentScale
    }

    /** While moving, the library is one GPU layer: drawn once, then only moved and faded. */
    private fun setMoving(moving: Boolean) {
        val type = if (moving) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        if (drawer.layerType != type) drawer.setLayerType(type, null)
    }

    private fun animateTo(open: Boolean, velocity: Float) {
        if (height == 0) return
        isOpen = open
        if (!open) drawer.dismissKeyboard()
        setMoving(true)
        // A running spring keeps its own velocity: overwriting it would stop it dead.
        if (!settle.isRunning) settle.setStartVelocity(velocity)
        settle.animateToFinalPosition(if (open) 0f else height.toFloat())
    }

    // ---- touch handling -------------------------------------------------

    private fun y(ev: MotionEvent): Float {
        val i = ev.findPointerIndex(activeId)
        return if (i >= 0) ev.getY(i) else ev.y
    }

    override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
        // Right after a page fling the pager claims every new touch; we keep
        // watching so a swipe up still opens the library.
        if (disallow && ignoreDisallow && mode == Mode.NONE) return
        super.requestDisallowInterceptTouchEvent(disallow)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!gesturesEnabled) {
            mode = Mode.NONE
            return false
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                activeId = ev.getPointerId(0)
                mode = Mode.NONE
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                tracker?.addMovement(ev)
                val g = ViewCompat.getRootWindowInsets(this)
                    ?.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
                inBottomGestureZone = g != null && ev.y >= height - g.bottom
                ignoreDisallow = !isOpen && homeSettling()
                if (settle.isRunning) {
                    val target = if (isOpen) 0f else height.toFloat()
                    if (abs(drawer.translationY - target) > catchDistance && !inBottomGestureZone) {
                        settle.cancel()
                        beginDrag(ev) // visibly moving: the finger catches it
                    } else {
                        settle.skipToEnd() // basically there: finish, and let the tap through
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                if (ignoreDisallow && abs(ev.x - downX) > touchSlop) {
                    ignoreDisallow = false // a real page drag: hand it to the pager
                    super.requestDisallowInterceptTouchEvent(true)
                }
                if (mode == Mode.NONE) decide(ev)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                ignoreDisallow = false
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
                    drawer.translationY = (y(ev) + dragOffset).coerceIn(0f, height.toFloat())
                    applyProgress()
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (mode == Mode.DRAWER) {
                // The newest finger takes over, without a jump.
                val i = ev.actionIndex
                activeId = ev.getPointerId(i)
                dragOffset = drawer.translationY - ev.getY(i)
            }
            MotionEvent.ACTION_POINTER_UP -> if (mode == Mode.DRAWER && ev.getPointerId(ev.actionIndex) == activeId) {
                val i = if (ev.actionIndex == 0) 1 else 0
                activeId = ev.getPointerId(i)
                dragOffset = drawer.translationY - ev.getY(i)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.DRAWER) {
                    if (ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                        animateTo(isOpen, 0f) // the system took the gesture: go back, no fling
                    } else {
                        tracker?.addMovement(ev)
                        tracker?.computeCurrentVelocity(1000)
                        val velocity = tracker?.getYVelocity(activeId) ?: 0f
                        val open = when {
                            velocity < -flingVelocity -> true
                            velocity > flingVelocity -> false
                            else -> drawer.translationY < height / 2f
                        }
                        animateTo(open, velocity)
                    }
                }
                mode = Mode.NONE
                releaseTracker()
            }
        }
        return true
    }

    private fun decide(ev: MotionEvent) {
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dy) < touchSlop || abs(dy) < abs(dx) * 1.5f) return
        // Let list widgets scroll; at their edge the next swipe opens library/shade.
        if (!isOpen && scrollableUnder(home, ev.rawX, ev.rawY, if (dy < 0) 1 else -1)) return
        when {
            isOpen && dy > 0 && drawer.isAtTop() -> beginDrag(ev)
            !isOpen && dy < 0 && !inBottomGestureZone -> beginDrag(ev)
            !isOpen && dy > 0 -> {
                mode = Mode.SWALLOW
                onSwipeDown?.invoke()
            }
        }
    }

    private val hitLoc = IntArray(2)

    private fun scrollableUnder(v: View, rx: Float, ry: Float, dir: Int): Boolean {
        if (!v.isShown) return false
        v.getLocationOnScreen(hitLoc)
        if (rx < hitLoc[0] || ry < hitLoc[1] || rx >= hitLoc[0] + v.width || ry >= hitLoc[1] + v.height) return false
        if (v !== home && v.canScrollVertically(dir)) return true
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                if (scrollableUnder(v.getChildAt(i), rx, ry, dir)) return true
            }
        }
        return false
    }

    private fun beginDrag(ev: MotionEvent) {
        mode = Mode.DRAWER
        setMoving(true)
        if (isOpen) drawer.dismissKeyboard()
        dragOffset = drawer.translationY - y(ev)
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun releaseTracker() {
        tracker?.recycle()
        tracker = null
    }
}
