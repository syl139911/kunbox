package com.kunk.singbox.repository.store

import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.AppGroup
import com.kunk.singbox.model.AppInfo
import com.kunk.singbox.model.AppRule
import com.kunk.singbox.model.RuleSetOutboundMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsStoreTest {

    @Test
    fun testMigrateSettingsReplacesLegacyLocalDnsAtVersionFour() {
        val migrated = SettingsStore.migrateSettings(
            version = 3,
            settings = AppSettings(localDns = AppSettings.LEGACY_LOCAL_DNS)
        )

        assertEquals(AppSettings.DEFAULT_LOCAL_DNS, migrated.localDns)
    }

    @Test
    fun testMigrateSettingsKeepsCustomLocalDns() {
        val customDns = "https://dns.google/dns-query"
        val migrated = SettingsStore.migrateSettings(
            version = 3,
            settings = AppSettings(localDns = customDns)
        )

        assertEquals(customDns, migrated.localDns)
    }

    @Test
    fun testMigrateSettingsReplacesOldVersionLocalDefaultWithDoh() {
        val migrated = SettingsStore.migrateSettings(
            version = 2,
            settings = AppSettings(localDns = "223.5.5.5")
        )

        assertEquals(AppSettings.DEFAULT_LOCAL_DNS, migrated.localDns)
    }

    @Test
    fun testMigrateSettingsNormalizesNullAppOutboundModesToProxy() {
        val migrated = SettingsStore.migrateSettings(
            version = 4,
            settings = AppSettings(
                appRules = listOf(
                    AppRule(
                        packageName = "com.example.x",
                        appName = "X",
                        outboundMode = null
                    )
                ),
                appGroups = listOf(
                    AppGroup(
                        name = "social",
                        apps = listOf(AppInfo(packageName = "com.example.y", appName = "Y")),
                        outboundMode = null
                    )
                )
            )
        )

        assertEquals(RuleSetOutboundMode.PROXY, migrated.appRules.single().outboundMode)
        assertEquals(RuleSetOutboundMode.PROXY, migrated.appGroups.single().outboundMode)
    }
}
