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
import android.view.animation.PathInterpolator
import java.util.WeakHashMap
import kotlin.math.min

/** One restrained motion vocabulary, respecting Android's disabled-animation setting. */
object UiMotion {
    const val PRESS = 100L
    const val PANEL = 160L
    const val SCREEN = 320L
    const val DOCUMENT = 460L
    val smooth = PathInterpolator(.22f, .0f, .18f, 1f)
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
     * Combines a short vertical lift and subtle depth change with a covered hand-off between
     * screens. Live content stays opaque; never sweep a full tablet width. No frame-by-frame layout
     * or PDF rerendering; bitmap memory is capped at 1440px longest edge.
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
        private val cover =
            android.graphics.drawable
                .ColorDrawable(
                    (host.background as? android.graphics.drawable.ColorDrawable)?.color
                        ?: 0xfff5f5f5.toInt()
                )
                .apply { setBounds(0, 0, host.width, host.height) }
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
            val distance = 12f * host.resources.displayMetrics.density
            fun position(progress: Float) {
                // The outgoing text is gone before incoming text is revealed. Keeping the
                // snapshot still avoids resampling and eliminates two overlapping text layers.
                val motion = smooth.getInterpolation(((progress - .18f) / .82f).coerceIn(0f, 1f))
                incoming.forEach { child ->
                    child.translationY = direction * distance * (1f - motion)
                    child.scaleX = 1f - direction * .008f * (1f - motion)
                    child.scaleY = child.scaleX
                }
                drawable.alpha = (255 * (1f - progress / .22f).coerceIn(0f, 1f)).toInt()
                cover.alpha = (255 * (1f - (progress - .22f) / .20f).coerceIn(0f, 1f)).toInt()
            }
            position(0f)
            host.overlay.add(cover)
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
                                duration = DOCUMENT
                                interpolator = linear
                                addUpdateListener { position(it.animatedValue as Float) }
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
            incoming.forEach {
                it.translationX = 0f
                it.translationY = 0f
                it.scaleX = 1f
                it.scaleY = 1f
            }
            incoming = emptyList()
            host.overlay.remove(drawable)
            host.overlay.remove(cover)
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
