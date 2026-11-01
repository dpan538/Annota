package io.github.dpan538.annota

import android.animation.ValueAnimator
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import java.util.WeakHashMap
import kotlin.math.min

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

    private val screenChanges = WeakHashMap<ViewGroup, ScreenChange>()

    /**
     * Keeps the last drawn frame through a synchronous layout replacement, then fades it out. No
     * frame-by-frame layout or PDF rerendering; bitmap memory is capped at 1440px longest edge.
     */
    fun captureScreen(host: ViewGroup): ScreenChange? {
        screenChanges.remove(host)?.finish()
        if (!enabled() || !host.isAttachedToWindow || host.width == 0 || host.height == 0)
            return null
        val scale = min(1f, 1440f / maxOf(host.width, host.height))
        val bitmap =
            try {
                Bitmap.createBitmap(
                        maxOf(1, (host.width * scale).toInt()),
                        maxOf(1, (host.height * scale).toInt()),
                        Bitmap.Config.ARGB_8888,
                    )
                    .also {
                        val canvas = Canvas(it)
                        canvas.scale(scale, scale)
                        host.draw(canvas)
                    }
            } catch (_: OutOfMemoryError) {
                return null
            }
        return ScreenChange(host, bitmap).also { screenChanges[host] = it }
    }

    class ScreenChange
    internal constructor(private val host: ViewGroup, private val bitmap: Bitmap) {
        private val drawable =
            BitmapDrawable(host.resources, bitmap).apply {
                setBounds(0, 0, host.width, host.height)
            }
        private var animator: ValueAnimator? = null
        private var closed = false
        private var listener: ViewTreeObserver.OnPreDrawListener? = null
        private val detach =
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}

                override fun onViewDetachedFromWindow(v: View) = finish()
            }

        fun start() {
            if (closed) return
            host.overlay.add(drawable)
            host.addOnAttachStateChangeListener(detach)
            listener =
                ViewTreeObserver.OnPreDrawListener {
                    listener?.let {
                        if (host.viewTreeObserver.isAlive)
                            host.viewTreeObserver.removeOnPreDrawListener(it)
                    }
                    listener = null
                    if (!closed) {
                        animator =
                            ValueAnimator.ofInt(255, 0).apply {
                                duration = SCREEN
                                interpolator = linear
                                addUpdateListener { drawable.alpha = it.animatedValue as Int }
                                addListener(
                                    object : android.animation.AnimatorListenerAdapter() {
                                        override fun onAnimationEnd(
                                            animation: android.animation.Animator
                                        ) = finish()
                                    }
                                )
                                start()
                            }
                    }
                    true
                }
            host.viewTreeObserver.addOnPreDrawListener(listener)
            host.postInvalidateOnAnimation()
        }

        fun finish() {
            if (closed) return
            closed = true
            listener?.let {
                if (host.viewTreeObserver.isAlive) host.viewTreeObserver.removeOnPreDrawListener(it)
            }
            listener = null
            animator?.cancel()
            host.overlay.remove(drawable)
            host.removeOnAttachStateChangeListener(detach)
            if (screenChanges[host] === this) screenChanges.remove(host)
            bitmap.recycle()
        }
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
