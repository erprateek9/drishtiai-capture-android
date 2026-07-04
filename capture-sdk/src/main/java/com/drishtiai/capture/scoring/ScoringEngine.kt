package com.drishtiai.capture.scoring

import android.graphics.Bitmap
import com.drishtiai.capture.model.CaptureDecision
import com.drishtiai.capture.model.CaptureStatus
import com.drishtiai.capture.model.CheckStatus
import com.drishtiai.capture.model.FrameAnalysisResult
import com.drishtiai.capture.model.FrameHistoryEntry
import com.drishtiai.capture.model.IssueCode
import com.drishtiai.capture.model.QualityCheckResult
import com.drishtiai.capture.model.QualityConfig
import kotlin.math.roundToInt

/**
 * Kotlin port of sdk-core/src/scoring/scoringEngine.ts (plus
 * quality checks, scoring/weights.ts, scoring/statusFromScore.ts,
 * scoring/decision.ts and guidance messages, all inlined into this one object
 * for the Android port). Behaviour is kept in lockstep with the TypeScript
 * reference implementation: same lenient decay bands, same weights, same
 * black-frame hard gate, same guidance priority order. If you change a
 * threshold/weight here, mirror the change in sdk-core and sdk-ios.
 */
object ScoringEngine {

    // ---- Guidance messages (sdk-core/src/guidance/messages.ts) ----
    private const val MOVE_TO_BETTER_LIGHT = "Move to better light"
    private const val HOLD_STEADY = "Hold steady"
    private const val TOO_DARK_MSG = "Image is too dark"
    private const val TOO_BRIGHT_MSG = "Image is too bright"
    private const val MOVE_SHIPMENT_INTO_FRAME = "Move shipment into frame"
    private const val CAMERA_MAY_BE_COVERED = "Camera may be covered"
    private const val CAPTURE_ALLOWED = "Capture allowed"
    private const val LOOKS_GOOD_HOLDING_STEADY = "Looks good, holding steady"

    // ---- Leniency bands (sdk-core/src/quality/*.ts) ----
    private const val BRIGHTNESS_BAND = 50.0
    private const val BLUR_BAND_RATIO = 0.6
    private const val OVEREXPOSURE_BAND = 0.3
    private const val CONTRAST_BAND_RATIO = 0.8
    private const val EDGE_DENSITY_BAND_RATIO = 1.0
    private const val DEFAULT_MIN_EDGE_DENSITY = 0.03

    /**
     * Default weights for the checks that feed the final weighted score.
     * `blackFrame` is intentionally excluded here - it acts as a hard gate
     * instead, not a weighted contributor. Mirrors
     * sdk-core/src/scoring/weights.ts#DEFAULT_WEIGHTS exactly.
     */
    private val DEFAULT_WEIGHTS = mapOf(
        "brightness" to 0.22,
        "blur" to 0.28,
        "contrast" to 0.13,
        "overexposure" to 0.13,
        "edgeDetail" to 0.09,
        "objectPresence" to 0.10,
        "stability" to 0.05
    )

    // ---- helpers.ts ----

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

    private fun clampScore(score: Double): Int = ImageUtils.clamp(score, 0.0, 100.0).roundToInt()

    private fun deriveStatus(passed: Boolean, score: Int): CheckStatus {
        if (passed) return CheckStatus.PASS
        return if (score >= 40) CheckStatus.WARN else CheckStatus.FAIL
    }

    private fun max(a: Double, b: Double) = if (a > b) a else b
    private fun min(a: Double, b: Double) = if (a < b) a else b

    // ---- Individual quality checks (sdk-core/src/quality/*.ts) ----

    fun checkBrightness(meanBrightness: Double, config: QualityConfig): QualityCheckResult {
        val tooDark = meanBrightness < config.minBrightness
        val tooBright = meanBrightness > config.maxBrightness
        val passed = !tooDark && !tooBright
        val score = clampScore(
            scoreWithinRange(meanBrightness, config.minBrightness, config.maxBrightness, BRIGHTNESS_BAND)
        )
        return QualityCheckResult(
            value = meanBrightness,
            score = score,
            status = deriveStatus(passed, score),
            issue = if (tooDark) IssueCode.TOO_DARK else if (tooBright) IssueCode.TOO_BRIGHT else null
        )
    }

    /**
     * Hard gate: a near-fully-black frame almost always means a covered
     * lens, lens cap, or capture in total darkness - not a subjective
     * quality issue. This is the one check allowed to force score to 0
     * regardless of leniency mode.
     */
    fun checkBlackFrame(meanBrightness: Double, config: QualityConfig): QualityCheckResult {
        val passed = meanBrightness >= config.blackFrameBrightnessThreshold
        return QualityCheckResult(
            value = meanBrightness,
            score = if (passed) 100 else 0,
            status = if (passed) CheckStatus.PASS else CheckStatus.FAIL,
            issue = if (passed) null else IssueCode.BLACK_FRAME
        )
    }

