package io.github.dpan538.annota

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.view.*
import android.widget.*
import kotlin.math.*

/** A temporary capture pad. Signatures are only retained after placement is confirmed. */
class SignaturePad(context: Context, private val unit: Float = 1f) : View(context) {
    private val strokes = mutableListOf<Mark>()
    private var current: Mark? = null
    private var activePointer = -1
    private var activePen = false
    private var awaitingPalmUp = false
    private val renderer = InkRenderer()
    var onChanged: () -> Unit = {}
    val hasInk
        get() = strokes.any { it.points.isNotEmpty() }

    init {
        contentDescription = "Signature canvas"
        setBackgroundColor(Color.WHITE)
    }

    fun undo() {
        if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
        current = null
        activePointer = -1
        renderer.invalidate()
        changed()
    }

    fun clear() {
        strokes.clear()
        current = null
        activePointer = -1
        renderer.invalidate()
        changed()
    }

    private fun changed() {
        invalidate()
        onChanged()
    }

    private fun drawInk(canvas: Canvas) {
        canvas.save()
        canvas.scale(unit, unit)
        strokes.forEach { renderer.draw(canvas, it) }
        canvas.restore()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawInk(canvas)
    }

    private fun cancelCurrent() {
        current?.let { pending -> strokes.removeAll { it === pending } }
        current = null
        activePointer = -1
    }

