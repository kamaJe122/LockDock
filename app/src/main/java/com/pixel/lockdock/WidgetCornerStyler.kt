package com.pixel.lockdock

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.RemoteViews
import kotlin.math.roundToInt

/**
 * Unified corner-radius styling engine so every LockDock widget —
 * Top Capsule, iOS 4-Slot Shelf, 2x1 Pills, 1x1 Gauge Tiles, Native Cards,
 * and Hosted 3rd-party AppWidgets — morphs its corners together in sync.
 */
object WidgetCornerStyler {

    const val DEFAULT_CORNER_RADIUS_DP = 24
    const val MIN_CORNER_RADIUS_DP = 0
    const val MAX_CORNER_RADIUS_DP = 42

    private val SLOT_CONTAINER_IDS = intArrayOf(
        R.id.slot_0_container,
        R.id.slot_1_container,
        R.id.slot_2_container,
        R.id.slot_3_container
    )

    fun outerRadiusDp(config: LockDockConfig): Int {
        return config.widgetCornerRadiusDp.coerceIn(MIN_CORNER_RADIUS_DP, MAX_CORNER_RADIUS_DP)
    }

    fun capsuleRadiusDp(config: LockDockConfig): Int {
        val r = outerRadiusDp(config)
        return if (r == 0) 0 else ((r * 16f) / 28f).roundToInt().coerceIn(1, 24)
    }

    fun innerPillRadiusDp(config: LockDockConfig): Int {
        val r = outerRadiusDp(config)
        if (r == 0) return 0
        return if (!config.showShelfOuterFrame) {
            r.coerceIn(1, 36)
        } else {
            ((r * 36f) / 42f).roundToInt().coerceIn(1, 36)
        }
    }

    fun slot1x1RadiusDp(config: LockDockConfig): Int {
        return if (!config.syncCircleSlotsWithCornerRadius) {
            36
        } else {
            innerPillRadiusDp(config)
        }
    }

    fun todoRowRadiusDp(config: LockDockConfig): Int {
        val r = outerRadiusDp(config)
        return if (r == 0) 0 else ((r * 13f) / 24f).roundToInt().coerceIn(1, 16)
    }

    fun describeCornerStyle(radiusDp: Int): String {
        return when (radiusDp.coerceIn(MIN_CORNER_RADIUS_DP, MAX_CORNER_RADIUS_DP)) {
            0 -> "0° 完全直角"
            in 1..8 -> "${radiusDp}° 俐落微角"
            in 9..16 -> "${radiusDp}° 柔和微圓"
            in 17..22 -> "${radiusDp}° 適中圓角"
            in 23..28 -> "${radiusDp}° 標準圓角"
            in 29..36 -> "${radiusDp}° 圓潤大角"
            else -> "${radiusDp}° 全圓膠囊"
        }
    }

    fun dpToPxFloat(context: Context, dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }

    fun createGlassDrawable(
        context: Context,
        radiusDp: Int,
        fillColor: Int,
        strokeColor: Int,
        strokeWidthDp: Float = 1f
    ): GradientDrawable {
        val rPx = dpToPxFloat(context, radiusDp.toFloat())
        val strokePx = dpToPxFloat(context, strokeWidthDp).roundToInt().coerceAtLeast(1)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = rPx
            setColor(fillColor)
            setStroke(strokePx, strokeColor)
        }
    }

    fun applyShelfInflatedCorners(context: Context, rootView: View, config: LockDockConfig) {
        rootView.findViewById<View>(R.id.inline_container)?.let { inlineBox ->
            inlineBox.background = createGlassDrawable(
                context = context,
                radiusDp = capsuleRadiusDp(config),
                fillColor = Color.argb(0x38, 0x1A, 0x20, 0x30),
                strokeColor = Color.argb(0x45, 0xFF, 0xFF, 0xFF)
            )
        }

        rootView.findViewById<View>(R.id.shelf_inner_box)?.let { shelfBox ->
            shelfBox.background = if (config.showShelfOuterFrame) {
                createGlassDrawable(
                    context = context,
                    radiusDp = outerRadiusDp(config),
                    fillColor = Color.argb(0x2E, 0x16, 0x1B, 0x26),
                    strokeColor = Color.argb(0x40, 0xFF, 0xFF, 0xFF)
                )
            } else {
                null
            }
        }

        val pillRadius = innerPillRadiusDp(config)
        val normalized = LockDockRepository.normalizedFourUnitSlots(config.slots)
        normalized.forEachIndexed { index, item ->
            if (index < SLOT_CONTAINER_IDS.size && item.type.span == 2) {
                rootView.findViewById<View>(SLOT_CONTAINER_IDS[index])?.let { pillView ->
                    pillView.background = createGlassDrawable(
                        context = context,
                        radiusDp = pillRadius,
                        fillColor = Color.argb(0x4D, 0x1E, 0x25, 0x36),
                        strokeColor = Color.argb(0x55, 0xFF, 0xFF, 0xFF)
                    )
                }
            }
        }
    }

    fun applyRemoteViewsOutlineRadius(views: RemoteViews, viewId: Int, radiusDp: Int) {
        val clamped = radiusDp.coerceIn(MIN_CORNER_RADIUS_DP, MAX_CORNER_RADIUS_DP)
        runCatching {
            views.setBoolean(viewId, "setClipToOutline", clamped > 0)
            views.setViewOutlinePreferredRadius(
                viewId,
                clamped.toFloat(),
                TypedValue.COMPLEX_UNIT_DIP
            )
        }
    }

    fun applyRoundRectOutline(view: View, radiusPx: Float) {
        if (radiusPx <= 0.5f) {
            view.outlineProvider = ViewOutlineProvider.BOUNDS
            view.clipToOutline = false
            view.invalidateOutline()
        } else {
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    val w = v.width
                    val h = v.height
                    if (w > 0 && h > 0) {
                        val clamped = radiusPx.coerceAtMost(minOf(w, h) / 2f)
                        outline.setRoundRect(0, 0, w, h, clamped)
                    }
                }
            }
            view.clipToOutline = true
            view.invalidateOutline()
        }
    }

    fun applyHostedWidgetCorners(
        wrapper: View,
        hostView: AppWidgetHostView?,
        radiusDp: Int,
        scale: Float
    ) {
        val safeScale = scale.coerceIn(0.5f, 1f)
        val outerPx = dpToPxFloat(wrapper.context, radiusDp.coerceIn(MIN_CORNER_RADIUS_DP, MAX_CORNER_RADIUS_DP).toFloat())
        applyRoundRectOutline(wrapper, outerPx)

        val unscaledPx = outerPx / safeScale
        if (hostView is LockDockHostView) {
            hostView.setTargetCornerRadiusPx(unscaledPx)
        } else if (hostView != null) {
            applyToHostViewContent(hostView, unscaledPx)
        }
    }

    fun applyToHostViewContent(hostView: AppWidgetHostView, unscaledRadiusPx: Float) {
        applyRoundRectOutline(hostView, unscaledRadiusPx)
        if (hostView.childCount <= 0) return
        val rootChild = hostView.getChildAt(0) ?: return
        val targetCard: View = rootChild.findViewById<View>(android.R.id.background)
            ?: if (rootChild is ViewGroup && rootChild.background == null && rootChild.childCount == 1) {
                rootChild.getChildAt(0)
            } else {
                rootChild
            }
        applyRoundRectOutline(targetCard, unscaledRadiusPx)
    }
}
