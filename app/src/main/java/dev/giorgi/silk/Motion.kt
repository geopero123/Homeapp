package dev.giorgi.silk

import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * Small helpers for spring animations. Springs have no fixed duration: they
 * start from the current value and velocity, so starting a new one mid-way
 * simply redirects the motion. That is what makes everything interruptible.
 */
object Motion {

    const val BOUNCY_STIFFNESS = 700f
    const val BOUNCY_DAMPING = 0.62f
    const val SMOOTH_STIFFNESS = 400f
    const val SMOOTH_DAMPING = 0.9f

    /** One end callback per spring; a new target replaces (drops) the old target's callback. */
    private class Pending : DynamicAnimation.OnAnimationEndListener {
        var onEnd: (() -> Unit)? = null
        override fun onAnimationEnd(a: DynamicAnimation<*>?, canceled: Boolean, value: Float, velocity: Float) {
            val cb = onEnd
            onEnd = null
            if (!canceled) cb?.invoke()
        }
    }

    private class Entry(val anim: SpringAnimation, val pending: Pending)

    @Suppress("UNCHECKED_CAST")
    private fun springsOf(view: View): HashMap<DynamicAnimation.ViewProperty, Entry> =
        view.getTag(R.id.silk_springs) as? HashMap<DynamicAnimation.ViewProperty, Entry>
            ?: HashMap<DynamicAnimation.ViewProperty, Entry>().also { view.setTag(R.id.silk_springs, it) }

    /** Springs [property] of [view] to [target], redirecting any spring already running on it. */
    fun spring(
        view: View,
        property: DynamicAnimation.ViewProperty,
        target: Float,
        stiffness: Float = SMOOTH_STIFFNESS,
        damping: Float = SMOOTH_DAMPING,
        onEnd: (() -> Unit)? = null,
    ): SpringAnimation {
        val e = springsOf(view).getOrPut(property) {
            val a = SpringAnimation(view, property)
            a.spring = SpringForce()
            if (property == DynamicAnimation.ALPHA || property == DynamicAnimation.SCALE_X ||
                property == DynamicAnimation.SCALE_Y
            ) {
                a.minimumVisibleChange = DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE
            }
            val p = Pending()
            a.addEndListener(p)
            Entry(a, p)
        }
        e.anim.spring.stiffness = stiffness
        e.anim.spring.dampingRatio = damping
        e.pending.onEnd = onEnd
        e.anim.animateToFinalPosition(target)
        return e.anim
    }

    fun scale(view: View, target: Float, stiffness: Float = BOUNCY_STIFFNESS, damping: Float = BOUNCY_DAMPING) {
        spring(view, DynamicAnimation.SCALE_X, target, stiffness, damping)
        spring(view, DynamicAnimation.SCALE_Y, target, stiffness, damping)
    }

    /** Stops every spring on [view] where it is, so values can be set directly. */
    fun cancel(view: View) {
        (view.getTag(R.id.silk_springs) as? HashMap<*, *>)?.values?.forEach {
            (it as Entry).pending.onEnd = null
            it.anim.cancel()
        }
    }
}
