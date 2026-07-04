package com.drishtiai.capture

import com.drishtiai.capture.model.CaptureStatus
import com.drishtiai.capture.model.CheckStatus
import com.drishtiai.capture.model.IssueCode
import com.drishtiai.capture.model.QualityConfig
import com.drishtiai.capture.scoring.ScoringEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-math numeric-parity tests against the sdk-core TypeScript reference
 * implementation (sdk-core's quality checks and scoring engine modules).
 * These deliberately avoid Bitmap/Context/Android-framework types (no
 * Robolectric needed) by calling the check functions directly with
 * primitives, and by driving `analyzeFrame` through its grayscale-array
 * overload with a hand-built synthetic array instead of a real Bitmap.
 *
 * Expected numbers below were hand-derived from sdk-core's
 * `scoreWithinRange` / `scoreAboveThreshold` / `scoreBelowThreshold`
 * formulas (see sdk-core/src/quality/helpers.ts) using
 * QualityConfig.DEFAULT's thresholds.
 */
class ScoringEngineTest {

    private val defaultConfig = QualityConfig.DEFAULT

    @Test
    fun `checkBrightness matches hand-computed scoreWithinRange for a too-dark frame`() {
        // meanBrightness = 20, min_brightness = 40, max_brightness = 230, band = 50.
        // value < min -> mapRange(20, min-band=-10, min=40, floor=10, 100)
        //   t = clamp((20 - -10) / (40 - -10), 0, 1) = clamp(30/50, 0, 1) = 0.6
        //   score = 10 + 0.6 * (100 - 10) = 64
        val result = ScoringEngine.checkBrightness(20.0, defaultConfig)

        assertEquals(20.0, result.value, 0.0001)
        assertEquals(64, result.score)
        // passed = false, score (64) >= 40 -> WARN
        assertEquals(CheckStatus.WARN, result.status)
        assertEquals(IssueCode.TOO_DARK, result.issue)
    }

    @Test
    fun `checkBrightness returns pass with score 100 when within range`() {
        val result = ScoringEngine.checkBrightness(128.0, defaultConfig)

        assertEquals(100, result.score)
        assertEquals(CheckStatus.PASS, result.status)
        assertEquals(null, result.issue)
    }

    @Test
    fun `checkBlur matches hand-computed scoreAboveThreshold for a blurry frame`() {
        // laplacianVariance = 50, blur_threshold = 110, band = 110*0.6 = 66.
        // value < threshold -> mapRange(50, max(0, 110-66)=44, 110, 15, 100)
        //   t = clamp((50-44)/(110-44), 0, 1) = clamp(6/66, 0, 1) = 0.090909...
        //   score = 15 + 0.090909 * 85 = 22.727... -> round -> 23
        val result = ScoringEngine.checkBlur(50.0, defaultConfig)

        assertEquals(50.0, result.value, 0.0001)
        assertEquals(23, result.score)
        // passed = false, score (23) < 40 -> FAIL
        assertEquals(CheckStatus.FAIL, result.status)
        assertEquals(IssueCode.BLURRY, result.issue)
    }

    @Test
    fun `checkBlackFrame is a hard 0-or-100 gate, not a lenient band`() {
        val passing = ScoringEngine.checkBlackFrame(20.0, defaultConfig) // == threshold -> passes
        val failing = ScoringEngine.checkBlackFrame(19.999, defaultConfig)

        assertEquals(100, passing.score)
        assertEquals(CheckStatus.PASS, passing.status)

        assertEquals(0, failing.score)
        assertEquals(CheckStatus.FAIL, failing.status)
        assertEquals(IssueCode.BLACK_FRAME, failing.issue)
    }

    @Test
    fun `analyzeFrame on a uniform mid-gray synthetic image lands on the hand-computed weighted score`() {
        // A perfectly uniform 10x10 grayscale image at luma 128: brightness
        // is comfortably in-range (score 100), but blur/contrast/edgeDetail
        // all measure zero variance/detail (a blank frame), each landing at
        // their lenient floor of 15. Overexposure/objectPresence/stability
        // are all perfect (score 100).
        //
        //   weighted = 100*0.22 + 15*0.28 + 15*0.13 + 100*0.13 + 15*0.09 + 100*0.10 + 100*0.05
        //            = 22 + 4.2 + 1.95 + 13 + 1.35 + 10 + 5 = 57.5
        //   weightSum = 0.22+0.28+0.13+0.13+0.09+0.10+0.05 = 1.0
        //   finalScore = round(57.5) = 58  (blackFrame passes, no hard gate applied)
        val width = 10
        val height = 10
        val gray = DoubleArray(width * height) { 128.0 }

        val result = ScoringEngine.analyzeFrame(gray, width, height, defaultConfig)

        assertEquals(58, result.qualityScore)
        // 58 < min_quality_score_manual_allow (60) -> blocked
        assertEquals(CaptureStatus.BLOCKED, result.status)
        assertTrue(result.issues.contains(IssueCode.BLURRY))
        assertTrue(result.issues.contains(IssueCode.LOW_CONTRAST))
        assertTrue(result.issues.contains(IssueCode.LOW_DETAIL))
        // blur fails (status != pass) -> guidance priority picks "Hold steady"
        // before contrast/edgeDetail's "Move shipment into frame".
        assertEquals("Hold steady", result.guidance)
        assertEquals(defaultConfig.configVersion, result.configVersion)
    }

    @Test
    fun `analyzeFrame hard-gates a black frame to at most score 5 regardless of other checks`() {
        // Uniform frame at luma 5: below black_frame_brightness_threshold
        // (20), so no amount of "good" contrast/blur can push the score
        // above the hard gate ceiling of 5.
        val width = 10
        val height = 10
        val gray = DoubleArray(width * height) { 5.0 }

        val result = ScoringEngine.analyzeFrame(gray, width, height, defaultConfig)

        assertTrue("expected score <= 5 but was ${result.qualityScore}", result.qualityScore <= 5)
        assertEquals(CaptureStatus.BLOCKED, result.status)
        assertTrue(result.issues.contains(IssueCode.BLACK_FRAME))
        assertEquals("Camera may be covered", result.guidance)
    }

    @Test
    fun `getCaptureDecision maps status to booleans correctly`() {
        val gray = DoubleArray(100) { 128.0 }
        val result = ScoringEngine.analyzeFrame(gray, 10, 10, defaultConfig)
        val decision = ScoringEngine.getCaptureDecision(result)

        assertEquals(result.status, decision.status)
        assertEquals(false, decision.canAutoCapture)
        assertEquals(false, decision.canManualCapture) // blocked -> cannot manual capture either
        assertEquals(result.guidance, decision.guidance)
    }
}
