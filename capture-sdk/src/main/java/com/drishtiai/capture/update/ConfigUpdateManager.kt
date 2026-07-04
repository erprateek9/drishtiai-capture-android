package com.drishtiai.capture.update

import com.drishtiai.capture.model.QualityConfig
import com.drishtiai.capture.storage.PersistentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val CONFIG_STORAGE_KEY = "drishtiai.quality_config"
private const val TIMEOUT_MS = 8000

/**
 * Kotlin mirror of sdk-core's `SDKVersionInfo` (sdk-core/src/types/update.ts).
 * Mirrors storage/version.json - the single small file every app instance
 * polls to discover new config/model files.
 */
data class SDKVersionInfo(
    val latestConfigVersion: String,
    val latestModelVersion: String?,
    val minSdkVersion: String?,
    val configUrl: String,
    val modelUrl: String?,
    val checksum: String,
    val rolloutPercent: Int,
    val forceUpdate: Boolean,
    val publishedAt: String
) {
    companion object {
        fun fromJson(json: JSONObject) = SDKVersionInfo(
            latestConfigVersion = json.getString("latest_config_version"),
            latestModelVersion = if (json.isNull("latest_model_version")) null else json.getString("latest_model_version"),
            minSdkVersion = if (json.has("min_sdk_version") && !json.isNull("min_sdk_version")) json.getString("min_sdk_version") else null,
            configUrl = json.getString("config_url"),
            modelUrl = if (json.isNull("model_url")) null else json.getString("model_url"),
            checksum = json.optString("checksum", ""),
            rolloutPercent = json.optInt("rollout_percent", 100),
            forceUpdate = json.optBoolean("force_update", false),
            publishedAt = json.optString("published_at", "")
        )
    }
}

/** Mirrors sdk-core's `UpdateReason` union. */
enum class UpdateReason {
    UP_TO_DATE, NEW_CONFIG, FORCED, NOT_IN_ROLLOUT, INVALID_CONFIG, CHECKSUM_MISMATCH, ERROR;

    fun toWire(): String = when (this) {
        UP_TO_DATE -> "up_to_date"
        NEW_CONFIG -> "new_config"
        FORCED -> "forced"
        NOT_IN_ROLLOUT -> "not_in_rollout"
        INVALID_CONFIG -> "invalid_config"
        CHECKSUM_MISMATCH -> "checksum_mismatch"
        ERROR -> "error"
    }
}

/** Result of `checkForUpdates()`. Always returned - never throws. */
data class UpdateResult(
    val updateAvailable: Boolean,
    val reason: UpdateReason,
    val config: QualityConfig,
    val version: SDKVersionInfo? = null,
    val error: String? = null
)

internal data class UpdateDecision(val shouldUpdateConfig: Boolean, val reason: UpdateReason)

/** Compares dotted version strings numerically, e.g. "1.2.0" vs "1.10.0". */
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
 * Deterministic 0-99 bucket for a device id, used for gradual
 * rollout_percent. Must match sdk-core's `rolloutBucket()` bit-for-bit:
 *
 *   let hash = 0
 *   for (ch of deviceId) hash = (hash * 31 + ch.charCodeAt) >>> 0   // unsigned 32-bit
 *   return hash % 100
 *
 * Kotlin's `Int` is a signed 32-bit type and `hash * 31 + code` is allowed
 * to overflow/wrap exactly like JS's implicit 32-bit math does before each
 * `>>> 0` - the bit pattern after overflow is identical between JS numbers
 * (which truncate to int32 for `>>>`) and Kotlin Ints (which wrap on
 * overflow). The only place the two diverge is in how the *final* value is
 * interpreted for `% 100`: JS's `>>> 0` forces an unsigned read, so a
 * "negative" Kotlin Int bit pattern must be reinterpreted as unsigned before
 * taking the modulo, which is what the final `.toLong() and 0xFFFFFFFFL`
 * step below does.
 */
fun rolloutBucket(deviceId: String): Int {
    var hash = 0
    for (ch in deviceId) {
        hash = hash * 31 + ch.code
    }
    return ((hash.toLong() and 0xFFFFFFFFL) % 100).toInt()
}

