package com.kunk.singbox.service.tun

import com.kunk.singbox.model.IpVersionMode
import com.kunk.singbox.model.includesIpv4
import com.kunk.singbox.model.includesIpv6

data class VpnTunAddressPlan(
    val addresses: List<Pair<String, Int>>,
    val globalRoutes: List<Pair<String, Int>>,
    val defaultDnsServers: List<String>
)

object VpnTunAddressPlanner {
    fun build(mode: IpVersionMode): VpnTunAddressPlan {
        val addresses = mutableListOf<Pair<String, Int>>()
        val routes = mutableListOf<Pair<String, Int>>()
        val dnsServers = mutableListOf<String>()

        if (mode.includesIpv4) {
            addresses.add("172.19.0.1" to 30)
            routes.add("0.0.0.0" to 0)
            dnsServers.add("223.5.5.5")
            dnsServers.add("119.29.29.29")
            dnsServers.add("1.1.1.1")
        }
        if (mode.includesIpv6) {
            addresses.add("fd00::1" to 126)
            routes.add("::" to 0)
            dnsServers.add("2606:4700:4700::1111")
        }

        return VpnTunAddressPlan(addresses, routes, dnsServers)
    }
}
