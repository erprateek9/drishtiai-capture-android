package com.drishtiai.capture.scoring

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Kotlin port of sdk-core/src/utils/imageUtils.ts. Kept as plain arithmetic
 * over a grayscale DoubleArray (mirroring TypeScript's Float64Array) so the
 * exact same algorithms (variance of Laplacian for blur, std-dev for
 * contrast, etc.) run natively without a TypeScript runtime, while staying
 * easy to diff against the TS reference.
 *
 * These functions only ever consume a grayscale array + width/height - they
 * are deliberately agnostic to how the grayscale was produced. That lets two
 * very different producers feed the same math:
 *   - Live CameraX preview frames (YUV_420_888): the Y-plane IS already
 *     luma, so [com.drishtiai.capture.camera.CameraXAnalyzer] reads it
 *     directly into a DoubleArray, no RGBA conversion involved.
 *   - A captured photo Bitmap: [toGrayscale] below does the real
 *     RGBA-weighted Rec.601 conversion.
 */
object ImageUtils {

    /**
     * Converts a decoded Bitmap's ARGB pixels to a single-channel luminance
     * (grayscale) buffer using the standard Rec. 601 luma weights (alpha is
     * ignored, matching sdk-core's RGBA->gray conversion which also ignores
     * alpha). Pure arithmetic, no dependency.
     */
    fun toGrayscale(bitmap: Bitmap): DoubleArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val gray = DoubleArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = 0.299 * r + 0.587 * g + 0.114 * b
        }
        return gray
    }

    fun mean(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        var sum = 0.0
        for (v in values) sum += v
        return sum / values.size
    }

    fun stdDev(values: DoubleArray, precomputedMean: Double? = null): Double {
        if (values.isEmpty()) return 0.0
        val m = precomputedMean ?: mean(values)
        var sumSq = 0.0
        for (v in values) {
            val d = v - m
            sumSq += d * d
        }
        return sqrt(sumSq / values.size)
    }

    /** Fraction of pixels at/above [threshold] (0-255) luminance. */
    fun overexposedRatio(gray: DoubleArray, threshold: Double = 250.0): Double {
        if (gray.isEmpty()) return 0.0
        var count = 0
        for (v in gray) if (v >= threshold) count++
        return count.toDouble() / gray.size
    }

    /**
     * Classic "variance of Laplacian" sharpness metric. Convolves the
     * grayscale image with a discrete Laplacian kernel
     * [[0,1,0],[1,-4,1],[0,1,0]] and returns the VARIANCE of the response
     * (i.e. stdDev squared) - higher variance = sharper edges = less blur.
     */
    fun laplacianVariance(gray: DoubleArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0
        val responses = DoubleArray((width - 2) * (height - 2))
        var idx = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val center = gray[y * width + x]
                val up = gray[(y - 1) * width + x]
                val down = gray[(y + 1) * width + x]
                val left = gray[y * width + (x - 1)]
                val right = gray[y * width + (x + 1)]
                responses[idx++] = up + down + left + right - 4 * center
            }
        }
        val sd = stdDev(responses)
        return sd * sd
    }

    /**
     * Lightweight "edge/detail density" metric distinct from blur: the
     * fraction of pixels whose simple horizontal+vertical neighbor
     * difference exceeds [threshold]. Single pass, no convolution kernel -
     * deliberately cheaper than [laplacianVariance] for a coarse secondary
     * signal.
     *
     * Optionally scoped to a sub-region of the frame via [x0]/[y0]/[x1]/[y1]
     * (default: the full frame) - used by [centerVsEdgeDetailRatio] to
     * compare center-region detail density against the full-frame value,
     * sharing this same loop instead of duplicating the gradient-threshold
     * logic. Mirrors sdk-core/src/utils/imageUtils.ts's `edgeDensity` and its
     * `BoundingBox` parameter exactly.
     */
    fun edgeDensity(
        gray: DoubleArray,
        width: Int,
        height: Int,
        threshold: Double = 15.0,
        x0: Int = 0,
        y0: Int = 0,
        x1: Int = width - 1,
        y1: Int = height - 1
    ): Double {
        if (width < 2 || height < 2) return 0.0
        val startX = max(0.0, x0.toDouble()).toInt()
        val startY = max(0.0, y0.toDouble()).toInt()
        val endX = min((width - 1).toDouble(), x1.toDouble()).toInt()
        val endY = min((height - 1).toDouble(), y1.toDouble()).toInt()

        var edgeCount = 0
        var total = 0
        for (y in startY until endY) {
            for (x in startX until endX) {
                val i = y * width + x
                val dx = kotlin.math.abs(gray[i] - gray[i + 1])
                val dy = kotlin.math.abs(gray[i] - gray[i + width])
                if (dx + dy >= threshold) edgeCount++
                total++
            }
        }
        return if (total > 0) edgeCount.toDouble() / total else 0.0
    }

    /**
     * Center-vs-edge detail ratio: edge density in the inner 50%x50% box of
     * the frame divided by the full-frame edge density (plus a small epsilon
     * to avoid divide-by-zero on a blank frame). A real foreground object (a
     * shipment package) tends to concentrate detail/contrast toward the
     * center of a reasonably framed photo; a random/no-object frame tends to
     * spread detail evenly or concentrate it at the edges (e.g. a doorway or
     * shelf). Ratio > 1 = center-heavy detail, ~1 = uniform, < 1 = edge-heavy.
     * Mirrors sdk-core/src/utils/imageUtils.ts's `centerVsEdgeDetailRatio`.
     */
    fun centerVsEdgeDetailRatio(gray: DoubleArray, width: Int, height: Int, threshold: Double = 15.0): Double {
        val fullDensity = edgeDensity(gray, width, height, threshold)
        val x0 = (width * 0.25).toInt()
        val y0 = (height * 0.25).toInt()
        val x1 = kotlin.math.ceil(width * 0.75).toInt()
        val y1 = kotlin.math.ceil(height * 0.75).toInt()
        val centerDensity = edgeDensity(gray, width, height, threshold, x0, y0, x1, y1)
        return centerDensity / (fullDensity + 1e-6)
    }

    /**
     * Per-pixel saturation proxy `(max(r,g,b) - min(r,g,b)) / max(r,g,b)`,
     * averaged and standard-deviated across the frame. Computed from the raw
     * ARGB pixels directly since grayscale alone loses color. Shipment
     * photos (cardboard, tape, printed labels) trend low-saturation and
     * fairly uniform; many "random photo" negatives (skin tones, colorful
     * clothing, cluttered backgrounds) trend higher and more varied - both
     * the level and the spread are potentially discriminative features.
     * Mirrors sdk-core/src/utils/imageUtils.ts's `saturationStats`.
     */
    fun saturationStats(bitmap: Bitmap): Pair<Double, Double> {
        val width = bitmap.width
        val height = bitmap.height
        val pixelCount = width * height
        if (pixelCount == 0) return Pair(0.0, 0.0)

        val pixels = IntArray(pixelCount)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val saturations = DoubleArray(pixelCount)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val maxC = max(r.toDouble(), max(g.toDouble(), b.toDouble()))
            val minC = min(r.toDouble(), min(g.toDouble(), b.toDouble()))
            saturations[i] = if (maxC > 0) (maxC - minC) / maxC else 0.0
        }

        val meanSaturation = mean(saturations)
        val stdDevSaturation = stdDev(saturations, meanSaturation)
        return Pair(meanSaturation, stdDevSaturation)
    }

    /** Clamp a number into [lo, hi]. */
    fun clamp(value: Double, lo: Double, hi: Double): Double = max(lo, min(hi, value))

    /** Linearly maps value from [inMin, inMax] into [outMin, outMax], clamped. */
    fun mapRange(value: Double, inMin: Double, inMax: Double, outMin: Double, outMax: Double): Double {
        if (inMax == inMin) return outMax
        val t = clamp((value - inMin) / (inMax - inMin), 0.0, 1.0)
        return outMin + t * (outMax - outMin)
    }
}
