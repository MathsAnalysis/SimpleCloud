package eu.thesimplecloud.module.updater.bootstrap

import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.api.external.ICloudModule
import eu.thesimplecloud.jsonlib.JsonLib
import eu.thesimplecloud.launcher.startup.Launcher
import eu.thesimplecloud.module.updater.api.UpdaterAPI
import eu.thesimplecloud.module.updater.command.UpdaterCommand
import eu.thesimplecloud.module.updater.config.AutoManagerConfig
import eu.thesimplecloud.module.updater.plugin.PluginManager
import eu.thesimplecloud.module.updater.manager.ServerVersionManager
import eu.thesimplecloud.module.updater.manager.ServiceVersionRegistrar
import eu.thesimplecloud.module.updater.manager.TemplateManager
import eu.thesimplecloud.module.updater.plugin.TemplatePluginManager
import eu.thesimplecloud.module.updater.thread.UpdateScheduler
import eu.thesimplecloud.module.updater.utils.LoggingUtils
import kotlinx.coroutines.*
import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

class PluginUpdaterModule : ICloudModule {

    companion object {
        private const val TAG = "AutoManager"

        @JvmStatic
        lateinit var instance: PluginUpdaterModule
            private set
    }

    private val configFile = File(DirectoryPaths.paths.modulesPath + "automanager/auto-manager-config.json")

    private lateinit var config: AutoManagerConfig
    private lateinit var serverVersionManager: ServerVersionManager
    private lateinit var pluginManager: PluginManager
    private lateinit var templateManager: TemplateManager
    private lateinit var templatePluginManager: TemplatePluginManager
    private lateinit var updateScheduler: UpdateScheduler
    private lateinit var updaterAPI: UpdaterAPI
    private lateinit var serviceVersionRegistrar: ServiceVersionRegistrar

    private val moduleScope = CoroutineScope(Dispatchers.IO)

    init {
        instance = this
    }

    override fun onEnable() {
        LoggingUtils.init(TAG, "Initializing module...")

        try {
            loadConfig()
            initializeManagers()

            runBlocking {
                registerServiceVersions()
            }

            registerCommands()

            if (config.enableAutomation) {
                scheduleUpdates()
                LoggingUtils.info(TAG, "Automation started")
            } else {
                LoggingUtils.info(TAG, "Automation is disabled in configuration")
            }

            LoggingUtils.info(TAG, "Module loaded successfully!")

            if (config.enableDebug) {
                LoggingUtils.debugStats(TAG, getStats())
            }

        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error during initialization: ${e.message}", e)
        }
    }

    override fun onDisable() {
        LoggingUtils.info(TAG, "Shutting down module...")

        try {
            if (::updateScheduler.isInitialized) {
                LoggingUtils.debug(TAG, "Shutting down update scheduler...")
                updateScheduler.shutdown()
                LoggingUtils.debug(TAG, "Update scheduler shut down")
            }

            LoggingUtils.debug(TAG, "Cancelling module scope...")
            moduleScope.cancel()

            LoggingUtils.info(TAG, "Module shut down correctly")
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error during shutdown: ${e.message}", e)
        }
    }

    private fun loadConfig() {
        LoggingUtils.debug(TAG, "Looking for config in: ${configFile.absolutePath}")

        config = if (configFile.exists()) {
            try {
                LoggingUtils.debug(TAG, "Config found, loading...")
                val jsonData = JsonLib.fromJsonFile(configFile)!!
                AutoManagerConfig.fromJson(jsonData)
            } catch (e: Exception) {
                LoggingUtils.error(TAG, "Error loading config, using default: ${e.message}", e)
                createDefaultConfig()
            }
        } else {
            LoggingUtils.debug(TAG, "Config not found, creating default")
            createDefaultConfig()
        }

        saveConfig()
        LoggingUtils.info(TAG, "Config loaded with ${config.plugins.size} configured plugins")

        if (config.enableDebug) {
            LoggingUtils.info(TAG, "Debug mode is ENABLED - verbose logging active")
            LoggingUtils.debugConfig(TAG, "enabled_plugins", config.plugins.filter { it.enabled }.map { it.name })
            LoggingUtils.debugConfig(TAG, "server_software", config.serverSoftware)
            LoggingUtils.debugConfig(TAG, "update_interval", config.updateInterval)
            LoggingUtils.debugConfig(TAG, "update_time", config.updateTime)
        } else {
            LoggingUtils.info(TAG, "Debug mode is DISABLED - minimal logging active")
        }
    }

