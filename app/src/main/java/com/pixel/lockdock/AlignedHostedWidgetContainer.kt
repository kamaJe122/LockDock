package com.pixel.lockdock

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Wraps any hosted 3rd-party AppWidgetHostView so that:
 * 1) Its outer container width strictly equals LockDock's unified widget width.
 * 2) Detected visual card insets are cached per appWidgetId so displays and screen wakes
 *    apply exact alignment on frame 0 with zero 140ms secondary scale jump.
 * 3) Widget size options are reported only via LockDockWidgetHostManager's deduplicated
 *    canonical dp dimensions so 3rd-party widgets never reload unnecessarily.
 */
class AlignedHostedWidgetContainer(context: Context) : FrameLayout(context) {

    private var hostView: AppWidgetHostView? = null
    private var appWidgetId: Int = -1
    private var entryHeightDp: Int = 195
    private var entryScalePercent: Int = 100
    private var globalWidthPercent: Int = WidgetCornerStyler.DEFAULT_WIDTH_PERCENT
    private var contentScale: Float = 1f
    private var cornerRadiusDp: Int = WidgetCornerStyler.DEFAULT_CORNER_RADIUS_DP
    private var autoAlignEdges: Boolean = true
    private var extraTrimDp: Int = 0

    private var hasResolvedInsets: Boolean = false
    private var cachedInsets: Rect = Rect()

    private val initialFallbackProbeRunnable = Runnable {
        if (!hasResolvedInsets) {
            tryResolveAndCacheInsets()
            refreshTransformOnly()
        }
    }

    init {
        clipChildren = true
        clipToPadding = false
    }

