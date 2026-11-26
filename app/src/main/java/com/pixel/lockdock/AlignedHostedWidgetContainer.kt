package com.pixel.lockdock

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Wraps any hosted 3rd-party AppWidgetHostView so that:
 * 1) Its outer container width strictly equals LockDock's unified widget width.
 * 2) Built-in transparent padding / XML margins / .9.png shadow insets inside 3rd-party
 *    widgets (such as KM 桌面行事曆 vs 發票存摺) are automatically detected and trimmed so
 *    every widget's visible left & right card edges align down to the pixel.
 * 3) Unified corner-radius clipping applies cleanly right on the flush card boundary.
 */
class AlignedHostedWidgetContainer(context: Context) : FrameLayout(context) {

    private var hostView: AppWidgetHostView? = null
    private var appWidgetId: Int = -1
    private var contentScale: Float = 1f
    private var cornerRadiusDp: Int = WidgetCornerStyler.DEFAULT_CORNER_RADIUS_DP
    private var autoAlignEdges: Boolean = true
    private var extraTrimDp: Int = 0

    private var insetsDirty: Boolean = true
    private var cachedInsets: Rect = Rect()
    private var cachedVirtW: Int = 0
    private var cachedVirtH: Int = 0
    private var lastReportedWidthDp: Int = -1
    private var lastReportedHeightDp: Int = -1

    private val delayedRecheckRunnable = Runnable {
        insetsDirty = true
        refreshAlignmentInPlace()
    }

    init {
        clipChildren = true
        clipToPadding = false
    }

