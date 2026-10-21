package io.github.dpan538.annota

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.text.*
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.core.graphics.PathParser
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject

/** Values are generated from the approved design/home-ui.fragment.html, not a new theme. */
class UiPalette(context: Context, index: Int) {
    private val json =
        JSONArray(
                context.assets.open("design-palettes.json").bufferedReader().use { it.readText() }
            )
            .getJSONObject(index.coerceIn(0, 3))

    fun color(role: String): Int = Color.parseColor(json.getString(role))

    val paper
        get() = color("paper")

    val ink
        get() = color("ink")

    val accent
        get() = color("accent")
}

object UiFonts {
    private var regular: Typeface? = null
    private var medium: Typeface? = null

    fun get(context: Context, weight: Int = 400): Typeface {
        if (regular == null)
            regular =
                Typeface.Builder(context.assets, "fonts/MiSansVF.ttf")
                    .setFontVariationSettings("'wght' 400")
                    .setWeight(400)
                    .build()
        if (medium == null)
            medium =
                Typeface.Builder(context.assets, "fonts/MiSansVF.ttf")
                    .setFontVariationSettings("'wght' 500")
                    .setWeight(500)
                    .build()
        return if (weight >= 500) medium!! else regular!!
    }
}

class LucideIcon(context: Context, val iconName: String) : View(context) {
    var tint = Color.BLACK
    var stroke = 2f
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private val paths: List<Path>

    init {
        if (data == null)
            data =
                JSONObject(
                    context.assets.open("lucide-icons.json").bufferedReader().use { it.readText() }
                )
        val nodes = data!!.getJSONArray(iconName)
        paths =
            List(nodes.length()) { i ->
                val node = nodes.getJSONArray(i)
                val a = node.getJSONObject(1)
                fun f(k: String) = a.getString(k).toFloat()
                when (node.getString(0)) {
                    "path" -> PathParser.createPathFromPathData(a.getString("d"))!!
                    "circle" ->
                        Path().apply { addCircle(f("cx"), f("cy"), f("r"), Path.Direction.CW) }
                    "rect" ->
                        Path().apply {
                            val x = f("x")
                            val y = f("y")
                            val rx = a.optString("rx", "0").toFloat()
                            addRoundRect(
                                RectF(x, y, x + f("width"), y + f("height")),
                                rx,
                                rx,
                                Path.Direction.CW,
                            )
                        }
                    "line" ->
                        Path().apply {
                            moveTo(f("x1"), f("y1"))
                            lineTo(f("x2"), f("y2"))
                        }
                    "polyline",
                    "polygon" ->
                        Path().apply {
                            val v =
                                a.getString("points")
                                    .split(Regex("[ ,]+"))
                                    .filter { it.isNotEmpty() }
                                    .map { it.toFloat() }
                            moveTo(v[0], v[1])
                            for (j in 2 until v.size step 2) lineTo(v[j], v[j + 1])
                            if (node.getString(0) == "polygon") close()
                        }
                    "ellipse" ->
                        Path().apply {
                            addOval(
                                RectF(
                                    f("cx") - f("rx"),
                                    f("cy") - f("ry"),
                                    f("cx") + f("rx"),
                                    f("cy") + f("ry"),
                                ),
                                Path.Direction.CW,
                            )
                        }
                    else -> error("Unsupported local icon node")
                }
            }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width, height) / 24f
        canvas.save()
        canvas.translate((width - 24 * scale) / 2, (height - 24 * scale) / 2)
        canvas.scale(scale, scale)
        paint.color = tint
        paint.strokeWidth = stroke
        paths.forEach { canvas.drawPath(it, paint) }
        canvas.restore()
    }

    companion object {
        private var data: JSONObject? = null
    }
}

