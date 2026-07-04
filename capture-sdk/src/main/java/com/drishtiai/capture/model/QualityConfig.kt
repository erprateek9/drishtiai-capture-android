package com.drishtiai.capture.model

import org.json.JSONObject

/**
 * Leniency mode hint published alongside a config. Not currently consumed by
 * the scoring engine itself (all leniency is expressed via the numeric
 * thresholds/bands), but round-tripped so backend tooling can tag configs.
 */
enum class LeniencyMode {
    STRICT, BALANCED, LENIENT;

    fun toWire(): String = when (this) {
        STRICT -> "strict"
        BALANCED -> "balanced"
        LENIENT -> "lenient"
    }

    companion object {
        fun fromWire(value: String?): LeniencyMode? = when (value) {
            "strict" -> STRICT
            "balanced" -> BALANCED
            "lenient" -> LENIENT
            else -> null
        }
    }
}

/** Matches sdk-core's `CheckName` union - used as keys into [QualityConfig.weights]. */
object CheckNames {
    const val BRIGHTNESS = "brightness"
    const val BLACK_FRAME = "blackFrame"
    const val BLUR = "blur"
    const val OVEREXPOSURE = "overexposure"
    const val CONTRAST = "contrast"
    const val EDGE_DETAIL = "edgeDetail"
    const val OBJECT_PRESENCE = "objectPresence"
    const val STABILITY = "stability"
}

/**
 * Kotlin mirror of sdk-core's `QualityConfig` (sdk-core/src/types/config.ts).
 * Field names here are idiomatic Kotlin camelCase, but [toJson]/[fromJson]
 * serialize to/from the exact snake_case wire format the backend publishes
 * (storage/quality_config.json), so this class is a 1:1 port of the
 * TypeScript contract, not just a loosely-inspired shape.
 *
 * Every threshold here is replaceable at runtime by [com.drishtiai.capture.update.ConfigUpdateManager] -
 * this class has no hardcoded business thresholds of its own besides the
 * bundled offline fallback in [DEFAULT].
 */
