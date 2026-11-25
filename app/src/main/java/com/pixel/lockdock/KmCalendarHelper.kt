package com.pixel.lockdock

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

data class CalendarAgendaRow(
    val isHeader: Boolean,
    val headerDow: String = "",
    val headerDayNum: String = "",
    val eventId: Long = -1L,
    val title: String = "",
    val timeLabel: String = "",
    val colorInt: Int = Color.rgb(220, 76, 76),
    val beginMillis: Long = 0L
)

object KmCalendarHelper {

    fun hasCalendarPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Queries the system CalendarContract.Instances database (same source as KM 桌面行事曆小工具)
     * for [daysAhead] days starting today, preserving Google Calendar colors and all-day accuracy.
     */
    fun queryAgendaRows(
        context: Context,
        daysAhead: Int = 14,
        includeAllDay: Boolean = true
    ): List<CalendarAgendaRow> {
        if (!hasCalendarPermission(context)) return emptyList()

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val endDay = today.plusDays(daysAhead.coerceIn(1, 60).toLong())

        // Query a slightly wider UTC window so UTC-stored all-day events are never missed
        val queryStartMs = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val queryEndMs = endDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, queryStartMs)
        ContentUris.appendId(builder, queryEndMs)

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.VISIBLE
        )

        data class RawInstance(
            val eventId: Long,
            val title: String,
            val startDay: LocalDate,
            val endDayInclusive: LocalDate,
            val allDay: Boolean,
            val beginMs: Long,
            val endMs: Long,
            val color: Int
        )

        val rawEvents = mutableListOf<RawInstance>()
        runCatching {
            context.contentResolver.query(
                builder.build(),
                projection,
                "${CalendarContract.Instances.VISIBLE} = 1",
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val visible = cursor.getInt(6)
                    if (visible != 1) continue
                    val allDay = cursor.getInt(4) == 1
                    if (allDay && !includeAllDay) continue

                    val eventId = cursor.getLong(0)
                    val title = cursor.getString(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: "(無標題)"
                    val beginMs = cursor.getLong(2)
                    val endMs = cursor.getLong(3).coerceAtLeast(beginMs)
                    val rawColor = cursor.getInt(5)
                    val color = if (rawColor != 0) (rawColor or -0x1000000) else Color.rgb(217, 68, 68)

                    val sDate = if (allDay) {
                        Instant.ofEpochMilli(beginMs).atZone(ZoneOffset.UTC).toLocalDate()
                    } else {
                        Instant.ofEpochMilli(beginMs).atZone(zone).toLocalDate()
                    }

                    val eDate = if (allDay) {
                        Instant.ofEpochMilli((endMs - 1000L).coerceAtLeast(beginMs))
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                    } else {
                        Instant.ofEpochMilli((endMs - 1L).coerceAtLeast(beginMs))
                            .atZone(zone)
                            .toLocalDate()
                    }

                    rawEvents.add(
                        RawInstance(
                            eventId = eventId,
                            title = title,
                            startDay = sDate,
                            endDayInclusive = eDate.coerceAtLeast(sDate),
                            allDay = allDay,
                            beginMs = beginMs,
                            endMs = endMs,
                            color = color
                        )
                    )
                }
            }
        }

        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        val rows = mutableListOf<CalendarAgendaRow>()
        val dowNames = arrayOf("週一", "週二", "週三", "週四", "週五", "週六", "週日")

        var dayCursor = today
        while (dayCursor.isBefore(endDay)) {
            val dayEvents = rawEvents.filter { ev ->
                !dayCursor.isBefore(ev.startDay) && !dayCursor.isAfter(ev.endDayInclusive)
            }.sortedWith(compareByDescending<RawInstance> { it.allDay }.thenBy { it.beginMs })

            if (dayEvents.isNotEmpty() || dayCursor == today) {
                val dowStr = dowNames[dayCursor.dayOfWeek.value - 1] + " "
                rows.add(
                    CalendarAgendaRow(
                        isHeader = true,
                        headerDow = dowStr,
                        headerDayNum = "${dayCursor.dayOfMonth}日"
                    )
                )
                if (dayEvents.isEmpty() && dayCursor == today) {
                    rows.add(
                        CalendarAgendaRow(
                            isHeader = false,
                            title = "今日尚無排定行程",
                            timeLabel = "全天",
                            colorInt = Color.rgb(158, 166, 178)
                        )
                    )
                } else {
                    dayEvents.forEach { ev ->
                        val timeStr = if (ev.allDay) {
                            "全天"
                        } else {
                            val st = Instant.ofEpochMilli(ev.beginMs).atZone(zone).toLocalTime()
                            val et = Instant.ofEpochMilli(ev.endMs).atZone(zone).toLocalTime()
                            "${st.format(timeFmt)} - ${et.format(timeFmt)}"
                        }
                        rows.add(
                            CalendarAgendaRow(
                                isHeader = false,
                                eventId = ev.eventId,
                                title = ev.title,
                                timeLabel = timeStr,
                                colorInt = ev.color,
                                beginMillis = ev.beginMs
                            )
                        )
                    }
                }
            }
            dayCursor = dayCursor.plusDays(1)
        }

        return rows
    }

    /**
     * Renders the left-hand monthly calendar matching KM 桌面行事曆小工具:
     * "11月 第 48 週", Sunday-Saturday columns, week numbers on left, and red rounded square on today.
     * Sized 420x310 (~520KB ARGB_8888, well within Android 17 RemoteViews limits).
     */
    fun renderKmMonthBitmap(): Bitmap {
        val w = 420
        val h = 310
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val today = LocalDate.now()
        val weekFields = WeekFields.of(Locale.TAIWAN)
        val currentWeek = today.get(weekFields.weekOfWeekBasedYear())

        // Header: "11月" (Red) + "第 48 週" (Grey)
        val monthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(217, 68, 68)
            textSize = 31f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val monthStr = "${today.monthValue}月"
        canvas.drawText(monthStr, 8f, 34f, monthPaint)

        val weekHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(132, 138, 148)
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val monthWidth = monthPaint.measureText(monthStr)
        canvas.drawText("第 $currentWeek 週", 8f + monthWidth + 12f, 32f, weekHeaderPaint)

        // Day-of-week header row: 週日 .. 週六
        val dowHeaders = arrayOf("週日", "週一", "週二", "週三", "週四", "週五", "週六")
        val gridLeft = 44f
        val colWidth = (w - gridLeft - 4f) / 7f

        val dowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(136, 142, 152)
            textSize = 16.5f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        for (c in 0..6) {
            val cx = gridLeft + colWidth * (c + 0.5f)
            canvas.drawText(dowHeaders[c], cx, 64f, dowPaint)
        }

        // First cell of 6x7 Sunday-first calendar grid
        val firstOfMonth = today.withDayOfMonth(1)
        val offsetFromSunday = firstOfMonth.dayOfWeek.value % 7 // Sunday = 7 % 7 = 0
        var cellDate = firstOfMonth.minusDays(offsetFromSunday.toLong())

        val weekNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(150, 156, 166)
            textSize = 15.5f
            textAlign = Paint.Align.CENTER
        }
        val activeDayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(28, 32, 38)
            textSize = 21f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val mutedDayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(176, 182, 192)
            textSize = 20f
            textAlign = Paint.Align.CENTER
        }
        val todayTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 21f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val todayBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(223, 75, 75)
            style = Paint.Style.FILL
        }

        val gridTop = 78f
        val rowHeight = (h - gridTop - 4f) / 6f

        for (r in 0..5) {
            val rowCenterY = gridTop + rowHeight * (r + 0.5f)
            val weekNum = cellDate.get(weekFields.weekOfWeekBasedYear())
            canvas.drawText(weekNum.toString(), 20f, rowCenterY + 6f, weekNumPaint)

            for (c in 0..6) {
                val cx = gridLeft + colWidth * (c + 0.5f)
                val isToday = cellDate == today
                val inCurrentMonth = cellDate.month == today.month

                if (isToday) {
                    val half = 18.5f
                    canvas.drawRoundRect(
                        RectF(cx - half, rowCenterY - half, cx + half, rowCenterY + half),
                        9f,
                        9f,
                        todayBgPaint
                    )
                    canvas.drawText(cellDate.dayOfMonth.toString(), cx, rowCenterY + 7.5f, todayTextPaint)
                } else {
                    val paint = if (inCurrentMonth) activeDayPaint else mutedDayPaint
                    canvas.drawText(cellDate.dayOfMonth.toString(), cx, rowCenterY + 7.5f, paint)
                }
                cellDate = cellDate.plusDays(1)
            }
        }

        return bmp
    }

    fun renderColorBarBitmap(colorInt: Int): Bitmap {
        val bmp = Bitmap.createBitmap(8, 56, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorInt
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(0f, 0f, 8f, 56f), 4f, 4f, paint)
        return bmp
    }
}
