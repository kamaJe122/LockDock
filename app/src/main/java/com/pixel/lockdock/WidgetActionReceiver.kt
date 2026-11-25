package com.pixel.lockdock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate

class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_WATER_ADD -> {
                val cfg = LockDockRepository.loadConfig(context)
                val updated = cfg.copy(
                    waterCount = (cfg.waterCount + 1).coerceAtMost(99),
                    lastCounterDate = LocalDate.now().toString()
                )
                DeviceStateHelper.vibrateClick(context)
                LockDockRepository.saveConfig(context, updated, notify = true)
            }

            ACTION_HABIT_ADD -> {
                val cfg = LockDockRepository.loadConfig(context)
                val updated = cfg.copy(
                    habitCount = (cfg.habitCount + 1).coerceAtMost(999),
                    lastCounterDate = LocalDate.now().toString()
                )
                DeviceStateHelper.vibrateClick(context)
                LockDockRepository.saveConfig(context, updated, notify = true)
            }

            ACTION_TORCH_TOGGLE -> {
                DeviceStateHelper.toggleTorch(context)
                LockDockRepository.notifyAllWidgetsChanged(context)
            }

            ACTION_TODO_TOGGLE -> {
                val index = intent.getIntExtra(EXTRA_TODO_INDEX, -1)
                val cfg = LockDockRepository.loadConfig(context)
                if (index in cfg.todos.indices) {
                    val newList = cfg.todos.mapIndexed { idx, item ->
                        if (idx == index) item.copy(done = !item.done) else item
                    }
                    DeviceStateHelper.vibrateClick(context)
                    LockDockRepository.saveConfig(context, cfg.copy(todos = newList), notify = true)
                } else {
                    // Toggle first unchecked todo when tapped from 2x1 pill
                    val firstUnchecked = cfg.todos.indexOfFirst { !it.done }
                    val targetIdx = if (firstUnchecked >= 0) firstUnchecked else 0
                    if (targetIdx in cfg.todos.indices) {
                        val newList = cfg.todos.mapIndexed { idx, item ->
                            if (idx == targetIdx) item.copy(done = !item.done) else item
                        }
                        DeviceStateHelper.vibrateClick(context)
                        LockDockRepository.saveConfig(context, cfg.copy(todos = newList), notify = true)
                    }
                }
            }

            ACTION_REFRESH_ALL -> {
                LockDockRepository.notifyAllWidgetsChanged(context)
            }
        }
    }

    companion object {
        const val ACTION_WATER_ADD = "com.pixel.lockdock.ACTION_WATER_ADD"
        const val ACTION_HABIT_ADD = "com.pixel.lockdock.ACTION_HABIT_ADD"
        const val ACTION_TORCH_TOGGLE = "com.pixel.lockdock.ACTION_TORCH_TOGGLE"
        const val ACTION_TODO_TOGGLE = "com.pixel.lockdock.ACTION_TODO_TOGGLE"
        const val ACTION_REFRESH_ALL = "com.pixel.lockdock.ACTION_REFRESH_ALL"
        const val EXTRA_TODO_INDEX = "extra_todo_index"
    }
}
