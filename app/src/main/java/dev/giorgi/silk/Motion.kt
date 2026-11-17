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

    /** Snappy, with a little bounce (press feedback, menus). */
    const val BOUNCY_STIFFNESS = 700f
    const val BOUNCY_DAMPING = 0.62f

    /** Smooth, no visible overshoot (fades, settling). */
    const val SMOOTH_STIFFNESS = 400f
    const val SMOOTH_DAMPING = 0.9f

    private fun key(property: DynamicAnimation.ViewProperty): Int = when (property) {
        DynamicAnimation.SCALE_X -> R_SCALE_X
        DynamicAnimation.SCALE_Y -> R_SCALE_Y
        DynamicAnimation.ALPHA -> R_ALPHA
        DynamicAnimation.TRANSLATION_Y -> R_TRANSLATION_Y
        else -> R_OTHER
    }

    /** Springs [property] of [view] to [target], reusing (and redirecting) any spring already running. */
    fun spring(
        view: View,
        property: DynamicAnimation.ViewProperty,
        target: Float,
        stiffness: Float = SMOOTH_STIFFNESS,
        damping: Float = SMOOTH_DAMPING,
        onEnd: (() -> Unit)? = null,
    ): SpringAnimation {
        val tag = key(property)
        val anim = view.getTag(tag) as? SpringAnimation ?: SpringAnimation(view, property).also {
            it.spring = SpringForce()
            if (property == DynamicAnimation.ALPHA || property == DynamicAnimation.SCALE_X ||
                property == DynamicAnimation.SCALE_Y
            ) {
                it.minimumVisibleChange = DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE
            }
            view.setTag(tag, it)
        }
        anim.spring.stiffness = stiffness
        anim.spring.dampingRatio = damping
        if (onEnd != null) {
            anim.addEndListener(object : DynamicAnimation.OnAnimationEndListener {
                override fun onAnimationEnd(a: DynamicAnimation<*>?, canceled: Boolean, value: Float, velocity: Float) {
                    anim.removeEndListener(this)
                    if (!canceled) onEnd()
                }
            })
        }
        anim.animateToFinalPosition(target)
        return anim
    }

    fun scale(view: View, target: Float, stiffness: Float = BOUNCY_STIFFNESS, damping: Float = BOUNCY_DAMPING) {
        spring(view, DynamicAnimation.SCALE_X, target, stiffness, damping)
        spring(view, DynamicAnimation.SCALE_Y, target, stiffness, damping)
    }

    // View tag keys (any unique non-zero ints with the 0x7f.. resource prefix shape).
    private const val R_SCALE_X = 0x7f0f0001
    private const val R_SCALE_Y = 0x7f0f0002
    private const val R_ALPHA = 0x7f0f0003
    private const val R_TRANSLATION_Y = 0x7f0f0004
    private const val R_OTHER = 0x7f0f0005
}
