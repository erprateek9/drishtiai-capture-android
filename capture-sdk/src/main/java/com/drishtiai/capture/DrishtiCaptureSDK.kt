package com.drishtiai.capture

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import com.drishtiai.capture.camera.CameraXAnalyzer
import com.drishtiai.capture.model.CaptureDecision
import com.drishtiai.capture.model.FrameAnalysisResult
import com.drishtiai.capture.model.ObjectPresenceModel
import com.drishtiai.capture.model.QualityConfig
import com.drishtiai.capture.scoring.ScoringEngine
import com.drishtiai.capture.storage.SharedPreferencesStore
import com.drishtiai.capture.update.ConfigUpdateManager
import com.drishtiai.capture.update.UpdateResult
import kotlin.random.Random

/**
 * Public entry point for Android integrators. Keep this class's surface
 * stable - it is the one thing app code compiles against; everything it
 * delegates to (config thresholds, and eventually a tiny object-presence
 * model) can change server-side without an app release.
 *
 * Usage:
 *   val sdk = DrishtiCaptureSDK.init(context, backendBaseUrl = "https://api.example.com")
 *   sdk.checkForUpdates()
 *   val result = sdk.analyzeFrame(bitmap)
 *   val analyzer = sdk.newCameraAnalyzer { result -> ... }
 */
class DrishtiCaptureSDK private constructor(
    private val updateManager: ConfigUpdateManager
) {
    fun getConfig(): QualityConfig = updateManager.getConfig()

    /** The currently-applied object-presence model, or null if none has ever been fetched/published. */
    fun getObjectPresenceModel(): ObjectPresenceModel? = updateManager.getObjectPresenceModel()

    /**
     * Synchronous scoring of a captured photo - safe to call on a background
     * thread. Performs the true RGBA-weighted grayscale conversion (see
     * ImageUtils.toGrayscale) so the score reflects the actual saved image,
     * and - once a trained model has been fetched via [checkForUpdates] -
     * runs real object-presence inference instead of the neutral-pass stub.
     */
    fun analyzeFrame(bitmap: Bitmap): FrameAnalysisResult =
        ScoringEngine.analyzeFrame(bitmap, getConfig(), model = getObjectPresenceModel())

    /** Derives the boolean-friendly capture decision from a [FrameAnalysisResult]. */
    fun getCaptureDecision(result: FrameAnalysisResult): CaptureDecision =
        ScoringEngine.getCaptureDecision(result)

    /** Builds a CameraX analyzer wired to this SDK's live config for the live-preview path. */
    fun newCameraAnalyzer(onResult: (FrameAnalysisResult) -> Unit): ImageAnalysis.Analyzer =
        CameraXAnalyzer(configProvider = { getConfig() }, onResult = onResult)

    /**
     * Suspend version of the update check - preferred for callers already
     * using coroutines. Never throws; always resolves to an [UpdateResult].
     */
    suspend fun checkForUpdates(): UpdateResult = updateManager.checkForUpdatesSuspend()

    /**
     * Fire-and-forget callback overload for non-coroutine callers, matching
     * docs/sdk-integration.md's `sdk.checkForUpdates()` one-liner.
     */
    fun checkForUpdates(callback: ((UpdateResult) -> Unit)? = null) {
        updateManager.checkForUpdates(callback)
    }

    companion object {
        private const val DEVICE_ID_KEY = "drishtiai.device_id"

        fun init(
            context: Context,
            backendBaseUrl: String,
            onConfigUpdated: ((QualityConfig) -> Unit)? = null,
            onModelAvailable: ((modelUrl: String, modelVersion: String) -> Unit)? = null
        ): DrishtiCaptureSDK {
            val store = SharedPreferencesStore(context.applicationContext)

            // Generate once and persist so rolloutBucket() (and therefore
            // staged rollout_percent behavior) is stable across app restarts.
            var deviceId = store.get(DEVICE_ID_KEY)
            if (deviceId == null) {
                deviceId = generateDeviceId()
                store.set(DEVICE_ID_KEY, deviceId)
            }

            val updateManager = ConfigUpdateManager(
                baseUrl = backendBaseUrl,
                store = store,
                deviceId = deviceId,
                onConfigUpdated = onConfigUpdated,
                onModelAvailable = onModelAvailable
            )

            return DrishtiCaptureSDK(updateManager)
        }

        private fun generateDeviceId(): String {
            val alphanumeric = ('a'..'z') + ('0'..'9')
            val randomPart = (1..16).map { alphanumeric[Random.nextInt(alphanumeric.size)] }.joinToString("")
            val timestampPart = java.lang.Long.toString(System.currentTimeMillis(), 36)
            return "drishtiai-$randomPart$timestampPart"
        }
    }
}
