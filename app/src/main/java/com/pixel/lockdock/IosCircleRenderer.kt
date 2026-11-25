package com.pixel.lockdock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.time.LocalDate

/**
 * Renders crisp, authentic iOS Lock Screen circular 1x1 frosted-glass gauge widgets
 * within Android 17's RemoteViews bitmap memory limits (160x160 ARGB_8888 = ~100KB).
 */
object IosCircleRenderer {

    private const val SIZE = 160

    fun renderSlotCircle(
        context: Context,
        item: SlotItem,
        config: LockDockConfig
    ): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = SIZE / 2f
        val cy = SIZE / 2f
        val outerRadius = SIZE / 2f - 4f

        // Frosted glass circular background
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (item.type == SlotWidgetType.EMPTY_SLOT) {
                Color.argb(42, 30, 38, 56)
            } else {
                Color.argb(92, 28, 35, 52)
            }
        }
        canvas.drawCircle(cx, cy, outerRadius, bgPaint)

        // Subtle glass rim
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.4f
            color = Color.argb(95, 255, 255, 255)
        }
        canvas.drawCircle(cx, cy, outerRadius, borderPaint)

        when (item.type) {
            SlotWidgetType.BATTERY_RING -> {
                val (level, charging) = DeviceStateHelper.getBatteryStatus(context)
                val progress = level / 100f
                val accent = when {
                    charging -> Color.rgb(105, 240, 174)
                    level <= 20 -> Color.rgb(255, 110, 110)
                    else -> Color.rgb(255, 255, 255)
                }
                drawOpenGaugeArc(canvas, progress, accent)
                drawCenterMainText(canvas, "$level%", 32f, cy + 4f)
                drawBottomBadge(canvas, if (charging) "⚡" else "🔋", cy + 46f, 24f)
            }

            SlotWidgetType.WATER_RING -> {
                val goal = config.waterGoal.coerceAtLeast(1)
                val count = config.waterCount
                val progress = (count.toFloat() / goal.toFloat()).coerceIn(0f, 1f)
                drawOpenGaugeArc(canvas, progress, Color.rgb(112, 209, 255))
                drawCenterMainText(canvas, "$count/$goal", 29f, cy + 4f)
                drawBottomBadge(canvas, "💧+1", cy + 46f, 21f)
            }

            SlotWidgetType.HABIT_STREAK -> {
                val goal = config.habitGoal.coerceAtLeast(1)
                val count = config.habitCount
                val progress = (count.toFloat() / goal.toFloat()).coerceIn(0f, 1f)
                val emoji = item.customEmoji.ifBlank { "🔥" }
                drawOpenGaugeArc(canvas, progress, Color.rgb(255, 183, 77))
                drawCenterMainText(canvas, "$count/$goal", 29f, cy + 4f)
                drawBottomBadge(canvas, emoji, cy + 46f, 23f)
            }

            SlotWidgetType.YEAR_RING -> {
                val now = LocalDate.now()
                val pct = ((now.dayOfYear.toFloat() / now.lengthOfYear().toFloat()) * 100f).toInt()
                drawOpenGaugeArc(canvas, pct / 100f, Color.rgb(197, 163, 255))
                drawCenterMainText(canvas, "$pct%", 31f, cy + 4f)
                drawBottomBadge(canvas, "${now.year}", cy + 45f, 19f)
            }

            SlotWidgetType.TORCH_TOGGLE -> {
                val on = LockDockRepository.isTorchOn(context)
                if (on) {
                    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = Color.argb(180, 255, 213, 79)
                    }
                    canvas.drawCircle(cx, cy, outerRadius - 6f, glowPaint)
                }
                drawCenterMainText(
                    canvas,
                    "🔦",
                    44f,
                    cy + 6f,
                    textColor = if (on) Color.rgb(30, 30, 30) else Color.WHITE
                )
                drawBottomBadge(
                    canvas,
                    if (on) "開啟" else "手電筒",
                    cy + 46f,
                    20f,
                    textColor = if (on) Color.rgb(25, 25, 25) else Color.argb(220, 255, 255, 255)
                )
            }

            SlotWidgetType.APP_SHORTCUT -> {
                val iconBmp = DeviceStateHelper.loadAppIconBitmap(context, item.appPackage, 74)
                if (iconBmp != null) {
                    val left = cx - iconBmp.width / 2f
                    val top = cy - iconBmp.height / 2f - 10f
                    val clipPath = Path().apply {
                        addRoundRect(
                            RectF(left, top, left + iconBmp.width, top + iconBmp.height),
                            20f,
                            20f,
                            Path.Direction.CW
                        )
                    }
                    canvas.save()
                    canvas.clipPath(clipPath)
                    canvas.drawBitmap(iconBmp, left, top, null)
                    canvas.restore()
                } else {
                    drawCenterMainText(canvas, "🚀", 40f, cy + 2f)
                }
                val shortLabel = item.appName.ifBlank { "選App" }.take(5)
                drawBottomBadge(canvas, shortLabel, cy + 47f, 19f)
            }

            else -> {
                drawCenterMainText(canvas, "＋", 48f, cy + 14f, Color.argb(180, 255, 255, 255))
            }
        }

        return bmp
    }

    private fun drawOpenGaugeArc(canvas: Canvas, progress: Float, accentColor: Int) {
        val margin = 19f
        val rect = RectF(margin, margin, SIZE - margin, SIZE - margin)

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 12f
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(65, 255, 255, 255)
        }
        // iOS Lock Screen open-bottom 270-degree gauge (starts at 135 deg, sweeps 270 deg)
        canvas.drawArc(rect, 135f, 270f, false, trackPaint)

        val clamped = progress.coerceIn(0.02f, 1f)
        val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 12f
            strokeCap = Paint.Cap.ROUND
            color = accentColor
        }
        canvas.drawArc(rect, 135f, 270f * clamped, false, fgPaint)
    }

    private fun drawCenterMainText(
        canvas: Canvas,
        text: String,
        textSizePx: Float,
        baselineY: Float,
        textColor: Int = Color.WHITE
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = textSizePx
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(text, SIZE / 2f, baselineY, paint)
    }

    private fun drawBottomBadge(
        canvas: Canvas,
        text: String,
        baselineY: Float,
        textSizePx: Float,
        textColor: Int = Color.argb(230, 255, 255, 255)
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = textSizePx
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(text, SIZE / 2f, baselineY, paint)
    }
}
