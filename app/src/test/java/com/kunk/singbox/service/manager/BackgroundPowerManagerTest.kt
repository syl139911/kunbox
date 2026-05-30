package com.kunk.singbox.service.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackgroundPowerManagerTest {

    @Test
    fun returnRecoveryStateSkipReasonIsNullWhenRecoveryAllowed() {
        val result = BackgroundPowerManager.buildReturnRecoveryStateSkipReason(
            isVpnRunning = true,
            isVpnStarting = false,
            isVpnStopping = false,
            isManuallyStopped = false
        )

        assertNull(result)
    }

    @Test
    fun returnRecoveryStateSkipReasonBlocksManualStop() {
        val result = BackgroundPowerManager.buildReturnRecoveryStateSkipReason(
            isVpnRunning = true,
            isVpnStarting = false,
            isVpnStopping = false,
            isManuallyStopped = true
        )

        assertEquals(
            "recovery not allowed (running=true, starting=false, stopping=false, manuallyStopped=true)",
            result
        )
    }

    @Test
    fun returnRecoveryStateSkipReasonBlocksStopInProgress() {
        val result = BackgroundPowerManager.buildReturnRecoveryStateSkipReason(
            isVpnRunning = true,
            isVpnStarting = false,
            isVpnStopping = true,
            isManuallyStopped = false
        )

        assertEquals(
            "recovery not allowed (running=true, starting=false, stopping=true, manuallyStopped=false)",
            result
        )
    }

    @Test
    fun returnRecoveryStateSkipReasonBlocksStoppedService() {
        val result = BackgroundPowerManager.buildReturnRecoveryStateSkipReason(
            isVpnRunning = false,
            isVpnStarting = false,
            isVpnStopping = false,
            isManuallyStopped = true
        )

        assertEquals(
            "recovery not allowed (running=false, starting=false, stopping=false, manuallyStopped=true)",
            result
        )
    }
}
