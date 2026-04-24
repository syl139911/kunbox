package com.kunk.singbox.service.tun

import com.kunk.singbox.model.IpVersionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class VpnTunAddressPlanTest {

    @Test
    fun plannerUsesOnlyIpv4WhenIpv4Only() {
        val plan = VpnTunAddressPlanner.build(IpVersionMode.IPV4_ONLY)

        assertEquals(listOf("172.19.0.1" to 30), plan.addresses)
        assertEquals(listOf("0.0.0.0" to 0), plan.globalRoutes)
        assertEquals(listOf("223.5.5.5", "119.29.29.29", "1.1.1.1"), plan.defaultDnsServers)
    }

    @Test
    fun plannerUsesDualStackWhenDualStack() {
        val plan = VpnTunAddressPlanner.build(IpVersionMode.DUAL_STACK)

        assertEquals(listOf("172.19.0.1" to 30, "fd00::1" to 126), plan.addresses)
        assertEquals(listOf("0.0.0.0" to 0, "::" to 0), plan.globalRoutes)
        assertEquals(listOf("223.5.5.5", "119.29.29.29", "1.1.1.1", "2606:4700:4700::1111"), plan.defaultDnsServers)
    }

    @Test
    fun plannerUsesOnlyIpv6WhenIpv6Only() {
        val plan = VpnTunAddressPlanner.build(IpVersionMode.IPV6_ONLY)

        assertEquals(listOf("fd00::1" to 126), plan.addresses)
        assertEquals(listOf("::" to 0), plan.globalRoutes)
        assertEquals(listOf("2606:4700:4700::1111"), plan.defaultDnsServers)
    }
}