    private fun createDefaultConfig(): AutoManagerConfig {
        LoggingUtils.debug(TAG, "Creating default configuration...")

        return AutoManagerConfig(
            enableAutomation = true,
            enableServerVersionUpdates = true,
            enablePluginUpdates = true,
            enableTemplateSync = true,
            enableNotifications = false,
            enableBackup = true,
            enableDebug = false,
            updateInterval = "24h",
            updateTime = "04:00",
            serverSoftware = listOf("paper", "leaf"),
            plugins = listOf(
                AutoManagerConfig.PluginConfig(
                    name = "LuckPerms",
                    enabled = true,
                    platforms = listOf("bukkit", "velocity"),
                    customUrl = null
                ),
                AutoManagerConfig.PluginConfig(
                    name = "Spark",
                    enabled = true,
                    platforms = listOf("bukkit", "velocity"),
                    customUrl = null
                )
            ),
            templates = AutoManagerConfig.TemplateConfig(
                autoCreateBaseTemplates = true,
                syncOnStart = true
            )
        )
    }

    private fun initializeManagers() {
        LoggingUtils.debug(TAG, "Initializing managers...")

        LoggingUtils.debug(TAG, "Initializing ServerVersionManager...")
        serverVersionManager = ServerVersionManager(this, config)

        LoggingUtils.debug(TAG, "Initializing PluginManager...")
        pluginManager = PluginManager(config)

        LoggingUtils.debug(TAG, "Initializing TemplateManager...")
        templateManager = TemplateManager(this, config)

        LoggingUtils.debug(TAG, "Initializing TemplatePluginManager...")
        templatePluginManager = TemplatePluginManager(config)

        LoggingUtils.debug(TAG, "Initializing UpdateScheduler...")
        updateScheduler = UpdateScheduler(this, config)

        LoggingUtils.debug(TAG, "Initializing UpdaterAPI...")
        updaterAPI = UpdaterAPI(serverVersionManager, pluginManager, templateManager)

        LoggingUtils.debug(TAG, "Initializing ServiceVersionRegistrar...")
        serviceVersionRegistrar = ServiceVersionRegistrar()

        LoggingUtils.info(TAG, "All managers initialized successfully")
    }

    private suspend fun registerServiceVersions() {
        LoggingUtils.debugStart(TAG, "service version registration")

        try {
            val updated = serverVersionManager.updateAllVersions()
            if (!updated) {
                LoggingUtils.warn(TAG, "Failed to update server versions from APIs")
            } else {
                LoggingUtils.debug(TAG, "Server versions updated successfully")
            }

            val versions = serverVersionManager.getCurrentVersions()
            LoggingUtils.debug(TAG, "Retrieved ${versions.size} version entries")

            val leafVersions = versions.find { it.name == "Leaf" }
            if (leafVersions != null && leafVersions.downloadLinks.isNotEmpty()) {
                LoggingUtils.debug(TAG, "Registering ${leafVersions.downloadLinks.size} Leaf versions...")
                serviceVersionRegistrar.registerLeafVersions(leafVersions.downloadLinks)
            }

            val velocityCTD = versions.find { it.name == "VelocityCTD" }
            if (velocityCTD != null && velocityCTD.downloadLinks.isNotEmpty()) {
                LoggingUtils.debug(TAG, "Registering ${velocityCTD.downloadLinks.size} VelocityCTD versions...")
                velocityCTD.downloadLinks.forEach {
                    serviceVersionRegistrar.registerVelocityCTDVersion(it)
                }
            }

            LoggingUtils.debugSuccess(TAG, "service version registration")
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error registering service versions: ${e.message}", e)
        }
    }

