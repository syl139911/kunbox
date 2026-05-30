package com.kunk.singbox.service.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSwitchManagerTest {

    @Test
    fun healthCheckFailureEscalatesToForcedRecovery() {
        val decision = NetworkSwitchManager.resolveHealthCheckRecovery(
            validated = false,
            connected = false
        )

        assertEquals("network_type_changed", decision.reason)
        assertTrue(decision.force)
    }

    @Test
    fun validatedNetworkKeepsNonForcedRecovery() {
        val decision = NetworkSwitchManager.resolveHealthCheckRecovery(
            validated = true,
            connected = false
        )

        assertEquals("network_validated", decision.reason)
        assertFalse(decision.force)
    }

    @Test
    fun socketConnectivityFallbackKeepsNonForcedRecovery() {
        val decision = NetworkSwitchManager.resolveHealthCheckRecovery(
            validated = false,
            connected = true
        )

        assertEquals("network_validated", decision.reason)
        assertFalse(decision.force)
    }
}