/** Native controls placed using the same units and layout rules as the approved HTML. */
open class DesignScreen(
    context: Context,
    val theme: Int,
    val buttonSize: Int,
    val english: Boolean,
) : FrameLayout(context) {
    val palette = UiPalette(context, theme)
    var unit = 1f
        protected set

    var portrait = false
        protected set

    var designWidth = 1024f
        protected set

    var designHeight = 682.6667f
        protected set

    fun tr(zh: String, en: String) = if (english) en else zh

    fun px(v: Float) = max(0, (v * unit).roundToInt())

    fun box(parent: FrameLayout, view: View, x: Float, y: Float, w: Float, h: Float): View {
        parent.addView(
            view,
            LayoutParams(px(w), px(h)).apply {
                leftMargin = px(x)
                topMargin = px(y)
            },
        )
        return view
    }

    fun background(fill: Int, radius: Float = 14f, border: Int? = null) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius * unit
            if (border != null) setStroke(max(1, px(1f)), border)
        }

    fun label(value: String, size: Float = 16f, color: Int = palette.ink, weight: Int = 400) =
        TextView(context).apply {
            text = value
            typeface = UiFonts.get(context, weight)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size * unit)
            setTextColor(color)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    fun icon(name: String, color: Int = palette.ink) =
        LucideIcon(context, name).apply { tint = color }

    fun action(label: String, onClick: () -> Unit): FrameLayout =
        UiButton(context).apply {
            isClickable = true
            isFocusable = true
            contentDescription = label
            tooltipText = label
            setOnClickListener { onClick() }
        }

    fun configure(width: Int, height: Int) {
        portrait = width < height
        designWidth = if (portrait) 682.6667f else 1024f
        unit = width / designWidth
        designHeight = height / unit
    }

    open fun render() {}

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            portrait = w < h
            designWidth = if (portrait) 682.6667f else 1024f
            unit = w / designWidth
            designHeight = h / unit
            render()
        }
    }
}

