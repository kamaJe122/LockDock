package com.pixel.lockdock

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.SizeF
import android.widget.RemoteViews
import kotlin.math.roundToInt

class LockDockHostView(context: Context) : AppWidgetHostView(context) {
    private var targetCornerRadiusPx: Float = 0f

    init {
        setPadding(0, 0, 0, 0)
    }

    fun setTargetCornerRadiusPx(radiusPx: Float) {
        targetCornerRadiusPx = radiusPx
        if (parent !is AlignedHostedWidgetContainer) {
            WidgetCornerStyler.applyToHostViewContent(this, radiusPx)
        }
    }

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        super.updateAppWidget(remoteViews)
        setPadding(0, 0, 0, 0)
        val alignedParent = parent as? AlignedHostedWidgetContainer
        if (alignedParent != null) {
            alignedParent.onHostWidgetUpdated()
        } else {
            WidgetCornerStyler.applyToHostViewContent(this, targetCornerRadiusPx)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed && parent !is AlignedHostedWidgetContainer) {
            WidgetCornerStyler.applyToHostViewContent(this, targetCornerRadiusPx)
        }
    }
}

private class LockDockAppWidgetHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {
    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?
    ): AppWidgetHostView {
        return LockDockHostView(context)
    }
}

data class SystemWidgetCandidate(
    val info: AppWidgetProviderInfo,
    val appLabel: String,
    val widgetLabel: String,
    val packageName: String,
    val cellBadge: String
)

/**
 * Shared singleton AppWidgetHost manager ensuring 100% of installed widgets
 * (including 5x2 widgets like KM 桌面行事曆小工具) can be discovered, bound,
 * configured, previewed inside LockDock, and rendered on the lock screen.
 */
object LockDockWidgetHostManager {

    const val HOST_ID = 2026
    @Volatile
    private var sharedHost: AppWidgetHost? = null
    @Volatile
    private var listening = false

    fun getHost(context: Context): AppWidgetHost {
        val appCtx = context.applicationContext
        val existing = sharedHost
        if (existing != null) {
            ensureListening(existing)
            return existing
        }
        return synchronized(this) {
            val again = sharedHost
            if (again != null) {
                ensureListening(again)
                again
            } else {
                val created = LockDockAppWidgetHost(appCtx, HOST_ID)
                ensureListening(created)
                sharedHost = created
                created
            }
        }
    }

    private fun ensureListening(host: AppWidgetHost) {
        if (!listening) {
            runCatching {
                host.startListening()
                listening = true
            }
        }
    }

    fun loadAllInstalledWidgets(context: Context): List<SystemWidgetCandidate> {
        val pm = context.packageManager
        val mgr = AppWidgetManager.getInstance(context)
        val rawProviders = LinkedHashMap<String, AppWidgetProviderInfo>()

        runCatching {
            mgr.getInstalledProvidersForProfile(Process.myUserHandle()).forEach { info ->
                rawProviders[info.provider.flattenToString()] = info
            }
        }
        runCatching {
            mgr.installedProviders.forEach { info ->
                rawProviders.putIfAbsent(info.provider.flattenToString(), info)
            }
        }

        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)

        return rawProviders.values
            .filter { it.provider.packageName != context.packageName }
            .map { info ->
                val pkg = info.provider.packageName
                val appLabel = runCatching {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(appInfo).toString().trim()
                }.getOrDefault(pkg)

                val widgetLabel = runCatching {
                    info.loadLabel(pm)?.toString()?.trim().orEmpty()
                }.getOrDefault("").ifBlank { appLabel }

                val cellsW = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellWidth > 0) {
                    info.targetCellWidth
                } else {
                    (((info.minWidth / density) + 30f) / 70f).roundToInt().coerceIn(1, 5)
                }
                val cellsH = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellHeight > 0) {
                    info.targetCellHeight
                } else {
                    (((info.minHeight / density) + 30f) / 70f).roundToInt().coerceIn(1, 5)
                }

                SystemWidgetCandidate(
                    info = info,
                    appLabel = appLabel,
                    widgetLabel = widgetLabel,
                    packageName = pkg,
                    cellBadge = "${cellsW}×${cellsH}"
                )
            }
            .sortedWith(
                compareByDescending<SystemWidgetCandidate> {
                    // Highlight KM / Calendar widgets at top for convenience
                    val key = "${it.appLabel} ${it.widgetLabel} ${it.packageName}".lowercase()
                    key.contains("km") || key.contains("行事曆") || key.contains("月曆")
                }.thenBy { it.appLabel.lowercase() }
                    .thenBy { it.widgetLabel.lowercase() }
            )
    }

    /**
     * Creates an AppWidgetHostView and reports full 5-column width options (420dp)
     * so 5x2 widgets like KM 桌面行事曆小工具 render their complete two-pane layout cleanly.
     */
    fun createConfiguredHostView(
        context: Context,
        appWidgetId: Int,
        heightDp: Int,
        virtualWidthDp: Int = 420
    ): AppWidgetHostView? {
        if (appWidgetId < 0) return null
        val appCtx = context.applicationContext
        val mgr = AppWidgetManager.getInstance(appCtx)
        val info = mgr.getAppWidgetInfo(appWidgetId) ?: return null
        val host = getHost(appCtx)
        return try {
            val view = host.createView(appCtx, appWidgetId, info)
            view.setAppWidget(appWidgetId, info)
            applyWidgetSizeOptions(mgr, view, appWidgetId, virtualWidthDp, heightDp)
            view
        } catch (_: Exception) {
            null
        }
    }

    fun applyWidgetSizeOptions(
        mgr: AppWidgetManager,
        view: AppWidgetHostView,
        appWidgetId: Int,
        widthDp: Int,
        heightDp: Int
    ) {
        val w = widthDp.coerceIn(240, 520)
        val h = heightDp.coerceIn(80, 420)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                view.updateAppWidgetSize(
                    Bundle(),
                    listOf(SizeF(w.toFloat(), h.toFloat()))
                )
            } else {
                @Suppress("DEPRECATION")
                view.updateAppWidgetSize(Bundle(), w, h, w, h)
            }
        }
        runCatching {
            val opts = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h)
            }
            mgr.updateAppWidgetOptions(appWidgetId, opts)
        }
    }
}
