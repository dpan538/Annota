package io.github.dpan538.annota

import android.appwidget.*
import android.content.*
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.*
import org.junit.*
import org.junit.Assert.*

/** Dedicated emulator: real GitHub receipt, cross-UID PDF grants and a real RemoteViews host. */
class IntegrationTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private var activity: MainActivity? = null
    private lateinit var store: Store
    private val prefs
        get() = context.getSharedPreferences("settings", 0)

    private fun <T> ui(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        var result: T? = null
        inst.runOnMainSync { result = action() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun all(view: View): List<View> =
        listOf(view) +
            (if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) }
            else emptyList())

    private fun waitFor(action: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 30000
        while (!action()) {
            check(SystemClock.uptimeMillis() < end) { "Integration flow did not settle" }
            SystemClock.sleep(60)
        }
        inst.waitForIdleSync()
    }

    private fun start(): MainActivity {
        val a =
            inst.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
        activity = a
        waitFor { ui { all(a.window.decorView).any { it is LibraryScreen } } }
        return a
    }

    private fun reader(): ReaderView? = ui {
        activity?.let { all(it.window.decorView).filterIsInstance<ReaderView>().firstOrNull() }
    }

    private fun invoke(name: String, vararg args: Any?) {
        ui {
            MainActivity::class
                .java
                .declaredMethods
                .first { it.name == name && it.parameterCount == args.size }
                .apply { isAccessible = true }
                .invoke(activity, *args)
        }
    }

    @Before
    fun setup() {
        Assume.assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        prefs
            .edit()
            .remove("feedbackBody")
            .remove("feedbackId")
            .remove("feedbackPending")
            .remove("feedbackReceipt")
            .putBoolean("english", false)
            .putInt("theme", 2)
            .commit()
        finishReadersBeforeDatabaseReset()
        context.deleteDatabase("library.sqlite")
        context.getSharedPreferences("continue-reading", 0).edit().clear().commit()
        store = Store(context)
    }

    @After
    fun cleanup() {
        ui { activity?.finish() }
        inst.waitForIdleSync()
        store.close()
        prefs.edit().remove("feedbackPending").commit()
    }

    private fun capture(name: String) {
        inst.waitForIdleSync()
        SystemClock.sleep(180)
        val dir = File(context.filesDir, "integration-capture").apply { mkdirs() }
        inst.uiAutomation.takeScreenshot().also { bitmap ->
            File(dir, "integration-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun shell(command: String) {
        inst.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }

    @Test
    fun offlineFeedbackShowsExplicitPopupAndKeepsDraft() {
        val a = start()
        invoke("feedback")
        // The dialog window is independent of the Activity; use its exposed status callback and
        // accessibility input.
        fun nodes(
            n: android.view.accessibility.AccessibilityNodeInfo?
        ): List<android.view.accessibility.AccessibilityNodeInfo> =
            if (n == null) emptyList()
            else listOf(n) + (0 until n.childCount).flatMap { nodes(n.getChild(it)) }
        waitFor {
            nodes(inst.uiAutomation.rootInActiveWindow).any {
                it.className == "android.widget.EditText"
            }
        }
        val input =
            nodes(inst.uiAutomation.rootInActiveWindow).first {
                it.className == "android.widget.EditText"
            }
        input.performAction(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(
                    android.view.accessibility.AccessibilityNodeInfo
                        .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    "Offline feedback should remain a draft",
                )
            },
        )
        try {
            shell("svc wifi disable")
            shell("svc data disable")
            waitFor { !Network.hasInternet(context) }
            waitFor {
                nodes(inst.uiAutomation.rootInActiveWindow).any {
                    it.isClickable && it.contentDescription?.toString() == "提交反馈"
                }
            }
            val submit =
                nodes(inst.uiAutomation.rootInActiveWindow).first {
                    it.isClickable && it.contentDescription?.toString() == "提交反馈"
                }
            inst.uiAutomation.executeAndWaitForEvent(
                {
                    submit.performAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                    )
                },
                { event ->
                    event.eventType ==
                        android.view.accessibility.AccessibilityEvent
                            .TYPE_NOTIFICATION_STATE_CHANGED &&
                        event.text.any { it.toString().contains("未连接互联网") }
                },
                6000,
            )
            assertEquals(
                "Offline feedback should remain a draft",
                GitHubFeedback(context).draft().body,
            )
            assertNull(GitHubFeedback(context).pending())
            capture("feedback-offline-popup")
        } finally {
            shell("svc wifi enable")
            shell("svc data enable")
            waitFor { Network.hasInternet(context) }
        }
    }

    @Test
    fun receivedFeedbackShowsPopupOnlyAfterRealReceipt() {
        val a = start()
        invoke("feedback")
        val field =
            MainActivity::class.java.getDeclaredField("feedbackDialog").apply {
                isAccessible = true
            }
        val d = ui { field.get(a) as android.app.Dialog }
        ui {
            all(d.window!!.decorView)
                .filterIsInstance<EditText>()
                .single()
                .setText("Annota 1.0.0 automatic feedback QA. Test report; safe to close.")
        }
        // Reuse the live QA request so rerunning instrumentation does not spam the repository.
        val id = "305b920b-a27e-4786-9fdb-99caaa3f2b1e"
        prefs.edit().putString("feedbackId", id).commit()
        inst.uiAutomation.executeAndWaitForEvent(
            {
                ui {
                    all(d.window!!.decorView)
                        .first { it.isClickable && it.contentDescription == "提交反馈" }
                        .performClick()
                }
            },
            { event ->
                event.eventType ==
                    android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                    event.text.any { it.toString().contains("已收到反馈") }
            },
            90000,
        )
        assertEquals("", GitHubFeedback(context).draft().body)
        val receipt = GitHubFeedback(context).receipt()!!
        assertTrue(GitHubFeedback.validReceipt(receipt))
        // Check the returned issue directly; the unauthenticated issues list can be cached.
        val connection =
            java.net
                .URL(
                    "https://api.github.com/repos/dpan538/Annota/issues/" +
                        receipt.substringAfterLast('/')
                )
                .openConnection() as javax.net.ssl.HttpsURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.setRequestProperty("User-Agent", "Annota-live-feedback-verification")
        val issue =
            try {
                assertEquals(200, connection.responseCode)
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        assertEquals(receipt, issue.getString("html_url"))
        assertTrue(issue.getString("body").contains(GitHubFeedback.marker(id)))
        File(context.filesDir, "integration-automatic-feedback.json")
            .writeText(
                JSONObject()
                    .put("requestId", id)
                    .put("issueUrl", receipt)
                    .put("nativeButtonSubmitted", true)
                    .put("successPopupObserved", true)
                    .toString(2)
            )
        capture("feedback-received-popup")
        val evidence =
            File(context.getExternalFilesDir(null), "feedback-verification").apply { mkdirs() }
        File(context.filesDir, "integration-capture/integration-feedback-received-popup.png")
            .copyTo(File(evidence, "feedback-received-popup.png"), true)
        File(context.filesDir, "integration-automatic-feedback.json")
            .copyTo(File(evidence, "automatic-feedback.json"), true)
    }

    @Test
    fun annotationsAndSearchSurviveActivityRecreation() {
        val a = start()
        val b = store.books().first { it.title == "Attention Is All You Need" }
        b.pages[2]
            .marks
            .add(Mark("pen", Color.BLUE, 2f, mutableListOf(Point(50f, 200f), Point(350f, 200f))))
        store.commit(b)
        invoke("open", b)
        waitFor { reader()?.width?.let { it > 0 } == true }
        invoke("showNavigation", false, null)
        val field =
            MainActivity::class.java.getDeclaredField("navigationPanel").apply {
                isAccessible = true
            }
        var panel = ui { field.get(a) as ReaderNavigator }
        ui { panel.showTab(3) }
        val list = ui { all(panel.view).filterIsInstance<ListView>().single() }
        assertEquals(1, ui { list.adapter.count })
        ui { list.performItemClick(list.getChildAt(0), 0, 0) }
        assertEquals(b.pages[2].id, reader()!!.viewport()!!.pageId)
        capture("annotation-overview")
        ui { panel.beginSearch(false) }
        ui { all(panel.view).filterIsInstance<EditText>().single().setText("attention") }
        waitFor { ui { panel.snapshot().active >= 0 } }
        val monitor = inst.addMonitor(MainActivity::class.java.name, null, false)
        ui { a.recreate() }
        activity = inst.waitForMonitorWithTimeout(monitor, 25000) as MainActivity
        inst.removeMonitor(monitor)
        waitFor {
            ui {
                (field.get(activity) as? ReaderNavigator)?.snapshot()?.let {
                    it.searching && it.query == "attention" && it.active >= 0
                } == true
            }
        }
        panel = ui { field.get(activity) as ReaderNavigator }
        assertEquals("attention", ui { panel.snapshot().query })
        capture("search-restored")
    }

    @Test
    fun feedbackFormScalesWithTabletAndKeyboardAcrossButtonSizes() {
        val a = start()
        val field =
            MainActivity::class.java.getDeclaredField("feedbackDialog").apply {
                isAccessible = true
            }
        fun dialog() = ui { field.get(a) as? android.app.Dialog }
        for (portrait in listOf(false, true)) {
            ui {
                a.requestedOrientation =
                    if (portrait) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitFor { ui { (a.window.decorView.width < a.window.decorView.height) == portrait } }
            var previousHeight = 0
            for (size in listOf(60, 68, 76)) {
                prefs.edit().putInt("buttonSize", size).commit()
                invoke("feedback")
                waitFor {
                    ui {
                        dialog()?.window?.decorView?.width?.let {
                            it > a.window.decorView.width * .42f
                        } == true
                    }
                }
                val d = dialog()!!
                val edit = ui { all(d.window!!.decorView).filterIsInstance<EditText>().single() }
                val submit = ui {
                    all(d.window!!.decorView).first {
                        it.isClickable && it.contentDescription == "提交反馈"
                    }
                }
                ui {
                    val minimum = if (portrait) .62f else .42f
                    assertTrue(
                        "Feedback should use the tablet scale",
                        d.window!!.decorView.width >= a.window.decorView.width * minimum,
                    )
                    val baseline = a.window.decorView.width / (if (portrait) 682.6667f else 1024f)
                    assertTrue("Feedback text is too small", edit.textSize >= 14f * baseline)
                    assertTrue("Button size must actually increase", submit.height > previousHeight)
                    previousHeight = submit.height
                    edit.setText("反馈布局检查 · $size")
                }
                capture("feedback-$size-${if(portrait) "portrait" else "landscape"}")
                if (size == 76) {
                    ui {
                        edit.requestFocus()
                        (a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                                as android.view.inputmethod.InputMethodManager)
                            .showSoftInput(
                                edit,
                                android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT,
                            )
                    }
                    waitFor {
                        ui {
                            androidx.core.view.ViewCompat.getRootWindowInsets(d.window!!.decorView)
                                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) ==
                                true
                        }
                    }
                    SystemClock.sleep(250)
                    ui {
                        val frame =
                            Rect().also { d.window!!.decorView.getWindowVisibleDisplayFrame(it) }
                        val point = IntArray(2).also(d.window!!.decorView::getLocationOnScreen)
                        assertTrue(
                            "Feedback must fit above the keyboard",
                            point[1] + d.window!!.decorView.height <= frame.bottom + 8,
                        )
                        all(d.window!!.decorView)
                            .filterIsInstance<ScrollView>()
                            .first()
                            .fullScroll(View.FOCUS_DOWN)
                    }
                    capture("feedback-76-${if(portrait) "portrait" else "landscape"}-keyboard")
                    ui {
                        androidx.core.view
                            .WindowInsetsControllerCompat(d.window!!, d.window!!.decorView)
                            .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
                    }
                    waitFor {
                        ui {
                            androidx.core.view.ViewCompat.getRootWindowInsets(d.window!!.decorView)
                                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) !=
                                true
                        }
                    }
                }
                ui { d.dismiss() }
            }
        }
    }

    @Test
    fun feedbackPayloadPreservesDraftAndExcludesContact() {
        val delivery = GitHubFeedback(context)
        delivery.save("测试 & feedback # with 中文\nsecond line")
        prefs.edit().putString("feedbackContact", "private@example.invalid").commit()
        val draft = delivery.draft()
        val payload = delivery.prepare()
        assertEquals(draft.body, delivery.draft().body)
        assertEquals(draft.id, delivery.pending())
        assertEquals(draft.body, payload.getString("body"))
        assertEquals(draft.id, payload.getString("requestId"))
        assertEquals(BuildConfig.VERSION_NAME, payload.getString("version"))
        assertFalse(payload.toString().contains("private"))
        assertNull(delivery.receipt())
    }

    @Test
    fun feedbackAcknowledgementRejectsMismatchesAndFalseSuccess() {
        val id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val good =
            JSONObject()
                .put("accepted", true)
                .put("requestId", id)
                .put("receipt", "https://github.com/dpan538/Annota/issues/2")
        assertEquals(good.getString("receipt"), Network.feedbackAcknowledgement(good, id))
        for (bad in
            listOf(
                JSONObject(good.toString()).put("accepted", false),
                JSONObject(good.toString()).put("requestId", "other"),
                JSONObject(good.toString()).put("receipt", "local-only"),
            )) {
            assertTrue(runCatching { Network.feedbackAcknowledgement(bad, id) }.isFailure)
        }
    }

    @Test
    fun feedbackRequiresRealIssueAndKeepsNewEdits() {
        val delivery = GitHubFeedback(context)
        delivery.save("First report")
        delivery.prepare()
        val first = delivery.draft()
        delivery.save("A newer draft")
        val url = "https://github.com/dpan538/Annota/issues/1"
        val issue = JSONObject().put("html_url", url).put("body", GitHubFeedback.marker(first.id))
        assertEquals(url, GitHubFeedback.findReceipt(JSONArray().put(issue), first.id))
        assertNull(
            GitHubFeedback.findReceipt(
                JSONArray().put(JSONObject(issue.toString()).put("pull_request", JSONObject())),
                first.id,
            )
        )
        assertNull(
            GitHubFeedback.findReceipt(
                JSONArray()
                    .put(
                        JSONObject(issue.toString())
                            .put("html_url", "https://evil.invalid/issues/1")
                    ),
                first.id,
            )
        )
        assertNull(
            GitHubFeedback.findReceipt(
                JSONArray().put(JSONObject(issue.toString()).put("body", "accepted locally")),
                first.id,
            )
        )
        delivery.acknowledge(first.id, url)
        assertEquals("A newer draft", delivery.draft().body)
        assertEquals(url, delivery.receipt())
        assertNull(delivery.pending())
    }

    @Test
    fun realGitHubReceiptAndMissingSubmission() {
        val receipt =
            Network(context).feedbackReceipt("407ac072-7dc9-41d5-b73e-3efbb8f582bd", Network.Task())
        assertEquals("https://github.com/dpan538/Annota/issues/1", receipt)
        assertNull(
            Network(context).feedbackReceipt("a7c4b197-bcc5-4a34-a260-75e88ad8aca1", Network.Task())
        )
        val delivery = GitHubFeedback(context)
        delivery.save("Receipt QA")
        val id = delivery.draft().id
        delivery.prepare()
        assertEquals("Receipt QA", delivery.draft().body)
        delivery.acknowledge(id, receipt!!)
        assertEquals("", delivery.draft().body)
        File(context.filesDir, "integration-github-receipt.json")
            .writeText(
                JSONObject()
                    .put("url", receipt)
                    .put("verifiedBy", "anonymous HTTPS GitHub API on Android")
                    .put("draftClearedOnlyAfterReceipt", true)
                    .toString(2)
            )
    }

    @Test
    fun widgetHostResizesAndOpensSavedPosition() {
        val a = start()
        val b = store.books().first { it.title == "Attention Is All You Need" }
        invoke("open", b)
        waitFor { reader()?.width?.let { it > 0 } == true }
        ui {
            reader()!!.navigatePage(b.pages[3].id)
            reader()!!.persistPosition()
            ContinueReadingWidget.remember(context, reader()!!.book)
        }
        val expected = reader()!!.viewport()!!
        invoke("library")
        inst.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        val host = AppWidgetHost(context, 6221)
        val manager = AppWidgetManager.getInstance(context)
        val id = host.allocateAppWidgetId()
        try {
            assertTrue(
                manager.bindAppWidgetIdIfAllowed(
                    id,
                    ComponentName(context, ContinueReadingWidget::class.java),
                )
            )
            val info = manager.getAppWidgetInfo(id)
            assertEquals(AppWidgetProviderInfo.RESIZE_BOTH, info.resizeMode)
            lateinit var hosted: AppWidgetHostView
            ui {
                host.startListening()
                hosted = host.createView(a, id, info)
                val root =
                    MainActivity::class
                        .java
                        .getDeclaredField("root")
                        .apply { isAccessible = true }
                        .get(a) as LinearLayout
                root.removeAllViews()
                root.addView(
                    FrameLayout(a).apply {
                        setPadding(24, 24, 24, 24)
                        addView(hosted, FrameLayout.LayoutParams(600, 240))
                    },
                    LinearLayout.LayoutParams(-1, -1),
                )
                ContinueReadingWidget.render(context, manager, id)
            }
            waitFor {
                ui {
                    all(hosted).filterIsInstance<TextView>().any {
                        it.text.toString().contains("4 / 11")
                    }
                }
            }
            capture("widget-wide")
            ui {
                hosted.updateAppWidgetSize(Bundle(), 160, 72, 160, 72)
                hosted.layoutParams = FrameLayout.LayoutParams(400, 180)
                ContinueReadingWidget.render(context, manager, id)
            }
            SystemClock.sleep(250)
            capture("widget-compact")
            ui { hosted.findViewById<View>(R.id.widget_card).performClick() }
            waitFor { reader() != null }
            assertEquals(expected.pageId, reader()!!.viewport()!!.pageId)
            assertEquals(expected.fraction, reader()!!.viewport()!!.fraction, .025f)
            capture("widget-resumed-paper")
        } finally {
            ui { host.stopListening() }
            host.deleteHost()
            inst.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test
    fun widgetTrashFallsBackToLibrary() {
        start()
        val b = store.books().first()
        ContinueReadingWidget.remember(context, b)
        b.trash = true
        store.metadata(b)
        assertNull(ContinueReadingWidget.target(context))
        context.startActivity(ContinueReadingWidget.intent(context))
        waitFor { ui { all(activity!!.window.decorView).any { it is LibraryScreen } } }
    }

    @Test
    fun crossUidViewAndSendMakeDurablePrivateCopyWithoutDuplicates() {
        val a = start()
        val initial = store.libraryBooks().size
        fun send(action: String, mime: String = "application/pdf") {
            inst.context.startActivity(
                Intent()
                    .setComponent(
                        ComponentName(
                            "io.github.dpan538.annota.test",
                            "io.github.dpan538.annota.testsender.SenderActivity",
                        )
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("action", action)
                    .putExtra("mime", mime)
            )
        }
        send(Intent.ACTION_VIEW, "application/octet-stream")
        waitFor { reader()?.book?.title == "Attention" }
        val imported = reader()!!.book
        assertEquals(initial + 1, store.libraryBooks().size)
        assertEquals(11, imported.pages.size)
        assertTrue(store.file(imported.source).exists())
        assertEquals(
            sha256(store.file(imported.source)),
            context.assets.open("attention-is-all-you-need.pdf").use { input ->
                val file = File(context.cacheDir, "source-check.pdf")
                file.outputStream().use(input::copyTo)
                sha256(file).also { file.delete() }
            },
        )
        invoke("library")
        send(Intent.ACTION_SEND)
        waitFor { reader()?.book?.id == imported.id }
        assertEquals(initial + 1, store.libraryBooks().size)
        capture("external-attention")
    }

    @Test
    fun corruptExternalPdfDoesNotCreateBook() {
        start()
        val initial = store.libraryBooks().size
        inst.context.startActivity(
            Intent()
                .setComponent(
                    ComponentName(
                        "io.github.dpan538.annota.test",
                        "io.github.dpan538.annota.testsender.SenderActivity",
                    )
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("invalid", true)
        )
        fun contains(n: android.view.accessibility.AccessibilityNodeInfo?): Boolean =
            n != null &&
                (n.text?.toString()?.contains("这不是可读取的 PDF 文件") == true ||
                    (0 until n.childCount).any { contains(n.getChild(it)) })
        waitFor { contains(inst.uiAutomation.rootInActiveWindow) }
        assertNull(reader())
        assertEquals(initial, store.libraryBooks().size)
    }

    @Test
    fun sessionRoundTripsSearchOriginAndHistory() {
        val v = ReaderView.Viewport("page", .44f, 2.2f, -.3f, true, false)
        val state = ReaderSession(v, ReaderNavigator.State(3, true, "attention", v, 4), listOf(v))
        assertEquals(state, ReaderSession.decode(state.encode()))
        assertNull(ReaderSession.decode("invalid"))
    }

    @Test
    fun filterKeepsTrashAndFolderContext() {
        val a = start()
        val b = store.books().first { it.source.isNotEmpty() }
        b.trash = true
        store.metadata(b)
        ui {
            val section =
                MainActivity::class.java.getDeclaredField("section").apply { isAccessible = true }
            section.set(a, "trash")
        }
        invoke("library")
        val screen = ui { all(a.window.decorView).filterIsInstance<LibraryScreen>().single() }
        fun chooseFilter(label: String) {
            invoke("libraryFilterMenu")
            val popup =
                MainActivity::class
                    .java
                    .getDeclaredField("paperPopup")
                    .apply { isAccessible = true }
                    .get(a) as PopupWindow
            ui {
                all(popup.contentView)
                    .first { it.isClickable && it.contentDescription == label }
                    .performClick()
            }
        }
        chooseFilter("PDF")
        assertEquals("trash", screen.section)
        assertTrue(ui { all(screen).any { it.contentDescription == b.title } })
        chooseFilter("笔记")
        assertFalse(ui { all(screen).any { it.contentDescription == b.title } })
        invoke("libraryFilterMenu")
        capture("trash-filter")
    }
}
