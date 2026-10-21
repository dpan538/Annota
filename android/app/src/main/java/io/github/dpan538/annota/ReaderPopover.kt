package io.github.dpan538.annota

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.view.*
import android.widget.*
import kotlin.math.*

/** Small, undimmed reader surfaces. Position follows the anchor and the visible IME bounds. */
class ReaderPopover(
    private val activity: Activity,
    private val anchor: View,
    theme: Int,
    english: Boolean,
    private val panelWidth: Float = 250f,
    buttonSize: Int = 68,
) {
    val ui =
        DesignScreen(activity, theme, buttonSize, english).apply {
            configure(
                activity.resources.displayMetrics.widthPixels,
                activity.resources.displayMetrics.heightPixels,
                UiSizing.scale(buttonSize),
            )
        }
    val palette = ui.palette
    val content =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(12f), ui.px(10f), ui.px(12f), ui.px(10f))
            isFocusableInTouchMode = true
        }
    private val scroller =
        ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
    private val shell =
        FrameLayout(activity).apply {
            background = ui.background(palette.color("panel"), 18f, palette.color("rule"))
            clipToOutline = true
            addView(scroller, FrameLayout.LayoutParams(-1, -1))
        }
    val popup =
        PopupWindow(shell, 1, 1, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = ui.px(12f).toFloat()
            isOutsideTouchable = true
            setTouchModal(false)
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            animationStyle = 0
        }
    private val host = activity.window.decorView
    private var selection: RectF? = null
    private var besideAnchor = false
    /** Ribbon alignment follows the actual reading viewport, including its thumbnail inset. */
    var centerOn: View? = null
    private var fixedBody: ScrollView? = null
    private var bodyHeightLimit = 280f
    private val observer = host.viewTreeObserver
    private val reposition = ViewTreeObserver.OnGlobalLayoutListener { position(true) }
    var onDismiss: () -> Unit = {}
    var onAvailableHeightChanged: (Int) -> Unit = {}

    /**
     * Keep dictionary controls fixed; only definitions scroll when the IME reduces available space.
     */
    fun scrollBody(body: ScrollView, maxHeight: Float = 280f) {
        fixedBody = body
        bodyHeightLimit = maxHeight
        scroller.removeView(content)
        shell.removeAllViews()
        shell.addView(content, FrameLayout.LayoutParams(-1, -1))
    }

    fun refreshSize() {
        if (popup.isShowing) position(true)
    }

    init {
        popup.setOnDismissListener {
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(reposition)
            (activity.getSystemService(Activity.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(content.windowToken, 0)
            onDismiss()
        }
    }

    fun label(value: String, size: Float = 16f, muted: Boolean = false): TextView =
        ui.label(value, size, if (muted) palette.color("muted") else palette.ink).apply {
            maxLines = Int.MAX_VALUE
            ellipsize = null
            setPadding(ui.px(5f), ui.px(5f), ui.px(5f), ui.px(5f))
        }

    fun header(title: String) {
        val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(label(title, 18f), LinearLayout.LayoutParams(0, ui.px(40f), 1f))
        row.addView(
            chip("close", ui.tr("关闭", "Close"), false) { popup.dismiss() },
            LinearLayout.LayoutParams(ui.px(36f), ui.px(36f)),
        )
        content.addView(row)
    }

    fun chip(glyph: String, description: String, selected: Boolean, action: () -> Unit): View =
        ui.action(description, action).apply {
            isSelected = selected
            background =
                ui.background(
                    if (selected) palette.color("side-active") else Color.TRANSPARENT,
                    10f,
                )
            val icon =
                ReaderGlyph(activity, glyph).apply {
                    tint = if (selected) palette.accent else palette.ink
                }
            addView(icon, FrameLayout.LayoutParams(ui.px(22f), ui.px(22f), Gravity.CENTER))
        }

    fun item(
        glyph: String,
        title: String,
        enabled: Boolean = true,
        danger: Boolean = false,
        selected: Boolean = false,
        action: () -> Unit,
    ) {
        val row =
            ui.action(title.removeSuffix(" ✓")) {
                popup.dismiss()
                action()
            }
        row.isEnabled = enabled
        row.isSelected = selected || title.endsWith(" ✓")
        if (row.isSelected) row.background = ui.background(palette.color("side-active"), 10f)
        row.alpha = if (enabled) 1f else .35f
        val tint =
            if (danger) 0xffba3e45.toInt() else if (row.isSelected) palette.accent else palette.ink
        if (glyph.isNotEmpty())
            row.addView(
                ReaderGlyph(activity, glyph).apply { this.tint = tint },
                FrameLayout.LayoutParams(ui.px(22f), ui.px(22f), Gravity.CENTER_VERTICAL).apply {
                    leftMargin = ui.px(8f)
                },
            )
        row.addView(
            ui.label(title, 16f, tint, 500),
            FrameLayout.LayoutParams(-1, -1).apply {
                leftMargin = ui.px(if (glyph.isEmpty()) 12f else 44f)
                rightMargin = ui.px(8f)
            },
        )
        content.addView(row, LinearLayout.LayoutParams(-1, ui.px(46f)))
    }

    fun show(bounds: RectF? = null, beside: Boolean = false): PopupWindow {
        selection = bounds
        besideAnchor = beside
        position(false)
        content.requestFocus()
        observer.addOnGlobalLayoutListener(reposition)
        return popup
    }

    private fun position(update: Boolean) {
        if (update && !popup.isShowing) return
        val frame = Rect().also { host.getWindowVisibleDisplayFrame(it) }
        val margin = ui.px(10f)
        val width = min(ui.px(panelWidth), frame.width() - 2 * margin).coerceAtLeast(1)
        // Anchor to layout bounds, not the button's transient press-scale transform.
        val location = IntArray(2)
        val parent = anchor.parent as? View
        if (parent != null) {
            parent.getLocationOnScreen(location)
            location[0] += anchor.left - parent.scrollX
            location[1] += anchor.top - parent.scrollY
        } else anchor.getLocationOnScreen(location)
        val top = max(frame.top + margin, location[1] + anchor.height + ui.px(8f))
        val available =
            if (fixedBody != null) frame.bottom - margin - top else frame.height() - 2 * margin
        onAvailableHeightChanged(available)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        var naturalHeight = content.measuredHeight
        fixedBody?.let { body ->
            val params = body.layoutParams as LinearLayout.LayoutParams
            params.height = 0
            params.weight = 0f
            content.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val chrome = content.measuredHeight
            body
                .getChildAt(0)
                .measure(
                    View.MeasureSpec.makeMeasureSpec(
                        width - content.paddingLeft - content.paddingRight,
                        View.MeasureSpec.EXACTLY,
                    ),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
            naturalHeight =
                chrome +
                    body.getChildAt(0).measuredHeight.coerceIn(ui.px(110f), ui.px(bodyHeightLimit))
            params.weight = 1f
        }
        val height = min(naturalHeight, available).coerceAtLeast(1)
        val range =
            selection?.let {
                RectF(it).apply { offset(location[0].toFloat(), location[1].toFloat()) }
            }
        val desiredX =
            if (besideAnchor) location[0] + anchor.width + margin
            else
                centerOn?.let { view ->
                    val position = IntArray(2).also { view.getLocationOnScreen(it) }
                    position[0] + view.width / 2 - width / 2
                }
                    ?: range?.let { it.centerX().roundToInt() - width / 2 }
                    ?: (location[0] + anchor.width / 2 - width / 2)
        val desiredY =
            if (besideAnchor) location[1]
            else
                range?.let {
                    if (it.top - height - margin >= frame.top) it.top.roundToInt() - height - margin
                    else it.bottom.roundToInt() + margin
                } ?: top
        val x =
            desiredX.coerceIn(
                frame.left + margin,
                max(frame.left + margin, frame.right - width - margin),
            )
        val y =
            desiredY.coerceIn(
                frame.top + margin,
                max(frame.top + margin, frame.bottom - height - margin),
            )
        if (update) popup.update(x, y, width, height)
        else {
            popup.width = width
            popup.height = height
            popup.showAtLocation(host, Gravity.TOP or Gravity.LEFT, x, y)
        }
    }
}

/** Extra menu glyphs drawn as paths, using the same stroke as the approved Lucide set. */
class ReaderGlyph(context: android.content.Context, private val name: String) : View(context) {
    var tint = Color.BLACK
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private val standard =
        when (name) {
            "hand" -> "Hand"
            "pen" -> "PenLine"
            "fountain" -> "PenTool"
            "eraser" -> "Eraser"
            "copy" -> "FilePlus2"
            "rotate" -> "RefreshCw"
            "trash" -> "Trash2"
            "dictionary" -> "BookA"
            "search" -> "Search"
            "keyboard" -> "Keyboard"
            "undo" -> "Undo2"
            "redo" -> "Redo2"
            "previous" -> "ChevronLeft"
            "next" -> "ChevronRight"
            "export" -> "Download"
            "share" -> "ExternalLink"
            "favorite" -> "Star"
            "info" -> "Info"
            "paper" -> "FilePlus2"
            else -> null
        }?.let { LucideIcon(context, it) }

    override fun onDraw(c: Canvas) {
        standard?.let {
            it.tint = tint
            it.layout(0, 0, width, height)
            it.draw(c)
            return
        }
        c.save()
        c.scale(width / 24f, height / 24f)
        paint.color = tint
        when (name) {
            "close" -> {
                c.drawLine(6f, 6f, 18f, 18f, paint)
                c.drawLine(18f, 6f, 6f, 18f, paint)
            }
            "pencil" -> {
                c.drawPath(
                    Path().apply {
                        moveTo(3f, 21f)
                        lineTo(4.5f, 15f)
                        lineTo(16.5f, 3f)
                        quadTo(18f, 1.5f, 20f, 3.5f)
                        quadTo(22.5f, 6f, 21f, 7.5f)
                        lineTo(9f, 19.5f)
                        close()
                        moveTo(4.5f, 15f)
                        lineTo(9f, 19.5f)
                        moveTo(15f, 4.5f)
                        lineTo(19.5f, 9f)
                    },
                    paint,
                )
            }
            "line" -> c.drawLine(5f, 19f, 19f, 5f, paint)
            "rectangle",
            "fit",
            "width" -> c.drawRoundRect(4f, 4f, 20f, 20f, 2f, 2f, paint)
            "ellipse" -> c.drawOval(3f, 5f, 21f, 19f, paint)
            "arrow" -> {
                c.drawLine(4f, 20f, 20f, 4f, paint)
                c.drawLine(10f, 4f, 20f, 4f, paint)
                c.drawLine(20f, 4f, 20f, 14f, paint)
            }
            "triangle" ->
                c.drawPath(
                    Path().apply {
                        moveTo(12f, 3f)
                        lineTo(22f, 21f)
                        lineTo(2f, 21f)
                        close()
                    },
                    paint,
                )
            "fountain" ->
                c.drawPath(
                    Path().apply {
                        moveTo(5f, 20f)
                        lineTo(7f, 7f)
                        lineTo(14f, 3f)
                        lineTo(21f, 10f)
                        lineTo(17f, 17f)
                        close()
                        moveTo(5f, 20f)
                        lineTo(13f, 12f)
                    },
                    paint,
                )
            "highlight" -> {
                c.drawLine(4f, 21f, 15f, 21f, paint)
                c.drawPath(
                    Path().apply {
                        moveTo(5f, 17f)
                        lineTo(16f, 3f)
                        lineTo(22f, 9f)
                        lineTo(11f, 20f)
                        close()
                    },
                    paint,
                )
            }
            else -> c.drawCircle(12f, 12f, 6f, paint)
        }
        c.restore()
    }
}
