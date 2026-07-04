package com.drishtiai.capture.model

/** Matches sdk-core's `ObjectPresenceFeatures` keys exactly - the SET must be identical between training and inference. */
object ObjectPresenceFeatureKeys {
    val ALL = listOf(
        "meanBrightness",
        "stdDevBrightness",
        "edgeDensityFull",
        "laplacianVariance",
        "centerVsEdgeDetailRatio",
        "meanSaturation",
        "stdDevSaturation"
    )
}

/**
 * Feature vector for one frame, keyed the same way as
 * [ObjectPresenceFeatureKeys.ALL]. Mirrors
 * sdk-core/src/quality/objectPresenceFeatures.ts's `ObjectPresenceFeatures`.
 */
data class ObjectPresenceFeatures(
    val meanBrightness: Double,
    val stdDevBrightness: Double,
    val edgeDensityFull: Double,
    val laplacianVariance: Double,
    val centerVsEdgeDetailRatio: Double,
    val meanSaturation: Double,
    val stdDevSaturation: Double
) {
    fun toMap(): Map<String, Double> = mapOf(
        "meanBrightness" to meanBrightness,
        "stdDevBrightness" to stdDevBrightness,
        "edgeDensityFull" to edgeDensityFull,
        "laplacianVariance" to laplacianVariance,
        "centerVsEdgeDetailRatio" to centerVsEdgeDetailRatio,
        "meanSaturation" to meanSaturation,
        "stdDevSaturation" to stdDevSaturation
    )
}
