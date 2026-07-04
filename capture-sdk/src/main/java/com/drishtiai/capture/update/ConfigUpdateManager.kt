package com.drishtiai.capture.update

import com.drishtiai.capture.model.ObjectPresenceModel
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
private const val MODEL_STORAGE_KEY = "drishtiai.object_presence_model"
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
    /**
     * Checksum of the object-presence model file, kept separate from
     * [checksum] (which is config-only) now that model publishing is a
     * real, independent artifact lifecycle. Mirrors sdk-core's
     * `model_checksum` addition to `SDKVersionInfo`.
     */
    val modelChecksum: String? = null,
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
            modelChecksum = if (json.has("model_checksum") && !json.isNull("model_checksum")) json.getString("model_checksum") else null,
            rolloutPercent = json.optInt("rollout_percent", 100),
            forceUpdate = json.optBoolean("force_update", false),
            publishedAt = json.optString("published_at", "")
        )
    }
}

/** Mirrors sdk-core's `UpdateReason` union. */
enum class UpdateReason {
    UP_TO_DATE, NEW_CONFIG, NEW_MODEL, FORCED, NOT_IN_ROLLOUT, INVALID_CONFIG, INVALID_MODEL, CHECKSUM_MISMATCH, ERROR;

    fun toWire(): String = when (this) {
        UP_TO_DATE -> "up_to_date"
        NEW_CONFIG -> "new_config"
        NEW_MODEL -> "new_model"
        FORCED -> "forced"
        NOT_IN_ROLLOUT -> "not_in_rollout"
        INVALID_CONFIG -> "invalid_config"
        INVALID_MODEL -> "invalid_model"
        CHECKSUM_MISMATCH -> "checksum_mismatch"
        ERROR -> "error"
    }
}

/**
 * Result of `checkForUpdates()`. Always returned - never throws.
 *
 * The `model*` fields are additive siblings of `updateAvailable`/`reason`/
 * `error` above (which keep their original, config-only meaning) - [model]
 * is only present and non-null when a NEW model was fetched and validated
 * during this call.
 */
data class UpdateResult(
    val updateAvailable: Boolean,
    val reason: UpdateReason,
    val config: QualityConfig,
    val version: SDKVersionInfo? = null,
    val error: String? = null,
    val modelUpdateAvailable: Boolean = false,
    val model: ObjectPresenceModel? = null,
    val modelReason: UpdateReason? = null,
    val modelError: String? = null
)

internal data class UpdateDecision(val shouldUpdateConfig: Boolean, val reason: UpdateReason)
internal data class ModelUpdateDecision(val shouldUpdateModel: Boolean, val reason: UpdateReason)

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
 * Model-side sibling of [decideUpdate]. A device that has never received a
 * model (currentModelVersion "0.0.0") and whose manifest has never had one
 * published (`latestModelVersion == null`) simply has nothing to update to -
 * this is the permanent, normal state for every device today, not an error.
 * Uses the same rolloutPercent/forceUpdate gating as config, since
 * version.json only ever has one instance of each field. Mirrors
 * sdk-core's `decideModelUpdate` exactly.
 */
