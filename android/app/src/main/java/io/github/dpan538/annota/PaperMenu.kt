package io.github.dpan538.annota

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.text.InputFilter
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import kotlin.math.*

/** Anchored paper chooser shared by note creation, insertion and template changes. */
class PaperMenu(
    private val activity: Activity,
    private val anchor: View,
    theme: Int,
    buttonSize: Int,
    english: Boolean,
    title: String,
    name: String?,
    initialStyle: String = "blank",
    initialPaper: String = "white",
    private val onChosen: (String, String, String) -> Unit,
) {
    private val ui =
        DesignScreen(activity, theme, buttonSize, english).apply {
            configure(
                activity.resources.displayMetrics.widthPixels,
                activity.resources.displayMetrics.heightPixels,
            )
        }
    private val palette = ui.palette
    private val styles = listOf("blank", "dots", "grid", "lined")
    private val papers = listOf("white", "gray", "black")
    private var style = initialStyle.takeIf { it in styles } ?: "blank"
    private var paper = initialPaper.takeIf { it in papers } ?: "white"
    private val content = FrameLayout(activity)
    private val shell = FrameLayout(activity)
    private val scroll = ScrollView(activity)
    private val popup = PopupWindow(shell, 1, 1, true)
    private val patternButtons = mutableListOf<FrameLayout>()
    private val colorButtons = mutableListOf<FrameLayout>()
    private val samples = mutableListOf<PaperSample>()
    private val field: EditText?
    private val panelWidth = min(440f, ui.designWidth - 32f)
    private val panelHeight = if (name == null) 372f else 436f
    private var submitted = false
    private val host = activity.window.decorView
    private val hostObserver = host.viewTreeObserver
    private val reposition = ViewTreeObserver.OnGlobalLayoutListener { position(true) }

    init {
        val shift = if (name == null) -64f else 0f
        shell.background = ui.background(palette.color("panel"), 20f, palette.color("rule"))
        shell.clipToOutline = true
        shell.isFocusableInTouchMode = true
        shell.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(content, FrameLayout.LayoutParams(ui.px(panelWidth), ui.px(panelHeight)))
        ui.box(content, ui.label(title, 21f, palette.ink, 500), 22f, 18f, panelWidth - 124f, 30f)
        val cancel = ui.action(ui.tr("取消", "Cancel")) { popup.dismiss() }
        ui.box(
            cancel,
            ui.label(ui.tr("取消", "Cancel"), 13f, palette.color("muted")).apply {
                gravity = Gravity.CENTER
            },
            0f,
            0f,
            60f,
            42f,
        )
        ui.box(content, cancel, panelWidth - 78f, 12f, 60f, 42f)
        field =
            name?.let {
                EditText(activity).apply {
                    setText(it)
                    hint = ui.tr("笔记名称", "Note name")
                    contentDescription = hint
                    typeface = UiFonts.get(activity)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * ui.unit)
                    setTextColor(palette.ink)
                    setSingleLine()
                    setSelectAllOnFocus(true)
                    filters = arrayOf(InputFilter.LengthFilter(200))
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    setPadding(ui.px(13f), 0, ui.px(13f), 0)
                    background = ui.background(palette.paper, 11f, palette.color("rule"))
                    setOnEditorActionListener { _, action, _ ->
                        if (action == EditorInfo.IME_ACTION_DONE) {
                            submit()
                            true
                        } else false
                    }
                    ui.box(content, this, 22f, 60f, panelWidth - 44f, 42f)
                }
            }
        ui.box(
            content,
            ui.label(ui.tr("纸张", "Paper"), 12f, palette.color("muted")),
            22f,
            115f + shift,
            panelWidth - 44f,
            20f,
        )
        val gap = 10f
        val cardWidth = (panelWidth - 44f - gap * 3) / 4
        val labels =
            listOf(
                ui.tr("空白", "Blank"),
                ui.tr("点阵", "Dots"),
                ui.tr("格子", "Grid"),
                ui.tr("横线", "Lined"),
            )
        styles.forEachIndexed { i, key ->
            val card =
                ui.action(labels[i]) {
                    style = key
                    updateSelection()
                }
            patternButtons += card
            val sample = PaperSample(activity, key, paper, palette.color("rule"))
            samples += sample
            ui.box(card, sample, 10f, 8f, cardWidth - 20f, 82f)
            ui.box(
                card,
                ui.label(labels[i], 13f).apply { gravity = Gravity.CENTER },
                0f,
                97f,
                cardWidth,
                24f,
            )
            ui.box(content, card, 22f + i * (cardWidth + gap), 142f + shift, cardWidth, 128f)
        }
        ui.box(
            content,
            ui.label(ui.tr("底色", "Background"), 12f, palette.color("muted")),
            22f,
            281f + shift,
            panelWidth - 44f,
            20f,
        )
        val colorWidth = (panelWidth - 64f) / 3
        val colorNames = listOf(ui.tr("白", "White"), ui.tr("灰", "Gray"), ui.tr("黑", "Black"))
        papers.forEachIndexed { i, key ->
            val button =
                ui.action(colorNames[i]) {
                    paper = key
                    updateSelection()
                }
            colorButtons += button
            ui.box(
                button,
                View(activity).apply {
                    background = ui.background(PaperSample.color(key), 20f, palette.color("rule"))
                },
                14f,
                12f,
                16f,
                16f,
            )
            ui.box(button, ui.label(colorNames[i], 13f), 40f, 0f, colorWidth - 46f, 40f)
            ui.box(content, button, 22f + i * (colorWidth + 10f), 309f + shift, colorWidth, 40f)
        }
        val createLabel =
            if (name == null) ui.tr("应用纸张", "Apply paper") else ui.tr("创建笔记", "Create note")
        val create = ui.action(createLabel) { submit() }
        create.background = ui.background(palette.accent, 12f)
        ui.box(
            create,
            ui.label(createLabel, 15f, palette.paper, 500).apply { gravity = Gravity.CENTER },
            0f,
            0f,
            panelWidth - 44f,
            46f,
        )
        ui.box(content, create, 22f, 369f + shift, panelWidth - 44f, 46f)
        updateSelection()
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = ui.px(14f).toFloat()
        popup.isOutsideTouchable = true
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        popup.softInputMode =
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        popup.animationStyle = if (UiMotion.enabled()) R.style.AnnotaPanelMotion else 0
        popup.setOnDismissListener {
            hostObserver.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(reposition)
        }
    }

    private fun submit() {
        val title = field?.text?.toString()?.trim().orEmpty()
        if (submitted || (field != null && title.isBlank())) {
            if (field != null && title.isBlank()) field.error = ui.tr("写一个名称", "Enter a name")
            return
        }
        submitted = true
        popup.dismiss()
        onChosen(style, paper, title)
    }

    private fun updateSelection() {
        patternButtons.forEachIndexed { i, view ->
            view.isSelected = styles[i] == style
            view.background =
                ui.background(
                    if (view.isSelected) palette.color("side-active") else Color.TRANSPARENT,
                    12f,
                    if (view.isSelected) palette.accent else palette.color("rule"),
                )
            samples[i].paper = paper
            samples[i].invalidate()
        }
        colorButtons.forEachIndexed { i, view ->
            view.isSelected = papers[i] == paper
            view.background =
                ui.background(
                    if (view.isSelected) palette.color("side-active") else Color.TRANSPARENT,
                    11f,
                    if (view.isSelected) palette.accent else palette.color("rule"),
                )
        }
    }

    private fun currentAnchor(view: View = host): View? {
        if (
            view.isAttachedToWindow &&
                view.contentDescription == anchor.contentDescription &&
                view.isClickable
        )
            return view
        if (view is ViewGroup)
            for (i in 0 until view.childCount) currentAnchor(view.getChildAt(i))?.let {
                return it
            }
        return null
    }

    private fun position(update: Boolean) {
        val origin = if (anchor.isAttachedToWindow) anchor else currentAnchor() ?: host
        val bounds = Rect()
        host.getWindowVisibleDisplayFrame(bounds)
        val margin = ui.px(12f)
        val w = min(ui.px(panelWidth), max(1, bounds.width() - 2 * margin))
        val h = min(ui.px(panelHeight), max(1, bounds.height() - 2 * margin))
        val location = IntArray(2)
        origin.getLocationOnScreen(location)
        val x =
            location[0].coerceIn(
                bounds.left + margin,
                max(bounds.left + margin, bounds.right - w - margin),
            )
        val below = location[1] + origin.height + ui.px(8f)
        val y =
            below.coerceIn(
                bounds.top + margin,
                max(bounds.top + margin, bounds.bottom - h - margin),
            )
        if (update) popup.update(x, y, w, h)
        else {
            popup.width = w
            popup.height = h
            popup.showAtLocation(host, Gravity.TOP or Gravity.LEFT, x, y)
        }
    }

    fun show(): PopupWindow {
        position(false)
        shell.requestFocus()
        hostObserver.addOnGlobalLayoutListener(reposition)
        return popup
    }
}

private class PaperSample(
    context: android.content.Context,
    val style: String,
    var paper: String,
    val rule: Int,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val box = RectF(1f, 1f, width - 1f, height - 1f)
        paint.color = color(paper)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(box, 5f, 5f, paint)
        paint.color = rule
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawRoundRect(box, 5f, 5f, paint)
        paint.style = Paint.Style.FILL
        paint.color = if (paper == "black") 0xff696b6c.toInt() else 0xffc5c8ca.toInt()
        val step = max(8f, width / 7f)
        var y = step
        while (y < height - 3) {
            if (style == "lined" || style == "grid")
                canvas.drawRect(4f, y, width - 4f, y + 1f, paint)
            if (style == "dots") {
                var x = step
                while (x < width - 3) {
                    canvas.drawCircle(x, y, 1.3f, paint)
                    x += step
                }
            }
            y += step
        }
        if (style == "grid") {
            var x = step
            while (x < width - 3) {
                canvas.drawRect(x, 4f, x + 1f, height - 4f, paint)
                x += step
            }
        }
    }

    companion object {
        fun color(paper: String) =
            when (paper) {
                "black" -> 0xff27282a.toInt()
                "gray" -> 0xffe9e9e9.toInt()
                else -> Color.WHITE
            }
    }
}
