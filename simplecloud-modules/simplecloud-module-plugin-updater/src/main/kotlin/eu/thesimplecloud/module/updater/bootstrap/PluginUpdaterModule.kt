package eu.thesimplecloud.module.updater.bootstrap

import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.api.external.ICloudModule
import eu.thesimplecloud.jsonlib.JsonLib
import eu.thesimplecloud.launcher.startup.Launcher
import eu.thesimplecloud.module.updater.api.UpdaterAPI
import eu.thesimplecloud.module.updater.command.UpdaterCommand
import eu.thesimplecloud.module.updater.config.AutoManagerConfig
import eu.thesimplecloud.module.updater.manager.PluginManager
import eu.thesimplecloud.module.updater.manager.ServerVersionManager
import eu.thesimplecloud.module.updater.manager.ServiceVersionRegistrar
import eu.thesimplecloud.module.updater.manager.TemplateManager
import eu.thesimplecloud.module.updater.thread.UpdateScheduler
import kotlinx.coroutines.*
import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

class PluginUpdaterModule : ICloudModule {

    private val configFile = File(DirectoryPaths.paths.modulesPath + "automanager/auto-manager-config.json")

    private lateinit var config: AutoManagerConfig
    private lateinit var serverVersionManager: ServerVersionManager
    private lateinit var pluginManager: PluginManager
    private lateinit var templateManager: TemplateManager
    private lateinit var updateScheduler: UpdateScheduler
    private lateinit var updaterAPI: UpdaterAPI
    private lateinit var serviceVersionRegistrar: ServiceVersionRegistrar

    private val moduleScope = CoroutineScope(Dispatchers.IO)