    fun bindHostView(
        view: AppWidgetHostView?,
        widgetId: Int,
        scalePercent: Int,
        radiusDp: Int,
        autoAlign: Boolean,
        trimDp: Int
    ) {
        removeCallbacks(delayedRecheckRunnable)
        removeAllViews()
        hostView = view
        appWidgetId = widgetId
        contentScale = (scalePercent / 100f).coerceIn(0.75f, 1f)
        cornerRadiusDp = radiusDp.coerceIn(
            WidgetCornerStyler.MIN_CORNER_RADIUS_DP,
            WidgetCornerStyler.MAX_CORNER_RADIUS_DP
        )
        autoAlignEdges = autoAlign
        extraTrimDp = trimDp.coerceIn(-12, 24)
        insetsDirty = true
        cachedInsets.setEmpty()
        lastReportedWidthDp = -1
        lastReportedHeightDp = -1

        if (view != null) {
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        applyOuterCornerOutline()
        requestLayout()
        postDelayed(delayedRecheckRunnable, 140L)
    }

    fun updateAlignmentConfig(
        scalePercent: Int,
        radiusDp: Int,
        autoAlign: Boolean,
        trimDp: Int
    ) {
        val nextScale = (scalePercent / 100f).coerceIn(0.75f, 1f)
        val scaleChanged = abs(nextScale - contentScale) > 0.005f
        contentScale = nextScale
        cornerRadiusDp = radiusDp.coerceIn(
            WidgetCornerStyler.MIN_CORNER_RADIUS_DP,
            WidgetCornerStyler.MAX_CORNER_RADIUS_DP
        )
        autoAlignEdges = autoAlign
        extraTrimDp = trimDp.coerceIn(-12, 24)

        applyOuterCornerOutline()
        if (scaleChanged) {
            insetsDirty = true
            requestLayout()
        } else {
            refreshAlignmentInPlace()
        }
    }

    fun onHostWidgetUpdated() {
        insetsDirty = true
        post {
            refreshAlignmentInPlace()
        }
        removeCallbacks(delayedRecheckRunnable)
        postDelayed(delayedRecheckRunnable, 120L)
    }

    private fun applyOuterCornerOutline() {
        val outerPx = WidgetCornerStyler.dpToPxFloat(context, cornerRadiusDp.toFloat())
        WidgetCornerStyler.applyRoundRectOutline(this, outerPx)

        // When 0° sharp corners are selected, also disable any built-in outline clipping
        // on the 3rd-party widget's internal @android:id/background view if present.
        val child = hostView ?: return
        if (cornerRadiusDp == 0 && child.childCount > 0) {
            val rootChild = child.getChildAt(0)
            rootChild?.findViewById<View>(android.R.id.background)?.let { bg ->
                WidgetCornerStyler.applyRoundRectOutline(bg, 0f)
            }
            if (rootChild != null) {
                WidgetCornerStyler.applyRoundRectOutline(rootChild, 0f)
            }
        }
    }

    private fun refreshAlignmentInPlace() {
        val child = hostView ?: return
        val w = width
        val h = height
        val virtW = child.width
        val virtH = child.height
        if (w <= 0 || h <= 0 || virtW <= 0 || virtH <= 0) return

        if (insetsDirty || cachedVirtW != virtW || cachedVirtH != virtH) {
            recomputeInsets(child, virtW, virtH)
        }
        applyOuterCornerOutline()
        applyChildTransform(child, w, h, virtW, virtH)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(delayedRecheckRunnable)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val h = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
        setMeasuredDimension(w, h)

        val child = hostView ?: return
        val virtW = (w / contentScale).roundToInt().coerceAtLeast(1)
        val virtH = (h / contentScale).roundToInt().coerceAtLeast(1)
        child.measure(
            MeasureSpec.makeMeasureSpec(virtW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(virtH, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = (right - left).coerceAtLeast(1)
        val h = (bottom - top).coerceAtLeast(1)
        val child = hostView ?: return
        val virtW = child.measuredWidth.coerceAtLeast(1)
        val virtH = child.measuredHeight.coerceAtLeast(1)
        child.layout(0, 0, virtW, virtH)

        maybeReportWidgetSizeOptions(child, virtW, virtH)

        if (changed || insetsDirty || cachedVirtW != virtW || cachedVirtH != virtH) {
            recomputeInsets(child, virtW, virtH)
        }
        applyOuterCornerOutline()
        applyChildTransform(child, w, h, virtW, virtH)
    }

    private fun maybeReportWidgetSizeOptions(child: AppWidgetHostView, virtW: Int, virtH: Int) {
        if (appWidgetId < 0) return
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        // Report >= 390dp virtual width so 5x1 / 5x2 widgets (e.g. KM Calendar) render full columns
        val virtWDp = (virtW / density).roundToInt().coerceAtLeast(390)
        val virtHDp = (virtH / density).roundToInt().coerceAtLeast(60)
        if (abs(virtWDp - lastReportedWidthDp) >= 4 || abs(virtHDp - lastReportedHeightDp) >= 4) {
            lastReportedWidthDp = virtWDp
            lastReportedHeightDp = virtHDp
            val mgr = AppWidgetManager.getInstance(context.applicationContext)
            LockDockWidgetHostManager.applyWidgetSizeOptions(mgr, child, appWidgetId, virtWDp, virtHDp)
        }
    }

    private fun recomputeInsets(child: AppWidgetHostView, virtW: Int, virtH: Int) {
        cachedVirtW = virtW
        cachedVirtH = virtH
        if (child.childCount == 0) {
            cachedInsets.setEmpty()
            return
        }
        val detected = detectVisualCardInsets(child, virtW, virtH)
        cachedInsets.set(detected)
        insetsDirty = false
    }

    private fun applyChildTransform(
        child: AppWidgetHostView,
        containerW: Int,
        containerH: Int,
        virtW: Int,
        virtH: Int
    ) {
        val baseL = if (autoAlignEdges) cachedInsets.left else 0
        val baseT = if (autoAlignEdges) cachedInsets.top else 0
        val baseR = if (autoAlignEdges) cachedInsets.right else 0
        val baseB = if (autoAlignEdges) cachedInsets.bottom else 0

        val extraVirtPx = (WidgetCornerStyler.dpToPxFloat(context, extraTrimDp.toFloat()) / contentScale).roundToInt()
        val maxTrimX = virtW / 5
        val maxTrimY = virtH / 5
        val minTrimX = -(virtW / 8)

        val trimL = (baseL + extraVirtPx).coerceIn(minTrimX, maxTrimX)
        val trimR = (baseR + extraVirtPx).coerceIn(minTrimX, maxTrimX)
        val trimT = baseT.coerceIn(0, maxTrimY)
        val trimB = baseB.coerceIn(0, maxTrimY)

        val visibleW = (virtW - trimL - trimR).coerceAtLeast(1)
        val visibleH = (virtH - trimT - trimB).coerceAtLeast(1)

        val sx = containerW.toFloat() / visibleW.toFloat()
        val sy = containerH.toFloat() / visibleH.toFloat()

        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = sx
        child.scaleY = sy
        child.translationX = -trimL * sx
        child.translationY = -trimT * sy
    }

    companion object {
        private const val SAMPLE_W = 360
        private const val SAMPLE_H = 180

        /**
         * Renders the laid-out AppWidgetHostView into a lightweight 360x180 probe bitmap and
         * scans the straight mid-bands (avoiding rounded corners and bottom-right FABs like
         * KM Calendar's '+' button) to detect any transparent padding or .9.png shadow margin.
         */
        private fun detectVisualCardInsets(
            hostView: AppWidgetHostView,
            virtW: Int,
            virtH: Int
        ): Rect {
            if (virtW <= 0 || virtH <= 0) return Rect()
            return runCatching {
                val bmp = Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888)
                try {
                    bmp.eraseColor(Color.TRANSPARENT)
                    val canvas = Canvas(bmp)
                    canvas.save()
                    canvas.scale(SAMPLE_W.toFloat() / virtW.toFloat(), SAMPLE_H.toFloat() / virtH.toFloat())
                    hostView.draw(canvas)
                    canvas.restore()

                    val pixels = IntArray(SAMPLE_W * SAMPLE_H)
                    bmp.getPixels(pixels, 0, SAMPLE_W, 0, 0, SAMPLE_W, SAMPLE_H)

                    var maxAlpha = 0
                    for (px in pixels) {
                        val a = px ushr 24
                        if (a > maxAlpha) maxAlpha = a
                    }
                    if (maxAlpha < 40) {
                        return Rect()
                    }

                    // Skip .9.png soft drop-shadows (< 115 alpha) when card itself is opaque;
                    // support translucent frosted cards when maxAlpha is lower.
                    val threshold = if (maxAlpha >= 180) {
                        115
                    } else {
                        (maxAlpha * 0.55f).roundToInt().coerceAtLeast(32)
                    }

                    // Scan Y band [25%..62%] for left & right straight card edges
                    // (clears top/bottom rounded corners AND bottom-right FABs!)
                    val yStart = (SAMPLE_H * 0.25f).roundToInt()
                    val yEnd = (SAMPLE_H * 0.62f).roundToInt()
                    val minVerticalHits = ((yEnd - yStart) * 0.30f).roundToInt().coerceAtLeast(6)
                    val maxScanX = (SAMPLE_W * 0.16f).roundToInt()

                    var leftSample = -1
                    for (x in 0..maxScanX) {
                        var hits = 0
                        for (y in yStart..yEnd) {
                            if ((pixels[y * SAMPLE_W + x] ushr 24) >= threshold) {
                                hits++
                                if (hits >= minVerticalHits) break
                            }
                        }
                        if (hits >= minVerticalHits) {
                            leftSample = x
                            break
                        }
                    }

                    var rightSample = -1
                    for (x in (SAMPLE_W - 1) downTo (SAMPLE_W - 1 - maxScanX)) {
                        var hits = 0
                        for (y in yStart..yEnd) {
                            if ((pixels[y * SAMPLE_W + x] ushr 24) >= threshold) {
                                hits++
                                if (hits >= minVerticalHits) break
                            }
                        }
                        if (hits >= minVerticalHits) {
                            rightSample = (SAMPLE_W - 1) - x
                            break
                        }
                    }

                    // Scan X band [22%..70%] for top & bottom straight card edges
                    val xStart = (SAMPLE_W * 0.22f).roundToInt()
                    val xEnd = (SAMPLE_W * 0.70f).roundToInt()
                    val minHorizontalHits = ((xEnd - xStart) * 0.30f).roundToInt().coerceAtLeast(12)
                    val maxScanY = (SAMPLE_H * 0.18f).roundToInt()

                    var topSample = -1
                    for (y in 0..maxScanY) {
                        var hits = 0
                        val rowOffset = y * SAMPLE_W
                        for (x in xStart..xEnd) {
                            if ((pixels[rowOffset + x] ushr 24) >= threshold) {
                                hits++
                                if (hits >= minHorizontalHits) break
                            }
                        }
                        if (hits >= minHorizontalHits) {
                            topSample = y
                            break
                        }
                    }

                    var bottomSample = -1
                    for (y in (SAMPLE_H - 1) downTo (SAMPLE_H - 1 - maxScanY)) {
                        var hits = 0
                        val rowOffset = y * SAMPLE_W
                        for (x in xStart..xEnd) {
                            if ((pixels[rowOffset + x] ushr 24) >= threshold) {
                                hits++
                                if (hits >= minHorizontalHits) break
                            }
                        }
                        if (hits >= minHorizontalHits) {
                            bottomSample = (SAMPLE_H - 1) - y
                            break
                        }
                    }

                    // Only trim axes where an authentic card edge was found within the margin zone
                    // (protects borderless floating text/clock widgets from ever being stretched!)
                    val insetL = if (leftSample >= 0 && rightSample >= 0) {
                        ((leftSample.toFloat() / SAMPLE_W) * virtW).roundToInt()
                    } else 0
                    val insetR = if (leftSample >= 0 && rightSample >= 0) {
                        ((rightSample.toFloat() / SAMPLE_W) * virtW).roundToInt()
                    } else 0
                    val insetT = if (topSample >= 0 && bottomSample >= 0) {
                        ((topSample.toFloat() / SAMPLE_H) * virtH).roundToInt()
                    } else 0
                    val insetB = if (topSample >= 0 && bottomSample >= 0) {
                        ((bottomSample.toFloat() / SAMPLE_H) * virtH).roundToInt()
                    } else 0

                    Rect(insetL, insetT, insetR, insetB)
                } finally {
                    bmp.recycle()
                }
            }.getOrDefault(Rect())
        }
    }
}
