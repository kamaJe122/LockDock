package com.pixel.lockdock

import android.app.KeyguardManager
import android.content.ContentUris
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * Trampoline activity shown over the lock screen when an App Shortcut or Calendar button is tapped.
 * Requests smooth Pixel Face Unlock / Fingerprint dismissal via KeyguardManager
 * and immediately opens the target app or calendar event.
 */
class LockLaunchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val dismissOnly = intent?.getBooleanExtra(EXTRA_DISMISS_ONLY, false) == true
        val openCalendarInsert = intent?.getBooleanExtra(EXTRA_OPEN_CALENDAR_INSERT, false) == true
        val openCalendarEvent = intent?.getBooleanExtra(EXTRA_OPEN_CALENDAR_EVENT, false) == true
        val eventId = intent?.getLongExtra(EXTRA_CALENDAR_EVENT_ID, -1L) ?: -1L
        val beginMs = intent?.getLongExtra(EXTRA_CALENDAR_BEGIN_MS, 0L) ?: 0L
        val pkg = intent?.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()

        if (!dismissOnly && !openCalendarInsert && !openCalendarEvent && pkg.isBlank()) {
            finish()
            return
        }

        val actionRunner = {
            when {
                dismissOnly -> {}
                openCalendarInsert -> launchCalendarInsert()
                openCalendarEvent -> launchCalendarEvent(eventId, beginMs)
                else -> launchPackage(pkg)
            }
            finish()
        }

        val keyguardManager = getSystemService(KeyguardManager::class.java)
        if (keyguardManager != null && keyguardManager.isKeyguardLocked) {
            keyguardManager.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = actionRunner()
                    override fun onDismissCancelled() = finish()
                    override fun onDismissError() = actionRunner()
                }
            )
        } else {
            actionRunner()
        }
    }

    private fun launchCalendarInsert() {
        try {
            val insertIntent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(insertIntent)
        } catch (_: Exception) {
            Toast.makeText(this, "無法開啟日曆新增畫面", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchCalendarEvent(eventId: Long, beginMs: Long) {
        try {
            if (eventId > 0L) {
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = uri
                    if (beginMs > 0L) {
                        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMs)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(viewIntent)
            } else {
                val calUri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build()
                startActivity(Intent(Intent.ACTION_VIEW).setData(calUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (_: Exception) {
            Toast.makeText(this, "無法開啟行事曆", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchPackage(pkg: String) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, "找不到該應用程式", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "無法啟動應用程式", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_DISMISS_ONLY = "extra_dismiss_only"
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        const val EXTRA_TARGET_LABEL = "extra_target_label"
        const val EXTRA_OPEN_CALENDAR_INSERT = "extra_open_calendar_insert"
        const val EXTRA_OPEN_CALENDAR_EVENT = "extra_open_calendar_event"
        const val EXTRA_CALENDAR_EVENT_ID = "extra_calendar_event_id"
        const val EXTRA_CALENDAR_BEGIN_MS = "extra_calendar_begin_ms"
    }
}