/**
 * Pure decision function: given the currently-applied config version and a
 * freshly-fetched manifest, decides whether this device should download and
 * apply the new config. Mirrors sdk-core's `decideUpdate()` exactly.
 */
internal fun decideUpdate(currentConfigVersion: String, manifest: SDKVersionInfo, deviceId: String): UpdateDecision {
    if (manifest.forceUpdate) {
        return UpdateDecision(shouldUpdateConfig = true, reason = UpdateReason.FORCED)
    }

    val inRollout = rolloutBucket(deviceId) < manifest.rolloutPercent
    if (!inRollout) {
        return UpdateDecision(shouldUpdateConfig = false, reason = UpdateReason.NOT_IN_ROLLOUT)
    }

    if (compareVersions(manifest.latestConfigVersion, currentConfigVersion) > 0) {
        return UpdateDecision(shouldUpdateConfig = true, reason = UpdateReason.NEW_CONFIG)
    }

    return UpdateDecision(shouldUpdateConfig = false, reason = UpdateReason.UP_TO_DATE)
}

/**
 * Best-effort SHA-256 checksum verification using `java.security.MessageDigest`
 * (built into the JDK/Android platform - no extra hashing dependency
 * needed). If no checksum was published, verification is skipped rather
 * than blocking the update - never throws, any failure is treated as "can't
 * verify, so don't block".
 */
suspend fun verifyChecksum(rawText: String, expectedHex: String?): Boolean = withContext(Dispatchers.Default) {
    if (expectedHex.isNullOrEmpty()) return@withContext true
    try {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawText.toByteArray(Charsets.UTF_8))
        // Mask to the unsigned 0-255 range before formatting - a signed Kotlin
        // Byte >= 0x80 is negative, and passing that directly to a hex format
        // string would produce a sign-extended value instead of a clean 2-digit
        // hex byte (e.g. 0xFF must format as "ff", not "ffffffff").
        val hex = digest.joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
        hex.equals(expectedHex, ignoreCase = true)
    } catch (e: Exception) {
        true
    }
}

private fun resolveUrl(maybeRelative: String, baseUrl: String): String {
    return try {
        URL(URL(baseUrl), maybeRelative).toString()
    } catch (e: Exception) {
        maybeRelative
    }
}

