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
    private val strokes = mutableListOf<MutableList<PointF>>()
    private var current: MutableList<PointF>? = null
    var onChanged: () -> Unit = {}
    val hasInk
        get() = strokes.any { it.size > 1 }

    private val ink =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xff20232a.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * unit
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    init {
        contentDescription = "Signature canvas"
        setBackgroundColor(Color.WHITE)
    }

    fun undo() {
        if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
        current = null
        changed()
    }

    fun clear() {
        strokes.clear()
        current = null
        changed()
    }

    private fun changed() {
        invalidate()
        onChanged()
    }

    private fun drawInk(canvas: Canvas) {
        strokes.forEach { points ->
            if (points.isEmpty()) return@forEach
            val path =
                Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                }
            canvas.drawPath(path, ink)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawInk(canvas)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pen =
            (0 until e.pointerCount).firstOrNull {
                e.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS ||
                    e.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER
            }
        val index = pen ?: 0
        if (
            pen != null &&
                e.actionMasked in
                    setOf(MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP) &&
                e.actionIndex != pen
        )
            return true
        val action =
            when (e.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN ->
                    if (pen != null) MotionEvent.ACTION_DOWN else e.actionMasked
                MotionEvent.ACTION_POINTER_UP ->
                    if (pen != null) MotionEvent.ACTION_UP else e.actionMasked
                else -> e.actionMasked
            }
        if (
            (pen == null && e.pointerCount > 1) ||
                e.actionMasked == MotionEvent.ACTION_CANCEL ||
                (android.os.Build.VERSION.SDK_INT >= 33 &&
                    e.flags and MotionEvent.FLAG_CANCELED != 0)
        ) {
            current?.let { strokes.remove(it) }
            current = null
            changed()
            return true
        }
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                current =
                    mutableListOf(
                        PointF(
                            e.getX(index).coerceIn(0f, width.toFloat()),
                            e.getY(index).coerceIn(0f, height.toFloat()),
                        )
                    )
                strokes.add(current!!)
            }
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP ->
                current?.let { points ->
                    repeat(e.historySize) { h ->
                        points.add(
                            PointF(
                                e.getHistoricalX(index, h).coerceIn(0f, width.toFloat()),
                                e.getHistoricalY(index, h).coerceIn(0f, height.toFloat()),
                            )
                        )
                    }
                    points.add(
                        PointF(
                            e.getX(index).coerceIn(0f, width.toFloat()),
                            e.getY(index).coerceIn(0f, height.toFloat()),
                        )
                    )
                    if (action == MotionEvent.ACTION_UP) {
                        current = null
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
        val points = strokes.flatten()
        val padding = 6f * unit
        val left = max(0f, points.minOf { it.x } - padding)
        val top = max(0f, points.minOf { it.y } - padding)
        val right = min(width.toFloat(), points.maxOf { it.x } + padding)
        val bottom = min(height.toFloat(), points.maxOf { it.y } + padding)
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
