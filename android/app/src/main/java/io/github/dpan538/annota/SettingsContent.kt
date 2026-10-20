package io.github.dpan538.annota

import android.content.Context
import android.graphics.*
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import kotlin.math.*

class SettingsContent(
    val tab: String,
    val haptics: Boolean,
    val doubleTap: String,
    val twoFinger: String,
    val threeFinger: String,
    val onEvent: (String) -> Unit,
) {
    fun render(ui: LibraryScreen, parent: FrameLayout, width: Float): Float =
        with(ui) {
            val size = buttonSize.toFloat()
            val tabHeight = size + if (portrait) 8f else 0f
            val gap = 8f
            val tabWidth = (width - gap * 3) / 4
            listOf(
                    Triple("appearance", "Palette", tr("外观", "Appearance")),
                    Triple("stylus", "PenTool", tr("手写笔", "Stylus")),
                    Triple("gestures", "Hand", tr("手势", "Gestures")),
                    Triple("about", "Info", tr("关于", "About")),
                )
                .forEachIndexed { i, item ->
                    val b = action(item.third) { onEvent("tab:${item.first}") }
                    val selected = tab == item.first
                    b.isSelected = selected
                    val tint = if (selected) palette.accent else palette.color("muted")
                    if (selected) b.background = background(palette.color("side-active"), 12f)
                    if (portrait) {
                        val iconSize = if (english) 20f else 22f
                        box(
                            b,
                            icon(item.second, tint),
                            (tabWidth - iconSize) / 2,
                            11f,
                            iconSize,
                            iconSize,
                        )
                        box(
                            b,
                            label(item.third, if (english) 13f else 14f, tint, 500).apply {
                                gravity = Gravity.CENTER
                            },
                            0f,
                            38f,
                            tabWidth,
                            22f,
                        )
                    } else {
                        val iconSize = if (english) 20f else 22f
                        val caption = label(item.third, if (english) 15f else 16f, tint, 500)
                        val textWidth = caption.paint.measureText(item.third) / unit + 2f
                        val start = (tabWidth - iconSize - 10f - textWidth) / 2
                        box(
                            b,
                            icon(item.second, tint),
                            start,
                            (tabHeight - iconSize) / 2,
                            iconSize,
                            iconSize,
                        )
                        box(b, caption, start + iconSize + 10f, 0f, textWidth, tabHeight)
                    }
                    box(parent, b, i * (tabWidth + gap), 6f, tabWidth, tabHeight)
                }
            box(
                parent,
                View(context).apply { setBackgroundColor(palette.color("rule")) },
                0f,
                6f + tabHeight + 16f,
                width,
                .7f,
            )
            var y = 6f + tabHeight + 37f
            fun intro(title: String, detail: String) {
                box(parent, label(title, 22f, palette.ink, 500), 0f, y, width, 31f)
                box(parent, label(detail, 13f, palette.color("muted")), 0f, y + 38f, width, 21f)
                y += 77f
            }
            fun preference(
                title: String,
                detail: String,
                value: String,
                key: String,
                toggle: Boolean = false,
            ) {
                val row =
                    FrameLayout(context).apply {
                        background = background(palette.color("panel"), 14f, palette.color("rule"))
                    }
                val h = if (portrait) 132f else 112f
                val selectorWidth = if (toggle) 68f else if (portrait) 174f else 224f
                val copyWidth = width - selectorWidth - 72f
                box(row, label(title, 18f, palette.ink, 500), 24f, 24f, copyWidth, 27f)
                box(
                    row,
                    label(detail, 13f, palette.color("muted")).apply {
                        maxLines = 2
                        gravity = Gravity.TOP
                    },
                    24f,
                    57f,
                    copyWidth,
                    44f,
                )
                val selector = action(title) { onEvent(key) }
                if (toggle) {
                    val switch =
                        SwitchDrawing(context, palette.accent, palette.color("rule"), haptics)
                    box(selector, switch, 8f, (size - 30f) / 2, 52f, 30f)
                } else {
                    selector.background = background(palette.paper, 10f, palette.color("rule"))
                    box(selector, label(value, 15f), 16f, 0f, selectorWidth - 52f, size)
                    box(
                        selector,
                        icon("ChevronRight", palette.color("muted")).apply { rotation = 90f },
                        selectorWidth - 32f,
                        (size - 18f) / 2,
                        18f,
                        18f,
                    )
                }
                box(row, selector, width - selectorWidth - 24f, (h - size) / 2, selectorWidth, size)
                box(parent, row, 0f, y, width, h)
                y += h + 14f
            }
            fun fontCredit() {
                box(parent, icon("Type", palette.color("muted")), 0f, y + 18f, 18f, 18f)
                box(
                    parent,
                    label(tr("本界面使用 MiSans 字体", "Set in MiSans"), 12f, palette.color("muted")),
                    27f,
                    y + 15f,
                    width - 27f,
                    24f,
                )
                y += 57f
            }
            when (tab) {
                "appearance" -> {
                    intro(
                        tr("外观", "Appearance"),
                        tr("选择喜欢的配色，点选即应用。", "Choose a palette. Changes apply immediately."),
                    )
                    val columns = if (portrait) 2 else 4
                    val themeGap = if (portrait) 22f else 18f
                    val cw = (width - (columns - 1) * themeGap) / columns
                    val ph = cw / if (portrait) 1.8f else 1.4f
                    val names =
                        listOf(
                            tr("松间", "Pine"),
                            tr("晴昼", "Daylight"),
                            tr("蓝调", "Blue Note"),
                            tr("原野", "Terrain"),
                        )
                    val details =
                        listOf(
                            tr("松绿 · 雾蓝", "Pine · Mist blue"),
                            tr("明黄 · 石墨", "Yellow · Graphite"),
                            tr("钴蓝 · 鼠尾草", "Cobalt · sage"),
                            tr("暖棕 · 橙蓝", "Kraft · orange"),
                        )
                    repeat(4) { i ->
                        val card = action(names[i]) { onEvent("theme:$i") }
                        val selected = theme == i
                        card.isSelected = selected
                        val preview = ThemePreview(context, i)
                        preview.background =
                            background(
                                UiPalette(context, i).paper,
                                12f,
                                if (selected) palette.accent else palette.color("rule"),
                            )
                        preview.clipToOutline = true
                        box(card, preview, 0f, 0f, cw, ph)
                        if (selected) {
                            val outline =
                                View(context).apply {
                                    background =
                                        background(
                                            android.graphics.Color.TRANSPARENT,
                                            12f,
                                            palette.accent,
                                        )
                                }
                            box(card, outline, 0f, 0f, cw, ph)
                        }
                        box(
                            card,
                            label(names[i], 19f, palette.ink, 500),
                            0f,
                            ph + 16f,
                            cw - 30f,
                            27f,
                        )
                        box(
                            card,
                            label(details[i], 11f, palette.color("muted")),
                            0f,
                            ph + 48f,
                            cw,
                            17f,
                        )
                        val check =
                            FrameLayout(context).apply {
                                background =
                                    background(
                                        if (selected) palette.accent
                                        else android.graphics.Color.TRANSPARENT,
                                        12f,
                                        if (selected) palette.accent else palette.color("rule"),
                                    )
                            }
                        if (selected) box(check, icon("Check", palette.paper), 4f, 4f, 15f, 15f)
                        box(card, check, cw - 23f, ph + 18f, 23f, 23f)
                        box(
                            parent,
                            card,
                            (i % columns) * (cw + themeGap),
                            y + (i / columns) * (ph + 85f),
                            cw,
                            ph + 65f,
                        )
                    }
                    y += ceil(4f / columns) * (ph + 85f) - 20f + 26f
                    box(
                        parent,
                        View(context).apply { setBackgroundColor(palette.color("rule")) },
                        0f,
                        y,
                        width,
                        .7f,
                    )
                    y += 22f
                    box(
                        parent,
                        label(tr("按键大小", "Button size"), 19f, palette.ink, 500),
                        0f,
                        y,
                        if (portrait) width else width - 305f,
                        28f,
                    )
                    box(
                        parent,
                        label(
                            tr("图标、文字和点击区域一起放大。", "Icons, text and touch targets scale together."),
                            13f,
                            palette.color("muted"),
                        ),
                        0f,
                        y + 35f,
                        if (portrait) width else width - 305f,
                        21f,
                    )
                    val sy = if (portrait) y + 76f else y
                    val sw = if (portrait) (width - 20f) / 3 else 86f
                    val sx = if (portrait) 0f else width - 278f
                    intArrayOf(60, 68, 76).forEachIndexed { i, n ->
                        val b = action("$n") { onEvent("size:$n") }
                        val selected = buttonSize == n
                        b.isSelected = selected
                        b.background =
                            background(
                                if (selected) palette.color("side-active")
                                else palette.color("panel"),
                                12f,
                                if (selected) palette.accent else palette.color("rule"),
                            )
                        val tint = if (selected) palette.accent else palette.ink
                        box(
                            b,
                            label(n.toString(), 16f, tint, 500).apply { gravity = Gravity.CENTER },
                            0f,
                            10f,
                            sw,
                            24f,
                        )
                        box(
                            b,
                            label(
                                    listOf(
                                        tr("标准", "Standard"),
                                        tr("放大", "Larger"),
                                        tr("大号", "Largest"),
                                    )[i],
                                    11f,
                                    tint,
                                )
                                .apply { gravity = Gravity.CENTER },
                            0f,
                            37f,
                            sw,
                            17f,
                        )
                        box(parent, b, sx + i * (sw + 10f), sy, sw, size)
                    }
                    y = sy + size
                    fontCredit()
                }
                "stylus" -> {
                    intro(
                        tr("手写笔", "Stylus"),
                        tr("支持小米手写笔，具体功能随型号和系统而异。", "Make writing and tool switching feel natural."),
                    )
                    preference(
                        tr("震动反馈", "Haptic feedback"),
                        tr("支持的手写笔切换工具时，提供轻微震动提示。", "A light cue when switching tools."),
                        "",
                        "haptics",
                        true,
                    )
                    preference(
                        tr("轻点两下切换", "Double tap"),
                        tr("轻点笔身两下时执行的操作。", "Action when the pen provides a double-tap event."),
                        when (doubleTap) {
                            "previous-tool" -> tr("上一个工具", "Previous tool")
                            "none" -> tr("无操作", "None")
                            else -> tr("画笔 / 橡皮擦", "Pen / eraser")
                        },
                        "doubleTap",
                    )
                    val info =
                        action(tr("查看实际设备能力", "Observed device capabilities")) { onEvent("device") }
                    box(
                        info,
                        label(tr("查看实际设备能力", "Observed device capabilities"), 13f, palette.accent),
                        0f,
                        0f,
                        width,
                        36f,
                    )
                    box(parent, info, 0f, y, width, 36f)
                    y += 50f
                }
                "gestures" -> {
                    intro(
                        tr("手势", "Gestures"),
                        tr("用指尖完成常用操作。", "Common actions at your fingertips."),
                    )
                    fun actionName(key: String): String {
                        return when (key) {
                            "redo" -> tr("重做", "Redo")
                            "toolbar" -> tr("显示 / 隐藏工具栏", "Toggle toolbar")
                            "none" -> tr("无操作", "None")
                            else -> tr("撤销", "Undo")
                        }
                    }
                    preference(
                        tr("双指点击", "Two-finger tap"),
                        tr("两根手指同时轻点页面。", "Tap the page with two fingers."),
                        actionName(twoFinger),
                        "twoFinger",
                    )
                    preference(
                        tr("三指点击", "Three-finger tap"),
                        tr("三根手指同时轻点页面。", "Tap the page with three fingers."),
                        actionName(threeFinger),
                        "threeFinger",
                    )
                }
                else -> {
                    box(
                        parent,
                        label("Annota", 52f, palette.ink, 500).apply { letterSpacing = -.038f },
                        0f,
                        y + 28f,
                        width,
                        63f,
                    )
                    box(
                        parent,
                        label(
                            tr("源自 Annotation · 批注", "From Annotation"),
                            14f,
                            palette.color("muted"),
                        ),
                        0f,
                        y + 103f,
                        width,
                        22f,
                    )
                    box(
                        parent,
                        label(
                                tr(
                                    "专为阅读，让思考落在笔尖。",
                                    "Read with focus. Put your thoughts on the page.",
                                ),
                                22f,
                            )
                            .apply {
                                maxLines = 2
                                gravity = Gravity.TOP
                            },
                        0f,
                        y + 157f,
                        min(width, 560f),
                        75f,
                    )
                    y += 272f
                    preference(
                        tr("应用更新", "App update"),
                        "${tr("当前版本","Current version")} · ${BuildConfig.VERSION_NAME}",
                        tr("检查更新", "Check update"),
                        "check-update",
                    )
                    fontCredit()
                    listOf(
                            "backup" to tr("导出本地备份", "Export local backup"),
                            "restore" to tr("恢复本地备份", "Restore local backup"),
                            "licenses" to tr("开源与词库许可", "Open-source licenses"),
                            "github" to tr("GitHub 项目主页", "GitHub repository"),
                            "data" to tr("本地数据与卸载", "Local data and uninstall"),
                        )
                        .forEach { (key, title) ->
                            val b = action(title) { onEvent(key) }
                            b.background =
                                background(palette.color("panel"), 12f, palette.color("rule"))
                            box(b, label(title, 14f, palette.ink), 16f, 0f, width - 32f, 60f)
                            box(parent, b, 0f, y, width, 60f)
                            y += 72f
                        }
                }
            }
            y + 24f
        }
}

