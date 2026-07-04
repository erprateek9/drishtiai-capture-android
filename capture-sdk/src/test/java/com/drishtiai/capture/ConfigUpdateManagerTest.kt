package com.drishtiai.capture

import com.drishtiai.capture.update.compareVersions
import com.drishtiai.capture.update.decideModelUpdate
import com.drishtiai.capture.update.decideUpdate
import com.drishtiai.capture.update.rolloutBucket
import com.drishtiai.capture.update.SDKVersionInfo
import com.drishtiai.capture.update.UpdateReason
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-function parity tests for the update manager's deterministic rollout
 * hash and version comparison against sdk-core's
 * sdk-core/src/update/versionChecker.ts. `rolloutBucket` reference values
 * below were computed by simulating the exact JS semantics
 * (`hash = (hash * 31 + charCode) >>> 0` per-iteration unsigned 32-bit
 * truncation, then `% 100`) in an independent script - see PR description /
 * task notes for the derivation.
 */
class ConfigUpdateManagerTest {

    @Test
    fun `rolloutBucket matches independently-computed JS-equivalent hash`() {
        assertEquals(92, rolloutBucket("drishtiai-abc123"))
        assertEquals(66, rolloutBucket("device-1"))
        assertEquals(0, rolloutBucket(""))
        assertEquals(19, rolloutBucket("Xy19!@#Zz"))
    }

    @Test
    fun `rolloutBucket is always within 0 until 100`() {
        val ids = listOf("a", "device-xyz", "drishtiai-" + "z".repeat(200), "12345", "!!!")
        for (id in ids) {
            val bucket = rolloutBucket(id)
            assert(bucket in 0..99) { "bucket $bucket out of range for id=$id" }
        }
    }

    @Test
    fun `compareVersions compares dotted numeric versions correctly`() {
        assertEquals(1, compareVersions("1.10.0", "1.2.0"))
        assertEquals(-1, compareVersions("1.2.0", "1.10.0"))
        assertEquals(0, compareVersions("1.0.0", "1.0.0"))
        assertEquals(1, compareVersions("1.0.1", "1.0.0"))
        assertEquals(-1, compareVersions("1.0", "1.0.1"))
    }

    private fun manifest(
        latestConfigVersion: String = "1.1.0",
        latestModelVersion: String? = null,
        forceUpdate: Boolean = false,
        rolloutPercent: Int = 100
    ) = SDKVersionInfo(
        latestConfigVersion = latestConfigVersion,
        latestModelVersion = latestModelVersion,
        minSdkVersion = null,
        configUrl = "/storage/configs/x.json",
        modelUrl = if (latestModelVersion != null) "/storage/models/x.json" else null,
        checksum = "",
        rolloutPercent = rolloutPercent,
        forceUpdate = forceUpdate,
        publishedAt = "2026-01-01T00:00:00Z"
    )

    @Test
    fun `decideUpdate returns forced when manifest force_update is true`() {
        val decision = decideUpdate("1.0.0", manifest(forceUpdate = true), "any-device")
        assertEquals(true, decision.shouldUpdateConfig)
        assertEquals(UpdateReason.FORCED, decision.reason)
    }

    @Test
    fun `decideUpdate returns not_in_rollout when device bucket is outside rollout_percent`() {
        // rolloutPercent = 0 means no bucket (0-99) is ever < 0.
        val decision = decideUpdate("1.0.0", manifest(rolloutPercent = 0), "device-1")
        assertEquals(false, decision.shouldUpdateConfig)
        assertEquals(UpdateReason.NOT_IN_ROLLOUT, decision.reason)
    }

    @Test
    fun `decideUpdate returns new_config when manifest version is newer and in rollout`() {
        val decision = decideUpdate("1.0.0", manifest(latestConfigVersion = "1.1.0", rolloutPercent = 100), "device-1")
        assertEquals(true, decision.shouldUpdateConfig)
        assertEquals(UpdateReason.NEW_CONFIG, decision.reason)
    }

    @Test
    fun `decideUpdate returns up_to_date when versions match and in rollout`() {
        val decision = decideUpdate("1.1.0", manifest(latestConfigVersion = "1.1.0", rolloutPercent = 100), "device-1")
        assertEquals(false, decision.shouldUpdateConfig)
        assertEquals(UpdateReason.UP_TO_DATE, decision.reason)
    }

    @Test
    fun `decideModelUpdate is permanently up_to_date when no model has ever been published`() {
        val decision = decideModelUpdate("0.0.0", manifest(latestModelVersion = null), "device-1")
        assertEquals(false, decision.shouldUpdateModel)
        assertEquals(UpdateReason.UP_TO_DATE, decision.reason)
    }

    @Test
    fun `decideModelUpdate returns forced when manifest force_update is true, ignoring version-rollout`() {
        val decision = decideModelUpdate(
            "9.9.9",
            manifest(latestModelVersion = "1.0.0", forceUpdate = true, rolloutPercent = 0),
            "device-1"
        )
        assertEquals(true, decision.shouldUpdateModel)
        assertEquals(UpdateReason.FORCED, decision.reason)
    }

    @Test
    fun `decideModelUpdate returns new_model when a newer published model exists`() {
        val decision = decideModelUpdate("0.0.0", manifest(latestModelVersion = "0.0.1"), "device-1")
        assertEquals(true, decision.shouldUpdateModel)
        assertEquals(UpdateReason.NEW_MODEL, decision.reason)
    }

    @Test
    fun `decideModelUpdate returns up_to_date when the same model version is already loaded`() {
        val decision = decideModelUpdate("0.0.1", manifest(latestModelVersion = "0.0.1"), "device-1")
        assertEquals(false, decision.shouldUpdateModel)
        assertEquals(UpdateReason.UP_TO_DATE, decision.reason)
    }

    @Test
    fun `decideModelUpdate returns not_in_rollout when device bucket is outside rollout_percent`() {
        val decision = decideModelUpdate("0.0.0", manifest(latestModelVersion = "0.0.1", rolloutPercent = 0), "device-1")
        assertEquals(false, decision.shouldUpdateModel)
        assertEquals(UpdateReason.NOT_IN_ROLLOUT, decision.reason)
    }
}
