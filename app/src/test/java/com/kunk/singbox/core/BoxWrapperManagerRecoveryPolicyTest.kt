package com.kunk.singbox.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxWrapperManagerRecoveryPolicyTest {

    @Test
    fun networkTypeChangedSkipsInitialProbe() {
        assertTrue(BoxWrapperManager.shouldSkipProbeLevel("network_type_changed"))
        assertTrue(BoxWrapperManager.shouldSkipProbeLevel("NETWORK_TYPE_CHANGED"))
    }

    @Test
    fun networkTypeChangedDoesNotTrustSelectiveProbeSuccess() {
        assertFalse(BoxWrapperManager.shouldTrustSelectiveProbeSuccess("network_type_changed"))
        assertFalse(BoxWrapperManager.shouldTrustSelectiveProbeSuccess("NETWORK_TYPE_CHANGED"))
    }

    @Test
    fun nonNetworkTypeChangedReasonsKeepExistingProbeBehavior() {
        assertFalse(BoxWrapperManager.shouldSkipProbeLevel("app_foreground"))
        assertTrue(BoxWrapperManager.shouldTrustSelectiveProbeSuccess("app_foreground"))
        assertFalse(BoxWrapperManager.shouldSkipProbeLevel("network_validated"))
        assertTrue(BoxWrapperManager.shouldTrustSelectiveProbeSuccess("network_validated"))
    }
}