    private fun sample(e: MotionEvent, index: Int, history: Int = -1): Point {
        val x = if (history < 0) e.getX(index) else e.getHistoricalX(index, history)
        val y = if (history < 0) e.getY(index) else e.getHistoricalY(index, history)
        val pressure =
            if (!activePen) .65f
            else if (history < 0) e.getPressure(index) else e.getHistoricalPressure(index, history)
        return Point(
            x.coerceIn(0f, width.toFloat()) / unit,
            y.coerceIn(0f, height.toFloat()) / unit,
            pressure,
            if (history < 0) e.eventTime else e.getHistoricalEventTime(history),
        )
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (
            e.actionMasked == MotionEvent.ACTION_CANCEL ||
                (android.os.Build.VERSION.SDK_INT >= 33 &&
                    e.flags and MotionEvent.FLAG_CANCELED != 0 &&
                    (e.actionMasked != MotionEvent.ACTION_POINTER_UP ||
                        e.getPointerId(e.actionIndex) == activePointer))
        ) {
            cancelCurrent()
            awaitingPalmUp = e.pointerCount > 1
            changed()
            return true
        }
        val pen =
            (0 until e.pointerCount).firstOrNull {
                e.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS ||
                    e.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER
            }
        if (awaitingPalmUp && pen == null) {
            if (e.actionMasked == MotionEvent.ACTION_UP) awaitingPalmUp = false
            return true
        }
        val penDown =
            pen != null &&
                (e.actionMasked == MotionEvent.ACTION_DOWN ||
                    (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN && e.actionIndex == pen))
        if (penDown || e.actionMasked == MotionEvent.ACTION_DOWN) {
            // A palm can land and move before the pen. Roll back its provisional stroke rather
            // than leaving a stray line when the stylus takes over as pointer 1 or later.
            cancelCurrent()
            val index = pen ?: e.actionIndex
            activePen = pen != null
            activePointer = e.getPointerId(index)
            awaitingPalmUp = false
            if (activePen) requestUnbufferedDispatch(e)
            parent?.requestDisallowInterceptTouchEvent(true)
            current =
                Mark(
                    "pen",
                    0xff20232a.toInt(),
                    2.2f,
                    mutableListOf(sample(e, index)),
                    inkVersion = InkGeometry.VERSION,
                )
            strokes.add(current!!)
        } else if (pen == null && e.pointerCount > 1) {
            cancelCurrent()
            awaitingPalmUp = true
        } else {
            val index = e.findPointerIndex(activePointer)
            val ownUp =
                e.actionMasked == MotionEvent.ACTION_UP ||
                    (e.actionMasked == MotionEvent.ACTION_POINTER_UP &&
                        e.getPointerId(e.actionIndex) == activePointer)
            if (index >= 0 && (e.actionMasked == MotionEvent.ACTION_MOVE || ownUp)) {
                current?.points?.let { points ->
                    repeat(e.historySize) { points.add(sample(e, index, it)) }
                    points.add(sample(e, index))
                }
                if (ownUp) {
                    awaitingPalmUp = activePen && e.pointerCount > 1
                    current = null
                    activePointer = -1
                    performClick()
                }
            }
        }
        changed()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun bitmap(): Bitmap {
        require(hasInk)
        val points = strokes.flatMap { it.points }
        val padding = 6f * unit
        val left = max(0f, points.minOf { it.x } * unit - padding)
        val top = max(0f, points.minOf { it.y } * unit - padding)
        val right = min(width.toFloat(), points.maxOf { it.x } * unit + padding)
        val bottom = min(height.toFloat(), points.maxOf { it.y } * unit + padding)
        val result =
            Bitmap.createBitmap(
                max(1, ceil(right - left).toInt()),
                max(1, ceil(bottom - top).toInt()),
                Bitmap.Config.ARGB_8888,
            )
        Canvas(result).apply {
            translate(-left, -top)
            drawInk(this)
        }
        return result
    }
}

fun showSignaturePad(
    activity: Activity,
    theme: Int,
    english: Boolean,
    buttonSize: Int,
    ready: (Bitmap) -> Unit,
): Dialog {
    fun tr(zh: String, en: String) = if (english) en else zh
    val ui =
        DesignScreen(activity, theme, buttonSize, english).apply {
            configure(
                activity.resources.displayMetrics.widthPixels,
                activity.resources.displayMetrics.heightPixels,
                UiSizing.scale(buttonSize),
            )
        }
    val dialog = Dialog(activity)
    val body =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(20f), ui.px(18f), ui.px(20f), ui.px(18f))
            background = ui.background(ui.palette.color("panel"), 20f, ui.palette.color("rule"))
        }
    body.addView(ui.label(tr("手写签名", "Handwritten signature"), 20f, ui.palette.ink, 500))
    body.addView(
        ui.label(
                tr("在下方签字，再放到文档中。", "Sign below, then position it on the page."),
                14f,
                ui.palette.color("muted"),
            )
            .apply { setPadding(0, ui.px(8f), 0, ui.px(16f)) }
    )
    val pad =
        SignaturePad(activity, ui.unit).apply {
            contentDescription = tr("签名画布", "Signature canvas")
        }
    val height = min(ui.px(210f), (activity.resources.displayMetrics.heightPixels * .38f).toInt())
    body.addView(pad, LinearLayout.LayoutParams(-1, height))
    val buttons =
        LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.px(14f), 0, 0)
        }
    fun button(label: String, primary: Boolean = false, action: () -> Unit): View =
        ui.action(label, action).apply {
            if (primary) background = ui.background(ui.palette.accent, 10f)
            addView(
                ui.label(label, 15f, if (primary) Color.WHITE else ui.palette.ink, 500).apply {
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(-1, -1),
            )
        }
    buttons.addView(
        button(tr("取消", "Cancel")) { dialog.dismiss() },
        LinearLayout.LayoutParams(0, ui.px(44f), 1f),
    )
    buttons.addView(
        button(tr("撤销", "Undo")) { pad.undo() },
        LinearLayout.LayoutParams(0, ui.px(44f), 1f),
    )
    buttons.addView(
        button(tr("清空", "Clear")) { pad.clear() },
        LinearLayout.LayoutParams(0, ui.px(44f), 1f),
    )
    val insert =
        button(tr("放入文档", "Place signature"), true) {
            if (pad.hasInk) {
                val bitmap = pad.bitmap()
                dialog.dismiss()
                ready(bitmap)
            }
        }
    buttons.addView(insert, LinearLayout.LayoutParams(0, ui.px(44f), if (english) 1.8f else 1.5f))
    pad.onChanged = {
        insert.isEnabled = pad.hasInk
        insert.alpha = if (pad.hasInk) 1f else .4f
    }
    pad.onChanged()
    body.addView(buttons)
    dialog.setContentView(body)
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialog.show()
    dialog.window?.setLayout(
        min(ui.px(620f), activity.resources.displayMetrics.widthPixels - ui.px(32f)),
        -2,
    )
    return dialog
}
