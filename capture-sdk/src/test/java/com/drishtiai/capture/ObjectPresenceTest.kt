package com.drishtiai.capture

import com.drishtiai.capture.model.CheckStatus
import com.drishtiai.capture.model.IssueCode
import com.drishtiai.capture.model.ObjectPresenceFeatures
import com.drishtiai.capture.model.ObjectPresenceModel
import com.drishtiai.capture.scoring.ScoringEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-math parity tests for `checkObjectPresence`, mirroring
 * sdk-core/tests/objectPresence.test.ts's cases 1:1 (null model -> stub;
 * known model+features -> deterministic score). Deliberately avoids Bitmap
 * (no Robolectric needed) by exercising the features+model overload
 * directly, same convention as ScoringEngineTest.
 */
class ObjectPresenceTest {

    private val sampleFeatures = ObjectPresenceFeatures(
        meanBrightness = 140.0,
        stdDevBrightness = 30.0,
        edgeDensityFull = 0.08,
        laplacianVariance = 200.0,
        centerVsEdgeDetailRatio = 1.2,
        meanSaturation = 0.1,
        stdDevSaturation = 0.05
    )

    private fun zeroModel(
        weights: Map<String, Double> = ObjectPresenceFeatures(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0).toMap(),
        bias: Double = 0.0
    ) = ObjectPresenceModel(
        modelVersion = "test-1",
        weights = weights,
        bias = bias,
        featureMeans = ObjectPresenceFeatures(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0).toMap(),
        featureStdDevs = mapOf(
            "meanBrightness" to 1.0,
            "stdDevBrightness" to 1.0,
            "edgeDensityFull" to 1.0,
            "laplacianVariance" to 1.0,
            "centerVsEdgeDetailRatio" to 1.0,
            "meanSaturation" to 1.0,
            "stdDevSaturation" to 1.0
        ),
        threshold = 0.5
    )

    @Test
    fun `a null model yields the exact same neutral-pass stub as before this feature existed`() {
        val result = ScoringEngine.checkObjectPresence(sampleFeatures, null)
        assertEquals(1.0, result.value, 0.0001)
        assertEquals(100, result.score)
        assertEquals(CheckStatus.PASS, result.status)
        assertNull(result.issue)
    }

    @Test
    fun `a model with all-zero weights and bias always predicts probability 0-5, right at a default threshold`() {
        val result = ScoringEngine.checkObjectPresence(sampleFeatures, zeroModel())
        assertEquals(0.5, result.value, 0.0001)
        assertEquals(50, result.score)
        assertEquals(CheckStatus.PASS, result.status) // 0.5 >= threshold 0.5
    }

    @Test
    fun `a strongly positive weight on a feature above its mean pushes the score toward pass with no issue`() {
        val weights = zeroModel().weights.toMutableMap()
        weights["meanBrightness"] = 5.0
        val result = ScoringEngine.checkObjectPresence(sampleFeatures, zeroModel(weights = weights))
        assertTrue("expected probability > 0.5, got ${result.value}", result.value > 0.5)
        assertEquals(CheckStatus.PASS, result.status)
        assertNull(result.issue)
    }

    @Test
    fun `a strongly negative bias drives the score to fail with OBJECT_NOT_DETECTED`() {
        val result = ScoringEngine.checkObjectPresence(sampleFeatures, zeroModel(bias = -10.0))
        assertTrue("expected a low probability, got ${result.value}", result.value < 0.1)
        assertEquals(CheckStatus.FAIL, result.status)
        assertEquals(IssueCode.OBJECT_NOT_DETECTED, result.issue)
    }
}
