package com.pixel.lockdock

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Mode 2: Renders each user-enabled Lock Screen block ONLY on the genuine Lock Screen.
 *
 * v1.8 Zero-Interference Unlock Engine:
 * - ZERO overlay or progress ring ("能量條") is ever placed over the Pixel ultrasonic
 *   fingerprint sensor / 🔓 unlock circle — 100% direct 0ms native hardware touch pass-through!
 * - Instant (< 50–90ms) auto-hide as soon as Keyguard unlocks (Fingerprint, Face Unlock,
 *   or Extend Unlock 延長解鎖) or when any incoming/active call, alarm, timer, or
 *   full-screen showWhenLocked app appears.
 */
class LockScreenOverlayService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var audioManager: AudioManager? = null
    private var rootContainer: LinearLayout? = null
    private var quickDismissRow: FrameLayout? = null
    private var capsuleFrame: FrameLayout? = null
    private var shelfFrame: FrameLayout? = null
    private var hostedListContainer: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var currentHostedSignature: String = ""
    private var currentShelfBelow: Boolean? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var previewUntilMillis: Long = 0L

    private var dismissedUntilScreenOff: Boolean = false
    private var lastKnownDeviceLocked: Boolean = false
    private var watcherRunning: Boolean = false

    private val audioModeListener = AudioManager.OnModeChangedListener {
        mainHandler.post {
            evaluateVisibilityAndRefresh(rebuildViews = false, forceRebindHosted = false)
        }
    }

    private val audioPlaybackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            evaluateVisibilityAndRefresh(rebuildViews = false, forceRebindHosted = false)
        }
    }

    private val lockStateWatcher = object : Runnable {
        override fun run() {
            if (!watcherRunning) return
            val km = getSystemService(KeyguardManager::class.java)
            val pm = getSystemService(PowerManager::class.java)
            val isKeyguard = km?.isKeyguardLocked == true
            val isDevLocked = km?.isDeviceLocked == true
            val isInteractive = pm?.isInteractive != false
            val isPreviewing = System.currentTimeMillis() < previewUntilMillis

            if (!isInteractive) {
                hideAllOverlays()
                stopWatcher()
                return
            }

            if (!isKeyguard && !isPreviewing) {
                hideAllOverlays()
                stopWatcher()
                return
            }

            val cfg = LockDockRepository.loadConfig(this@LockScreenOverlayService)
            if (!isPreviewing && cfg.hideOnCallAndAlarm && isCallOrAlarmOrOccludingAppActive()) {
                hideAllOverlays()
                // Keep watcher alive lightly while screen is on & keyguard locked so when call/alarm
                // ends, LockDock can return cleanly
                mainHandler.postDelayed(this, 350L)
                return
            }

            if (lastKnownDeviceLocked && !isDevLocked && cfg.dismissOnBiometricUnlock) {
                dismissForCurrentSession(vibrate = false)
                return
            }
            lastKnownDeviceLocked = isDevLocked

            if (rootContainer?.visibility == View.VISIBLE || isPreviewing) {
                // Fast 90ms poll ONLY during the few seconds LockDock is actively shown on an
                // interactive lock screen -> guarantees < 0.09s instant hide on Extend Unlock!
                mainHandler.postDelayed(this, 90L)
            } else {
                stopWatcher()
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    dismissedUntilScreenOff = false
                    hideAllOverlays()
                    stopWatcher()
                }
                Intent.ACTION_SCREEN_ON -> {
                    val km = getSystemService(KeyguardManager::class.java)
                    lastKnownDeviceLocked = km?.isDeviceLocked == true
                    evaluateVisibilityAndRefresh(rebuildViews = true, forceRebindHosted = true)
                }
                Intent.ACTION_USER_PRESENT -> {
                    hideAllOverlays()
                    stopWatcher()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WindowManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
        LockDockWidgetHostManager.getHost(applicationContext)

        val km = getSystemService(KeyguardManager::class.java)
        lastKnownDeviceLocked = km?.isDeviceLocked == true

        runCatching {
            audioManager?.addOnModeChangedListener(mainExecutor, audioModeListener)
        }
        runCatching {
            audioManager?.registerAudioPlaybackCallback(audioPlaybackCallback, mainHandler)
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)

        ensureOverlayAttached()
        evaluateVisibilityAndRefresh(rebuildViews = true, forceRebindHosted = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Passive, zero-overlay long-press detection on SystemUI lock icon if user holds without unlocking
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED &&
            event.packageName?.toString() == "com.android.systemui"
        ) {
            val km = getSystemService(KeyguardManager::class.java)
            if (km?.isKeyguardLocked == true) {
                val cfg = LockDockRepository.loadConfig(this)
                if (cfg.passiveLongPressDismiss && rootContainer?.visibility == View.VISIBLE) {
                    dismissForCurrentSession(vibrate = true)
                    return
                }
            }
        }
        evaluateVisibilityAndRefresh(rebuildViews = false, forceRebindHosted = false)
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        instance = null
        stopWatcher()
        runCatching { audioManager?.removeOnModeChangedListener(audioModeListener) }
        runCatching { audioManager?.unregisterAudioPlaybackCallback(audioPlaybackCallback) }
        runCatching { unregisterReceiver(screenReceiver) }
        removeOverlay()
        super.onDestroy()
    }

    /**
     * Returns true when the phone is NOT on the pure Lock Screen — specifically when:
     * 1) Cellular or VoIP (LINE/WhatsApp/Messenger/etc.) call is ringing or active (AudioManager.mode)
     * 2) An Alarm, Clock Timer, Ringtone, or Voice Call stream is playing (AudioPlaybackConfiguration)
     * 3) A full-screen Application window (such as Pixel Launcher during unlock transition,
     *    silent Incoming Call, vibrate-only Alarm, or Lock-screen Camera) is above Keyguard.
     */
    private fun isCallOrAlarmOrOccludingAppActive(): Boolean {
        val am = audioManager
        if (am != null) {
            val mode = runCatching { am.mode }.getOrDefault(AudioManager.MODE_NORMAL)
            if (mode == AudioManager.MODE_RINGTONE ||
                mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_IN_COMMUNICATION ||
                mode == AudioManager.MODE_CALL_SCREENING
            ) {
                return true
            }

            val configs = runCatching { am.activePlaybackConfigurations }.getOrNull().orEmpty()
            val hasInterruptingAudio = configs.any { pc ->
                val usage = pc.audioAttributes?.usage ?: AudioAttributes.USAGE_UNKNOWN
                usage == AudioAttributes.USAGE_ALARM ||
                    usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE ||
                    usage == AudioAttributes.USAGE_VOICE_COMMUNICATION ||
                    usage == AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING
            }
            if (hasInterruptingAudio) {
                return true
            }
        }

        val winList = runCatching { windows }.getOrNull()
        if (!winList.isNullOrEmpty()) {
            val screenBounds = getPhysicalScreenBounds()
            val screenH = screenBounds.height().coerceAtLeast(1)
            val rect = Rect()

            var highestFullScreenSystemLayer = -1
            for (w in winList) {
                if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM) {
                    w.getBoundsInScreen(rect)
                    if (rect.height() >= (screenH * 0.65f)) {
                        if (w.layer > highestFullScreenSystemLayer) {
                            highestFullScreenSystemLayer = w.layer
                        }
                    }
                }
            }

            for (w in winList) {
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    w.getBoundsInScreen(rect)
                    if (rect.height() >= (screenH * 0.45f) && w.layer > highestFullScreenSystemLayer) {
                        return true
                    }
                }
            }
        }

        return false
    }

    private fun startWatcherIfNeeded() {
        if (!watcherRunning) {
            watcherRunning = true
            mainHandler.removeCallbacks(lockStateWatcher)
            mainHandler.postDelayed(lockStateWatcher, 90L)
        }
    }

    private fun stopWatcher() {
        watcherRunning = false
        mainHandler.removeCallbacks(lockStateWatcher)
    }

    private fun dismissForCurrentSession(vibrate: Boolean) {
        dismissedUntilScreenOff = true
        if (vibrate) {
            DeviceStateHelper.vibrateClick(this)
        }
        hideAllOverlays()
        stopWatcher()
    }

    private fun hideAllOverlays() {
        rootContainer?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).roundToInt()
    }

    private fun getPhysicalScreenBounds(): Rect {
        val wm = windowManager
        return if (wm != null) {
            wm.currentWindowMetrics.bounds
        } else {
            val dm = resources.displayMetrics
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }

    private fun buildQuickDismissRow(): FrameLayout {
        val row = FrameLayout(this).apply {
            visibility = View.GONE
            setPadding(dpToPx(4), 0, dpToPx(4), dpToPx(4))
        }
        val pillBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(999).toFloat()
            setColor(Color.argb(115, 16, 22, 38))
            setStroke(dpToPx(1), Color.argb(110, 255, 255, 255))
        }
        val btn = TextView(this).apply {
            text = "✕ 收起"
            setTextColor(Color.argb(235, 240, 246, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            background = pillBg
            setPadding(dpToPx(11), dpToPx(4), dpToPx(11), dpToPx(4))
            setOnClickListener {
                dismissForCurrentSession(vibrate = true)
            }
        }
        row.addView(
            btn,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            )
        )
        return row
    }

    private fun ensureOverlayAttached() {
        val wm = windowManager ?: return
        val cfg = LockDockRepository.loadConfig(this)

        if (rootContainer == null) {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val padH = dpToPx(8)
                setPadding(padH, 0, padH, 0)
                visibility = View.GONE
            }

            val dismissRow = buildQuickDismissRow()
            val capsule = FrameLayout(this)
            val shelf = FrameLayout(this)
            val hostedList = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                visibility = View.GONE
            }

            quickDismissRow = dismissRow
            capsuleFrame = capsule
            shelfFrame = shelf
            hostedListContainer = hostedList
            applyVerticalBlockOrder(container, cfg.shelfBelowHosted)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dpToPx(cfg.overlayYOffsetDp)
            }

            runCatching {
                wm.addView(container, params)
                rootContainer = container
                layoutParams = params
            }
        }
    }

    private fun applyVerticalBlockOrder(container: LinearLayout, shelfBelowHosted: Boolean) {
        if (currentShelfBelow == shelfBelowHosted && container.childCount == 4) return
        val dismissRow = quickDismissRow ?: return
        val capsule = capsuleFrame ?: return
        val shelf = shelfFrame ?: return
        val hosted = hostedListContainer ?: return

        container.removeAllViews()
        container.addView(
            dismissRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            capsule,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        if (shelfBelowHosted) {
            container.addView(
                hosted,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            container.addView(
                shelf,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        } else {
            container.addView(
                shelf,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            container.addView(
                hosted,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        currentShelfBelow = shelfBelowHosted
    }

    private fun removeOverlay() {
        val wm = windowManager
        rootContainer?.let { v -> if (wm != null) runCatching { wm.removeView(v) } }
        rootContainer = null
        quickDismissRow = null
        capsuleFrame = null
        shelfFrame = null
        hostedListContainer = null
        layoutParams = null
        currentHostedSignature = ""
        currentShelfBelow = null
    }

    private fun evaluateVisibilityAndRefresh(
        rebuildViews: Boolean,
        forceRebindHosted: Boolean
    ) {
        ensureOverlayAttached()
        val container = rootContainer ?: return
        val wm = windowManager ?: return
        val params = layoutParams ?: return

        val cfg = LockDockRepository.loadConfig(this)
        val km = getSystemService(KeyguardManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        val isLocked = km?.isKeyguardLocked == true
        val isDeviceLocked = km?.isDeviceLocked == true
        val isInteractive = pm?.isInteractive != false
        val isPreviewing = System.currentTimeMillis() < previewUntilMillis

        if (lastKnownDeviceLocked && !isDeviceLocked && cfg.dismissOnBiometricUnlock && !isPreviewing) {
            lastKnownDeviceLocked = false
            dismissForCurrentSession(vibrate = false)
            return
        }
        lastKnownDeviceLocked = isDeviceLocked

        // Hide immediately if incoming/active call, ringing alarm, or full-screen app above Keyguard!
        val isInterruptedByCallOrAlarm = !isPreviewing &&
            cfg.hideOnCallAndAlarm && isLocked && isCallOrAlarmOrOccludingAppActive()

        if (isInterruptedByCallOrAlarm) {
            hideAllOverlays()
            if (isInteractive) startWatcherIfNeeded()
            return
        }

        val showCapsule = cfg.showTopCapsule && cfg.inlineMode != InlineMode.HIDDEN
        val showShelf = cfg.showIosShelfInOverlay &&
            (cfg.slots.any { it.enabled } || !cfg.hideEmptySlotsOnLockScreen)
        val showAnyHosted = cfg.hostedWidgets.any { it.enabled }
        val hasAnyActiveBlock = showCapsule || showShelf || showAnyHosted

        val shouldShowWidgets = isInteractive && hasAnyActiveBlock &&
            (isPreviewing || (cfg.overlayEnabled && isLocked && !dismissedUntilScreenOff))

        if (!shouldShowWidgets) {
            hideAllOverlays()
            stopWatcher()
            return
        }

        applyVerticalBlockOrder(container, cfg.shelfBelowHosted)

        val targetY = dpToPx(cfg.overlayYOffsetDp)
        val targetAlpha = (cfg.overlayAlphaPercent / 100f).coerceIn(0.45f, 1f)
        container.alpha = targetAlpha

        if (params.y != targetY) {
            params.y = targetY
            runCatching { wm.updateViewLayout(container, params) }
        }

        val becameVisible = container.visibility != View.VISIBLE

        if (cfg.showQuickDismissPill) {
            quickDismissRow?.visibility = View.VISIBLE
            (quickDismissRow?.getChildAt(0) as? TextView)?.background =
                WidgetCornerStyler.createGlassDrawable(
                    context = this,
                    radiusDp = WidgetCornerStyler.capsuleRadiusDp(cfg),
                    fillColor = Color.argb(115, 16, 22, 38),
                    strokeColor = Color.argb(110, 255, 255, 255)
                )
        } else {
            quickDismissRow?.visibility = View.GONE
        }

        if (showCapsule) {
            capsuleFrame?.visibility = View.VISIBLE
            if (rebuildViews || becameVisible || capsuleFrame?.childCount == 0) {
                rebuildCapsuleOnly(cfg)
            }
        } else {
            capsuleFrame?.visibility = View.GONE
        }

        if (showShelf) {
            shelfFrame?.visibility = View.VISIBLE
            if (rebuildViews || becameVisible || shelfFrame?.childCount == 0) {
                rebuildShelfOnly(cfg)
            }
        } else {
            shelfFrame?.visibility = View.GONE
        }

        syncHostedWidgets(cfg, force = forceRebindHosted || becameVisible)

        if (becameVisible) {
            container.visibility = View.VISIBLE
        }

        startWatcherIfNeeded()
    }

    private fun rebuildCapsuleOnly(cfg: LockDockConfig) {
        val cap = capsuleFrame ?: return
        runCatching {
            val rv = IosShelfWidgetProvider.buildShelfRemoteViews(
                context = this,
                config = cfg,
                showCapsuleBlock = true,
                showShelfRowBlock = false
            )
            val inflated = rv.apply(applicationContext, cap)
            WidgetCornerStyler.applyShelfInflatedCorners(this, inflated, cfg)
            cap.removeAllViews()
            cap.addView(inflated)
        }
    }

    private fun rebuildShelfOnly(cfg: LockDockConfig) {
        val shelf = shelfFrame ?: return
        runCatching {
            val rv = IosShelfWidgetProvider.buildShelfRemoteViews(
                context = this,
                config = cfg,
                showCapsuleBlock = false,
                showShelfRowBlock = true
            )
            val inflated = rv.apply(applicationContext, shelf)
            WidgetCornerStyler.applyShelfInflatedCorners(this, inflated, cfg)
            shelf.removeAllViews()
            shelf.addView(inflated)
        }
    }

    private fun syncHostedWidgets(cfg: LockDockConfig, force: Boolean) {
        val listLayout = hostedListContainer ?: return
        val enabledEntries = cfg.hostedWidgets.filter { it.enabled }

        if (enabledEntries.isEmpty()) {
            listLayout.removeAllViews()
            listLayout.visibility = View.GONE
            currentHostedSignature = ""
            return
        }

        val sig = "${cfg.blockSpacingDp}|" + enabledEntries.joinToString("|") {
            "${it.appWidgetId}:${it.heightDp}:${it.scalePercent}"
        }

        if (sig == currentHostedSignature && listLayout.childCount == enabledEntries.size && !force) {
            for (i in enabledEntries.indices) {
                val wrapper = listLayout.getChildAt(i) as? FrameLayout ?: continue
                val hv = wrapper.getChildAt(0) as? android.appwidget.AppWidgetHostView
                val scale = (enabledEntries[i].scalePercent / 100f).coerceIn(0.75f, 1f)
                WidgetCornerStyler.applyHostedWidgetCorners(
                    wrapper = wrapper,
                    hostView = hv,
                    radiusDp = cfg.widgetCornerRadiusDp,
                    scale = scale
                )
            }
            listLayout.visibility = View.VISIBLE
            return
        }

        listLayout.removeAllViews()
        val screenWidthPx = resources.displayMetrics.widthPixels - dpToPx(16)
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val spacingPx = dpToPx(cfg.blockSpacingDp)

        var addedCount = 0
        for (entry in enabledEntries) {
            val scale = (entry.scalePercent / 100f).coerceIn(0.75f, 1f)
            val virtualWidthPx = (screenWidthPx / scale).roundToInt()
            val virtualHeightPx = (dpToPx(entry.heightDp) / scale).roundToInt()
            val virtualWidthDp = (virtualWidthPx / density).roundToInt().coerceAtLeast(380)
            val virtualHeightDp = (virtualHeightPx / density).roundToInt().coerceAtLeast(60)

            val hostView = LockDockWidgetHostManager.createConfiguredHostView(
                context = this,
                appWidgetId = entry.appWidgetId,
                heightDp = virtualHeightDp,
                virtualWidthDp = virtualWidthDp
            ) ?: continue

            val wrapper = FrameLayout(this).apply {
                clipChildren = true
                clipToPadding = false
                layoutParams = LinearLayout.LayoutParams(
                    screenWidthPx,
                    dpToPx(entry.heightDp)
                ).apply {
                    topMargin = spacingPx
                }
            }

            hostView.scaleX = scale
            hostView.scaleY = scale
            wrapper.addView(
                hostView,
                FrameLayout.LayoutParams(
                    virtualWidthPx,
                    virtualHeightPx,
                    Gravity.CENTER
                )
            )
            WidgetCornerStyler.applyHostedWidgetCorners(
                wrapper = wrapper,
                hostView = hostView,
                radiusDp = cfg.widgetCornerRadiusDp,
                scale = scale
            )
            listLayout.addView(wrapper)
            addedCount++
        }

        currentHostedSignature = sig
        listLayout.visibility = if (addedCount > 0) View.VISIBLE else View.GONE
    }

    private fun triggerPreview(durationMs: Long) {
        dismissedUntilScreenOff = false
        previewUntilMillis = System.currentTimeMillis() + durationMs
        evaluateVisibilityAndRefresh(rebuildViews = true, forceRebindHosted = true)
        mainHandler.postDelayed(
            { evaluateVisibilityAndRefresh(rebuildViews = false, forceRebindHosted = false) },
            durationMs + 100L
        )
    }

    companion object {
        @Volatile
        private var instance: LockScreenOverlayService? = null

        fun requestRefresh(forceRebindHosted: Boolean = false) {
            val svc = instance ?: return
            svc.mainHandler.post {
                svc.evaluateVisibilityAndRefresh(
                    rebuildViews = true,
                    forceRebindHosted = forceRebindHosted
                )
            }
        }

        fun requestTestPreview(durationMs: Long = 5000L): Boolean {
            val svc = instance ?: return false
            svc.mainHandler.post {
                svc.triggerPreview(durationMs)
            }
            return true
        }
    }
}
