package io.github.dpan538.annota

import android.content.Context
import android.graphics.*
import android.view.View

/** The same Lucide glyphs used by the approved reader, in its 34-unit icon slots. */
class ToolButton(context: Context, val name: String) : View(context) {
    private var wasPressed = false
    private var selectionProgress = 0f
    private var selectionAnimator: android.animation.ValueAnimator? = null

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (wasPressed != isPressed) {
            wasPressed = isPressed
            UiMotion.press(this, isPressed)
        }
    }

    override fun setSelected(selected: Boolean) {
        val changed = selected != isSelected
        super.setSelected(selected)
        if (!changed) return
        selectionAnimator?.cancel()
        val target = if (selected) 1f else 0f
        if (isAttachedToWindow && UiMotion.enabled()) {
            selectionAnimator =
                android.animation.ValueAnimator.ofFloat(selectionProgress, target).apply {
                    duration = UiMotion.PANEL
                    interpolator = UiMotion.linear
                    addUpdateListener {
                        selectionProgress = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
        } else {
            selectionProgress = target
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        selectionAnimator?.cancel()
        animate().cancel()
        super.onDetachedFromWindow()
    }

    var tint = 0xff262a2d.toInt()
    var selectedFill = 0x33ffffff
    var selectedTint = 0xff262a2d.toInt()
    var dotColor = 0xff262a2d.toInt()
    var annotationAction = false
    var unit = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val annotateGlyph = if (name == "book") LucideIcon(context, "PenLine") else null
    private val glyph =
        LucideIcon(
            context,
            when (name) {
                "back" -> "ChevronLeft"
                "list" -> "LayoutList"
                "spread" -> "Columns2"
                "hand" -> "Hand"
                "pen" -> "PenLine"
                "eraser" -> "Eraser"
                "shape" -> "Shapes"
                "image" -> "Image"
                "ruler" -> "Ruler"
                "undo" -> "Undo2"
                "redo" -> "Redo2"
                "book" -> "BookOpenText"
                "dictionary" -> "BookA"
                "more" -> "Ellipsis"
                "filter" -> "Funnel"
                "trash" -> "Trash2"
                "language" -> "Languages"
                "keyboard" -> "Keyboard"
                "add" -> "FilePlus2"
                else -> error("Unknown local tool icon")
            },
        )

    init {
        isClickable = true
        isFocusable = true
        setBackgroundResource(android.R.drawable.list_selector_background)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (selectionProgress > 0) {
            paint.color = selectedFill
            paint.alpha = (Color.alpha(selectedFill) * selectionProgress).toInt()
            val s = 28 * unit
            canvas.drawRoundRect(
                RectF((width - s) / 2, (height - s) / 2, (width + s) / 2, (height + s) / 2),
                8 * unit,
                8 * unit,
                paint,
            )
            paint.alpha = 255
        }
        val s = (20 * unit).toInt()
        val visibleGlyph = if (annotationAction) annotateGlyph ?: glyph else glyph
        visibleGlyph.tint = if (isSelected) selectedTint else tint
        visibleGlyph.layout(0, 0, s, s)
        canvas.save()
        canvas.translate((width - s) / 2f, (height - s) / 2f)
        visibleGlyph.draw(canvas)
        canvas.restore()
        if (name == "pen" && isSelected) {
            paint.color = dotColor
            canvas.drawRoundRect(
                RectF(
                    width / 2f - 4 * unit,
                    height / 2f + 10 * unit,
                    width / 2f + 4 * unit,
                    height / 2f + 12 * unit,
                ),
                unit,
                unit,
                paint,
            )
        }
    }
}
