package com.pixel.lockdock

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class SlotWidgetType(
    val span: Int,
    val title: String,
    val subtitle: String,
    val badge: String
) {
    BATTERY_RING(1, "電量圓環", "即時顯示 Pixel 電量與充電狀態", "🔋 1×1"),
    WATER_RING(1, "喝水打卡環", "鎖屏直接點擊 +1 杯，每日自動歸零", "💧 1×1"),
    TORCH_TOGGLE(1, "手電筒開關", "免解鎖直接在鎖定畫面開關閃光燈", "🔦 1×1"),
    HABIT_STREAK(1, "習慣計數環", "自訂圖示與目標，鎖屏點擊 +1 紀錄", "🔥 1×1"),
    YEAR_RING(1, "年度進度環", "以 iOS 圓環顯示今年已過百分比", "⏳ 1×1"),
    APP_SHORTCUT(1, "自選 App 捷徑", "任選喜愛 App，刷臉/指紋秒開", "🚀 1×1"),
    COUNTDOWN_PILL(2, "倒數日膠囊", "重要紀念日、旅遊或目標倒數天數", "📅 2×1"),
    TODO_PILL(2, "待辦事項膠囊", "鎖屏直接點擊打勾或免解鎖新增", "✅ 2×1"),
    MEMO_PILL(2, "鎖屏備忘錄", "置頂座右銘或提醒，免解鎖隨時改", "📝 2×1"),
    EMPTY_SLOT(1, "空白插槽", "點擊自訂小工具", "＋ 1×1")
}

enum class InlineMode(val label: String) {
    DATE_AND_BATTERY("日期 ＋ 即時電量"),
    COUNTDOWN_INLINE("倒數日提醒膠囊"),
    MEMO_INLINE("自訂一句話座右銘"),
    HIDDEN("隱藏頂部膠囊")
}

data class SlotItem(
    val id: String = UUID.randomUUID().toString(),
    val type: SlotWidgetType,
    val enabled: Boolean = true,
    val customLabel: String = "",
    val customEmoji: String = "🔥",
    val targetDateIso: String = LocalDate.now().plusDays(37).toString(),
    val appPackage: String = "",
    val appName: String = ""
) {
    fun displayTitle(): String {
        return when (type) {
            SlotWidgetType.COUNTDOWN_PILL -> "${customEmoji.ifBlank { "📅" }} ${customLabel.ifBlank { "倒數日" }}"
            SlotWidgetType.APP_SHORTCUT -> "🚀 ${appName.ifBlank { "App 捷徑" }}"
            SlotWidgetType.HABIT_STREAK -> "${customEmoji.ifBlank { "🔥" }} 習慣打卡"
            else -> type.title
        }
    }
}

data class TodoItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val done: Boolean = false
)

data class HostedWidgetEntry(
    val appWidgetId: Int,
    val label: String,
    val enabled: Boolean = true,
    val heightDp: Int = 195,
    val scalePercent: Int = 100,
    val extraTrimDp: Int = 0
)

data class LockDockConfig(
    val showTopCapsule: Boolean = true,
    val inlineMode: InlineMode = InlineMode.DATE_AND_BATTERY,
    val slots: List<SlotItem> = defaultSlots(),
    val hideEmptySlotsOnLockScreen: Boolean = true,
    val showShelfOuterFrame: Boolean = true,
    val memoText: String = "保持專注，今天也要閃閃發光 ✨",
    val todos: List<TodoItem> = defaultTodos(),
    val waterCount: Int = 3,
    val waterGoal: Int = 8,
    val habitCount: Int = 1,
    val habitGoal: Int = 5,
    val lastCounterDate: String = LocalDate.now().toString(),
    val overlayEnabled: Boolean = false,
    val showIosShelfInOverlay: Boolean = true,
    val shelfBelowHosted: Boolean = false,
    val blockSpacingDp: Int = 6,
    val overlayYOffsetDp: Int = 220,
    val overlayAlphaPercent: Int = 95,
    val passiveLongPressDismiss: Boolean = true,
    val showQuickDismissPill: Boolean = false,
    val dismissOnBiometricUnlock: Boolean = false,
    val hideOnCallAndAlarm: Boolean = true,
    val hostedWidgets: List<HostedWidgetEntry> = emptyList(),
    val calendarDaysAhead: Int = 14,
    val calendarShowAllDay: Boolean = true,
    val widgetCornerRadiusDp: Int = WidgetCornerStyler.DEFAULT_CORNER_RADIUS_DP,
    val syncCircleSlotsWithCornerRadius: Boolean = true,
    val widgetWidthPercent: Int = WidgetCornerStyler.DEFAULT_WIDTH_PERCENT,
    val autoAlignHostedWidgetEdges: Boolean = true,
    val stretchTopCapsuleToUnifiedWidth: Boolean = false
) {
    companion object {
        fun defaultSlots(): List<SlotItem> = listOf(
            SlotItem(type = SlotWidgetType.BATTERY_RING),
            SlotItem(type = SlotWidgetType.WATER_RING),
            SlotItem(
                type = SlotWidgetType.COUNTDOWN_PILL,
                customLabel = "跨年倒數",
                customEmoji = "🎉",
                targetDateIso = "${LocalDate.now().year + 1}-01-01"
            )
        )

        fun defaultTodos(): List<TodoItem> = listOf(
            TodoItem(text = "點擊鎖屏圓圈直接完成待辦", done = false),
            TodoItem(text = "自訂專屬 iPhone 風格鎖屏小工具", done = true),
            TodoItem(text = "免解鎖快速記錄靈感筆記", done = false)
        )
    }
}

