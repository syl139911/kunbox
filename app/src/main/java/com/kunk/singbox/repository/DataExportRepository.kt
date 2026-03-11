package com.kunk.singbox.repository

import com.kunk.singbox.R
import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
// import com.kunk.singbox.BuildConfig // Build config is usually in root package or needs verification
import com.kunk.singbox.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 */
class DataExportRepository(private val context: Context) {

    companion object {
        private const val TAG = "DataExportRepository"
        private const val CURRENT_VERSION = 1

        @Volatile
        private var instance: DataExportRepository? = null

        fun getInstance(context: Context): DataExportRepository {
            return instance ?: synchronized(this) {
                instance ?: DataExportRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .create()

    private val settingsRepository = SettingsRepository.getInstance(context)
    private val configRepository = ConfigRepository.getInstance(context)
    private val ruleSetRepository = RuleSetRepository.getInstance(context)

    private val configDir: File
        get() = File(context.filesDir, "configs").also { it.mkdirs() }

    /**
     */
    suspend fun exportAllData(): Result<String> = withContext(Dispatchers.IO) {
        try {

            val settings = settingsRepository.settings.first()

            val profiles = configRepository.profiles.value
            val activeProfileId = configRepository.activeProfileId.value
            val activeNodeId = configRepository.activeNodeId.value

            val profileExportDataList = profiles.mapNotNull { profile ->
                try {
                    val configFile = File(configDir, "${profile.id}.json")
                    if (configFile.exists()) {
                        val configJson = configFile.readText()
                        val config = gson.fromJson(configJson, SingBoxConfig::class.java)
                        ProfileExportData(profile = profile, config = config)
                    } else {
                        Log.w(TAG, "Config file not found for profile: ${profile.id}")
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load config for profile: ${profile.id}", e)
                    null
                }
            }

            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val appVersionName = packageInfo.versionName ?: "Unknown"

            val exportData = ExportData(
                version = CURRENT_VERSION,
                exportTime = System.currentTimeMillis(),
                appVersion = appVersionName,
                settings = settings,
                profiles = profileExportDataList,
                activeProfileId = activeProfileId,
                activeNodeId = activeNodeId
            )

            // 5. 閹兼潙绻愰崹顏堝礌閺嶏箒绀?JSON
            val jsonString = gson.toJson(exportData)

            Result.success(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            Result.failure(e)
        }
    }

    /**
     */
    suspend fun exportToFile(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val jsonResult = exportAllData()
            if (jsonResult.isFailure) {
                return@withContext Result.failure(jsonResult.exceptionOrNull() ?: Exception(context.getString(R.string.export_failed)))
            }

            val jsonString = jsonResult.getOrThrow()

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(jsonString.toByteArray(Charsets.UTF_8))
                outputStream.flush()
            } ?: throw Exception("Could not open file for writing")

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Export to file failed", e)
            Result.failure(e)
        }
    }

    /**
     */
    suspend fun validateImportData(jsonData: String): Result<ExportData> = withContext(Dispatchers.IO) {
        try {
            val exportData = gson.fromJson(jsonData, ExportData::class.java)

            // 濡ょ姴鐭侀惁澶愭偋閸喐鎷?
            if (exportData.version > CURRENT_VERSION) {
                return@withContext Result.failure(
                    Exception("Data version too high (v${exportData.version}), please update app and try again")
                )
            }

            if (exportData.settings == null) {
                return@withContext Result.failure(Exception("Data format error: missing settings info"))
            }

            Result.success(exportData)
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "Invalid JSON format", e)
            Result.failure(Exception("Data format error, please check file validity"))
        } catch (e: Exception) {
            Log.e(TAG, "Validation failed", e)
            Result.failure(e)
        }
    }

    /**
     */
    fun getExportDataSummary(exportData: ExportData): ExportDataSummary {
        val totalNodeCount = exportData.profiles.sumOf { profileData ->
            profileData.config.outbounds?.count { outbound ->
                outbound.type in listOf(
                    "shadowsocks", "vmess", "vless", "trojan",
                    "hysteria", "hysteria2", "tuic", "wireguard",
                    "shadowtls", "ssh", "anytls"
                )
            } ?: 0
        }

        return ExportDataSummary(
            version = exportData.version,
            exportTime = exportData.exportTime,
            appVersion = exportData.appVersion,
            profileCount = exportData.profiles.size,
            totalNodeCount = totalNodeCount,
            hasSettings = true,
            hasCustomRules = exportData.settings.customRules.isNotEmpty(),
            hasRuleSets = exportData.settings.ruleSets.isNotEmpty(),
            hasAppRules = exportData.settings.appRules.isNotEmpty() || exportData.settings.appGroups.isNotEmpty()
        )
    }

    /**
     */
    suspend fun importData(jsonData: String, options: ImportOptions = ImportOptions()): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            val validateResult = validateImportData(jsonData)
            if (validateResult.isFailure) {
                val error = validateResult.exceptionOrNull()
                    ?: Exception(context.getString(R.string.import_failed))
                return@withContext Result.failure(error)
            }
            val exportData = validateResult.getOrThrow()

            var profilesImported = 0
            var nodesImported = 0
            var settingsImported = false
            val errors = mutableListOf<String>()

            if (options.importSettings) {
                try {
                    importSettings(exportData.settings)
                    settingsImported = true

                    if (exportData.settings.ruleSets.isNotEmpty()) {
                        Log.i(TAG, "Triggering rule set download after import...")

                        repositoryScope.launch {
                            try {
                                ruleSetRepository.ensureRuleSetsReady(forceUpdate = false, allowNetwork = true) {
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to download rule sets after import", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to import settings", e)
                    errors.add("Failed to import settings: ${e.message}")
                }
            }

            if (options.importProfiles) {
                for (profileData in exportData.profiles) {
                    try {
                        val nodeCount = importProfile(profileData, options.overwriteExisting)
                        profilesImported++
                        nodesImported += nodeCount
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to import profile: ${profileData.profile.name}", e)
                        errors.add("Profile '${profileData.profile.name}' import failed: ${e.message}")
                    }
                }
            }

            if (options.importProfiles && exportData.activeProfileId != null) {
                try {

                    val profiles = configRepository.profiles.value
                    if (profiles.any { it.id == exportData.activeProfileId }) {
                        configRepository.setActiveProfile(
                            exportData.activeProfileId,
                            exportData.activeNodeId
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore active profile", e)
                }
            }

            val result = when {
                errors.isEmpty() -> ImportResult.Success(
                    profilesImported = profilesImported,
                    nodesImported = nodesImported,
                    settingsImported = settingsImported
                )
                profilesImported > 0 || settingsImported -> ImportResult.PartialSuccess(
                    profilesImported = profilesImported,
                    profilesFailed = exportData.profiles.size - profilesImported,
                    errors = errors
                )
                else -> ImportResult.Failed(errors.joinToString("\n"))
            }

            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            Result.failure(e)
        }
    }

    /**
     */
    suspend fun importFromFile(uri: Uri, options: ImportOptions = ImportOptions()): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            val jsonData = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().readText()
            } ?: throw Exception("Could not read file")

            importData(jsonData, options)
        } catch (e: Exception) {
            Log.e(TAG, "Import from file failed", e)
            Result.failure(e)
        }
    }

    /**
     */
    suspend fun validateFromFile(uri: Uri): Result<ExportData> = withContext(Dispatchers.IO) {
        try {
            val jsonData = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().readText()
            } ?: throw Exception("Could not read file")

            validateImportData(jsonData)
        } catch (e: Exception) {
            Log.e(TAG, "Validate from file failed", e)
            Result.failure(e)
        }
    }

    /**
     */
    private suspend fun importSettings(settings: AppSettings) {

        settingsRepository.setAutoConnect(settings.autoConnect)
        settingsRepository.setExcludeFromRecent(settings.excludeFromRecent)
        settingsRepository.setAppTheme(settings.appTheme)

        settingsRepository.setTunEnabled(settings.tunEnabled)
        settingsRepository.setTunStack(settings.tunStack)
        settingsRepository.setTunMtu(settings.tunMtu)
        settingsRepository.setTunInterfaceName(settings.tunInterfaceName)
        settingsRepository.setAutoRoute(settings.autoRoute)
        settingsRepository.setStrictRoute(settings.strictRoute)
        settingsRepository.setEndpointIndependentNat(settings.endpointIndependentNat)
        settingsRepository.setVpnRouteMode(settings.vpnRouteMode)
        settingsRepository.setVpnRouteIncludeCidrs(settings.vpnRouteIncludeCidrs)
        settingsRepository.setVpnAppMode(settings.vpnAppMode)
        settingsRepository.setVpnAllowlist(settings.vpnAllowlist)
        settingsRepository.setVpnBlocklist(settings.vpnBlocklist)

        settingsRepository.setLocalDns(settings.localDns)
        settingsRepository.setRemoteDns(settings.remoteDns)
        settingsRepository.setFakeDnsEnabled(settings.fakeDnsEnabled)
        settingsRepository.setFakeIpRange(settings.fakeIpRange)
        settingsRepository.setDnsStrategy(settings.dnsStrategy)
        settingsRepository.setRemoteDnsStrategy(settings.remoteDnsStrategy)
        settingsRepository.setDirectDnsStrategy(settings.directDnsStrategy)
        settingsRepository.setServerAddressStrategy(settings.serverAddressStrategy)
        settingsRepository.setDnsCacheEnabled(settings.dnsCacheEnabled)

        settingsRepository.setRoutingMode(settings.routingMode, notifyRestartRequired = false)
        settingsRepository.setDefaultRule(settings.defaultRule)
        settingsRepository.setBypassLan(settings.bypassLan)
        settingsRepository.setBlockQuic(settings.blockQuic)
        settingsRepository.setDebugLoggingEnabled(settings.debugLoggingEnabled)

        settingsRepository.setLatencyTestMethod(settings.latencyTestMethod)
        settingsRepository.setLatencyTestUrl(settings.latencyTestUrl)

        if (settings.ghProxyMirror != null) {
            settingsRepository.setGhProxyMirror(settings.ghProxyMirror)
        }

        settingsRepository.setProxyPort(settings.proxyPort)
        settingsRepository.setAllowLan(settings.allowLan)
        settingsRepository.setAppendHttpProxy(settings.appendHttpProxy)

        // 濡ゅ倹顭囨鍥╂崉椤栨粍鏆犻悷娆忓閸?
        settingsRepository.setCustomRules(settings.customRules)
        settingsRepository.setRuleSets(settings.ruleSets, notify = false)
        settingsRepository.setAppRules(settings.appRules)
        settingsRepository.setAppGroups(settings.appGroups)

        settingsRepository.setRuleSetAutoUpdateEnabled(settings.ruleSetAutoUpdateEnabled)
        settingsRepository.setRuleSetAutoUpdateInterval(settings.ruleSetAutoUpdateInterval)

        settingsRepository.setNodeFilter(settings.nodeFilter)
        settingsRepository.setNodeSortType(settings.nodeSortType)
        settingsRepository.setCustomNodeOrder(settings.customNodeOrder)
    }

    /**
     */
    private suspend fun importProfile(profileData: ProfileExportData, overwrite: Boolean): Int {
        val profile = profileData.profile
        val config = profileData.config

        val existingProfiles = configRepository.profiles.value
        val existingById = existingProfiles.find { it.id == profile.id }
        val existingByName = existingProfiles.find { it.name == profile.name }

        if (!overwrite && (existingById != null || existingByName != null)) {
            throw Exception("Profile already exists")
        }

        val configFile = File(configDir, "${profile.id}.json")
        try {
            configFile.writeText(gson.toJson(config))

            val newProfile = profile.copy(
                id = profile.id,
                lastUpdated = System.currentTimeMillis(),
                updateStatus = UpdateStatus.Idle
            )

            configRepository.importProfileDirectly(newProfile, config)
        } catch (e: Exception) {
            if (configFile.exists() && !configFile.delete()) {
                Log.w(TAG, "Failed to delete orphaned imported config: ${configFile.absolutePath}")
            }
            throw e
        }

        if (overwrite) {
            val oldProfileId = existingByName
                ?.takeIf { existingById == null && it.id != profile.id }
                ?.id
            if (oldProfileId != null) {
                configRepository.deleteProfile(oldProfileId)
            }
        }

        val nodeCount = config.outbounds?.count { outbound ->
            outbound.type in listOf(
                "shadowsocks", "vmess", "vless", "trojan",
                "hysteria", "hysteria2", "tuic", "wireguard",
                "shadowtls", "ssh", "anytls"
            )
        } ?: 0

        return nodeCount
    }

    /**
     *
     */
    fun cleanup() {
        repositoryScope.cancel()
        Log.i(TAG, "DataExportRepository cleanup completed")
    }
}