    fun bindHostView(
        view: AppWidgetHostView?,
        widgetId: Int,
        heightDp: Int,
        scalePercent: Int,
        widthPercent: Int,
        radiusDp: Int,
        autoAlign: Boolean,
        trimDp: Int
    ) {
        removeCallbacks(initialFallbackProbeRunnable)
        if (hostView !== view) {
            removeAllViews()
            hostView = view
            if (view != null) {
                (view.parent as? FrameLayout)?.removeView(view)
                addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
        }
        appWidgetId = widgetId
        entryHeightDp = heightDp
        entryScalePercent = scalePercent
        globalWidthPercent = widthPercent
        contentScale = (scalePercent / 100f).coerceIn(0.75f, 1f)
        cornerRadiusDp = radiusDp.coerceIn(
            WidgetCornerStyler.MIN_CORNER_RADIUS_DP,
            WidgetCornerStyler.MAX_CORNER_RADIUS_DP
        )
        autoAlignEdges = autoAlign
        extraTrimDp = trimDp.coerceIn(-12, 24)

        val preCached = LockDockWidgetHostManager.getCachedInsets(context, widgetId)
        hasResolvedInsets = preCached != null
        if (preCached == null) {
            cachedInsets.setEmpty()
            postDelayed(initialFallbackProbeRunnable, 140L)
        }

        applyOuterCornerOutline()
        requestLayout()
    }

    fun updateAlignmentConfig(
        heightDp: Int,
        scalePercent: Int,
        widthPercent: Int,
        radiusDp: Int,
        autoAlign: Boolean,
        trimDp: Int
    ) {
        val nextScale = (scalePercent / 100f).coerceIn(0.75f, 1f)
        val scaleChanged = abs(nextScale - contentScale) > 0.005f
        val sizeInputsChanged = entryHeightDp != heightDp ||
            entryScalePercent != scalePercent ||
            globalWidthPercent != widthPercent

        entryHeightDp = heightDp
        entryScalePercent = scalePercent
        globalWidthPercent = widthPercent
        contentScale = nextScale
        cornerRadiusDp = radiusDp.coerceIn(
            WidgetCornerStyler.MIN_CORNER_RADIUS_DP,
            WidgetCornerStyler.MAX_CORNER_RADIUS_DP
        )
        autoAlignEdges = autoAlign
        extraTrimDp = trimDp.coerceIn(-12, 24)

        if (sizeInputsChanged) {
            maybeReportCanonicalSizeOptions()
        }

        applyOuterCornerOutline()
        if (scaleChanged) {
            requestLayout()
        } else {
            refreshTransformOnly()
        }
    }

    fun onHostWidgetUpdated() {
        if (hasResolvedInsets) {
            // Insets already cached for this appWidgetId -> zero re-scan, zero delayed jump!
            post { applyOuterCornerOutline() }
            return
        }
        post {
            tryResolveAndCacheInsets()
            refreshTransformOnly()
        }
    }

    private fun applyOuterCornerOutline() {
        val outerPx = WidgetCornerStyler.dpToPxFloat(context, cornerRadiusDp.toFloat())
        WidgetCornerStyler.applyRoundRectOutline(this, outerPx)

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

    private fun refreshTransformOnly() {
        val child = hostView ?: return
        val w = width
        val h = height
        val virtW = child.width
        val virtH = child.height
        if (w <= 0 || h <= 0 || virtW <= 0 || virtH <= 0) return

        LockDockWidgetHostManager.getCachedInsets(context, appWidgetId)?.let { norm ->
            cachedInsets.set(norm.toPixelRect(virtW, virtH))
            hasResolvedInsets = true
        }
        applyOuterCornerOutline()
        applyChildTransform(child, w, h, virtW, virtH)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(initialFallbackProbeRunnable)
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

        maybeReportCanonicalSizeOptions()

        val existing = LockDockWidgetHostManager.getCachedInsets(context, appWidgetId)
        if (existing != null) {
            cachedInsets.set(existing.toPixelRect(virtW, virtH))
            hasResolvedInsets = true
            removeCallbacks(initialFallbackProbeRunnable)
        } else {
            tryResolveAndCacheInsets()
        }

        applyOuterCornerOutline()
        applyChildTransform(child, w, h, virtW, virtH)
    }

    private fun maybeReportCanonicalSizeOptions() {
        val child = hostView ?: return
        if (appWidgetId < 0) return
        val (canonicalW, canonicalH) = LockDockWidgetHostManager.computeCanonicalWidgetSizeDp(
            context = context,
            heightDp = entryHeightDp,
            scalePercent = entryScalePercent,
            widthPercent = globalWidthPercent
        )
        val mgr = AppWidgetManager.getInstance(context.applicationContext)
        LockDockWidgetHostManager.applyWidgetSizeOptions(
            mgr = mgr,
            view = child,
            appWidgetId = appWidgetId,
            widthDp = canonicalW,
            heightDp = canonicalH
        )
    }

    private fun tryResolveAndCacheInsets() {
        val child = hostView ?: return
        val virtW = child.width
        val virtH = child.height
        if (virtW <= 0 || virtH <= 0 || child.childCount == 0) return

        val detected = detectVisualCardInsetsNormalized(child, virtW, virtH) ?: return
        LockDockWidgetHostManager.saveCachedInsets(context, appWidgetId, detected)
        cachedInsets.set(detected.toPixelRect(virtW, virtH))
        hasResolvedInsets = true
        removeCallbacks(initialFallbackProbeRunnable)
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
         * Returns null if the widget has not drawn any visible pixels yet (maxAlpha < 40),
         * or NormalizedWidgetInsets once actual widget content is rendered.
         */
        private fun detectVisualCardInsetsNormalized(
            hostView: AppWidgetHostView,
            virtW: Int,
            virtH: Int
        ): NormalizedWidgetInsets? {
            if (virtW <= 0 || virtH <= 0) return null
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
                        return null
                    }

                    val threshold = if (maxAlpha >= 180) {
                        115
                    } else {
                        (maxAlpha * 0.55f).roundToInt().coerceAtLeast(32)
                    }

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

                    val lRatio = if (leftSample >= 0 && rightSample >= 0) {
                        leftSample.toFloat() / SAMPLE_W
                    } else 0f
                    val rRatio = if (leftSample >= 0 && rightSample >= 0) {
                        rightSample.toFloat() / SAMPLE_W
                    } else 0f
                    val tRatio = if (topSample >= 0 && bottomSample >= 0) {
                        topSample.toFloat() / SAMPLE_H
                    } else 0f
                    val bRatio = if (topSample >= 0 && bottomSample >= 0) {
                        bottomSample.toFloat() / SAMPLE_H
                    } else 0f

                    NormalizedWidgetInsets(lRatio, tRatio, rRatio, bRatio)
                } finally {
                    bmp.recycle()
                }
            }.getOrNull()
        }
    }
}
