package com.drishtiai.capture.model

enum class Decision { AUTO_CAPTURE, MANUAL_ALLOW, BLOCK }

data class CheckResult(
    val name: String,
    val score: Int,
    val passed: Boolean,
    val value: Double? = null,
    val message: String? = null
)

data class QualityResult(
    val score: Int,
    val decision: Decision,
    val checks: List<CheckResult>,
    val guidance: List<String>,
    val configVersion: String,
    val timestamp: Long
)
