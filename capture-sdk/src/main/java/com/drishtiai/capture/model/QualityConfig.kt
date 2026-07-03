package com.drishtiai.capture.model

import org.json.JSONObject

/**
 * Kotlin mirror of sdk-core's QualityConfig / storage/quality_config.json.
 * Every threshold here is replaceable at runtime by ConfigUpdateManager -
 * this class has no hardcoded business thresholds of its own besides the
 * bundled offline fallback in [DEFAULT].
 */
data class QualityConfig(
    val configVersion: String,
    val minQualityScoreAutoCapture: Int,
    val minQualityScoreManualAllow: Int,
    val blurThreshold: Double,
    val minBrightness: Double,
    val maxBrightness: Double,
    val minContrast: Double,
    val blackFrameBrightnessThreshold: Double,
    val overexposedPixelRatioThreshold: Double,
    val requiredStableFrames: Int,
    val autoCaptureDelayMs: Long,
    val leniencyMode: String
) {
    companion object {
        /** Bundled fallback so the SDK works before the first successful remote fetch. */
        val DEFAULT = QualityConfig(
            configVersion = "1.0.0",
            minQualityScoreAutoCapture = 75,
            minQualityScoreManualAllow = 60,
            blurThreshold = 110.0,
            minBrightness = 40.0,
            maxBrightness = 230.0,
            minContrast = 25.0,
            blackFrameBrightnessThreshold = 20.0,
            overexposedPixelRatioThreshold = 0.35,
            requiredStableFrames = 8,
            autoCaptureDelayMs = 700,
            leniencyMode = "balanced"
        )

        fun fromJson(json: JSONObject): QualityConfig = QualityConfig(
            configVersion = json.getString("config_version"),
            minQualityScoreAutoCapture = json.getInt("min_quality_score_auto_capture"),
            minQualityScoreManualAllow = json.getInt("min_quality_score_manual_allow"),
            blurThreshold = json.getDouble("blur_threshold"),
            minBrightness = json.getDouble("min_brightness"),
            maxBrightness = json.getDouble("max_brightness"),
            minContrast = json.getDouble("min_contrast"),
            blackFrameBrightnessThreshold = json.getDouble("black_frame_brightness_threshold"),
            overexposedPixelRatioThreshold = json.getDouble("overexposed_pixel_ratio_threshold"),
            requiredStableFrames = json.optInt("required_stable_frames", 8),
            autoCaptureDelayMs = json.optLong("auto_capture_delay_ms", 700),
            leniencyMode = json.optString("leniency_mode", "balanced")
        )
    }
}
