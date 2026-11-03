package io.github.dpan538.annota

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/** Collapse layout space as the toolbar slides out; the document always fills the freed area. */
class ReaderChrome(
    private val host: FrameLayout,
    private val bar: View,
    private val fullHeight: Int,
    private val reader: ReaderView,
    private val canHide: () -> Boolean,
    private val onHidden: (Boolean) -> Unit,
) {
    companion object {
        const val IDLE_MS = 5000L
        const val DURATION_MS = 300L
    }

    private var hidden = false
    private var paused = false
    private var disposed = false
    private var touching = false
    private var animation: ValueAnimator? = null
    private val idle =
        object : Runnable {
            override fun run() {
                if (disposed || paused || !reader.reading || hidden) return
                if (!touching && canHide()) setHidden(true) else schedule()
            }
        }

    private fun schedule() {
        host.removeCallbacks(idle)
        if (!disposed && !paused && reader.reading && !hidden && !touching)
            host.postDelayed(idle, IDLE_MS)
    }

    fun syncMode() {
        if (!reader.reading) setHidden(false, false)
        onHidden(hidden)
        schedule()
    }

    fun touch(action: Int) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                host.removeCallbacks(idle)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                touching = false
                schedule()
            }
        }
    }

    fun toggle() = setHidden(!hidden)

    private fun applyHeight(height: Int) {
        host.layoutParams = host.layoutParams.apply { this.height = height }
        bar.translationY = (height - fullHeight).toFloat()
        host.visibility = if (height == 0) View.GONE else View.VISIBLE
    }

    private fun setHidden(value: Boolean, animate: Boolean = true) {
        if (disposed) return
        animation?.cancel()
        animation = null
        hidden = value
        onHidden(value)
        host.removeCallbacks(idle)
        val end = if (value) 0 else fullHeight
        val start = host.layoutParams.height.coerceIn(0, fullHeight)
        if (animate && start != end && UiMotion.enabled() && host.isAttachedToWindow) {
            host.visibility = View.VISIBLE
            reader.setLayoutTransition(true)
            animation =
                ValueAnimator.ofInt(start, end).apply {
                    duration = DURATION_MS
                    interpolator = UiMotion.smooth
                    addUpdateListener { applyHeight(it.animatedValue as Int) }
                    addListener(
                        object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                reader.setLayoutTransition(false)
                            }
                        }
                    )
                    start()
                }
        } else {
            applyHeight(end)
            reader.setLayoutTransition(false)
        }
        schedule()
    }

    fun pause() {
        paused = true
        touching = false
        host.removeCallbacks(idle)
        animation?.cancel()
        animation = null
        applyHeight(if (hidden) 0 else fullHeight)
    }

    fun resume() {
        paused = false
        schedule()
    }

    fun dispose() {
        pause()
        disposed = true
    }
}