    fun checkBlur(laplacianVariance: Double, config: QualityConfig): QualityCheckResult {
        val band = config.blurThreshold * BLUR_BAND_RATIO
        val passed = laplacianVariance >= config.blurThreshold
        val score = clampScore(scoreAboveThreshold(laplacianVariance, config.blurThreshold, band))
        return QualityCheckResult(
            value = laplacianVariance,
            score = score,
            status = deriveStatus(passed, score),
            issue = if (passed) null else IssueCode.BLURRY
        )
    }

    fun checkOverexposure(overexposedRatio: Double, config: QualityConfig): QualityCheckResult {
        val passed = overexposedRatio <= config.overexposedPixelRatioThreshold
        val score = clampScore(
            scoreBelowThreshold(overexposedRatio, config.overexposedPixelRatioThreshold, OVEREXPOSURE_BAND)
        )
        return QualityCheckResult(
            value = overexposedRatio,
            score = score,
            status = deriveStatus(passed, score),
            issue = if (passed) null else IssueCode.OVEREXPOSED
        )
    }

    fun checkContrast(stdDevValue: Double, config: QualityConfig): QualityCheckResult {
        val band = config.minContrast * CONTRAST_BAND_RATIO
        val passed = stdDevValue >= config.minContrast
        val score = clampScore(scoreAboveThreshold(stdDevValue, config.minContrast, band))
        return QualityCheckResult(
            value = stdDevValue,
            score = score,
            status = deriveStatus(passed, score),
            issue = if (passed) null else IssueCode.LOW_CONTRAST
        )
    }

    fun checkEdgeDetail(edgeDensityValue: Double, config: QualityConfig): QualityCheckResult {
        val threshold = config.minEdgeDensity ?: DEFAULT_MIN_EDGE_DENSITY
        val band = threshold * EDGE_DENSITY_BAND_RATIO
        val passed = edgeDensityValue >= threshold
        val score = clampScore(scoreAboveThreshold(edgeDensityValue, threshold, band))
        return QualityCheckResult(
            value = edgeDensityValue,
            score = score,
            status = deriveStatus(passed, score),
            issue = if (passed) null else IssueCode.LOW_DETAIL
        )
    }

    /**
     * Placeholder for future package/object presence detection. Always
     * returns a neutral pass so it never blocks capture, but is wired into
     * the weighted score so enabling a real model later requires no changes
     * to the scoring engine.
     */
    fun checkObjectPresence(): QualityCheckResult =
        QualityCheckResult(value = 1.0, score = 100, status = CheckStatus.PASS)

    /**
     * Placeholder for multi-frame hand-shake/motion stability detection. A
     * single still image has no "stability" of its own, so this always
     * returns a neutral pass when no history is supplied.
     */
    fun checkStability(history: List<FrameHistoryEntry>?, config: QualityConfig): QualityCheckResult {
        if (history == null || history.size < 2) {
            return QualityCheckResult(value = 0.0, score = 100, status = CheckStatus.PASS)
        }

        val recent = history.takeLast(config.requiredStableFrames)
        val brightnessValues = recent.map { it.meanBrightness }
        val avg = brightnessValues.sum() / brightnessValues.size
        val variance = brightnessValues.sumOf { (it - avg) * (it - avg) } / brightnessValues.size

        val score = if (variance < 4) 100 else if (variance < 15) 80 else 60

        return QualityCheckResult(
            value = variance,
            score = score,
            status = if (score >= 80) CheckStatus.PASS else CheckStatus.WARN,
            issue = if (score >= 80) null else IssueCode.UNSTABLE
        )
    }

    // ---- Guidance selection (sdk-core/src/guidance/selectGuidance.ts) ----

    private fun notPassing(check: QualityCheckResult?): Boolean = check != null && check.status != CheckStatus.PASS

    private fun selectGuidance(
        checks: Map<String, QualityCheckResult>,
        issues: List<IssueCode>,
        status: CaptureStatus
    ): String {
        if (issues.contains(IssueCode.BLACK_FRAME)) {
            return CAMERA_MAY_BE_COVERED
        }

        val brightness = checks["brightness"]
        if (brightness != null && brightness.status == CheckStatus.FAIL) {
            if (issues.contains(IssueCode.TOO_DARK)) return TOO_DARK_MSG
            if (issues.contains(IssueCode.TOO_BRIGHT)) return TOO_BRIGHT_MSG
        }
        if (brightness != null && brightness.status == CheckStatus.WARN) {
            return MOVE_TO_BETTER_LIGHT
        }

        if (notPassing(checks["blur"]) || notPassing(checks["stability"])) {
            return HOLD_STEADY
        }

        if (notPassing(checks["contrast"]) || notPassing(checks["edgeDetail"]) || notPassing(checks["objectPresence"])) {
            return MOVE_SHIPMENT_INTO_FRAME
        }

        if (notPassing(checks["overexposure"])) {
            return TOO_BRIGHT_MSG
        }

        return when (status) {
            CaptureStatus.BLOCKED -> MOVE_SHIPMENT_INTO_FRAME
            CaptureStatus.MANUAL_CAPTURE_ALLOWED_WITH_WARNING -> CAPTURE_ALLOWED
            CaptureStatus.AUTO_CAPTURE_ALLOWED -> LOOKS_GOOD_HOLDING_STEADY
        }
    }