    private fun registerCommands() {
        try {
            LoggingUtils.debug(TAG, "Registering commands...")
            val commandManager = Launcher.instance.commandManager
            commandManager.registerCommand(this, UpdaterCommand(this))
            LoggingUtils.info(TAG, "Commands registered successfully")
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Failed to register commands: ${e.message}", e)
        }
    }

    private fun scheduleUpdates() {
        LoggingUtils.debug(TAG, "Setting up update scheduling...")

        if (config.updateTime.isNotEmpty()) {
            LoggingUtils.debug(TAG, "Using time-based scheduling: ${config.updateTime}")
            scheduleNextUpdate()
        } else {
            LoggingUtils.debug(TAG, "Using interval-based scheduling: ${config.updateInterval}")
            updateScheduler.start()
        }
    }

    private fun scheduleNextUpdate() {
        try {
            val updateTime = LocalTime.parse(config.updateTime)
            val now = LocalDateTime.now()
            var nextUpdate = now.with(updateTime)

            if (nextUpdate.isBefore(now)) {
                nextUpdate = nextUpdate.plusDays(1)
            }

            val delayMillis = now.until(nextUpdate, ChronoUnit.MILLIS)
            val delayMinutes = delayMillis / 1000 / 60

            LoggingUtils.info(TAG, "Next update scheduled at: $nextUpdate (in $delayMinutes minutes)")

            moduleScope.launch {
                delay(delayMillis)
                performScheduledUpdate()
                scheduleNextUpdate()
            }
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error scheduling update: ${e.message}", e)
            LoggingUtils.warn(TAG, "Falling back to interval-based scheduling")
            updateScheduler.start()
        }
    }

    private suspend fun performScheduledUpdate() {
        LoggingUtils.info(TAG, "=== SCHEDULED UPDATE STARTED ===")

        try {
            val needsUpdate = checkIfUpdateNeeded()
            LoggingUtils.debug(TAG, "Update check result: needs_update=$needsUpdate")

            if (!needsUpdate) {
                LoggingUtils.info(TAG, "All files are up to date, skipping download")
                return
            }

            var success = true

            if (config.enableServerVersionUpdates) {
                LoggingUtils.debug(TAG, "Updating server versions...")
                val serverUpdateSuccess = serverVersionManager.updateAllVersions()
                success = serverUpdateSuccess && success

                if (serverUpdateSuccess) {
                    LoggingUtils.debug(TAG, "Re-registering service versions after update...")
                    registerServiceVersions()
                } else {
                    LoggingUtils.warn(TAG, "Server version update failed")
                }
            }

            if (config.enablePluginUpdates) {
                LoggingUtils.debug(TAG, "Updating plugins...")
                val pluginUpdateSuccess = pluginManager.ensureAllPluginsDownloaded()
                success = pluginUpdateSuccess && success

                if (!pluginUpdateSuccess) {
                    LoggingUtils.warn(TAG, "Plugin update failed")
                }
            }

            if (config.enableTemplateSync) {
                LoggingUtils.debug(TAG, "Syncing templates...")
                val templateSyncSuccess = templateManager.syncAllTemplates()
                success = templateSyncSuccess && success

                LoggingUtils.debug(TAG, "Syncing static servers on restart...")
                val staticSyncSuccess = templateManager.syncStaticServersOnRestart()
                success = staticSyncSuccess && success

                if (!templateSyncSuccess || !staticSyncSuccess) {
                    LoggingUtils.warn(TAG, "Template sync failed")
                }
            }

            val resultMessage = if (success) "SUCCESS" else "PARTIAL FAILURE"
            LoggingUtils.info(TAG, "=== SCHEDULED UPDATE COMPLETED: $resultMessage ===")

        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error during scheduled update: ${e.message}", e)
        }
    }

