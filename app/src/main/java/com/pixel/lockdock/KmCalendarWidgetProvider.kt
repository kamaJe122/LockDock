package com.pixel.lockdock

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews

class KmCalendarWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action.orEmpty()
        if (action == Intent.ACTION_PROVIDER_CHANGED ||
            action == Intent.ACTION_DATE_CHANGED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, KmCalendarWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                updateWidgets(context, mgr, ids)
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateWidgets(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateWidgets(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    companion object {
        fun updateWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            val hasPerm = KmCalendarHelper.hasCalendarPermission(context)
            val monthBmp = KmCalendarHelper.renderKmMonthBitmap()

            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_km_calendar)
                views.setImageViewBitmap(R.id.km_month_image, monthBmp)

                val svcIntent = Intent(context, KmCalendarRemoteViewsService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                }
                @Suppress("DEPRECATION")
                views.setRemoteAdapter(R.id.km_agenda_list, svcIntent)
                views.setEmptyView(R.id.km_agenda_list, R.id.km_empty_view)

                if (hasPerm) {
                    views.setTextViewText(R.id.km_empty_view, "近期無排定行程 🎉")
                } else {
                    views.setTextViewText(
                        R.id.km_empty_view,
                        "點擊此處授權「讀取行事曆」\n即可同步顯示 Google 日曆行程"
                    )
                }

                // Tapping left calendar or empty prompt opens LockDock / Calendar
                val openAppIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val openAppPi = DeviceStateHelper.createActivityPendingIntent(
                    context,
                    8100 + appWidgetId,
                    openAppIntent
                )
                views.setOnClickPendingIntent(R.id.km_empty_view, openAppPi)
                views.setOnClickPendingIntent(R.id.km_left_container, openAppPi)

                // '+' button prompts unlock and opens system Calendar insert event screen
                val addEventIntent = Intent(context, LockLaunchActivity::class.java).apply {
                    putExtra(LockLaunchActivity.EXTRA_OPEN_CALENDAR_INSERT, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val addEventPi = DeviceStateHelper.createActivityPendingIntent(
                    context,
                    8300 + appWidgetId,
                    addEventIntent
                )
                views.setOnClickPendingIntent(R.id.km_btn_add_event, addEventPi)

                // Clicking an agenda item opens that event in the Calendar app after biometric unlock
                val templateIntent = Intent(context, LockLaunchActivity::class.java).apply {
                    putExtra(LockLaunchActivity.EXTRA_OPEN_CALENDAR_EVENT, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val templatePi = DeviceStateHelper.createMutableTemplatePendingIntent(
                    context,
                    8500 + appWidgetId,
                    templateIntent
                )
                views.setPendingIntentTemplate(R.id.km_agenda_list, templatePi)

                appWidgetManager.updateAppWidget(appWidgetId, views)
                @Suppress("DEPRECATION")
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.km_agenda_list)
            }
        }
    }
}
