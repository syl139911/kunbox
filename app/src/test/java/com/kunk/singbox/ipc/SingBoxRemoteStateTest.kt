package com.kunk.singbox.ipc

import com.kunk.singbox.service.ServiceState
import org.junit.Assert.*
import org.junit.Test

class SingBoxRemoteStateTest {

    @Test
    fun `pending starting returns STARTING`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "starting",
            isActive = false,
            mode = VpnStateStore.CoreMode.NONE,
            hasVpnTransport = false
        )
        assertEquals(ServiceState.STARTING, result)
    }

    @Test
    fun `pending stopping returns STOPPING`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "stopping",
            isActive = false,
            mode = VpnStateStore.CoreMode.NONE,
            hasVpnTransport = false
        )
        assertEquals(ServiceState.STOPPING, result)
    }

    @Test
    fun `isActive true with hasVpnTransport false returns STOPPED`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "",
            isActive = true,
            mode = VpnStateStore.CoreMode.VPN,
            hasVpnTransport = false
        )
        assertEquals(ServiceState.STOPPED, result)
    }

    @Test
    fun `isActive true with hasVpnTransport true returns RUNNING`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "",
            isActive = true,
            mode = VpnStateStore.CoreMode.VPN,
            hasVpnTransport = true
        )
        assertEquals(ServiceState.RUNNING, result)
    }

    @Test
    fun `mode PROXY with hasVpnTransport true returns RUNNING`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "",
            isActive = false,
            mode = VpnStateStore.CoreMode.PROXY,
            hasVpnTransport = true
        )
        assertEquals(ServiceState.RUNNING, result)
    }

    @Test
    fun `mode PROXY with hasVpnTransport false returns STOPPED`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "",
            isActive = false,
            mode = VpnStateStore.CoreMode.PROXY,
            hasVpnTransport = false
        )
        assertEquals(ServiceState.STOPPED, result)
    }

    @Test
    fun `all false returns STOPPED`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "",
            isActive = false,
            mode = VpnStateStore.CoreMode.NONE,
            hasVpnTransport = false
        )
        assertEquals(ServiceState.STOPPED, result)
    }

    @Test
    fun `pending takes precedence over isActive`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "stopping",
            isActive = true,
            mode = VpnStateStore.CoreMode.VPN,
            hasVpnTransport = true
        )
        assertEquals(ServiceState.STOPPING, result)
    }

    @Test
    fun `isActive takes precedence over mode PROXY`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "",
            isActive = true,
            mode = VpnStateStore.CoreMode.PROXY,
            hasVpnTransport = true
        )
        assertEquals(ServiceState.RUNNING, result)
    }
}