class LibraryScreen(
    context: Context,
    theme: Int,
    buttonSize: Int,
    english: Boolean,
    val section: String,
    val folder: String,
    val query: String,
    val pdfOnly: Boolean,
    val books: List<Book>,
    val folders: List<Pair<String, String>>,
    val pdf: PdfEngine,
    val onAction: (String) -> Unit,
    val onBook: (Book) -> Unit,
    val onBookMenu: (Book) -> Unit,
    val onFolder: (String) -> Unit,
    val onFolderMenu: (String) -> Unit,
    val onQuery: (String) -> Unit,
    val customBody: ((LibraryScreen, FrameLayout, Float) -> Float)? = null,
) : DesignScreen(context, theme, buttonSize, english) {
    private val covers = CoverRepository(context, pdf)
    private var searchText = query
    var searchVisible = false
    private lateinit var main: FrameLayout
    private lateinit var body: FrameLayout
    private var sideWidth = 176f
    private var mainWidth = 848f
    private var bodyY = 108f
    private val navHeight
        get() = buttonSize.toFloat() + if (portrait) 8f else 0f

    override fun onDetachedFromWindow() {
        covers.close()
        super.onDetachedFromWindow()
    }

    override fun render() {
        removeAllViews()
        setBackgroundColor(palette.paper)
        sideWidth = if (portrait) 104f else 176f
        mainWidth = designWidth - sideWidth
        val side = FrameLayout(context).apply { setBackgroundColor(palette.color("side")) }
        box(this, side, 0f, 0f, sideWidth, designHeight)
        box(
            side,
            View(context).apply { setBackgroundColor(palette.color("rule")) },
            sideWidth - 0.6f,
            0f,
            .6f,
            designHeight,
        )
        val brand =
            ImageView(context).apply {
                setImageResource(R.drawable.annota_wordmark)
                contentDescription = "Annota"
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        box(side, brand, if (portrait) 15f else 26f, 45.75f, if (portrait) 74f else 112f, 43f)
        fun nav(
            id: String,
            name: String,
            text: String,
            y: Float,
            selected: Boolean = false,
            border: Boolean = false,
        ) {
            val x = if (portrait) 10f else 14f
            val width = sideWidth - x * 2 - 1f
            val b = action(text) { onAction(id) }
            b.isSelected = selected
            b.background =
                background(
                    if (selected) palette.color("side-active") else Color.TRANSPARENT,
                    14f,
                    if (border) palette.color("side-rule") else null,
                )
            val color = if (selected) palette.accent else palette.color("side-ink")
            val iconSize = if (buttonSize == 76) 28f else if (buttonSize == 60) 24f else 26f
            if (portrait) {
                box(b, icon(name, color), (width - iconSize) / 2, 11f, iconSize, iconSize)
                box(
                    b,
                    label(text, if (buttonSize == 76) 14f else 13f, color, 500).apply {
                        gravity = Gravity.CENTER
                    },
                    0f,
                    43f,
                    width,
                    21f,
                )
            } else {
                box(b, icon(name, color), 16f, (navHeight - iconSize) / 2, iconSize, iconSize)
                box(
                    b,
                    label(text, if (buttonSize == 76) 17f else 16f, color, 500),
                    16f + iconSize + 10f,
                    0f,
                    width - 16f - iconSize - 20f,
                    navHeight,
                )
            }
            box(side, b, x, y, width, navHeight)
        }
        val navTop = if (portrait) 135f else 129f
        nav("search", "Search", tr("搜索", "Search"), navTop, border = true)
        nav(
            "home",
            "House",
            tr("主页", "Home"),
            navTop + navHeight + 10f,
            section in listOf("home", "notes") && folder.isEmpty(),
        )
        nav(
            "favorites",
            "Star",
            tr("收藏", "Favorites"),
            navTop + 2 * (navHeight + 10f),
            section == "favorites",
        )
        val footerHeight = if (portrait) 14f else 16.5f
        val bottom = if (portrait) 20f else 16f
        val bottomTop = designHeight - bottom - footerHeight - 8f - navHeight * 2
        nav("settings", "Settings2", tr("设置", "Settings"), bottomTop, section == "settings")
        nav("feedback", "MessageSquare", tr("意见反馈", "Feedback"), bottomTop + navHeight)
        box(
            side,
            label(
                    tr("本地书库", "LOCAL LIBRARY"),
                    if (portrait && english) 9f else if (portrait) 10f else 11f,
                    palette.color("muted"),
                )
                .apply {
                    letterSpacing = if (portrait && english) .08f else .12f
                    gravity = if (portrait) Gravity.CENTER else Gravity.CENTER_VERTICAL
                },
            if (portrait) 10f else 28f,
            designHeight - bottom - footerHeight,
            sideWidth - if (portrait) 20f else 42f,
            footerHeight,
        )
        val sc =
            ScrollView(context).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                clipToPadding = false
            }
        box(this, sc, sideWidth, 0f, mainWidth, designHeight)
        main = FrameLayout(context)
        sc.addView(main, FrameLayout.LayoutParams(-1, -2))
        val top = if (portrait) 50f - max(62f, buttonSize.toFloat()) / 2 else 56f - buttonSize / 2f
        val heading = FrameLayout(context)
        box(
            main,
            heading,
            24f,
            top,
            mainWidth - 48f + max(0f, (buttonSize - 34f) / 2),
            buttonSize.toFloat(),
        )
        val title =
            folders.firstOrNull { it.first == folder }?.second
                ?: when (section) {
                    "trash" -> tr("废纸篓", "Trash")
                    "favorites" -> tr("收藏", "Favorites")
                    "settings" -> tr("设置", "Settings")
                    else -> tr("主页", "Home")
                }
        val headingSize = if (portrait) 36f else 32f
        val headingHeight = 15f + 2f + headingSize * 1.25f
        val headingY = (buttonSize - headingHeight) / 2
        box(
            heading,
            label(if (section == "settings") "SETTINGS" else "LIBRARY", 10f, palette.color("muted"))
                .apply { letterSpacing = .2f },
            0f,
            headingY,
            mainWidth - 250f,
            15f,
        )
        box(
            heading,
            label(title, headingSize, palette.ink, 500).apply { letterSpacing = -.025f },
            0f,
            headingY + 17f,
            mainWidth - 235f,
            headingSize * 1.25f,
        )
        val utility = max(60f, buttonSize - 8f)
        val right = mainWidth - 48f + (utility - 26f) / 2
        listOf(
                Triple("filter", "Funnel", tr("筛选", "Filter")),
                Triple("trash", "Trash2", tr("废纸篓", "Trash")),
                Triple("language", "Languages", "中文 / English"),
            )
            .filter { section != "settings" || it.first == "language" }
            .forEachIndexed { i, item ->
                val b = action(item.third) { onAction(item.first) }
                box(b, icon(item.second), (utility - 26f) / 2, (utility - 26f) / 2, 26f, 26f)
                box(
                    heading,
                    b,
                    right - ((if (section == "settings") 1 else 3) - i) * utility,
                    (buttonSize - utility) / 2,
                    utility,
                    utility,
                )
            }
        bodyY = top + buttonSize + if (portrait) 22f else 18f
        body = FrameLayout(context)
        box(main, body, 24f, bodyY, mainWidth - 48f, 1f)
        renderBody()
    }

    fun showSearch() {
        searchVisible = !searchVisible
        renderBody()
    }

    private fun renderBody() {
        body.removeAllViews()
        if (customBody != null) {
            val h = customBody.invoke(this, body, mainWidth - 48f)
            body.layoutParams = body.layoutParams.apply { height = px(h) }
            main.layoutParams =
                main.layoutParams.apply {
                    height =
                        max(
                            px(designHeight),
                            (body.layoutParams as LayoutParams).topMargin + px(h) + 24,
                        )
                }
            return
        }
        val width = mainWidth - 48f
        var y = 0f
        val sections = FrameLayout(context)
        if (searchVisible || searchText.isNotEmpty()) {
            val field =
                FrameLayout(context).apply { background = background(palette.color("panel"), 12f) }
            box(
                field,
                icon("Search", palette.color("muted")),
                16f,
                (buttonSize - 26f) / 2,
                26f,
                26f,
            )
            val edit =
                EditText(context).apply {
                    setSingleLine()
                    setText(searchText)
                    setSelection(text.length)
                    hint = tr("搜索笔记与文件名", "Search notes and files")
                    typeface = UiFonts.get(context)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f * unit)
                    setTextColor(palette.ink)
                    setHintTextColor(palette.color("muted"))
                    background = null
                    setPadding(0, 0, 0, 0)
                    filters = arrayOf(InputFilter.LengthFilter(80))
                }
            box(field, edit, 54f, 0f, width - 70f, buttonSize.toFloat())
            box(body, field, 0f, 0f, width, buttonSize.toFloat())
            y += buttonSize + 16f
            edit.addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int,
                    ) {}

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int,
                    ) {
                        searchText = s.toString()
                        onQuery(searchText)
                        renderSections(sections, width)
                    }

                    override fun afterTextChanged(e: Editable?) {}
                }
            )
        }
        val createY = y
        val gap = if (portrait) 10f else 12f
        val available = width - gap * 2
        val widths =
            if (portrait) listOf(available / 3, available / 3, available / 3)
            else listOf(available * 1.12f / 3.1f, available * 1.12f / 3.1f, available * .86f / 3.1f)
        val height = if (portrait) 120f else 94f
        val items =
            listOf(
                arrayOf("new-note", "Plus", tr("新建笔记", "New note"), tr("空白手写本", "Blank notebook")),
                arrayOf(
                    "import",
                    "FileUp",
                    tr("导入文件", "Import file"),
                    tr("PDF · 本地文件", "PDF · local files"),
                ),
                arrayOf(
                    "new-folder",
                    "FolderPlus",
                    tr("新建文件夹", "New folder"),
                    tr("分类与整理", "Sort and organize"),
                ),
            )
        var x = 0f
        items.forEachIndexed { i, a ->
            val b = action(a[2]) { onAction(a[0]) }
            val foreground =
                when (i) {
                    0 -> palette.color("action-ink")
                    1 -> palette.color("import-ink")
                    else -> palette.ink
                }
            b.background =
                background(
                    when (i) {
                        0 -> palette.color("orange")
                        1 -> palette.color("blue")
                        else -> palette.color("panel")
                    },
                    14f,
                    if (i == 2) palette.color("rule") else null,
                )
            if (portrait) {
                box(b, icon(a[1], foreground), 15f, 15f, 26f, 26f)
                box(b, label(a[2], 16f, foreground, 500), 15f, 65f, widths[i] - 30f, 24f)
                box(b, label(a[3], 10f, foreground), 15f, 92f, widths[i] - 30f, 15f)
            } else {
                box(b, icon(a[1], foreground), 18f, 34f, 26f, 26f)
                box(b, label(a[2], 18f, foreground, 500), 58f, 24f, widths[i] - 76f, 27f)
                box(b, label(a[3], 11f, foreground), 58f, 54f, widths[i] - 76f, 16.5f)
            }
            box(body, b, x, createY, widths[i], height)
            x += widths[i] + gap
        }
        y += height + if (portrait) 26f else 28f
        box(body, sections, 0f, y, width, 1f)
        renderSections(sections, width)
    }

    private fun renderSections(target: FrameLayout, width: Float) {
        target.removeAllViews()
        var y = 0f
        if (section != "trash" && folders.isNotEmpty()) {
            sectionTitle(
                target,
                tr("文件夹", "Folders"),
                tr("${folders.size} 个", "${folders.size}"),
                0f,
                width,
            )
            y += 34f
            val gap = if (portrait) 10f else 12f
            val fw = (width - gap) / 2
            folders.forEachIndexed { i, f ->
                val b = action(f.second) { onFolder(f.first) }
                b.background =
                    background(
                        if (folder == f.first) palette.color("side-active")
                        else palette.color("panel"),
                        11f,
                        if (folder == f.first) palette.accent else palette.color("rule"),
                    )
                val size = if (portrait) 24f else 26f
                box(
                    b,
                    icon("Folder", palette.accent),
                    if (portrait) 12f else 14f,
                    (buttonSize - size) / 2,
                    size,
                    size,
                )
                box(
                    b,
                    label(f.second, if (portrait) 15f else 14f, palette.ink, 500),
                    if (portrait) 46f else 52f,
                    0f,
                    fw - 110f,
                    buttonSize.toFloat(),
                )
                val n = books.count { !it.trash && it.folder == f.first }
                box(
                    b,
                    label(tr("$n 份", "$n files"), 12f, palette.color("muted")).apply {
                        gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                    },
                    fw - 62f,
                    0f,
                    48f,
                    buttonSize.toFloat(),
                )
                b.setOnLongClickListener {
                    onFolderMenu(f.first)
                    true
                }
                box(
                    target,
                    b,
                    (i % 2) * (fw + gap),
                    y + (i / 2) * (buttonSize + gap),
                    fw,
                    buttonSize.toFloat(),
                )
            }
            y += ceil(folders.size / 2f) * (buttonSize + gap) - gap + if (portrait) 28f else 26f
        }
        if (folder.isNotEmpty()) {
            val back = action(tr("返回主页", "Back to Home")) { onFolder("") }
            box(back, icon("ChevronLeft", palette.accent), 0f, 6f, 20f, 20f)
            box(
                back,
                label(tr("返回主页", "Back to Home"), 13f, palette.accent),
                24f,
                0f,
                width - 24f,
                32f,
            )
            box(target, back, 0f, y, width, 32f)
            y += 40f
        }
        val title =
            when (section) {
                "favorites" -> tr("收藏的笔记", "Favorites")
                "trash" -> tr("废纸篓", "Trash")
                else ->
                    if (searchText.isBlank()) tr("我的笔记", "My notes")
                    else tr("搜索结果", "Search results")
            }
        sectionTitle(target, title, tr("最近打开", "Recently opened"), y, width)
        y += 34f
        val docs =
            books
                .filter { b ->
                    b.trash == (section == "trash") &&
                        (section != "favorites" || b.favorite) &&
                        (section != "notes" || b.source.isEmpty()) &&
                        (!pdfOnly || b.source.isNotEmpty()) &&
                        (folder.isEmpty() || b.folder == folder)
                }
                .filter { b ->
                    searchText.isBlank() ||
                        b.title.contains(searchText, true) ||
                        (searchText.length >= 3 &&
                            b.title.lowercase().split(Regex("\\W+")).any {
                                Dictionary.distance(it, searchText.lowercase()) <= 1
                            })
                }
        val cols = if (portrait) 2 else 4
        val gap = if (portrait) 18f else 16f
        val cw = (width - gap * (cols - 1)) / cols
        val cardHeight = if (portrait) 247.5f else 237f
        docs.forEachIndexed { i, b ->
            val card = action(b.title) { onBook(b) }
            card.setOnLongClickListener {
                onBookMenu(b)
                true
            }
            val thumbnail =
                FrameLayout(context).apply {
                    background = background(palette.color("thumb"), 11f)
                    clipToOutline = true
                }
            box(card, thumbnail, 0f, 0f, cw, 182f)
            box(thumbnail, DocumentCoverView(context, b, covers), 0f, 0f, cw, 182f)
            box(
                card,
                label(
                    (if (b.favorite) "★ " else "") + b.title,
                    if (portrait) 17f else 16f,
                    palette.ink,
                    500,
                ),
                0f,
                if (portrait) 194f else 192f,
                cw,
                if (portrait) 25.5f else 24f,
            )
            val date =
                if (b.opened == 0L) "--:--"
                else SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.ROOT).format(Date(b.opened))
            box(
                card,
                label(date, if (portrait) 13f else 12f, palette.color("muted")),
                0f,
                if (portrait) 223.5f else 219f,
                cw,
                if (portrait) 19.5f else 18f,
            )
            box(
                target,
                card,
                (i % cols) * (cw + gap),
                y + (i / cols) * (cardHeight + 22f),
                cw,
                cardHeight,
            )
        }
        if (docs.isEmpty())
            box(
                target,
                label(tr("没有文件", "No documents"), 16f, palette.color("muted")).apply {
                    gravity = Gravity.CENTER
                },
                0f,
                y,
                width,
                100f,
            )
        y +=
            if (docs.isEmpty()) 100f
            else ceil(docs.size / cols.toFloat()) * (cardHeight + 22f) - 22f
        target.layoutParams = target.layoutParams.apply { height = px(y + 24f) }
        body.layoutParams =
            body.layoutParams.apply {
                height = (target.layoutParams as LayoutParams).topMargin + px(y + 24f)
            }
        main.layoutParams =
            main.layoutParams.apply {
                height =
                    max(
                        px(designHeight),
                        (body.layoutParams as LayoutParams).topMargin + body.layoutParams.height,
                    )
            }
    }

    private fun sectionTitle(
        parent: FrameLayout,
        title: String,
        detail: String,
        y: Float,
        width: Float,
    ) {
        box(parent, label(title, 17f, palette.ink, 500), 0f, y, width - 130f, 24f)
        box(
            parent,
            label(detail, 12f, palette.color("muted")).apply {
                gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            },
            width - 130f,
            y,
            130f,
            24f,
        )
    }

    fun trimMemory(background: Boolean) {
        covers.trim(background)
        invalidateCoverViews(this)
    }

    fun resumeMemory() {
        covers.resume()
        invalidateCoverViews(this)
    }

    private fun invalidateCoverViews(view: View) {
        if (view is DocumentCoverView) view.invalidate()
        if (view is ViewGroup)
            for (i in 0 until view.childCount) invalidateCoverViews(view.getChildAt(i))
    }
}

private class UiButton(context: Context) : FrameLayout(context) {
    private var wasPressed = false

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (wasPressed != isPressed) {
            wasPressed = isPressed
            UiMotion.press(this, isPressed)
        }
    }

    override fun onInitializeAccessibilityNodeInfo(
        info: android.view.accessibility.AccessibilityNodeInfo
    ) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
    }
}
