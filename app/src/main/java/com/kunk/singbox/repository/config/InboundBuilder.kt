package com.kunk.singbox.repository.config

import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.Inbound
import com.kunk.singbox.model.TunAddressConfig
import com.kunk.singbox.model.TunStack
import com.kunk.singbox.model.tunAddresses

/**
 */
object InboundBuilder {

    /**
     */
    fun build(settings: AppSettings, effectiveTunStack: TunStack): List<Inbound> {
        val inbounds = mutableListOf<Inbound>()
        val tunAddress = settings.tunAddress ?: TunAddressConfig.DEFAULT

        if (settings.proxyPort > 0) {
            inbounds.add(
                Inbound(
                    type = "mixed",
                    tag = "mixed-in",
                    listen = if (settings.allowLan) "0.0.0.0" else "127.0.0.1",
                    listenPort = settings.proxyPort,
                    reuseAddr = true
                )
            )
        }

        if (settings.tunEnabled) {
            inbounds.add(
                Inbound(
                    type = "tun",
                    tag = "tun-in",
                    interfaceName = settings.tunInterfaceName,
                    addressRaw = settings.ipVersionMode.tunAddresses(tunAddress),
                    mtu = settings.tunMtu,
                    autoRoute = false,
                    strictRoute = false,
                    stack = effectiveTunStack.name.lowercase(),
                    endpointIndependentNat = settings.endpointIndependentNat,
                    gso = null,
                    sniff = true,
                    sniffOverrideDestination = true,
                    sniffTimeout = "100ms"
                )
            )
        } else if (settings.proxyPort <= 0) {
            inbounds.add(
                Inbound(
                    type = "mixed",
                    tag = "mixed-in",
                    listen = "127.0.0.1",
                    listenPort = 2080,
                    reuseAddr = true
                )
            )
        }

        return inbounds
    }
}
