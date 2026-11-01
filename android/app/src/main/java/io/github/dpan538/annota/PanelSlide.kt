package io.github.dpan538.annota

import android.animation.Animator
import android.animation.ObjectAnimator
import android.transition.TransitionValues
import android.transition.Visibility
import android.view.View
import android.view.ViewGroup

/** Move a small surface from its anchor; keep text and the document fully opaque. */
internal class PanelSlide : Visibility() {
    override fun onAppear(
        root: ViewGroup,
        view: View,
        start: TransitionValues?,
        end: TransitionValues?,
    ): Animator =
        ObjectAnimator.ofFloat(
            view,
            View.TRANSLATION_Y,
            -8f * view.resources.displayMetrics.density,
            0f,
        )

    override fun onDisappear(
        root: ViewGroup,
        view: View,
        start: TransitionValues?,
        end: TransitionValues?,
    ): Animator =
        ObjectAnimator.ofFloat(
            view,
            View.TRANSLATION_Y,
            0f,
            -8f * view.resources.displayMetrics.density,
        )
}
