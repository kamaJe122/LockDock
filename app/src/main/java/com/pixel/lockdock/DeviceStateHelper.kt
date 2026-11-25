package com.pixel.lockdock

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings

data class InstalledAppInfo(
    val label: String,
    val packageName: String
)

object DeviceStateHelper {

    fun getBatteryStatus(context: Context): Pair<Int, Boolean> {
        return try {
            val bm = context.getSystemService(BatteryManager::class.java)
            val capacity = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val isCharging = bm?.isCharging ?: false
            if (capacity in 0..100) {
                capacity to isCharging
            } else {
                val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 85) ?: 85
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                ((level * 100) / scale.coerceAtLeast(1)).coerceIn(0, 100) to charging
            }
        } catch (_: Exception) {
            85 to false
        }
    }

    fun toggleTorch(context: Context): Boolean {
        val cameraManager = context.getSystemService(CameraManager::class.java) ?: return false
        return try {
            val flashCamId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            val nextState = !LockDockRepository.isTorchOn(context)
            cameraManager.setTorchMode(flashCamId, nextState)
            LockDockRepository.setTorchOn(context, nextState)
            vibrateClick(context)
            nextState
        } catch (_: Exception) {
            LockDockRepository.setTorchOn(context, false)
            false
        }
    }

    fun vibrateClick(context: Context) {
        try {
            val vm = context.getSystemService(VibratorManager::class.java)
            vm?.defaultVibrator?.vibrate(
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            )
        } catch (_: Exception) {
        }
    }

    fun getInstalledLauncherApps(context: Context): List<InstalledAppInfo> {
        return try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            pm.queryIntentActivities(mainIntent, 0)
                .mapNotNull { resolveInfo ->
                    val pkg = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == context.packageName) return@mapNotNull null
                    val label = resolveInfo.loadLabel(pm)?.toString()?.trim().orEmpty()
                    if (label.isEmpty()) null else InstalledAppInfo(label = label, packageName = pkg)
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun loadAppIconBitmap(context: Context, packageName: String, sizePx: Int = 72): Bitmap? {
        if (packageName.isBlank()) return null
        return try {
            val drawable: Drawable = context.packageManager.getApplicationIcon(packageName)
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                Bitmap.createScaledBitmap(drawable.bitmap, sizePx, sizePx, true)
            } else {
                val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, sizePx, sizePx)
                drawable.draw(canvas)
                bmp
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isOverlayAccessibilityEnabled(context: Context): Boolean {
        return try {
            val expected = ComponentName(context, LockScreenOverlayService::class.java).flattenToString()
            val expectedShort = ComponentName(context, LockScreenOverlayService::class.java).flattenToShortString()
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            enabledServices.split(':').any {
                it.equals(expected, ignoreCase = true) || it.equals(expectedShort, ignoreCase = true)
            }
        } catch (_: Exception) {
            false
        }
    }

    fun createActivityPendingIntent(
        context: Context,
        requestCode: Int,
        intent: Intent
    ): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return try {
            if (Build.VERSION.SDK_INT >= 36) {
                val options = ActivityOptions.makeBasic().apply {
                    setPendingIntentCreatorBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                    )
                }
                PendingIntent.getActivity(context, requestCode, intent, flags, options.toBundle())
            } else {
                PendingIntent.getActivity(context, requestCode, intent, flags)
            }
        } catch (_: Throwable) {
            PendingIntent.getActivity(context, requestCode, intent, flags)
        }
    }

    fun createMutableTemplatePendingIntent(
        context: Context,
        requestCode: Int,
        explicitIntent: Intent
    ): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return try {
            if (Build.VERSION.SDK_INT >= 36) {
                val options = ActivityOptions.makeBasic().apply {
                    setPendingIntentCreatorBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                    )
                }
                PendingIntent.getActivity(context, requestCode, explicitIntent, flags, options.toBundle())
            } else {
                PendingIntent.getActivity(context, requestCode, explicitIntent, flags)
            }
        } catch (_: Throwable) {
            PendingIntent.getActivity(context, requestCode, explicitIntent, flags)
        }
    }

    fun buildHostConfigureActivityOptions(): Bundle? {
        return try {
            if (Build.VERSION.SDK_INT >= 36) {
                ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                    )
                }.toBundle()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions.makeBasic().apply {
                    @Suppress("DEPRECATION")
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }.toBundle()
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