private class SwitchDrawing(
    context: Context,
    val activeColor: Int,
    val inactiveColor: Int,
    val checked: Boolean,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val s = width / 52f
        c.save()
        c.scale(s, s)
        paint.color = if (checked) activeColor else inactiveColor
        c.drawRoundRect(RectF(0f, 0f, 52f, 30f), 15f, 15f, paint)
        paint.color = Color.WHITE
        c.drawCircle(if (checked) 37f else 15f, 15f, 11f, paint)
        c.restore()
    }
}

private class ThemePreview(context: Context, index: Int) : View(context) {
    private val colors = UiPalette(context, index)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val sx = w * .22f
        val u = w / 186f
        fun rect(x: Float, y: Float, ww: Float, hh: Float, color: Int) {
            p.color = color
            c.drawRoundRect(RectF(x, y, x + ww, y + hh), 2 * u, 2 * u, p)
        }
        c.drawColor(colors.paper)
        p.color = colors.color("side")
        c.drawRect(0f, 0f, sx, h, p)
        rect(6 * u, 15 * u, sx - 12 * u, 4 * u, colors.ink)
        for (i in 0..2) rect(6 * u, (37 + i * 10) * u, sx * .65f, 3 * u, colors.color("side-rule"))
        rect(6 * u, h - 16 * u, sx * .65f, 3 * u, colors.color("side-rule"))
        val left = sx + 9 * u
        val mw = w - left - 9 * u
        rect(left, 13 * u, mw * .28f, 5 * u, colors.ink)
        val aw = (mw - 8 * u) / 2.8f
        rect(left, 27 * u, aw, 19 * u, colors.color("orange"))
        rect(left + aw + 4 * u, 27 * u, aw, 19 * u, colors.color("blue"))
        rect(left + 2 * aw + 8 * u, 27 * u, aw * .8f, 19 * u, colors.color("panel"))
        rect(left, 55 * u, (mw - 4 * u) / 2, 12 * u, colors.color("panel"))
        rect(left + (mw + 4 * u) / 2, 55 * u, (mw - 4 * u) / 2, 12 * u, colors.color("panel"))
        for (i in 0..2) {
            val nw = (mw - 10 * u) / 3
            rect(left + i * (nw + 5 * u), 76 * u, nw, h - 85 * u, colors.color("panel"))
            rect(
                left + i * (nw + 5 * u),
                76 * u,
                nw,
                4 * u,
                if (i == 0) colors.color("orange")
                else if (i == 1) colors.color("blue") else colors.ink,
            )
        }
    }
}
