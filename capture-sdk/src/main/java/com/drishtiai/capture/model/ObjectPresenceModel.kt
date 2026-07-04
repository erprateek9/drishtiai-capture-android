package com.drishtiai.capture.model

import org.json.JSONObject

/**
 * Kotlin mirror of sdk-core's `ObjectPresenceModel`
 * (sdk-core/src/quality/objectPresence.ts) - a trained binary
 * logistic-regression classifier distinguishing "photo contains a real
 * shipment object" from "random/irrelevant photo", published the same way a
 * QualityConfig is (see [com.drishtiai.capture.update.ConfigUpdateManager]).
 *
 * [featureMeans]/[featureStdDevs] are the per-feature standardization stats
 * used at TRAINING time - they must be reapplied identically at inference
 * time, since the learned weights are only meaningful against standardized
 * (not raw) feature scales.
 */
data class ObjectPresenceModel(
    val modelVersion: String,
    val weights: Map<String, Double>,
    val bias: Double,
    val featureMeans: Map<String, Double>,
    val featureStdDevs: Map<String, Double>,
    val threshold: Double
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("model_version", modelVersion)
        json.put("weights", JSONObject(weights))
        json.put("bias", bias)
        json.put("featureMeans", JSONObject(featureMeans))
        json.put("featureStdDevs", JSONObject(featureStdDevs))
        json.put("threshold", threshold)
        return json
    }

    override fun toString(): String = toJson().toString()

    companion object {
        private fun mapFromJson(json: JSONObject): Map<String, Double> {
            val map = mutableMapOf<String, Double>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = json.getDouble(k)
            }
            return map
        }

        fun fromJson(json: JSONObject): ObjectPresenceModel = ObjectPresenceModel(
            modelVersion = json.getString("model_version"),
            weights = mapFromJson(json.getJSONObject("weights")),
            bias = json.getDouble("bias"),
            featureMeans = mapFromJson(json.getJSONObject("featureMeans")),
            featureStdDevs = mapFromJson(json.getJSONObject("featureStdDevs")),
            threshold = json.getDouble("threshold")
        )

        /**
         * Structural type guard for a model fetched over the network -
         * mirrors sdk-core's `isValidObjectPresenceModel`'s "never trust the
         * wire" philosophy. Returns false (discard, keep last-good model)
         * rather than throwing on any malformed field.
         */
        fun isValid(candidateJson: JSONObject): Boolean {
            val modelVersion = candidateJson.opt("model_version")
            if (modelVersion !is String || modelVersion.isEmpty()) return false

            val bias = candidateJson.opt("bias")
            if (bias !is Number) return false

            val threshold = candidateJson.opt("threshold")
            if (threshold !is Number) return false

            for (field in listOf("weights", "featureMeans", "featureStdDevs")) {
                val obj = candidateJson.opt(field)
                if (obj !is JSONObject) return false
                for (key in ObjectPresenceFeatureKeys.ALL) {
                    if (obj.opt(key) !is Number) return false
                }
            }

            return true
        }
    }
}