    override fun onEnable() {
        println("[AutoManager] Initializing module...")

        try {
            loadConfig()
            initializeManagers()

            // Register service versions BEFORE services start
            runBlocking {
                registerServiceVersions()
            }

            // Register commands
            registerCommands()

            if (config.enableAutomation) {
                scheduleUpdates()
                println("[AutoManager] Automation started")
            }

            println("[AutoManager] Module loaded successfully!")
        } catch (e: Exception) {
            println("[AutoManager] Error during initialization: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun onDisable() {
        println("[AutoManager] Shutting down module...")

        try {
            if (::updateScheduler.isInitialized) {
                updateScheduler.shutdown()
            }
            moduleScope.cancel()
            println("[AutoManager] Module shut down correctly")
        } catch (e: Exception) {
            println("[AutoManager] Error during shutdown: ${e.message}")
        }
    }

    private fun loadConfig() {
        println("[AutoManager] Looking for config in: ${configFile.absolutePath}")

        config = if (configFile.exists()) {
            try {
                println("[AutoManager] Config found, loading...")
                val jsonData = JsonLib.fromJsonFile(configFile)!!
                AutoManagerConfig.fromJson(jsonData)
            } catch (e: Exception) {
                println("[AutoManager] Error loading config, using default: ${e.message}")
                e.printStackTrace()
                createDefaultConfig()
            }
        } else {
            println("[AutoManager] Config not found, creating default")
            createDefaultConfig()
        }

        saveConfig()
        println("[AutoManager] Config loaded with ${config.plugins.size} configured plugins")
    }

    private fun createDefaultConfig(): AutoManagerConfig {
        return AutoManagerConfig(
            enableAutomation = true,
            enableServerVersionUpdates = true,
            enablePluginUpdates = true,
            enableTemplateSync = true,
            enableNotifications = false,
            enableBackup = true,
            updateInterval = "24h",  // Changed from 6h to 24h
            updateTime = "04:00",    // New field: update at 4 AM
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
        println("[AutoManager] Initializing managers...")

        serverVersionManager = ServerVersionManager(config)
        pluginManager = PluginManager(this, config)
        templateManager = TemplateManager(this, config)
        updateScheduler = UpdateScheduler(this, config)
        updaterAPI = UpdaterAPI(serverVersionManager, pluginManager, templateManager)
        serviceVersionRegistrar = ServiceVersionRegistrar()
    }

    private suspend fun registerServiceVersions() {
        println("[AutoManager] Registering service versions...")

        try {
            // Update server versions from APIs
            val updated = serverVersionManager.updateAllVersions()
            if (!updated) {
                println("[AutoManager] Failed to update server versions from APIs")
            }

            // Get current versions
            val versions = serverVersionManager.getCurrentVersions()

            // Register Leaf versions
            val leafVersions = versions.find { it.name == "Leaf" }
            if (leafVersions != null && leafVersions.downloadLinks.isNotEmpty()) {
                serviceVersionRegistrar.registerLeafVersions(leafVersions.downloadLinks)
            }

            // Register VelocityCTD version
            val velocityCTD = versions.find { it.name == "VelocityCTD" }
            if (velocityCTD != null && velocityCTD.downloadLinks.isNotEmpty()) {
                velocityCTD.downloadLinks.forEach {
                    serviceVersionRegistrar.registerVelocityCTDVersion(it)
                }
            }

            println("[AutoManager] Service version registration complete")
        } catch (e: Exception) {
            println("[AutoManager] Error registering service versions: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun registerCommands() {
        try {
            val commandManager = Launcher.instance.commandManager
            commandManager.registerCommand(this, UpdaterCommand(this))
            println("[AutoManager] Commands registered")
        } catch (e: Exception) {
            println("[AutoManager] Failed to register commands: ${e.message}")
        }
    }

    private fun scheduleUpdates() {
        if (config.updateTime.isNotEmpty()) {
            // Schedule daily update at specified time
            scheduleNextUpdate()
        } else {
            // Use interval-based updates
            updateScheduler.start()
        }
    }

    private fun scheduleNextUpdate() {
        try {
            val updateTime = LocalTime.parse(config.updateTime)
            val now = LocalDateTime.now()
            var nextUpdate = now.with(updateTime)

            // If the time has already passed today, schedule for tomorrow
            if (nextUpdate.isBefore(now)) {
                nextUpdate = nextUpdate.plusDays(1)
            }

            val delayMillis = now.until(nextUpdate, ChronoUnit.MILLIS)

            println("[AutoManager] Next update scheduled at: $nextUpdate (in ${delayMillis / 1000 / 60} minutes)")

            moduleScope.launch {
                delay(delayMillis)
                performScheduledUpdate()
                // Schedule next update
                scheduleNextUpdate()
            }
        } catch (e: Exception) {
            println("[AutoManager] Error scheduling update: ${e.message}")
            // Fall back to interval-based updates
            updateScheduler.start()
        }
    }

    private suspend fun performScheduledUpdate() {
        println("[AutoManager] === SCHEDULED UPDATE STARTED ===")

        try {
            // Check if files need updating (don't download if they exist and are recent)
            val needsUpdate = checkIfUpdateNeeded()

            if (!needsUpdate) {
                println("[AutoManager] All files are up to date, skipping download")
                return
            }

            var success = true

            if (config.enableServerVersionUpdates) {
                success = serverVersionManager.updateAllVersions() && success
                if (success) {
                    registerServiceVersions()  // Re-register in case new versions are available
                }
            }

            if (config.enablePluginUpdates) {
                success = pluginManager.updateAllPlugins() && success
            }

            if (config.enableTemplateSync) {
                success = templateManager.syncAllTemplates() && success
                success = templateManager.syncStaticServersOnRestart() && success
            }

            println("[AutoManager] === SCHEDULED UPDATE COMPLETED: ${if (success) "SUCCESS" else "PARTIAL FAILURE"} ===")
        } catch (e: Exception) {
            println("[AutoManager] Error during scheduled update: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun checkIfUpdateNeeded(): Boolean {
        // Check if it's been more than 24 hours since last update
        val lastUpdateFile = File(DirectoryPaths.paths.storagePath + "last_update.txt")

        return if (lastUpdateFile.exists()) {
            val lastUpdate = lastUpdateFile.readText().toLongOrNull() ?: 0L
            val dayInMillis = 24 * 60 * 60 * 1000
            System.currentTimeMillis() - lastUpdate > dayInMillis
        } else {
            true
        }
    }

    private fun saveConfig() {
        try {
            configFile.parentFile.mkdirs()
            JsonLib.fromObject(config.toJson()).saveAsFile(configFile)
        } catch (e: Exception) {
            println("[AutoManager] Error saving config: ${e.message}")
        }
    }

    suspend fun forceUpdate(): Boolean {
        println("[AutoManager] Force update requested")

        // Update timestamp
        val lastUpdateFile = File(DirectoryPaths.paths.storagePath + "last_update.txt")
        lastUpdateFile.parentFile.mkdirs()
        lastUpdateFile.writeText(System.currentTimeMillis().toString())

        return performScheduledUpdate().let { true }
    }

    fun checkVersions(): Map<String, String> {
        val versions = mutableMapOf<String, String>()

        serverVersionManager.getCurrentVersions().forEach { entry ->
            versions[entry.name] = entry.latestVersion
        }

        return versions
    }

    fun getConfig(): AutoManagerConfig = config

    fun getServerVersionManager(): ServerVersionManager = serverVersionManager

    fun getPluginManager(): PluginManager = pluginManager

    fun getTemplateManager(): TemplateManager = templateManager

    fun getUpdateScheduler(): UpdateScheduler = updateScheduler

    fun getUpdaterAPI(): UpdaterAPI = updaterAPI

    fun isEnabled(): Boolean = config.enableAutomation

    fun getStats(): Map<String, Any> {
        return mapOf(
            "enabled" to config.enableAutomation,
            "next_update" to getNextUpdateTime(),
            "configured_plugins" to config.plugins.size,
            "enabled_plugins" to config.plugins.count { it.enabled },
            "server_software" to config.serverSoftware.size,
            "update_time" to config.updateTime
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

    companion object {
        @JvmStatic
        lateinit var instance: PluginUpdaterModule
            private set
    }

    init {
        instance = this
    }
}