package io.github.dpan538.annota

import android.animation.ValueAnimator
import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.LinearInterpolator

/** One restrained motion vocabulary, respecting Android's disabled-animation setting. */
object UiMotion {
    const val PRESS = 100L
    const val PANEL = 160L
    const val SCREEN = 180L
    val linear = LinearInterpolator()

    fun enabled() = ValueAnimator.areAnimatorsEnabled()

    fun press(view: View, pressed: Boolean) {
        view.animate().cancel()
        val size = if (pressed) .975f else 1f
        val alpha = if (pressed) .78f else 1f
        if (enabled() && view.isAttachedToWindow) {
            view
                .animate()
                .scaleX(size)
                .scaleY(size)
                .alpha(alpha)
                .setDuration(PRESS)
                .setInterpolator(linear)
                .start()
        } else {
            view.scaleX = size
            view.scaleY = size
            view.alpha = alpha
        }
    }

    fun enter(view: View, distance: Float = 8f * view.resources.displayMetrics.density) {
        if (!enabled()) return
        view.alpha = 0f
        view.translationY = distance
        view
            .animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(SCREEN)
            .setInterpolator(linear)
            .start()
    }

    fun dialog(dialog: Dialog, palette: UiPalette) {
        val window = dialog.window ?: return
        val radius = 22 * dialog.context.resources.displayMetrics.density
        window.setBackgroundDrawable(
            GradientDrawable().apply {
                setColor(palette.paper)
                cornerRadius = radius
            }
        )
        window.decorView.clipToOutline = true
        window.attributes =
            window.attributes.apply {
                windowAnimations = if (enabled()) R.style.AnnotaPanelMotion else 0
            }
    }
}
