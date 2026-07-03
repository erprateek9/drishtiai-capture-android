package com.drishtiai.capture

import android.content.Context
import android.graphics.Bitmap
import com.drishtiai.capture.camera.QualityAnalyzer
import com.drishtiai.capture.model.QualityConfig
import com.drishtiai.capture.model.QualityResult
import com.drishtiai.capture.scoring.ScoringEngine
import com.drishtiai.capture.storage.SharedPreferencesStore
import com.drishtiai.capture.update.ConfigUpdateManager
import java.util.UUID

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

    /** Synchronous single-frame/photo scoring - safe to call on a background thread. */
    fun analyzeFrame(bitmap: Bitmap): QualityResult = ScoringEngine.score(bitmap, getConfig())

    /** Builds a CameraX analyzer wired to this SDK's live config. */
    fun newCameraAnalyzer(onResult: (QualityResult) -> Unit): QualityAnalyzer =
        QualityAnalyzer(configProvider = { getConfig() }, onResult = onResult)

    /** Triggers an async version/config check against the backend. No-op if offline. */
    fun checkForUpdates(callback: ((success: Boolean) -> Unit)? = null) {
        updateManager.checkNow(callback)
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
            var deviceId = store.get(DEVICE_ID_KEY)
            if (deviceId == null) {
                deviceId = UUID.randomUUID().toString()
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
    }
}