    /**
     * The one place the 3-tier lenient decision rule lives:
     *
     *   score >= min_quality_score_auto_capture -> auto_capture_allowed
     *   score >= min_quality_score_manual_allow  -> manual_capture_allowed_with_warning
     *   otherwise                                -> blocked
     */
    fun statusFromScore(score: Int, config: QualityConfig): CaptureStatus {
        return when {
            score >= config.minQualityScoreAutoCapture -> CaptureStatus.AUTO_CAPTURE_ALLOWED
            score >= config.minQualityScoreManualAllow -> CaptureStatus.MANUAL_CAPTURE_ALLOWED_WITH_WARNING
            else -> CaptureStatus.BLOCKED
        }
    }

    /**
     * Packages a [FrameAnalysisResult]'s decision-relevant fields into a
     * small, boolean-friendly shape.
     */
    fun getCaptureDecision(result: FrameAnalysisResult): CaptureDecision {
        return CaptureDecision(
            status = result.status,
            canAutoCapture = result.status == CaptureStatus.AUTO_CAPTURE_ALLOWED,
            canManualCapture = result.status != CaptureStatus.BLOCKED,
            guidance = result.guidance,
            issues = result.issues
        )
    }

    /**
     * Core scoring entry point: scores a grayscale frame (already reduced to
     * luma, width x height) against [config]. Both camera-preview frames
     * (Y-plane read directly, see CameraXAnalyzer) and captured-photo
     * bitmaps (RGBA->grayscale via [ImageUtils.toGrayscale]) funnel into
     * this single function so the scoring math is identical regardless of
     * how the grayscale array was produced.
     */
    fun analyzeFrame(
        gray: DoubleArray,
        width: Int,
        height: Int,
        config: QualityConfig,
        frameHistory: List<FrameHistoryEntry>? = null
    ): FrameAnalysisResult {
        val meanBrightness = ImageUtils.mean(gray)

        val blackFrame = checkBlackFrame(meanBrightness, config)
        val brightness = checkBrightness(meanBrightness, config)
        val blur = checkBlur(ImageUtils.laplacianVariance(gray, width, height), config)
        val overexposure = checkOverexposure(ImageUtils.overexposedRatio(gray), config)
        val contrast = checkContrast(ImageUtils.stdDev(gray, meanBrightness), config)
        val edgeDetail = checkEdgeDetail(ImageUtils.edgeDensity(gray, width, height), config)
        val objectPresence = checkObjectPresence()
        val stability = checkStability(frameHistory, config)

        // LinkedHashMap to preserve insertion order, matching sdk-core's
        // Object.entries(checks) iteration order (not that order affects the
        // math, but it keeps checks.toJson() output diff-friendly).
        val checks = linkedMapOf(
            "blackFrame" to blackFrame,
            "brightness" to brightness,
            "blur" to blur,
            "overexposure" to overexposure,
            "contrast" to contrast,
            "edgeDetail" to edgeDetail,
            "objectPresence" to objectPresence,
            "stability" to stability
        )

        val weights = DEFAULT_WEIGHTS.toMutableMap()
        if (config.weights != null) weights.putAll(config.weights)

        var weightedScore = 0.0
        var weightSum = 0.0
        for ((name, check) in checks) {
            if (name == "blackFrame") continue // hard gate, not a weighted contributor
            val w = weights[name] ?: 0.0
            weightedScore += check.score * w
            weightSum += w
        }
        var finalScoreDouble = if (weightSum > 0) weightedScore / weightSum else 0.0

        // Black frame is a hard gate: no amount of "good" contrast/blur on a
        // covered-lens frame should let it through.
        if (blackFrame.status != CheckStatus.PASS) {
            finalScoreDouble = min(finalScoreDouble, 5.0)
        }

        val finalScore = ImageUtils.clamp(finalScoreDouble, 0.0, 100.0).roundToInt()

        val status = statusFromScore(finalScore, config)

        val issues = checks.values.mapNotNull { it.issue }

        val guidance = selectGuidance(checks, issues, status)

        return FrameAnalysisResult(
            qualityScore = finalScore,
            status = status,
            issues = issues,
            guidance = guidance,
            checks = checks,
            configVersion = config.configVersion,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Thin wrapper over [analyzeFrame]: decodes the Bitmap's ARGB pixels via
     * the exact RGBA-weighted [ImageUtils.toGrayscale] so the score attached
     * to a saved photo is the true final score (not a live-preview
     * approximation), then delegates to the shared grayscale-array core.
     */
    fun analyzeFrame(
        bitmap: Bitmap,
        config: QualityConfig,
        frameHistory: List<FrameHistoryEntry>? = null
    ): FrameAnalysisResult {
        val gray = ImageUtils.toGrayscale(bitmap)
        return analyzeFrame(gray, bitmap.width, bitmap.height, config, frameHistory)
    }
}
