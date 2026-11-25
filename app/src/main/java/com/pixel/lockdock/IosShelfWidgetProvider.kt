package com.pixel.lockdock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

class IosShelfWidgetProvider : AppWidgetProvider() {

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
        private val CONTAINER_IDS = intArrayOf(
            R.id.slot_0_container,
            R.id.slot_1_container,
            R.id.slot_2_container,
            R.id.slot_3_container
        )

        private val IMG_IDS = intArrayOf(
            R.id.slot_0_img,
            R.id.slot_1_img,
            R.id.slot_2_img,
            R.id.slot_3_img
        )

        private val HEADER_IDS = intArrayOf(
            R.id.slot_0_header,
            R.id.slot_1_header,
            R.id.slot_2_header,
            R.id.slot_3_header
        )

        private val TITLE_IDS = intArrayOf(
            R.id.slot_0_title,
            R.id.slot_1_title,
            R.id.slot_2_title,
            R.id.slot_3_title
        )

        private val SUBTITLE_IDS = intArrayOf(
            R.id.slot_0_subtitle,
            R.id.slot_1_subtitle,
            R.id.slot_2_subtitle,
            R.id.slot_3_subtitle
        )

        fun updateWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            val config = LockDockRepository.loadConfig(context)
            val rv = buildShelfRemoteViews(
                context = context,
                config = config,
                showCapsuleBlock = config.showTopCapsule,
                showShelfRowBlock = true
            )
            for (id in appWidgetIds) {
                appWidgetManager.updateAppWidget(id, rv)
            }
        }

        fun buildShelfRemoteViews(
            context: Context,
            config: LockDockConfig,
            showCapsuleBlock: Boolean = config.showTopCapsule,
            showShelfRowBlock: Boolean = config.showIosShelfInOverlay
        ): RemoteViews {
            val normalized = LockDockRepository.normalizedFourUnitSlots(config.slots)
            val pattern = normalized.joinToString("") { it.type.span.toString() }
            val layoutId = when (pattern) {
                "211" -> R.layout.widget_shelf_211
                "121" -> R.layout.widget_shelf_121
                "112" -> R.layout.widget_shelf_112
                "22" -> R.layout.widget_shelf_22
                else -> R.layout.widget_shelf_1111
            }

            val views = RemoteViews(context.packageName, layoutId)

            // Block 1: Top Capsule (completely independent!)
            if (!showCapsuleBlock || config.inlineMode == InlineMode.HIDDEN) {
                views.setViewVisibility(R.id.inline_container, View.GONE)
            } else {
                views.setViewVisibility(R.id.inline_container, View.VISIBLE)
                views.setTextViewText(R.id.inline_text, formatInlineText(context, config))
                val quickEditPi = DeviceStateHelper.createActivityPendingIntent(
                    context,
                    900,
                    Intent(context, QuickActionActivity::class.java)
                )
                views.setOnClickPendingIntent(R.id.inline_container, quickEditPi)
            }

            // Block 2: iPhone 4-Slot Shelf Row (completely independent!)
            val hasActiveSlots = normalized.any { it.type != SlotWidgetType.EMPTY_SLOT }
            if (!showShelfRowBlock || (!hasActiveSlots && config.hideEmptySlotsOnLockScreen)) {
                views.setViewVisibility(R.id.shelf_inner_box, View.GONE)
                return views
            }

            views.setViewVisibility(R.id.shelf_inner_box, View.VISIBLE)
            views.setInt(
                R.id.shelf_inner_box,
                "setBackgroundResource",
                if (config.showShelfOuterFrame) R.drawable.bg_frosted_shelf else 0
            )

            normalized.forEachIndexed { index, item ->
                if (index >= CONTAINER_IDS.size) return@forEachIndexed
                val containerId = CONTAINER_IDS[index]

                if (item.type == SlotWidgetType.EMPTY_SLOT && config.hideEmptySlotsOnLockScreen) {
                    views.setViewVisibility(containerId, View.GONE)
                    return@forEachIndexed
                }

                views.setViewVisibility(containerId, View.VISIBLE)
                if (item.type.span == 1) {
                    val bmp = IosCircleRenderer.renderSlotCircle(context, item, config)
                    views.setImageViewBitmap(IMG_IDS[index], bmp)
                } else {
                    val (header, title, subtitle) = formatPillStrings(item, config)
                    views.setTextViewText(HEADER_IDS[index], header)
                    views.setTextViewText(TITLE_IDS[index], title)
                    views.setTextViewText(SUBTITLE_IDS[index], subtitle)
                }

                val pi = buildSlotPendingIntent(context, index, item)
                views.setOnClickPendingIntent(containerId, pi)
            }

            return views
        }

        fun formatInlineText(context: Context, config: LockDockConfig): String {
            return when (config.inlineMode) {
                InlineMode.DATE_AND_BATTERY -> {
                    val now = LocalDate.now()
                    val dow = now.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.TAIWAN)
                    val (bat, chg) = DeviceStateHelper.getBatteryStatus(context)
                    val bolt = if (chg) "⚡" else "🔋"
                    "📅 ${now.monthValue}月${now.dayOfMonth}日 $dow  ·  $bolt $bat%"
                }

                InlineMode.COUNTDOWN_INLINE -> {
                    val cdSlot = config.slots.firstOrNull { it.type == SlotWidgetType.COUNTDOWN_PILL }
                    val label = cdSlot?.customLabel?.ifBlank { "目標日" } ?: "跨年倒數"
                    val dateIso = cdSlot?.targetDateIso ?: "${LocalDate.now().year + 1}-01-01"
                    val days = LockDockRepository.computeDaysDiff(dateIso)
                    val dayStr = when {
                        days > 0 -> "還有 $days 天"
                        days == 0L -> "就是今天！"
                        else -> "已過 ${-days} 天"
                    }
                    "⏳ $label · $dayStr"
                }

                InlineMode.MEMO_INLINE -> {
                    "✨ ${config.memoText.ifBlank { "點擊快速寫鎖屏備忘錄" }}"
                }

                InlineMode.HIDDEN -> ""
            }
        }

        fun formatPillStrings(item: SlotItem, config: LockDockConfig): Triple<String, String, String> {
            return when (item.type) {
                SlotWidgetType.COUNTDOWN_PILL -> {
                    val emoji = item.customEmoji.ifBlank { "📅" }
                    val label = item.customLabel.ifBlank { "重要日子" }
                    val days = LockDockRepository.computeDaysDiff(item.targetDateIso)
                    val main = when {
                        days > 0 -> "還有 $days 天"
                        days == 0L -> "🎉 就是今天"
                        else -> "已過 ${-days} 天"
                    }
                    Triple("$emoji $label", main, item.targetDateIso)
                }

                SlotWidgetType.TODO_PILL -> {
                    val pending = config.todos.filter { !it.done }
                    val total = config.todos.size
                    val doneCount = total - pending.size
                    val firstTitle = pending.firstOrNull()?.text ?: "🎉 今日待辦全部完成！"
                    val sub = if (pending.size > 1) {
                        "還有 ${pending.size} 項 · 點擊快速查看"
                    } else {
                        "已完成 $doneCount/$total · 點擊編輯"
                    }
                    Triple("✅ 待辦事項", firstTitle, sub)
                }

                SlotWidgetType.MEMO_PILL -> {
                    val text = config.memoText.ifBlank { "點擊新增鎖定畫面備忘錄" }
                    Triple("📝 鎖屏備忘錄", text, "點擊免解鎖即時修改")
                }

                else -> Triple("LockDock", item.type.title, "點擊自訂")
            }
        }

        private fun buildSlotPendingIntent(
            context: Context,
            slotIndex: Int,
            item: SlotItem
        ): PendingIntent {
            val reqBase = 100 + slotIndex * 10
            val bcastFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

            return when (item.type) {
                SlotWidgetType.WATER_RING -> {
                    val intent = Intent(context, WidgetActionReceiver::class.java).apply {
                        action = WidgetActionReceiver.ACTION_WATER_ADD
                    }
                    PendingIntent.getBroadcast(context, reqBase + 1, intent, bcastFlags)
                }

                SlotWidgetType.HABIT_STREAK -> {
                    val intent = Intent(context, WidgetActionReceiver::class.java).apply {
                        action = WidgetActionReceiver.ACTION_HABIT_ADD
                    }
                    PendingIntent.getBroadcast(context, reqBase + 2, intent, bcastFlags)
                }

                SlotWidgetType.TORCH_TOGGLE -> {
                    val intent = Intent(context, WidgetActionReceiver::class.java).apply {
                        action = WidgetActionReceiver.ACTION_TORCH_TOGGLE
                    }
                    PendingIntent.getBroadcast(context, reqBase + 3, intent, bcastFlags)
                }

                SlotWidgetType.BATTERY_RING, SlotWidgetType.YEAR_RING -> {
                    val intent = Intent(context, WidgetActionReceiver::class.java).apply {
                        action = WidgetActionReceiver.ACTION_REFRESH_ALL
                    }
                    PendingIntent.getBroadcast(context, reqBase + 4, intent, bcastFlags)
                }

                SlotWidgetType.APP_SHORTCUT -> {
                    if (item.appPackage.isNotBlank()) {
                        val intent = Intent(context, LockLaunchActivity::class.java).apply {
                            putExtra(LockLaunchActivity.EXTRA_TARGET_PACKAGE, item.appPackage)
                            putExtra(LockLaunchActivity.EXTRA_TARGET_LABEL, item.appName)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        DeviceStateHelper.createActivityPendingIntent(context, reqBase + 5, intent)
                    } else {
                        val intent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        DeviceStateHelper.createActivityPendingIntent(context, reqBase + 6, intent)
                    }
                }

                SlotWidgetType.MEMO_PILL, SlotWidgetType.TODO_PILL -> {
                    val intent = Intent(context, QuickActionActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    DeviceStateHelper.createActivityPendingIntent(context, reqBase + 7, intent)
                }

                SlotWidgetType.COUNTDOWN_PILL, SlotWidgetType.EMPTY_SLOT -> {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    DeviceStateHelper.createActivityPendingIntent(context, reqBase + 8, intent)
                }
            }
        }
    }
}