data class QualityConfig(
    val configVersion: String,
    val minQualityScoreAutoCapture: Double,
    val minQualityScoreManualAllow: Double,
    val blurThreshold: Double,
    val minBrightness: Double,
    val maxBrightness: Double,
    val minContrast: Double,
    val blackFrameBrightnessThreshold: Double,
    val overexposedPixelRatioThreshold: Double,
    val requiredStableFrames: Int,
    val autoCaptureDelayMs: Long,
    val leniencyMode: String = "balanced",
    /**
     * Minimum "edge density" (fraction of pixels with a strong local
     * gradient) expected in a frame with real shipment content. Optional -
     * older published configs without this field still validate; the
     * edgeDetail check falls back to [DEFAULT]'s value.
     */
    val minEdgeDensity: Double? = null,
    /**
     * Optional per-check weight overrides (0-1, should sum to ~1). Falls
     * back to ScoringEngine's DEFAULT_WEIGHTS for any key omitted here, so
     * older config files without this field keep working.
     */
    val weights: Map<String, Double>? = null,
    /** Reserved for future tiny TFLite/ONNX/Core ML model. */
    val modelVersion: String? = null
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("config_version", configVersion)
        json.put("min_quality_score_auto_capture", minQualityScoreAutoCapture)
        json.put("min_quality_score_manual_allow", minQualityScoreManualAllow)
        json.put("blur_threshold", blurThreshold)
        json.put("min_brightness", minBrightness)
        json.put("max_brightness", maxBrightness)
        json.put("min_contrast", minContrast)
        json.put("black_frame_brightness_threshold", blackFrameBrightnessThreshold)
        json.put("overexposed_pixel_ratio_threshold", overexposedPixelRatioThreshold)
        json.put("required_stable_frames", requiredStableFrames)
        json.put("auto_capture_delay_ms", autoCaptureDelayMs)
        json.put("leniency_mode", leniencyMode)
        if (minEdgeDensity != null) json.put("min_edge_density", minEdgeDensity)
        if (weights != null) {
            val w = JSONObject()
            for ((k, v) in weights) w.put(k, v)
            json.put("weights", w)
        }
        if (modelVersion != null) json.put("model_version", modelVersion)
        return json
    }

    override fun toString(): String = toJson().toString()

    companion object {
        /** Bundled fallback config, mirrors sdk-core's DEFAULT_QUALITY_CONFIG. */
        val DEFAULT = QualityConfig(
            configVersion = "1.0.0",
            minQualityScoreAutoCapture = 75.0,
            minQualityScoreManualAllow = 60.0,
            blurThreshold = 110.0,
            minBrightness = 40.0,
            maxBrightness = 230.0,
            minContrast = 25.0,
            blackFrameBrightnessThreshold = 20.0,
            overexposedPixelRatioThreshold = 0.35,
            requiredStableFrames = 8,
            autoCaptureDelayMs = 700,
            leniencyMode = "balanced",
            minEdgeDensity = 0.03
        )

        fun fromJson(json: JSONObject): QualityConfig {
            var weights: Map<String, Double>? = null
            if (json.has("weights") && !json.isNull("weights")) {
                val w = json.getJSONObject("weights")
                val map = mutableMapOf<String, Double>()
                val keys = w.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = w.getDouble(k)
                }
                weights = map
            }

            return QualityConfig(
                configVersion = json.getString("config_version"),
                minQualityScoreAutoCapture = json.getDouble("min_quality_score_auto_capture"),
                minQualityScoreManualAllow = json.getDouble("min_quality_score_manual_allow"),
                blurThreshold = json.getDouble("blur_threshold"),
                minBrightness = json.getDouble("min_brightness"),
                maxBrightness = json.getDouble("max_brightness"),
                minContrast = json.getDouble("min_contrast"),
                blackFrameBrightnessThreshold = json.getDouble("black_frame_brightness_threshold"),
                overexposedPixelRatioThreshold = json.getDouble("overexposed_pixel_ratio_threshold"),
                requiredStableFrames = json.optInt("required_stable_frames", 8),
                autoCaptureDelayMs = json.optLong("auto_capture_delay_ms", 700),
                leniencyMode = json.optString("leniency_mode", "balanced"),
                minEdgeDensity = if (json.has("min_edge_density") && !json.isNull("min_edge_density")) {
                    json.getDouble("min_edge_density")
                } else null,
                weights = weights,
                modelVersion = if (json.has("model_version") && !json.isNull("model_version")) {
                    json.getString("model_version")
                } else null
            )
        }

        /**
         * Schema validation mirroring sdk-core's `isValidQualityConfig`
         * (sdk-core/src/config/validate.ts). Returns false (discard update,
         * keep last-good config) if any check fails. `candidateJson` is
         * validated directly against the raw wire JSON (not a partially
         * parsed [QualityConfig]) so a malformed field type is caught the
         * same way the TypeScript `typeof` checks catch it.
         */
        fun isValid(candidateJson: JSONObject): Boolean {
            val requiredNumericFields = listOf(
                "min_quality_score_auto_capture",
                "min_quality_score_manual_allow",
                "blur_threshold",
                "min_brightness",
                "max_brightness",
                "min_contrast",
                "black_frame_brightness_threshold",
                "overexposed_pixel_ratio_threshold",
                "required_stable_frames",
                "auto_capture_delay_ms"
            )

            val configVersion = candidateJson.opt("config_version")
            if (configVersion !is String || configVersion.isEmpty()) return false

            for (field in requiredNumericFields) {
                val value = candidateJson.opt(field)
                if (value !is Number) return false
                val d = value.toDouble()
                if (d.isNaN()) return false
            }

            val autoCapture = (candidateJson.opt("min_quality_score_auto_capture") as Number).toDouble()
            val manualAllow = (candidateJson.opt("min_quality_score_manual_allow") as Number).toDouble()
            if (autoCapture < manualAllow) return false

            val minBrightness = (candidateJson.opt("min_brightness") as Number).toDouble()
            val maxBrightness = (candidateJson.opt("max_brightness") as Number).toDouble()
            if (minBrightness >= maxBrightness) return false

            val blackFrameThreshold = (candidateJson.opt("black_frame_brightness_threshold") as Number).toDouble()
            if (blackFrameThreshold >= minBrightness) return false

            val overexposedRatio = (candidateJson.opt("overexposed_pixel_ratio_threshold") as Number).toDouble()
            if (overexposedRatio < 0 || overexposedRatio > 1) return false

            if (candidateJson.has("leniency_mode") && !candidateJson.isNull("leniency_mode")) {
                val mode = candidateJson.opt("leniency_mode")
                if (mode !is String || LeniencyMode.fromWire(mode) == null) return false
            }

            if (candidateJson.has("min_edge_density") && !candidateJson.isNull("min_edge_density")) {
                if (candidateJson.opt("min_edge_density") !is Number) return false
            }

            return true
        }
    }
}