private fun httpGet(urlString: String): String {
    val connection = URL(urlString).openConnection() as HttpURLConnection
    connection.connectTimeout = TIMEOUT_MS
    connection.readTimeout = TIMEOUT_MS
    connection.requestMethod = "GET"
    try {
        val code = connection.responseCode
        if (code !in 200..299) {
            throw java.io.IOException("HTTP $code")
        }
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

/**
 * Fetches `updateUrl` (expected to point at a version.json-shaped manifest),
 * decides whether a newer config is available for this device, and if so
 * downloads + validates it. Uses plain `HttpURLConnection` off the main
 * thread (Dispatchers.IO) - no HTTP client dependency needed.
 *
 * Never throws: every failure mode (network error, bad JSON, checksum
 * mismatch, schema validation failure) resolves to an [UpdateResult] with
 * `updateAvailable = false` and `config = currentConfig`, so a broken
 * publish or a flaky network can never brick a capture flow.
 */
suspend fun checkForUpdates(updateUrl: String, currentConfig: QualityConfig, deviceId: String): UpdateResult =
    withContext(Dispatchers.IO) {
        val manifest: SDKVersionInfo = try {
            val body = httpGet(updateUrl)
            SDKVersionInfo.fromJson(JSONObject(body))
        } catch (e: Exception) {
            return@withContext UpdateResult(
                updateAvailable = false,
                reason = UpdateReason.ERROR,
                config = currentConfig,
                error = e.message ?: e.toString()
            )
        }

        val decision = decideUpdate(currentConfig.configVersion, manifest, deviceId)
        if (!decision.shouldUpdateConfig) {
            return@withContext UpdateResult(
                updateAvailable = false,
                reason = decision.reason,
                config = currentConfig,
                version = manifest
            )
        }

        val rawText: String = try {
            val configUrl = resolveUrl(manifest.configUrl, updateUrl)
            httpGet(configUrl)
        } catch (e: Exception) {
            return@withContext UpdateResult(
                updateAvailable = false,
                reason = UpdateReason.ERROR,
                config = currentConfig,
                version = manifest,
                error = "Config fetch failed: ${e.message ?: e}"
            )
        }

        val checksumOk = verifyChecksum(rawText, manifest.checksum)
        if (!checksumOk) {
            return@withContext UpdateResult(
                updateAvailable = false,
                reason = UpdateReason.CHECKSUM_MISMATCH,
                config = currentConfig,
                version = manifest,
                error = "Downloaded config failed checksum verification"
            )
        }

        val parsedJson: JSONObject = try {
            JSONObject(rawText)
        } catch (e: Exception) {
            return@withContext UpdateResult(
                updateAvailable = false,
                reason = UpdateReason.ERROR,
                config = currentConfig,
                version = manifest,
                error = "Config JSON parse failed: ${e.message ?: e}"
            )
        }

        if (!QualityConfig.isValid(parsedJson)) {
            return@withContext UpdateResult(
                updateAvailable = false,
                reason = UpdateReason.INVALID_CONFIG,
                config = currentConfig,
                version = manifest,
                error = "Downloaded config failed schema validation"
            )
        }

        val parsedConfig = QualityConfig.fromJson(parsedJson)
        UpdateResult(
            updateAvailable = true,
            reason = decision.reason,
            config = parsedConfig,
            version = manifest
        )
    }

/**
 * Stateful wrapper around the pure [checkForUpdates] function: holds the
 * currently-active [QualityConfig] (persisted via [PersistentStore]) and
 * applies + persists any newer config the backend returns. Mirrors
 * sdk-core's `DrishtiCore.checkForUpdates()` method.
 *
 * Uses plain `HttpURLConnection` + `org.json` (both built into Android)
 * instead of adding OkHttp/Retrofit/Gson, keeping the SDK's dependency
 * footprint minimal beyond kotlinx-coroutines and the optional CameraX
 * camera integration.
 */
class ConfigUpdateManager(
    private val baseUrl: String,
    private val store: PersistentStore,
    private val deviceId: String,
    initialConfig: QualityConfig = loadPersisted(store) ?: QualityConfig.DEFAULT,
    private val onConfigUpdated: ((QualityConfig) -> Unit)? = null,
    private val onModelAvailable: ((modelUrl: String, modelVersion: String) -> Unit)? = null
) {
    @Volatile
    private var currentConfig: QualityConfig = initialConfig

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun getConfig(): QualityConfig = currentConfig

    private fun versionUrl(): String {
        val trimmed = baseUrl.trimEnd('/')
        return "$trimmed/version"
    }

    /** Suspend API - preferred for callers already using coroutines. */
    suspend fun checkForUpdatesSuspend(): UpdateResult {
        val result = checkForUpdates(versionUrl(), currentConfig, deviceId)
        if (result.updateAvailable) {
            currentConfig = result.config
            try {
                store.set(CONFIG_STORAGE_KEY, result.config.toJson().toString())
            } catch (e: Exception) {
                // Persistence failure shouldn't stop the new config from being
                // used for the rest of this session.
            }
            onConfigUpdated?.invoke(result.config)
        }
        if (result.version?.modelUrl != null && result.version.latestModelVersion != null) {
            onModelAvailable?.invoke(resolveUrl(result.version.modelUrl, versionUrl()), result.version.latestModelVersion)
        }
        return result
    }

    /**
     * Fire-and-forget callback overload for non-coroutine callers. Launches
     * on an internal coroutine scope so app code that hasn't adopted
     * coroutines can still call `sdk.checkForUpdates()` as a one-liner (see
     * docs/sdk-integration.md).
     */
    fun checkForUpdates(callback: ((UpdateResult) -> Unit)? = null) {
        scope.launch {
            val result = checkForUpdatesSuspend()
            callback?.invoke(result)
        }
    }

    companion object {
        private fun loadPersisted(store: PersistentStore): QualityConfig? {
            val raw = store.get(CONFIG_STORAGE_KEY) ?: return null
            return try {
                val json = JSONObject(raw)
                if (QualityConfig.isValid(json)) QualityConfig.fromJson(json) else null
            } catch (e: Exception) {
                null
            }
        }
    }
}
