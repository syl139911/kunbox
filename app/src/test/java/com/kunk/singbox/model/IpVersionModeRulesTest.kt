package com.kunk.singbox.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpVersionModeRulesTest {

    @Test
    fun includesAddressFamiliesForEachMode() {
        assertTrue(IpVersionMode.IPV4_ONLY.includesIpv4)
        assertFalse(IpVersionMode.IPV4_ONLY.includesIpv6)

        assertTrue(IpVersionMode.DUAL_STACK.includesIpv4)
        assertTrue(IpVersionMode.DUAL_STACK.includesIpv6)

        assertTrue(IpVersionMode.PREFER_IPV6.includesIpv4)
        assertTrue(IpVersionMode.PREFER_IPV6.includesIpv6)

        assertFalse(IpVersionMode.IPV6_ONLY.includesIpv4)
        assertTrue(IpVersionMode.IPV6_ONLY.includesIpv6)
    }

    @Test
    fun tunAddressesFollowMode() {
        val tunAddress = TunAddressConfig(ipv4 = "10.7.0.1/30", ipv6 = "fd07::1/126")

        assertEquals(listOf("10.7.0.1/30"), IpVersionMode.IPV4_ONLY.tunAddresses(tunAddress))
        assertEquals(listOf("10.7.0.1/30", "fd07::1/126"), IpVersionMode.DUAL_STACK.tunAddresses(tunAddress))
        assertEquals(listOf("10.7.0.1/30", "fd07::1/126"), IpVersionMode.PREFER_IPV6.tunAddresses(tunAddress))
        assertEquals(listOf("fd07::1/126"), IpVersionMode.IPV6_ONLY.tunAddresses(tunAddress))
    }

    @Test
    fun autoDnsStrategyFollowsMode() {
        assertEquals("ipv4_only", IpVersionMode.IPV4_ONLY.autoDnsStrategy())
        assertEquals("prefer_ipv4", IpVersionMode.DUAL_STACK.autoDnsStrategy())
        assertEquals("prefer_ipv6", IpVersionMode.PREFER_IPV6.autoDnsStrategy())
        assertEquals("ipv6_only", IpVersionMode.IPV6_ONLY.autoDnsStrategy())
    }

    @Test
    fun explicitDnsStrategyIsClampedWhenAddressFamilyIsDisabled() {
        assertEquals("ipv4_only", IpVersionMode.IPV4_ONLY.resolveDnsStrategy(DnsStrategy.PREFER_IPV6))
        assertEquals("ipv6_only", IpVersionMode.IPV6_ONLY.resolveDnsStrategy(DnsStrategy.PREFER_IPV4))
        assertEquals("prefer_ipv6", IpVersionMode.PREFER_IPV6.resolveDnsStrategy(DnsStrategy.AUTO))
        assertEquals("prefer_ipv4", IpVersionMode.DUAL_STACK.resolveDnsStrategy(DnsStrategy.AUTO))
    }
}
