package com.drishtiai.capture.update

import com.drishtiai.capture.model.QualityConfig
import com.drishtiai.capture.storage.PersistentStore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs

private const val CONFIG_STORAGE_KEY = "drishtiai.quality_config"

data class VersionManifest(
    val latestConfigVersion: String,
    val latestModelVersion: String?,
    val configUrl: String,
    val modelUrl: String?,
    val checksum: String,
    val rolloutPercent: Int,
    val forceUpdate: Boolean,
    val publishedAt: String
) {
    companion object {
        fun fromJson(json: JSONObject) = VersionManifest(
            latestConfigVersion = json.getString("latest_config_version"),
            latestModelVersion = if (json.isNull("latest_model_version")) null else json.optString("latest_model_version"),
            configUrl = json.getString("config_url"),
            modelUrl = if (json.isNull("model_url")) null else json.optString("model_url"),
            checksum = json.optString("checksum", ""),
            rolloutPercent = json.optInt("rollout_percent", 100),
            forceUpdate = json.optBoolean("force_update", false),
            publishedAt = json.optString("published_at", "")
        )
    }
}

/** Deterministic 0-99 bucket for gradual rollout_percent, same hash as sdk-core's rolloutBucket(). */
fun rolloutBucket(deviceId: String): Int {
    var hash = 0
    for (c in deviceId) hash = (hash * 31 + c.code)
    return abs(hash) % 100
}

fun compareVersions(a: String, b: String): Int {
    val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
    val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
    val len = maxOf(pa.size, pb.size)
    for (i in 0 until len) {
        val diff = (pa.getOrElse(i) { 0 }) - (pb.getOrElse(i) { 0 })
        if (diff != 0) return if (diff > 0) 1 else -1
    }
    return 0
}

/**
 * Fetches version.json + quality_config.json from the backend and applies
 * newer configs on-device, without ever touching SDK code. Mirrors
 * sdk-core/src/update/updateManager.ts logic 1:1 so behaviour (rollout
 * bucketing, force_update, semver-ish comparison) stays consistent across
 * platforms.
 *
 * Uses plain HttpURLConnection + org.json (both built into Android) instead
 * of adding OkHttp/Retrofit/Gson, keeping the SDK's dependency footprint at
 * effectively zero beyond the optional CameraX camera integration.
 */
class ConfigUpdateManager(
    private val baseUrl: String,
    private val store: PersistentStore,
    private val deviceId: String,
    private var currentConfig: QualityConfig = loadPersisted(store) ?: QualityConfig.DEFAULT,
    private val onConfigUpdated: ((QualityConfig) -> Unit)? = null,
    private val onModelAvailable: ((modelUrl: String, modelVersion: String) -> Unit)? = null
) {
    private val executor = Executors.newSingleThreadExecutor()

    fun getConfig(): QualityConfig = currentConfig

    fun checkNow(callback: ((success: Boolean) -> Unit)? = null) {
        executor.execute {
            try {
                val manifest = fetchJson("$baseUrl/version")?.let { VersionManifest.fromJson(it) }
                if (manifest == null) {
                    callback?.invoke(false)
                    return@execute
                }

                val inRollout = rolloutBucket(deviceId) < manifest.rolloutPercent
                val shouldUpdateConfig = manifest.forceUpdate ||
                    (inRollout && compareVersions(manifest.latestConfigVersion, currentConfig.configVersion) > 0)

                if (shouldUpdateConfig) {
                    val configUrl = resolveUrl(manifest.configUrl)
                    fetchJson(configUrl)?.let { json ->
                        val newConfig = QualityConfig.fromJson(json)
                        currentConfig = newConfig
                        store.set(CONFIG_STORAGE_KEY, json.toString())
                        onConfigUpdated?.invoke(newConfig)
                    }
                }

                if (inRollout && manifest.modelUrl != null && manifest.latestModelVersion != null) {
                    onModelAvailable?.invoke(resolveUrl(manifest.modelUrl), manifest.latestModelVersion)
                }

                callback?.invoke(true)
            } catch (e: Exception) {
                // Network errors are expected/benign (offline devices) - keep using
                // whatever config is already active.
                callback?.invoke(false)
            }
        }
    }

    private fun resolveUrl(url: String): String = if (url.startsWith("http")) url else "$baseUrl$url"

    private fun fetchJson(url: String): JSONObject? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        return try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private fun loadPersisted(store: PersistentStore): QualityConfig? {
            val raw = store.get(CONFIG_STORAGE_KEY) ?: return null
            return try {
                QualityConfig.fromJson(JSONObject(raw))
            } catch (e: Exception) {
                null
            }
        }
    }
}