    private fun checkIfUpdateNeeded(): Boolean {
        val lastUpdateFile = File(DirectoryPaths.paths.storagePath + "last_update.txt")

        return if (lastUpdateFile.exists()) {
            val lastUpdate = lastUpdateFile.readText().toLongOrNull() ?: 0L
            val dayInMillis = 24 * 60 * 60 * 1000
            val timeSinceUpdate = System.currentTimeMillis() - lastUpdate
            val needsUpdate = timeSinceUpdate > dayInMillis

            LoggingUtils.debug(TAG, "Last update: ${lastUpdate}, time since: ${timeSinceUpdate}ms, needs update: $needsUpdate")
            needsUpdate
        } else {
            LoggingUtils.debug(TAG, "No last update file found, update needed")
            true
        }
    }

    private fun saveConfig() {
        try {
            LoggingUtils.debug(TAG, "Saving configuration...")
            configFile.parentFile.mkdirs()
            JsonLib.fromObject(AutoManagerConfig.toJson(config)).saveAsFile(configFile)
            LoggingUtils.debug(TAG, "Configuration saved successfully")
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error saving config: ${e.message}", e)
        }
    }

    suspend fun forceUpdate(): Boolean {
        LoggingUtils.info(TAG, "Force update requested")

        val lastUpdateFile = File(DirectoryPaths.paths.storagePath + "last_update.txt")
        lastUpdateFile.parentFile.mkdirs()
        lastUpdateFile.writeText(System.currentTimeMillis().toString())

        return performScheduledUpdate().let { true }
    }

    fun checkVersions(): Map<String, String> {
        LoggingUtils.debug(TAG, "Checking current versions...")
        val versions = mutableMapOf<String, String>()

        serverVersionManager.getCurrentVersions().forEach { entry ->
            versions[entry.name] = entry.latestVersion
        }

        LoggingUtils.debug(TAG, "Retrieved ${versions.size} version entries")
        return versions
    }

    fun getConfig(): AutoManagerConfig = config
    fun getServerVersionManager(): ServerVersionManager = serverVersionManager
    fun getPluginManager(): PluginManager = pluginManager
    fun getTemplateManager(): TemplateManager = templateManager
    fun getTemplatePluginManager(): TemplatePluginManager = templatePluginManager
    fun getUpdateScheduler(): UpdateScheduler = updateScheduler
    fun getUpdaterAPI(): UpdaterAPI = updaterAPI
    fun isEnabled(): Boolean = config.enableAutomation

    fun getStats(): Map<String, Any> {
        return mapOf(
            "enabled" to config.enableAutomation,
            "debug_mode" to config.enableDebug,
            "next_update" to getNextUpdateTime(),
            "configured_plugins" to config.plugins.size,
            "enabled_plugins" to config.plugins.count { it.enabled },
            "server_software" to config.serverSoftware.size,
            "update_time" to config.updateTime,
            "update_interval" to config.updateInterval,
            "automation_features" to mapOf(
                "server_version_updates" to config.enableServerVersionUpdates,
                "plugin_updates" to config.enablePluginUpdates,
                "template_sync" to config.enableTemplateSync,
                "notifications" to config.enableNotifications,
                "backup" to config.enableBackup
            )
        )
    }

    private fun getNextUpdateTime(): String {
        return if (config.updateTime.isNotEmpty()) {
            val updateTime = LocalTime.parse(config.updateTime)
            val now = LocalDateTime.now()
            var nextUpdate = now.with(updateTime)

            if (nextUpdate.isBefore(now)) {
                nextUpdate = nextUpdate.plusDays(1)
            }

            nextUpdate.toString()
        } else {
            "Using interval: ${config.updateInterval}"
        }
    }
}