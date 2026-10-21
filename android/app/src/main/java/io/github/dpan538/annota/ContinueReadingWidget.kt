package io.github.dpan538.annota

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews

/** A local, resizable shortcut. No network, polling timer, document thumbnail or URI grant. */
class ContinueReadingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        options: Bundle,
    ) {
        render(context, manager, id)
    }

    companion object {
        const val OPEN = "io.github.dpan538.annota.CONTINUE_READING"
        private const val PREFS = "continue-reading"

        fun remember(context: Context, book: Book) {
            context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("book", book.id)
                .putString("title", book.title)
                .putInt("page", book.current + 1)
                .putInt("total", book.pages.size)
                .apply()
            update(context)
        }

        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager
                .getAppWidgetIds(ComponentName(context, ContinueReadingWidget::class.java))
                .forEach { render(context, manager, it) }
        }

        fun target(context: Context): String? {
            val id =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("book", null)
                    ?: return null
            return Store(context).use { store -> if (store.available(id)) id else null }
        }

        fun intent(context: Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                action = OPEN
                data = Uri.parse("annota://continue-reading")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

        fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val english =
                context
                    .getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .getBoolean("english", false)
            val metadata =
                prefs.getString("book", null)?.let { bookId ->
                    Store(context).use { it.widgetMetadata(bookId) }
                }
            val available = metadata != null
            val title = metadata?.first ?: "Annota"
            val subtitle =
                if (available) {
                    val page = (metadata!!.second + 1).coerceAtLeast(1)
                    val total = prefs.getInt("total", page).coerceAtLeast(page)
                    if (english) "Continue reading · $page / $total" else "继续阅读 · $page / $total 页"
                } else if (english) "Open your library" else "打开书库"
            val views = RemoteViews(context.packageName, R.layout.continue_reading_widget)
            val density = context.resources.displayMetrics.density
            val fontScale = context.resources.configuration.fontScale
            val vertical = if (fontScale >= 1.5f) 4 else 12
            views.setViewPadding(
                R.id.widget_card,
                (16 * density).toInt(),
                (vertical * density).toInt(),
                (16 * density).toInt(),
                (vertical * density).toInt(),
            )
            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_page, subtitle)
            views.setContentDescription(R.id.widget_card, "$title · $subtitle")
            views.setOnClickPendingIntent(
                R.id.widget_card,
                PendingIntent.getActivity(
                    context,
                    0,
                    intent(context),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            val tall =
                manager
                    .getAppWidgetOptions(id)
                    .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 72) >= 110
            views.setTextViewTextSize(
                R.id.widget_title,
                android.util.TypedValue.COMPLEX_UNIT_SP,
                if (tall) 20f else 16f,
            )
            manager.updateAppWidget(id, views)
        }
    }
}
