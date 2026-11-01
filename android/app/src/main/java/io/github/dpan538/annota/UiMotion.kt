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
        if (enabled() && view.isAttachedToWindow) {
            view
                .animate()
                .scaleX(size)
                .scaleY(size)
                .setDuration(PRESS)
                .setInterpolator(linear)
                .start()
        } else {
            view.scaleX = size
            view.scaleY = size
            view.alpha = 1f
        }
    }

    fun enter(view: View, distance: Float = 8f * view.resources.displayMetrics.density) {
        if (!enabled()) return
        view.alpha = 1f
        view.translationY = distance
        view.animate().translationY(0f).setDuration(SCREEN).setInterpolator(linear).start()
    }

    private val screenChanges = WeakHashMap<ViewGroup, ScreenChange>()

    /**
     * Keeps the previous screen opaque while the next screen slides alongside it. No frame-by-frame
     * layout or PDF rerendering; bitmap memory is capped at 1440px longest edge.
     */
    fun captureScreen(host: ViewGroup, forward: Boolean = true): ScreenChange? {
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
        return ScreenChange(host, bitmap, forward).also { screenChanges[host] = it }
    }

    class ScreenChange
    internal constructor(
        private val host: ViewGroup,
        private val bitmap: Bitmap,
        private val forward: Boolean,
    ) {
        private val drawable =
            BitmapDrawable(host.resources, bitmap).apply {
                setBounds(0, 0, host.width, host.height)
            }
        private var animator: ValueAnimator? = null
        private var incoming = emptyList<View>()
        private var closed = false
        private var listener: ViewTreeObserver.OnPreDrawListener? = null
        private val detach =
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}

                override fun onViewDetachedFromWindow(v: View) = finish()
            }

        private var onFinished: (() -> Unit)? = null

        fun start(ready: () -> Boolean = { true }, finished: () -> Unit = {}) {
            onFinished = finished
            if (closed) return
            incoming = (0 until host.childCount).map(host::getChildAt)
            val direction = if (forward) 1f else -1f
            incoming.forEach { it.translationX = direction * host.width }
            host.overlay.add(drawable)
            host.addOnAttachStateChangeListener(detach)
            val deadline = android.os.SystemClock.uptimeMillis() + 800
            listener =
                ViewTreeObserver.OnPreDrawListener {
                    if (!closed && !ready() && android.os.SystemClock.uptimeMillis() < deadline) {
                        host.postInvalidateOnAnimation()
                        return@OnPreDrawListener true
                    }
                    listener?.let {
                        if (host.viewTreeObserver.isAlive)
                            host.viewTreeObserver.removeOnPreDrawListener(it)
                    }
                    listener = null
                    if (!closed) {
                        animator =
                            ValueAnimator.ofFloat(0f, 1f).apply {
                                duration = SCREEN
                                interpolator = linear
                                addUpdateListener {
                                    val progress = it.animatedValue as Float
                                    val offset = (-direction * host.width * progress).toInt()
                                    drawable.setBounds(offset, 0, offset + host.width, host.height)
                                    incoming.forEach { child ->
                                        child.translationX =
                                            direction * host.width * (1f - progress)
                                    }
                                }
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
            incoming.forEach { it.translationX = 0f }
            incoming = emptyList()
            host.overlay.remove(drawable)
            host.removeOnAttachStateChangeListener(detach)
            if (screenChanges[host] === this) screenChanges.remove(host)
            bitmap.recycle()
            onFinished?.invoke()
            onFinished = null
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
