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
     */
    fun edgeDensity(gray: DoubleArray, width: Int, height: Int, threshold: Double = 15.0): Double {
        if (width < 2 || height < 2) return 0.0
        var edgeCount = 0
        var total = 0
        for (y in 0 until height - 1) {
            for (x in 0 until width - 1) {
                val i = y * width + x
                val dx = kotlin.math.abs(gray[i] - gray[i + 1])
                val dy = kotlin.math.abs(gray[i] - gray[i + width])
                if (dx + dy >= threshold) edgeCount++
                total++
            }
        }
        return if (total > 0) edgeCount.toDouble() / total else 0.0
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
