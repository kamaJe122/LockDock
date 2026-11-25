package com.pixel.lockdock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews

class TodoMemoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateWidgets(context, appWidgetManager, appWidgetIds)
    }

    companion object {
        private val ROW_IDS = intArrayOf(
            R.id.todo_item_0,
            R.id.todo_item_1,
            R.id.todo_item_2
        )

        fun updateWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            val config = LockDockRepository.loadConfig(context)
            val views = RemoteViews(context.packageName, R.layout.widget_todo_memo_card)

            val doneCount = config.todos.count { it.done }
            views.setTextViewText(
                R.id.card_header_title,
                "📌 鎖屏待辦 ($doneCount/${config.todos.size})"
            )
            views.setTextViewText(
                R.id.card_memo_text,
                "✨ ${config.memoText.ifBlank { "點擊新增鎖定畫面備忘錄" }}"
            )

            val quickEditIntent = Intent(context, QuickActionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val quickEditPi = DeviceStateHelper.createActivityPendingIntent(context, 701, quickEditIntent)
            views.setOnClickPendingIntent(R.id.btn_quick_add, quickEditPi)
            views.setOnClickPendingIntent(R.id.card_memo_text, quickEditPi)

            ROW_IDS.forEachIndexed { idx, viewId ->
                if (idx < config.todos.size) {
                    val item = config.todos[idx]
                    views.setViewVisibility(viewId, View.VISIBLE)
                    val prefix = if (item.done) "✓ " else "○ "
                    views.setTextViewText(viewId, prefix + item.text)
                    views.setTextColor(
                        viewId,
                        if (item.done) Color.argb(140, 200, 215, 235) else Color.WHITE
                    )

                    val toggleIntent = Intent(context, WidgetActionReceiver::class.java).apply {
                        action = WidgetActionReceiver.ACTION_TODO_TOGGLE
                        putExtra(WidgetActionReceiver.EXTRA_TODO_INDEX, idx)
                    }
                    val togglePi = PendingIntent.getBroadcast(
                        context,
                        720 + idx,
                        toggleIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(viewId, togglePi)
                } else {
                    views.setViewVisibility(viewId, View.GONE)
                }
            }

            for (id in appWidgetIds) {
                appWidgetManager.updateAppWidget(id, views)
            }
        }
    }
}
