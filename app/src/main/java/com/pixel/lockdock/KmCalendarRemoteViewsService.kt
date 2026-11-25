package com.pixel.lockdock

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class KmCalendarRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return KmAgendaFactory(applicationContext)
    }
}

private class KmAgendaFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {

    private var rows: List<CalendarAgendaRow> = emptyList()

    override fun onCreate() {
        reload()
    }

    override fun onDataSetChanged() {
        reload()
    }

    private fun reload() {
        val cfg = LockDockRepository.loadConfig(context)
        rows = KmCalendarHelper.queryAgendaRows(
            context = context,
            daysAhead = cfg.calendarDaysAhead,
            includeAllDay = cfg.calendarShowAllDay
        )
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewTypeCount(): Int = 2

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews {
        val row = rows.getOrNull(position)
            ?: return RemoteViews(context.packageName, R.layout.item_km_day_header)

        return if (row.isHeader) {
            RemoteViews(context.packageName, R.layout.item_km_day_header).apply {
                setTextViewText(R.id.km_day_dow, row.headerDow)
                setTextViewText(R.id.km_day_num, row.headerDayNum)
                setOnClickFillInIntent(R.id.km_day_header_root, Intent())
            }
        } else {
            RemoteViews(context.packageName, R.layout.item_km_event_row).apply {
                setTextViewText(R.id.km_event_title, row.title)
                setTextViewText(R.id.km_event_time, row.timeLabel)
                setImageViewBitmap(
                    R.id.km_event_color_bar,
                    KmCalendarHelper.renderColorBarBitmap(row.colorInt)
                )
                val fillIn = Intent().apply {
                    putExtra(LockLaunchActivity.EXTRA_CALENDAR_EVENT_ID, row.eventId)
                    putExtra(LockLaunchActivity.EXTRA_CALENDAR_BEGIN_MS, row.beginMillis)
                }
                setOnClickFillInIntent(R.id.km_event_row_root, fillIn)
            }
        }
    }
}
