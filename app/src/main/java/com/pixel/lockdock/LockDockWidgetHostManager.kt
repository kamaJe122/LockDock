package com.pixel.lockdock

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.SizeF
import android.widget.RemoteViews
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

data class NormalizedWidgetInsets(
    val leftRatio: Float,
    val topRatio: Float,
    val rightRatio: Float,
    val bottomRatio: Float
) {
    fun toPixelRect(virtW: Int, virtH: Int): Rect {
        return Rect(
            (leftRatio * virtW).roundToInt(),
            (topRatio * virtH).roundToInt(),
            (rightRatio * virtW).roundToInt(),
            (bottomRatio * virtH).roundToInt()
        )
    }
}

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
 * (including 5x2 widgets like KM 桌面行事曆小工具 and 發票存摺) can be bound, cached,
 * and displayed with zero redundant updateAppWidgetOptions reloads or inset jumps.
 */
object LockDockWidgetHostManager {

    const val HOST_ID = 2026
    private const val CACHE_PREFS = "lockdock_widget_host_cache"

    @Volatile
    private var sharedHost: AppWidgetHost? = null
    @Volatile
    private var listening = false

    private val lastSentSizeByWidgetId = ConcurrentHashMap<Int, Pair<Int, Int>>()
    private val cachedInsetsByWidgetId = ConcurrentHashMap<Int, NormalizedWidgetInsets>()

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

    fun computeCanonicalWidgetSizeDp(
        context: Context,
        heightDp: Int,
        scalePercent: Int,
        widthPercent: Int
    ): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        val density = dm.density.coerceAtLeast(1f)
        val screenWidthDp = dm.widthPixels / density
        val scale = (scalePercent / 100f).coerceIn(0.75f, 1f)
        val wFraction = widthPercent.coerceIn(
            WidgetCornerStyler.MIN_WIDTH_PERCENT,
            WidgetCornerStyler.MAX_WIDTH_PERCENT
        ) / 100f
        val wDp = ((screenWidthDp * wFraction) / scale).roundToInt().coerceIn(390, 520)
        val hDp = (heightDp / scale).roundToInt().coerceIn(80, 420)
        return wDp to hDp
    }

    fun getCachedInsets(context: Context, appWidgetId: Int): NormalizedWidgetInsets? {
        if (appWidgetId < 0) return null
        cachedInsetsByWidgetId[appWidgetId]?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString("insets_$appWidgetId", null) ?: return null
        val parts = raw.split(',')
        if (parts.size != 4) return null
        val parsed = runCatching {
            NormalizedWidgetInsets(
                leftRatio = parts[0].toFloat().coerceIn(0f, 0.25f),
                topRatio = parts[1].toFloat().coerceIn(0f, 0.25f),
                rightRatio = parts[2].toFloat().coerceIn(0f, 0.25f),
                bottomRatio = parts[3].toFloat().coerceIn(0f, 0.25f)
            )
        }.getOrNull() ?: return null
        cachedInsetsByWidgetId[appWidgetId] = parsed
        return parsed
    }

    fun saveCachedInsets(context: Context, appWidgetId: Int, insets: NormalizedWidgetInsets) {
        if (appWidgetId < 0) return
        cachedInsetsByWidgetId[appWidgetId] = insets
        val raw = "${insets.leftRatio},${insets.topRatio},${insets.rightRatio},${insets.bottomRatio}"
        context.applicationContext
            .getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("insets_$appWidgetId", raw)
            .apply()
    }

    fun clearWidgetCache(context: Context, appWidgetId: Int) {
        lastSentSizeByWidgetId.remove(appWidgetId)
        cachedInsetsByWidgetId.remove(appWidgetId)
        context.applicationContext
            .getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove("size_$appWidgetId")
            .remove("insets_$appWidgetId")
            .apply()
    }

    private fun getLastSentSize(
        context: Context,
        mgr: AppWidgetManager,
        appWidgetId: Int
    ): Pair<Int, Int>? {
        lastSentSizeByWidgetId[appWidgetId]?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString("size_$appWidgetId", null)
        if (raw != null) {
            val parts = raw.split('x')
            if (parts.size == 2) {
                val w = parts[0].toIntOrNull()
                val h = parts[1].toIntOrNull()
                if (w != null && h != null) {
                    val pair = w to h
                    lastSentSizeByWidgetId[appWidgetId] = pair
                    return pair
                }
            }
        }
        val existingOpts = runCatching { mgr.getAppWidgetOptions(appWidgetId) }.getOrNull()
        if (existingOpts != null) {
            val minW = existingOpts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, -1)
            val minH = existingOpts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, -1)
            if (minW > 0 && minH > 0) {
                val pair = minW to minH
                lastSentSizeByWidgetId[appWidgetId] = pair
                return pair
            }
        }
        return null
    }

    private fun recordSentSize(context: Context, appWidgetId: Int, w: Int, h: Int) {
        lastSentSizeByWidgetId[appWidgetId] = w to h
        context.applicationContext
            .getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("size_$appWidgetId", "${w}x${h}")
            .apply()
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
                    val key = "${it.appLabel} ${it.widgetLabel} ${it.packageName}".lowercase()
                    key.contains("km") || key.contains("行事曆") || key.contains("月曆")
                }.thenBy { it.appLabel.lowercase() }
                    .thenBy { it.widgetLabel.lowercase() }
            )
    }

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

    /**
     * Strictly deduplicated per appWidgetId: never calls updateAppWidgetSize() or
     * mgr.updateAppWidgetOptions() when the widget's dp dimensions have not changed,
     * preventing 3rd-party widgets (such as 發票存摺) from re-triggering their loading state.
     */
    fun applyWidgetSizeOptions(
        mgr: AppWidgetManager,
        view: AppWidgetHostView,
        appWidgetId: Int,
        widthDp: Int,
        heightDp: Int
    ) {
        if (appWidgetId < 0) return
        val w = widthDp.coerceIn(240, 520)
        val h = heightDp.coerceIn(80, 420)
        val appCtx = view.context.applicationContext

        val last = getLastSentSize(appCtx, mgr, appWidgetId)
        if (last != null && abs(last.first - w) < 4 && abs(last.second - h) < 4) {
            return
        }
        recordSentSize(appCtx, appWidgetId, w, h)

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                view.updateAppWidgetSize(
                    Bundle(),
                    listOf(SizeF(w.toFloat(), h.toFloat()))
                )
            } else {
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
}