object LockDockRepository {
    private const val PREFS_NAME = "lockdock_prefs"
    private const val KEY_CONFIG_JSON = "config_json_v1"
    private const val KEY_TORCH_ON = "torch_state_on"
    const val APPWIDGET_HOST_ID = 2026

    fun loadConfig(context: Context): LockDockConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CONFIG_JSON, null) ?: return LockDockConfig()
        return try {
            val obj = JSONObject(raw)
            val today = LocalDate.now().toString()
            val savedDate = obj.optString("lastCounterDate", today)
            val isNewDay = savedDate != today

            val slotsArr = obj.optJSONArray("slots") ?: JSONArray()
            val parsedSlots = mutableListOf<SlotItem>()
            var usedSpan = 0
            for (i in 0 until slotsArr.length()) {
                val s = slotsArr.getJSONObject(i)
                val typeName = s.optString("type", SlotWidgetType.BATTERY_RING.name)
                val type = runCatching { SlotWidgetType.valueOf(typeName) }
                    .getOrDefault(SlotWidgetType.BATTERY_RING)
                if (type != SlotWidgetType.EMPTY_SLOT && usedSpan + type.span <= 4) {
                    usedSpan += type.span
                    parsedSlots.add(
                        SlotItem(
                            id = s.optString("id", UUID.randomUUID().toString()),
                            type = type,
                            enabled = s.optBoolean("enabled", true),
                            customLabel = s.optString("customLabel", ""),
                            customEmoji = s.optString("customEmoji", "🔥"),
                            targetDateIso = s.optString("targetDateIso", "${LocalDate.now().year + 1}-01-01"),
                            appPackage = s.optString("appPackage", ""),
                            appName = s.optString("appName", "")
                        )
                    )
                }
            }

            val todosArr = obj.optJSONArray("todos") ?: JSONArray()
            val parsedTodos = mutableListOf<TodoItem>()
            for (i in 0 until todosArr.length()) {
                val t = todosArr.getJSONObject(i)
                val text = t.optString("text", "").trim()
                if (text.isNotEmpty()) {
                    parsedTodos.add(
                        TodoItem(
                            id = t.optString("id", UUID.randomUUID().toString()),
                            text = text,
                            done = t.optBoolean("done", false)
                        )
                    )
                }
            }

            val parsedHosted = mutableListOf<HostedWidgetEntry>()
            val hostedArr = obj.optJSONArray("hostedWidgets")
            if (hostedArr != null) {
                for (i in 0 until hostedArr.length()) {
                    val h = hostedArr.getJSONObject(i)
                    val id = h.optInt("appWidgetId", -1)
                    if (id >= 0) {
                        parsedHosted.add(
                            HostedWidgetEntry(
                                appWidgetId = id,
                                label = h.optString("label", "桌面小工具"),
                                enabled = h.optBoolean("enabled", true),
                                heightDp = h.optInt("heightDp", 195).coerceIn(60, 400),
                                scalePercent = h.optInt("scalePercent", 100).coerceIn(75, 100),
                                extraTrimDp = h.optInt("extraTrimDp", 0).coerceIn(-12, 24)
                            )
                        )
                    }
                }
            } else {
                val legacyId = obj.optInt("hostedAppWidgetId", -1)
                if (legacyId >= 0) {
                    parsedHosted.add(
                        HostedWidgetEntry(
                            appWidgetId = legacyId,
                            label = "已嵌入小工具",
                            enabled = true,
                            heightDp = obj.optInt("hostedWidgetHeightDp", 195).coerceIn(60, 400),
                            scalePercent = 100,
                            extraTrimDp = 0
                        )
                    )
                }
            }

            val rawInlineMode = runCatching {
                InlineMode.valueOf(obj.optString("inlineMode", InlineMode.DATE_AND_BATTERY.name))
            }.getOrDefault(InlineMode.DATE_AND_BATTERY)

            val showCapsuleDefault = rawInlineMode != InlineMode.HIDDEN
            val showTopCapsule = obj.optBoolean("showTopCapsule", showCapsuleDefault)
            val normalizedInlineMode = if (rawInlineMode == InlineMode.HIDDEN) {
                InlineMode.DATE_AND_BATTERY
            } else {
                rawInlineMode
            }

            val cfg = LockDockConfig(
                showTopCapsule = showTopCapsule,
                inlineMode = normalizedInlineMode,
                slots = if (parsedSlots.isEmpty() && !obj.has("slots")) LockDockConfig.defaultSlots() else parsedSlots,
                hideEmptySlotsOnLockScreen = obj.optBoolean("hideEmptySlotsOnLockScreen", true),
                showShelfOuterFrame = obj.optBoolean("showShelfOuterFrame", true),
                memoText = obj.optString("memoText", "保持專注，今天也要閃閃發光 ✨"),
                todos = if (parsedTodos.isEmpty()) LockDockConfig.defaultTodos() else parsedTodos,
                waterCount = if (isNewDay) 0 else obj.optInt("waterCount", 3),
                waterGoal = obj.optInt("waterGoal", 8).coerceIn(1, 30),
                habitCount = if (isNewDay) 0 else obj.optInt("habitCount", 1),
                habitGoal = obj.optInt("habitGoal", 5).coerceIn(1, 50),
                lastCounterDate = today,
                overlayEnabled = obj.optBoolean("overlayEnabled", false),
                showIosShelfInOverlay = obj.optBoolean("showIosShelfInOverlay", true),
                shelfBelowHosted = obj.optBoolean("shelfBelowHosted", false),
                blockSpacingDp = obj.optInt("blockSpacingDp", 6).coerceIn(0, 24),
                overlayYOffsetDp = obj.optInt("overlayYOffsetDp", 220).coerceIn(60, 620),
                overlayAlphaPercent = obj.optInt("overlayAlphaPercent", 95).coerceIn(45, 100),
                passiveLongPressDismiss = obj.optBoolean("passiveLongPressDismiss", true),
                showQuickDismissPill = obj.optBoolean("showQuickDismissPill", false),
                dismissOnBiometricUnlock = obj.optBoolean("dismissOnBiometricUnlock", false),
                hideOnCallAndAlarm = obj.optBoolean("hideOnCallAndAlarm", true),
                hostedWidgets = parsedHosted,
                calendarDaysAhead = obj.optInt("calendarDaysAhead", 14).coerceIn(1, 60),
                calendarShowAllDay = obj.optBoolean("calendarShowAllDay", true),
                widgetCornerRadiusDp = obj.optInt(
                    "widgetCornerRadiusDp",
                    WidgetCornerStyler.DEFAULT_CORNER_RADIUS_DP
                ).coerceIn(
                    WidgetCornerStyler.MIN_CORNER_RADIUS_DP,
                    WidgetCornerStyler.MAX_CORNER_RADIUS_DP
                ),
                syncCircleSlotsWithCornerRadius = obj.optBoolean("syncCircleSlotsWithCornerRadius", true),
                widgetWidthPercent = obj.optInt(
                    "widgetWidthPercent",
                    WidgetCornerStyler.DEFAULT_WIDTH_PERCENT
                ).coerceIn(
                    WidgetCornerStyler.MIN_WIDTH_PERCENT,
                    WidgetCornerStyler.MAX_WIDTH_PERCENT
                ),
                autoAlignHostedWidgetEdges = obj.optBoolean("autoAlignHostedWidgetEdges", true),
                stretchTopCapsuleToUnifiedWidth = obj.optBoolean("stretchTopCapsuleToUnifiedWidth", false)
            )
            if (isNewDay) {
                saveConfig(context, cfg, notify = false)
            }
            cfg
        } catch (_: Exception) {
            LockDockConfig()
        }
    }

    fun saveConfig(context: Context, config: LockDockConfig, notify: Boolean = true) {
        val obj = JSONObject().apply {
            put("showTopCapsule", config.showTopCapsule)
            put("inlineMode", config.inlineMode.name)
            put("hideEmptySlotsOnLockScreen", config.hideEmptySlotsOnLockScreen)
            put("showShelfOuterFrame", config.showShelfOuterFrame)
            put("memoText", config.memoText)
            put("waterCount", config.waterCount)
            put("waterGoal", config.waterGoal)
            put("habitCount", config.habitCount)
            put("habitGoal", config.habitGoal)
            put("lastCounterDate", config.lastCounterDate)
            put("overlayEnabled", config.overlayEnabled)
            put("showIosShelfInOverlay", config.showIosShelfInOverlay)
            put("shelfBelowHosted", config.shelfBelowHosted)
            put("blockSpacingDp", config.blockSpacingDp)
            put("overlayYOffsetDp", config.overlayYOffsetDp)
            put("overlayAlphaPercent", config.overlayAlphaPercent)
            put("passiveLongPressDismiss", config.passiveLongPressDismiss)
            put("showQuickDismissPill", config.showQuickDismissPill)
            put("dismissOnBiometricUnlock", config.dismissOnBiometricUnlock)
            put("hideOnCallAndAlarm", config.hideOnCallAndAlarm)
            put("calendarDaysAhead", config.calendarDaysAhead)
            put("calendarShowAllDay", config.calendarShowAllDay)
            put("widgetCornerRadiusDp", config.widgetCornerRadiusDp)
            put("syncCircleSlotsWithCornerRadius", config.syncCircleSlotsWithCornerRadius)
            put("widgetWidthPercent", config.widgetWidthPercent)
            put("autoAlignHostedWidgetEdges", config.autoAlignHostedWidgetEdges)
            put("stretchTopCapsuleToUnifiedWidth", config.stretchTopCapsuleToUnifiedWidth)

            val slotsArr = JSONArray()
            config.slots.forEach { s ->
                slotsArr.put(
                    JSONObject().apply {
                        put("id", s.id)
                        put("type", s.type.name)
                        put("enabled", s.enabled)
                        put("customLabel", s.customLabel)
                        put("customEmoji", s.customEmoji)
                        put("targetDateIso", s.targetDateIso)
                        put("appPackage", s.appPackage)
                        put("appName", s.appName)
                    }
                )
            }
            put("slots", slotsArr)

            val todosArr = JSONArray()
            config.todos.forEach { t ->
                todosArr.put(
                    JSONObject().apply {
                        put("id", t.id)
                        put("text", t.text)
                        put("done", t.done)
                    }
                )
            }
            put("todos", todosArr)

            val hostedArr = JSONArray()
            config.hostedWidgets.forEach { h ->
                hostedArr.put(
                    JSONObject().apply {
                        put("appWidgetId", h.appWidgetId)
                        put("label", h.label)
                        put("enabled", h.enabled)
                        put("heightDp", h.heightDp)
                        put("scalePercent", h.scalePercent)
                        put("extraTrimDp", h.extraTrimDp)
                    }
                )
            }
            put("hostedWidgets", hostedArr)
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONFIG_JSON, obj.toString())
            .apply()

        if (notify) {
            notifyAllWidgetsChanged(context)
        }
    }

    fun isTorchOn(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TORCH_ON, false)
    }

    fun setTorchOn(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TORCH_ON, enabled)
            .apply()
    }

    fun normalizedFourUnitSlots(slots: List<SlotItem>): List<SlotItem> {
        val clean = mutableListOf<SlotItem>()
        var span = 0
        for (item in slots) {
            if (!item.enabled || item.type == SlotWidgetType.EMPTY_SLOT) continue
            if (span + item.type.span <= 4) {
                clean.add(item)
                span += item.type.span
            }
        }
        while (span < 4) {
            clean.add(SlotItem(type = SlotWidgetType.EMPTY_SLOT))
            span += 1
        }
        return clean
    }

    fun computeDaysDiff(targetDateIso: String): Long {
        return try {
            val target = LocalDate.parse(targetDateIso)
            ChronoUnit.DAYS.between(LocalDate.now(), target)
        } catch (_: Exception) {
            0L
        }
    }

    fun notifyAllWidgetsChanged(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)

        val shelfIds = manager.getAppWidgetIds(ComponentName(appContext, IosShelfWidgetProvider::class.java))
        if (shelfIds.isNotEmpty()) {
            IosShelfWidgetProvider.updateWidgets(appContext, manager, shelfIds)
        }

        val cardIds = manager.getAppWidgetIds(ComponentName(appContext, TodoMemoWidgetProvider::class.java))
        if (cardIds.isNotEmpty()) {
            TodoMemoWidgetProvider.updateWidgets(appContext, manager, cardIds)
        }

        val kmCalIds = manager.getAppWidgetIds(ComponentName(appContext, KmCalendarWidgetProvider::class.java))
        if (kmCalIds.isNotEmpty()) {
            KmCalendarWidgetProvider.updateWidgets(appContext, manager, kmCalIds)
        }

        LockScreenOverlayService.requestRefresh(forceRebindHosted = false)
    }
}
