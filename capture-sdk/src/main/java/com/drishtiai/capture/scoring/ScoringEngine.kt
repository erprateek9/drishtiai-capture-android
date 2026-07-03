package com.drishtiai.capture.scoring

import android.graphics.Bitmap
import com.drishtiai.capture.model.CheckResult
import com.drishtiai.capture.model.Decision
import com.drishtiai.capture.model.QualityConfig
import com.drishtiai.capture.model.QualityResult
import kotlin.math.roundToInt

/**
 * Kotlin port of sdk-core/src/scoring/scoringEngine.ts. Behaviour is kept
 * in lockstep with the TypeScript reference implementation: same lenient
 * decay bands, same weights, same black-frame hard gate. If you change a
 * threshold/weight here, mirror the change in sdk-core and sdk-ios.
 */
object ScoringEngine {

    private const val BRIGHTNESS_BAND = 50.0
    private const val BLUR_BAND_RATIO = 0.6
    private const val OVEREXPOSURE_BAND = 0.3
    private const val CONTRAST_BAND_RATIO = 0.8

    private val WEIGHTS = mapOf(
        "brightness" to 0.25,
        "blur" to 0.30,
        "contrast" to 0.15,
        "overexposure" to 0.15,
        "objectPresence" to 0.10,
        "stability" to 0.05
    )

    private fun scoreAboveThreshold(value: Double, threshold: Double, band: Double, floor: Double = 15.0): Double {
        if (value >= threshold) return 100.0
        return ImageUtils.mapRange(value, max(0.0, threshold - band), threshold, floor, 100.0)
    }

    private fun scoreBelowThreshold(value: Double, threshold: Double, band: Double, floor: Double = 0.0): Double {
        if (value <= threshold) return 100.0
        return ImageUtils.mapRange(value, threshold, threshold + band, 100.0, floor)
    }

    private fun scoreWithinRange(value: Double, min: Double, max: Double, band: Double, floor: Double = 10.0): Double {
        if (value in min..max) return 100.0
        return if (value < min) ImageUtils.mapRange(value, min - band, min, floor, 100.0)
        else ImageUtils.mapRange(value, max, max + band, 100.0, floor)
    }

    private fun max(a: Double, b: Double) = if (a > b) a else b

    private fun clampScore(score: Double): Int = ImageUtils.clamp(score, 0.0, 100.0).roundToInt()

    /**
     * Scores a single decoded Bitmap frame against [config]. Callers
     * (CameraX analyzer, or a plain photo review flow) should decode to a
     * Bitmap once per frame/photo and pass it here - all pixel math is
     * self-contained, no other Android APIs are touched.
     */
    fun score(bitmap: Bitmap, config: QualityConfig): QualityResult {
        val gray = ImageUtils.toGrayscale(bitmap)
        val meanBrightness = ImageUtils.mean(gray)

        val blackFramePassed = meanBrightness >= config.blackFrameBrightnessThreshold
        val blackFrame = CheckResult(
            name = "blackFrame",
            score = if (blackFramePassed) 100 else 0,
            passed = blackFramePassed,
            value = meanBrightness,
            message = if (blackFramePassed) null else "Camera view is almost completely black. Check if the lens is covered."
        )

        val brightnessPassed = meanBrightness in config.minBrightness..config.maxBrightness
        val brightness = CheckResult(
            name = "brightness",
            score = clampScore(scoreWithinRange(meanBrightness, config.minBrightness, config.maxBrightness, BRIGHTNESS_BAND)),
            passed = brightnessPassed,
            value = meanBrightness,
            message = when {
                brightnessPassed -> null
                meanBrightness < config.minBrightness -> "Image is too dark. Move to a well-lit area or turn on more light."
                else -> "Image is too bright. Reduce direct light or glare on the package."
            }
        )

        val lapVar = ImageUtils.laplacianVariance(gray, bitmap.width, bitmap.height)
        val blurPassed = lapVar >= config.blurThreshold
        val blur = CheckResult(
            name = "blur",
            score = clampScore(scoreAboveThreshold(lapVar, config.blurThreshold, config.blurThreshold * BLUR_BAND_RATIO)),
            passed = blurPassed,
            value = lapVar,
            message = if (blurPassed) null else "Image looks blurry. Hold the camera steady and let it focus before capturing."
        )

        val overexposedRatio = ImageUtils.overexposedRatio(gray)
        val overexposedPassed = overexposedRatio <= config.overexposedPixelRatioThreshold
        val overexposure = CheckResult(
            name = "overexposure",
            score = clampScore(scoreBelowThreshold(overexposedRatio, config.overexposedPixelRatioThreshold, OVEREXPOSURE_BAND)),
            passed = overexposedPassed,
            value = overexposedRatio,
            message = if (overexposedPassed) null else "Too much glare or blown-out highlights. Avoid direct flash/light reflecting off the package."
        )

        val stdDev = ImageUtils.stdDev(gray, meanBrightness)
        val contrastPassed = stdDev >= config.minContrast
        val contrast = CheckResult(
            name = "contrast",
            score = clampScore(scoreAboveThreshold(stdDev, config.minContrast, config.minContrast * CONTRAST_BAND_RATIO)),
            passed = contrastPassed,
            value = stdDev,
            message = if (contrastPassed) null else "Image looks flat/washed out. Try a plainer background with more contrast against the package."
        )

        // Placeholders - see sdk-core/src/scoring/objectPresence.ts and stability.ts for rationale.
        val objectPresence = CheckResult(name = "objectPresence", score = 100, passed = true)
        val stability = CheckResult(name = "stability", score = 100, passed = true)

        val weighted = listOf(brightness, blur, overexposure, contrast, objectPresence, stability)
        var weightedScore = 0.0
        var weightSum = 0.0
        for (check in weighted) {
            val w = WEIGHTS[check.name] ?: 0.0
            weightedScore += check.score * w
            weightSum += w
        }
        var finalScore = if (weightSum > 0) weightedScore / weightSum else 0.0
        if (!blackFramePassed) finalScore = min(finalScore, 5.0)

        val score = clampScore(finalScore)
        val decision = when {
            score >= config.minQualityScoreAutoCapture -> Decision.AUTO_CAPTURE
            score >= config.minQualityScoreManualAllow -> Decision.MANUAL_ALLOW
            else -> Decision.BLOCK
        }

        val checks = listOf(blackFrame, brightness, blur, overexposure, contrast, objectPresence, stability)
        val guidance = checks.filter { it.message != null }.sortedBy { it.score }.mapNotNull { it.message }

        return QualityResult(
            score = score,
            decision = decision,
            checks = checks,
            guidance = guidance,
            configVersion = config.configVersion,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun min(a: Double, b: Double) = if (a < b) a else b
}
