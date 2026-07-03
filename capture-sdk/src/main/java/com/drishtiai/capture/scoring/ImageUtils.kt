package com.drishtiai.capture.scoring

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Kotlin port of sdk-core/src/utils/imageUtils.ts. Kept as plain arithmetic
 * over a grayscale FloatArray so the exact same algorithms (variance of
 * Laplacian for blur, std-dev for contrast, etc.) run natively without a
 * TypeScript runtime, while staying easy to diff against the TS reference.
 */
object ImageUtils {

    fun toGrayscale(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val gray = FloatArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        return gray
    }

    fun mean(values: FloatArray): Double {
        if (values.isEmpty()) return 0.0
        var sum = 0.0
        for (v in values) sum += v
        return sum / values.size
    }

    fun stdDev(values: FloatArray, precomputedMean: Double? = null): Double {
        if (values.isEmpty()) return 0.0
        val m = precomputedMean ?: mean(values)
        var sumSq = 0.0
        for (v in values) {
            val d = v - m
            sumSq += d * d
        }
        return sqrt(sumSq / values.size)
    }

    fun overexposedRatio(gray: FloatArray, threshold: Float = 250f): Double {
        if (gray.isEmpty()) return 0.0
        var count = 0
        for (v in gray) if (v >= threshold) count++
        return count.toDouble() / gray.size
    }

    /** Variance-of-Laplacian sharpness metric - higher means sharper/less blurry. */
    fun laplacianVariance(gray: FloatArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0
        val responses = FloatArray((width - 2) * (height - 2))
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

    fun clamp(value: Double, lo: Double, hi: Double): Double = max(lo, min(hi, value))

    fun mapRange(value: Double, inMin: Double, inMax: Double, outMin: Double, outMax: Double): Double {
        if (inMax == inMin) return outMax
        val t = clamp((value - inMin) / (inMax - inMin), 0.0, 1.0)
        return outMin + t * (outMax - outMin)
    }
}
