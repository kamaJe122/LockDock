package com.pixel.lockdock

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Mode 2: Renders each user-enabled Lock Screen block ONLY on the genuine Lock Screen.
 * Automatically hides immediately during:
 * - Incoming or ongoing phone / VoIP calls (cellular, LINE, WhatsApp, Messenger, Call Screen)
 * - Sounding or vibrate-only Alarms & Timers (Google Clock & third-party alarm apps)
 * - Any full-screen showWhenLocked app occluding Keyguard (e.g. Lock-screen Camera / Wallet)
 */
class LockScreenOverlayService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var audioManager: AudioManager? = null
    private var rootContainer: LinearLayout? = null
    private var capsuleFrame: FrameLayout? = null
    private var shelfFrame: FrameLayout? = null
    private var hostedListContainer: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var unlockZoneView: UnlockZoneSensorView? = null
    private var unlockZoneParams: WindowManager.LayoutParams? = null

    private var currentHostedSignature: String = ""
    private var currentShelfBelow: Boolean? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var previewUntilMillis: Long = 0L
    private var calibrationUntilMillis: Long = 0L

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
            val now = System.currentTimeMillis()
            val isPreviewing = now < previewUntilMillis || now < calibrationUntilMillis

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
                mainHandler.postDelayed(this, 400L)
                return
            }

            if (lastKnownDeviceLocked && !isDevLocked) {
                if (cfg.dismissOnBiometricUnlock) {
                    dismissForCurrentSession(vibrate = false)
                    return
                } else {
                    syncUnlockZoneSensor(
                        cfg = cfg,
                        isKeyguard = isKeyguard,
                        isDeviceLocked = false,
                        isPreviewing = now < previewUntilMillis,
                        isCalibrating = now < calibrationUntilMillis
                    )
                }
            }
            lastKnownDeviceLocked = isDevLocked

            if (rootContainer?.visibility == View.VISIBLE ||
                unlockZoneView?.visibility == View.VISIBLE ||
                isPreviewing
            ) {
                mainHandler.postDelayed(this, 250L)
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
                    evaluateVisibilityAndRefresh(rebuildViews = false, forceRebindHosted = false)
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
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED &&
            event.packageName?.toString() == "com.android.systemui"
        ) {
            val km = getSystemService(KeyguardManager::class.java)
            if (km?.isKeyguardLocked == true) {
                val cfg = LockDockRepository.loadConfig(this)
                if (cfg.holdUnlockZoneToDismiss && rootContainer?.visibility == View.VISIBLE) {
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
     * 3) A full-screen showWhenLocked Application window (such as a silent Incoming Call,
     *    vibrate-only Alarm, Camera on Lock Screen, or Wallet) is occluding Keyguard.
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
            mainHandler.postDelayed(lockStateWatcher, 250L)
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
        unlockZoneView?.let {
            it.cancelHold()
            if (it.visibility != View.GONE) it.visibility = View.GONE
        }
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

            val capsule = FrameLayout(this)
            val shelf = FrameLayout(this)
            val hostedList = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                visibility = View.GONE
            }

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

        if (unlockZoneView == null) {
            val zoneDiameterPx = dpToPx(106)
            val bounds = getPhysicalScreenBounds()
            val targetCenterX = (bounds.width() * (cfg.unlockZoneXPermille / 1000f)).roundToInt()
            val targetCenterY = (bounds.height() * (cfg.unlockZoneYPermille / 1000f)).roundToInt()

            val sensor = UnlockZoneSensorView(
                context = this,
                handler = mainHandler,
                onHoldCompleted = {
                    dismissForCurrentSession(vibrate = true)
                },
                onQuickTapOrSwipeUp = {
                    dismissedUntilScreenOff = true
                    hideAllOverlays()
                    stopWatcher()
                    runCatching {
                        val dismissIntent = Intent(this, LockLaunchActivity::class.java).apply {
                            putExtra(LockLaunchActivity.EXTRA_DISMISS_ONLY, true)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        }
                        startActivity(dismissIntent)
                    }
                },
                onCalibrationDragged = { rawCenterX, rawCenterY ->
                    val b = getPhysicalScreenBounds()
                    val xPm = ((rawCenterX / b.width().coerceAtLeast(1)) * 1000f)
                        .roundToInt().coerceIn(150, 850)
                    val yPm = ((rawCenterY / b.height().coerceAtLeast(1)) * 1000f)
                        .roundToInt().coerceIn(450, 930)
                    val latest = LockDockRepository.loadConfig(this)
                    LockDockRepository.saveConfig(
                        this,
                        latest.copy(unlockZoneXPermille = xPm, unlockZoneYPermille = yPm),
                        notify = false
                    )
                    positionSensorExact(xPm, yPm)
                }
            ).apply {
                visibility = View.GONE
            }

            val zParams = WindowManager.LayoutParams(
                zoneDiameterPx,
                zoneDiameterPx,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
                gravity = Gravity.TOP or Gravity.START
                x = (targetCenterX - zoneDiameterPx / 2).coerceAtLeast(0)
                y = (targetCenterY - zoneDiameterPx / 2).coerceAtLeast(0)
            }

            runCatching {
                wm.addView(sensor, zParams)
                unlockZoneView = sensor
                unlockZoneParams = zParams
            }
        }
    }

    private fun positionSensorExact(xPermille: Int, yPermille: Int) {
        val wm = windowManager ?: return
        val sensor = unlockZoneView ?: return
        val zParams = unlockZoneParams ?: return

        val bounds = getPhysicalScreenBounds()
        val radius = zParams.width / 2
        val desiredLeft = (bounds.width() * (xPermille / 1000f)).roundToInt() - radius
        val desiredTop = (bounds.height() * (yPermille / 1000f)).roundToInt() - radius

        if (zParams.x != desiredLeft || zParams.y != desiredTop) {
            zParams.x = desiredLeft
            zParams.y = desiredTop
            runCatching { wm.updateViewLayout(sensor, zParams) }
        }

        sensor.post {
            if (sensor.visibility != View.VISIBLE) return@post
            val loc = IntArray(2)
            sensor.getLocationOnScreen(loc)
            val dx = loc[0] - desiredLeft
            val dy = loc[1] - desiredTop
            if (abs(dx) > 2 || abs(dy) > 2) {
                zParams.x -= dx
                zParams.y -= dy
                runCatching { wm.updateViewLayout(sensor, zParams) }
            }
        }
    }

    private fun syncUnlockZoneSensor(
        cfg: LockDockConfig,
        isKeyguard: Boolean,
        isDeviceLocked: Boolean,
        isPreviewing: Boolean,
        isCalibrating: Boolean
    ) {
        val sensor = unlockZoneView ?: return

        val shouldShowSensor = isCalibrating || (
            cfg.holdUnlockZoneToDismiss &&
                (isPreviewing || (isKeyguard && !isDeviceLocked && !dismissedUntilScreenOff))
            )

        if (!shouldShowSensor) {
            sensor.cancelHold()
            if (sensor.visibility != View.GONE) {
                sensor.visibility = View.GONE
            }
            return
        }

        sensor.holdDurationMs = cfg.holdUnlockDurationMs.toLong()
        sensor.showAlignmentGuide = isPreviewing || isCalibrating
        sensor.calibrationMode = isCalibrating

        if (sensor.visibility != View.VISIBLE) {
            sensor.visibility = View.VISIBLE
        }
        positionSensorExact(cfg.unlockZoneXPermille, cfg.unlockZoneYPermille)
        sensor.invalidate()
    }

    private fun applyVerticalBlockOrder(container: LinearLayout, shelfBelowHosted: Boolean) {
        if (currentShelfBelow == shelfBelowHosted && container.childCount == 3) return
        val capsule = capsuleFrame ?: return
        val shelf = shelfFrame ?: return
        val hosted = hostedListContainer ?: return

        container.removeAllViews()
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
        unlockZoneView?.let { v ->
            v.cancelHold()
            if (wm != null) runCatching { wm.removeView(v) }
        }
        rootContainer = null
        capsuleFrame = null
        shelfFrame = null
        hostedListContainer = null
        layoutParams = null
        unlockZoneView = null
        unlockZoneParams = null
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
        val now = System.currentTimeMillis()
        val isPreviewing = now < previewUntilMillis
        val isCalibrating = now < calibrationUntilMillis

        // Hide immediately if incoming/active call, ringing alarm, or full-screen showWhenLocked app!
        val isInterruptedByCallOrAlarm = !isPreviewing && !isCalibrating &&
            cfg.hideOnCallAndAlarm && isLocked && isCallOrAlarmOrOccludingAppActive()

        if (isInterruptedByCallOrAlarm) {
            hideAllOverlays()
            // Keep light watcher running while screen is interactive so widgets return after call/alarm ends
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

        if (!shouldShowWidgets && !isCalibrating) {
            hideAllOverlays()
            stopWatcher()
            return
        }

        if (shouldShowWidgets) {
            applyVerticalBlockOrder(container, cfg.shelfBelowHosted)

            val targetY = dpToPx(cfg.overlayYOffsetDp)
            val targetAlpha = (cfg.overlayAlphaPercent / 100f).coerceIn(0.45f, 1f)
            container.alpha = targetAlpha

            if (params.y != targetY) {
                params.y = targetY
                runCatching { wm.updateViewLayout(container, params) }
            }

            val becameVisible = container.visibility != View.VISIBLE

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
        } else {
            container.visibility = View.GONE
        }

        syncUnlockZoneSensor(
            cfg = cfg,
            isKeyguard = isLocked,
            isDeviceLocked = isDeviceLocked,
            isPreviewing = isPreviewing,
            isCalibrating = isCalibrating
        )
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

        if (!force && sig == currentHostedSignature && listLayout.childCount > 0) {
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
                clipChildren = false
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

    private fun triggerCalibration(durationMs: Long) {
        dismissedUntilScreenOff = false
        calibrationUntilMillis = System.currentTimeMillis() + durationMs
        evaluateVisibilityAndRefresh(rebuildViews = false, forceRebindHosted = false)
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

        fun requestDragCalibration(durationMs: Long = 10000L): Boolean {
            val svc = instance ?: return false
            svc.mainHandler.post {
                svc.triggerCalibration(durationMs)
            }
            return true
        }
    }
}

private class UnlockZoneSensorView(
    context: Context,
    private val handler: Handler,
    private val onHoldCompleted: () -> Unit,
    private val onQuickTapOrSwipeUp: () -> Unit,
    private val onCalibrationDragged: (Float, Float) -> Unit
) : View(context) {

    var holdDurationMs: Long = 1000L
    var showAlignmentGuide: Boolean = false
    var calibrationMode: Boolean = false

    private var holding: Boolean = false
    private var holdTriggered: Boolean = false
    private var downUptimeMs: Long = 0L
    private var downRawX: Float = 0f
    private var downRawY: Float = 0f

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(240, 105, 240, 174)
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        color = Color.argb(80, 255, 255, 255)
    }

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.argb(235, 79, 195, 247)
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = Color.argb(220, 105, 240, 174)
    }

    private val guideFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(70, 20, 45, 75)
    }

    private val guideTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 23f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val completeRunnable = Runnable {
        if (holding && !holdTriggered) {
            holdTriggered = true
            holding = false
            invalidate()
            onHoldCompleted()
        }
    }

    private val animTickRunnable = object : Runnable {
        override fun run() {
            if (!holding) return
            invalidate()
            handler.postDelayed(this, 16L)
        }
    }

    fun cancelHold() {
        holding = false
        handler.removeCallbacks(completeRunnable)
        handler.removeCallbacks(animTickRunnable)
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (calibrationMode) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> {
                    onCalibrationDragged(event.rawX, event.rawY)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    DeviceStateHelper.vibrateClick(context)
                }
            }
            return true
        }

        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val cancelSlopPx = 46f * density
        val swipeUpThresholdPx = -46f * density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                holding = true
                holdTriggered = false
                downUptimeMs = SystemClock.uptimeMillis()
                downRawX = event.rawX
                downRawY = event.rawY
                handler.removeCallbacks(completeRunnable)
                handler.postDelayed(completeRunnable, holdDurationMs)
                handler.removeCallbacks(animTickRunnable)
                handler.post(animTickRunnable)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (holding) {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (dy < swipeUpThresholdPx) {
                        cancelHold()
                        holdTriggered = true
                        onQuickTapOrSwipeUp()
                    } else if (hypot(dx, dy) > cancelSlopPx) {
                        cancelHold()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val wasHolding = holding
                val alreadyTriggered = holdTriggered
                cancelHold()
                if (wasHolding && !alreadyTriggered) {
                    onQuickTapOrSwipeUp()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelHold()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 8f
        val rect = RectF(pad, pad, width - pad, height - pad)
        val cx = width / 2f
        val cy = height / 2f

        if (showAlignmentGuide && !holding) {
            canvas.drawOval(rect, guideFillPaint)
            canvas.drawOval(rect, guidePaint)
            canvas.drawLine(cx - 18f, cy, cx + 18f, cy, crosshairPaint)
            canvas.drawLine(cx, cy - 18f, cx, cy + 18f, crosshairPaint)
            val txt = if (calibrationMode) "拖曳對準🔓" else "解鎖偵測圈"
            canvas.drawText(txt, cx, cy + 36f, guideTextPaint)
            return
        }

        if (holding && !holdTriggered) {
            val elapsed = (SystemClock.uptimeMillis() - downUptimeMs).coerceAtLeast(0L)
            val pct = (elapsed.toFloat() / holdDurationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
            canvas.drawOval(rect, trackPaint)
            canvas.drawArc(rect, -90f, 360f * pct, false, progressPaint)
        }
    }
}
