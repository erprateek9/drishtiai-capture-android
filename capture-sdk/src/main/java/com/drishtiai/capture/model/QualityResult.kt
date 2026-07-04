package com.drishtiai.capture.model

import org.json.JSONArray
import org.json.JSONObject

/** Per-check pass/warn/fail tier, independent of the raw 0-100 score. */
enum class CheckStatus {
    PASS, WARN, FAIL;

    fun toWire(): String = when (this) {
        PASS -> "pass"
        WARN -> "warn"
        FAIL -> "fail"
    }

    companion object {
        fun fromWire(value: String): CheckStatus = when (value) {
            "pass" -> PASS
            "warn" -> WARN
            "fail" -> FAIL
            else -> throw IllegalArgumentException("Unknown CheckStatus: $value")
        }
    }
}

/**
 * Machine-readable problem codes. Used both for [FrameAnalysisResult.issues]
 * and internally by the guidance selector to pick a human message.
 */
enum class IssueCode {
    BLACK_FRAME, TOO_DARK, TOO_BRIGHT, OVEREXPOSED, BLURRY, LOW_CONTRAST, LOW_DETAIL,
    OBJECT_NOT_DETECTED, UNSTABLE;

    fun toWire(): String = name

    companion object {
        fun fromWire(value: String): IssueCode = valueOf(value)
    }
}

/** The 3-tier lenient decision the product spec defines. */
enum class CaptureStatus {
    AUTO_CAPTURE_ALLOWED, MANUAL_CAPTURE_ALLOWED_WITH_WARNING, BLOCKED;

    fun toWire(): String = when (this) {
        AUTO_CAPTURE_ALLOWED -> "auto_capture_allowed"
        MANUAL_CAPTURE_ALLOWED_WITH_WARNING -> "manual_capture_allowed_with_warning"
        BLOCKED -> "blocked"
    }

    companion object {
        fun fromWire(value: String): CaptureStatus = when (value) {
            "auto_capture_allowed" -> AUTO_CAPTURE_ALLOWED
            "manual_capture_allowed_with_warning" -> MANUAL_CAPTURE_ALLOWED_WITH_WARNING
            "blocked" -> BLOCKED
            else -> throw IllegalArgumentException("Unknown CaptureStatus: $value")
        }
    }
}

/** Result of a single quality check, e.g. brightness or blur. */
data class QualityCheckResult(
    /** Raw measured value (e.g. mean brightness, laplacian variance, ratio). */
    val value: Double,
    /** Normalized sub-score, 0-100. */
    val score: Int,
    val status: CheckStatus,
    val issue: IssueCode? = null
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("value", value)
        json.put("score", score)
        json.put("status", status.toWire())
        if (issue != null) json.put("issue", issue.toWire())
        return json
    }
}

/**
 * Public return shape of `analyzeFrame()`. Field names serialize to
 * snake_case in [toJson] to match the wire format sdk-core's
 * `FrameAnalysisResult` produces.
 */
data class FrameAnalysisResult(
    val qualityScore: Int,
    val status: CaptureStatus,
    val issues: List<IssueCode>,
    val guidance: String,
    val checks: Map<String, QualityCheckResult>,
    val configVersion: String,
    val timestamp: Long
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("quality_score", qualityScore)
        json.put("status", status.toWire())
        json.put("issues", JSONArray(issues.map { it.toWire() }))
        json.put("guidance", guidance)
        val checksJson = JSONObject()
        for ((name, check) in checks) checksJson.put(name, check.toJson())
        json.put("checks", checksJson)
        json.put("config_version", configVersion)
        json.put("timestamp", timestamp)
        return json
    }
}

/**
 * Explicit, boolean-friendly decision derived from a [FrameAnalysisResult] -
 * convenient for UI code that just wants "can I show a capture button"
 * without re-deriving it from the raw status.
 */
data class CaptureDecision(
    val status: CaptureStatus,
    val canAutoCapture: Boolean,
    val canManualCapture: Boolean,
    val guidance: String,
    val issues: List<IssueCode>
)

/** Multi-frame stability placeholder input (see ScoringEngine's checkStability). */
data class FrameHistoryEntry(
    val meanBrightness: Double,
    val timestamp: Long
)