internal fun decideModelUpdate(currentModelVersion: String, manifest: SDKVersionInfo, deviceId: String): ModelUpdateDecision {
    if (manifest.latestModelVersion == null) {
        return ModelUpdateDecision(shouldUpdateModel = false, reason = UpdateReason.UP_TO_DATE)
    }

    if (manifest.forceUpdate) {
        return ModelUpdateDecision(shouldUpdateModel = true, reason = UpdateReason.FORCED)
    }

    val inRollout = rolloutBucket(deviceId) < manifest.rolloutPercent
    if (!inRollout) {
        return ModelUpdateDecision(shouldUpdateModel = false, reason = UpdateReason.NOT_IN_ROLLOUT)
    }

    if (compareVersions(manifest.latestModelVersion, currentModelVersion) > 0) {
        return ModelUpdateDecision(shouldUpdateModel = true, reason = UpdateReason.NEW_MODEL)
    }

    return ModelUpdateDecision(shouldUpdateModel = false, reason = UpdateReason.UP_TO_DATE)
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

private data class ConfigOutcome(
    val updateAvailable: Boolean,
    val reason: UpdateReason,
    val config: QualityConfig,
    val error: String? = null
)

/** Config-only half of the check - unchanged behavior from before model support existed. */
private suspend fun resolveConfigUpdate(updateUrl: String, manifest: SDKVersionInfo, currentConfig: QualityConfig, deviceId: String): ConfigOutcome {
    val decision = decideUpdate(currentConfig.configVersion, manifest, deviceId)
    if (!decision.shouldUpdateConfig) {
        return ConfigOutcome(updateAvailable = false, reason = decision.reason, config = currentConfig)
    }

    val rawText: String = try {
        httpGet(resolveUrl(manifest.configUrl, updateUrl))
    } catch (e: Exception) {
        return ConfigOutcome(false, UpdateReason.ERROR, currentConfig, "Config fetch failed: ${e.message ?: e}")
    }

    if (!verifyChecksum(rawText, manifest.checksum)) {
        return ConfigOutcome(false, UpdateReason.CHECKSUM_MISMATCH, currentConfig, "Downloaded config failed checksum verification")
    }

    val parsedJson: JSONObject = try {
        JSONObject(rawText)
    } catch (e: Exception) {
        return ConfigOutcome(false, UpdateReason.ERROR, currentConfig, "Config JSON parse failed: ${e.message ?: e}")
    }

    if (!QualityConfig.isValid(parsedJson)) {
        return ConfigOutcome(false, UpdateReason.INVALID_CONFIG, currentConfig, "Downloaded config failed schema validation")
    }

    return ConfigOutcome(updateAvailable = true, reason = decision.reason, config = QualityConfig.fromJson(parsedJson))
}

private data class ModelOutcome(
    val modelUpdateAvailable: Boolean,
    val modelReason: UpdateReason,
    val model: ObjectPresenceModel? = null,
    val modelError: String? = null
)

/**
 * Model-only half of the check, run against the SAME already-fetched
 * manifest as the config half - no extra network round-trip to re-fetch
 * version.json. Mirrors [resolveConfigUpdate]'s fetch/checksum/validate
 * shape exactly, using manifest.modelUrl/modelChecksum instead. Never
 * throws: every failure mode resolves to `modelUpdateAvailable = false` so a
 * broken model publish can never brick a capture flow.
 */
private suspend fun resolveModelUpdate(updateUrl: String, manifest: SDKVersionInfo, currentModelVersion: String, deviceId: String): ModelOutcome {
    val decision = decideModelUpdate(currentModelVersion, manifest, deviceId)
    if (!decision.shouldUpdateModel) {
        return ModelOutcome(modelUpdateAvailable = false, modelReason = decision.reason)
    }

    val rawText: String = try {
        httpGet(resolveUrl(manifest.modelUrl!!, updateUrl))
    } catch (e: Exception) {
        return ModelOutcome(false, UpdateReason.ERROR, modelError = "Model fetch failed: ${e.message ?: e}")
    }

    if (!verifyChecksum(rawText, manifest.modelChecksum)) {
        return ModelOutcome(false, UpdateReason.CHECKSUM_MISMATCH, modelError = "Downloaded model failed checksum verification")
    }

    val parsedJson: JSONObject = try {
        JSONObject(rawText)
    } catch (e: Exception) {
        return ModelOutcome(false, UpdateReason.ERROR, modelError = "Model JSON parse failed: ${e.message ?: e}")
    }

    if (!ObjectPresenceModel.isValid(parsedJson)) {
        return ModelOutcome(false, UpdateReason.INVALID_MODEL, modelError = "Downloaded model failed schema validation")
    }

    return ModelOutcome(modelUpdateAvailable = true, modelReason = decision.reason, model = ObjectPresenceModel.fromJson(parsedJson))
}

/**
 * Fetches `updateUrl` (expected to point at a version.json-shaped manifest)
 * ONCE, then independently decides/downloads/validates a newer config and a
 * newer object-presence model against that same manifest. Uses plain
 * `HttpURLConnection` off the main thread (Dispatchers.IO) - no HTTP client
 * dependency needed.
 *
 * Never throws: every failure mode (network error, bad JSON, checksum
 * mismatch, schema validation failure) resolves to an [UpdateResult] with
 * `updateAvailable = false` and `config = currentConfig` (and the
 * model-side fields analogously), so a broken publish or a flaky network
 * can never brick a capture flow.
 */
suspend fun checkForUpdates(
    updateUrl: String,
    currentConfig: QualityConfig,
    deviceId: String,
    currentModelVersion: String = "0.0.0"
): UpdateResult = withContext(Dispatchers.IO) {
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

    val configOutcome = resolveConfigUpdate(updateUrl, manifest, currentConfig, deviceId)
    val modelOutcome = resolveModelUpdate(updateUrl, manifest, currentModelVersion, deviceId)

    UpdateResult(
        updateAvailable = configOutcome.updateAvailable,
        reason = configOutcome.reason,
        config = configOutcome.config,
        version = manifest,
        error = configOutcome.error,
        modelUpdateAvailable = modelOutcome.modelUpdateAvailable,
        model = modelOutcome.model,
        modelReason = modelOutcome.modelReason,
        modelError = modelOutcome.modelError
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
    initialConfig: QualityConfig = loadPersistedConfig(store) ?: QualityConfig.DEFAULT,
    initialModel: ObjectPresenceModel? = loadPersistedModel(store),
    private val onConfigUpdated: ((QualityConfig) -> Unit)? = null,
    /**
     * Fired AFTER this manager has already fetched, validated, and applied a
     * new model itself (see [checkForUpdatesSuspend]) - purely informational
     * now, kept for backward source compatibility with app code that already
     * wired it up. Use [getObjectPresenceModel] to read the applied model
     * directly instead of re-downloading it from the URL this hands back.
     */
    private val onModelAvailable: ((modelUrl: String, modelVersion: String) -> Unit)? = null
) {
    @Volatile
    private var currentConfig: QualityConfig = initialConfig

    @Volatile
    private var currentModel: ObjectPresenceModel? = initialModel

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun getConfig(): QualityConfig = currentConfig

    /** The currently-applied object-presence model, or null if none has ever been fetched/published. */
    fun getObjectPresenceModel(): ObjectPresenceModel? = currentModel

    private fun versionUrl(): String {
        val trimmed = baseUrl.trimEnd('/')
        return "$trimmed/version"
    }

    /** Suspend API - preferred for callers already using coroutines. */
    suspend fun checkForUpdatesSuspend(): UpdateResult {
        val result = checkForUpdates(versionUrl(), currentConfig, deviceId, currentModel?.modelVersion ?: "0.0.0")
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
        if (result.modelUpdateAvailable && result.model != null) {
            currentModel = result.model
            try {
                store.set(MODEL_STORAGE_KEY, result.model.toJson().toString())
            } catch (e: Exception) {
                // Persistence failure shouldn't stop the new model from being
                // used for the rest of this session.
            }
            onModelAvailable?.invoke(resolveUrl(result.version?.modelUrl ?: "", versionUrl()), result.model.modelVersion)
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
        private fun loadPersistedConfig(store: PersistentStore): QualityConfig? {
            val raw = store.get(CONFIG_STORAGE_KEY) ?: return null
            return try {
                val json = JSONObject(raw)
                if (QualityConfig.isValid(json)) QualityConfig.fromJson(json) else null
            } catch (e: Exception) {
                null
            }
        }

        private fun loadPersistedModel(store: PersistentStore): ObjectPresenceModel? {
            val raw = store.get(MODEL_STORAGE_KEY) ?: return null
            return try {
                val json = JSONObject(raw)
                if (ObjectPresenceModel.isValid(json)) ObjectPresenceModel.fromJson(json) else null
            } catch (e: Exception) {
                null
            }
        }
    }
}
