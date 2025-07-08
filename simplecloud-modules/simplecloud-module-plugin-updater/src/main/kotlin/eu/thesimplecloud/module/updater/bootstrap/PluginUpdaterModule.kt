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
import eu.thesimplecloud.module.updater.updater.AutomaticJarUpdater
import eu.thesimplecloud.module.updater.utils.LoggingUtils
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.LocalTime

class PluginUpdaterModule : ICloudModule {

    private val configFile = File(DirectoryPaths.paths.modulesPath + "automanager/auto-manager-config.json")

    private lateinit var config: AutoManagerConfig
    private lateinit var serverVersionManager: ServerVersionManager
    private lateinit var pluginManager: PluginManager
    private lateinit var templateManager: TemplateManager
    private lateinit var updateScheduler: UpdateScheduler
    private lateinit var updaterAPI: UpdaterAPI
    private lateinit var serviceVersionRegistrar: ServiceVersionRegistrar
    private lateinit var automaticJarUpdater: AutomaticJarUpdater

    private val moduleScope = CoroutineScope(Dispatchers.IO)

    override fun onEnable() {
        println("[AutoManager] Initializing module...")

        try {
            loadConfig()
            initializeManagers()

            runBlocking {
                registerServiceVersions()
            }

            registerCommands()

            if (config.enableAutomation) {
                scheduleUpdates()
                startAutomaticJarUpdater()
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

    private fun initializeManagers() {
        println("[AutoManager] Initializing managers...")

        serverVersionManager = ServerVersionManager(this, config)
        pluginManager = PluginManager(config)
        templateManager = TemplateManager(this, config)
        updateScheduler = UpdateScheduler(this, config)
        updaterAPI = UpdaterAPI(serverVersionManager, pluginManager, templateManager)
        serviceVersionRegistrar = ServiceVersionRegistrar()
        automaticJarUpdater = AutomaticJarUpdater(this)
    }

    private fun startAutomaticJarUpdater() {
        try {
            automaticJarUpdater.startAutomaticMonitoring()
            println("[AutoManager] AutomaticJarUpdater started successfully")
        } catch (e: Exception) {
            println("[AutoManager] Error starting AutomaticJarUpdater: ${e.message}")
            e.printStackTrace()
        }
    }

    fun getAutomaticJarUpdater(): AutomaticJarUpdater = automaticJarUpdater

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

    private suspend fun registerServiceVersions() {
        println("[AutoManager] Registering service versions...")

        try {
            val updated = serverVersionManager.updateAllVersions()
            if (!updated) {
                println("[AutoManager] Failed to update server versions from APIs")
            }

            val versions = serverVersionManager.getCurrentVersions()

            val leafVersions = versions.find { it.name == "Leaf" }
            if (leafVersions != null && leafVersions.downloadLinks.isNotEmpty()) {
                serviceVersionRegistrar.registerLeafVersions(leafVersions.downloadLinks)
            }

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
        LoggingUtils.info("[UpdaterScheduler]", "Starting unified scheduler...")
        updateScheduler.start()
    }

    private suspend fun performScheduledUpdate() {
        println("[AutoManager] === SCHEDULED UPDATE STARTED ===")

        try {
            val needsUpdate = checkIfUpdateNeeded()

            if (!needsUpdate) {
                println("[AutoManager] All files are up to date, skipping download")
                return
            }

            var success = true

            if (config.enableServerVersionUpdates) {
                success = serverVersionManager.updateAllVersions() && success
                if (success) {
                    registerServiceVersions()
                }
            }

            if (config.enablePluginUpdates) {
                success = pluginManager.ensureAllPluginsDownloaded() && success
            }

            if (config.enableTemplateSync) {
                success = templateManager.syncAllTemplates() && success
                success = templateManager.syncStaticServersOnRestart() && success
            }

            if (success && config.enableServerVersionUpdates) {
                println("[AutoManager] Updating JAR files in minecraftJars and templates...")
                try {
                    updateMinecraftJarsAndTemplates()
                    println("[AutoManager] JAR update completed successfully")
                } catch (e: Exception) {
                    println("[AutoManager] Error updating JARs: ${e.message}")
                    e.printStackTrace()
                    success = false
                }
            }

            println("[AutoManager] === SCHEDULED UPDATE COMPLETED: ${if (success) "SUCCESS" else "PARTIAL FAILURE"} ===")
        } catch (e: Exception) {
            println("[AutoManager] Error during scheduled update: ${e.message}")
            e.printStackTrace()
        }
    }

    private suspend fun updateMinecraftJarsAndTemplates() = withContext(Dispatchers.IO) {
        val minecraftJarsDir = File(DirectoryPaths.paths.minecraftJarsPath)
        val serverVersionsDir = File(DirectoryPaths.paths.storagePath + "server-versions")
        val templatesDir = File(DirectoryPaths.paths.templatesPath)

        if (!minecraftJarsDir.exists()) minecraftJarsDir.mkdirs()

        updateLeafJarFiles(serverVersionsDir, minecraftJarsDir, templatesDir)

        updateVelocityCTDJarFiles(serverVersionsDir, minecraftJarsDir, templatesDir)
    }

    private fun updateLeafJarFiles(serverVersionsDir: File, minecraftJarsDir: File, templatesDir: File) {
        try {
            val leafDir = File(serverVersionsDir, "leaf")
            if (!leafDir.exists()) {
                println("[AutoManager] Leaf versions directory not found")
                return
            }

            val latestLeafJar = leafDir.listFiles()?.filter { it.name.endsWith(".jar") }
                ?.maxByOrNull { it.lastModified() }

            if (latestLeafJar != null) {
                val targetJar = File(minecraftJarsDir, "LEAF_${sanitizeVersion(latestLeafJar.nameWithoutExtension)}.jar")

                if (!targetJar.exists()) {
                    Files.copy(latestLeafJar.toPath(), targetJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    println("[AutoManager] Copied ${latestLeafJar.name} to minecraftJars")
                }

                updateServerTemplates(templatesDir, targetJar)
            }
        } catch (e: Exception) {
            println("[AutoManager] Error updating Leaf JAR: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun updateVelocityCTDJarFiles(serverVersionsDir: File, minecraftJarsDir: File, templatesDir: File) {
        try {
            val velocityCTDDir = File(serverVersionsDir, "velocityctd")
            if (!velocityCTDDir.exists()) {
                println("[AutoManager] VelocityCTD versions directory not found")
                return
            }

            val latestVelocityJar = velocityCTDDir.listFiles()?.filter { it.name.endsWith(".jar") }
                ?.maxByOrNull { it.lastModified() }

            if (latestVelocityJar != null) {
                val targetJar = File(minecraftJarsDir, "VELOCITYCTD_${sanitizeVersion(latestVelocityJar.nameWithoutExtension)}.jar")

                if (!targetJar.exists()) {
                    Files.copy(latestVelocityJar.toPath(), targetJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    println("[AutoManager] Copied ${latestVelocityJar.name} to minecraftJars")
                }

                updateProxyTemplates(templatesDir, targetJar)
            }
        } catch (e: Exception) {
            println("[AutoManager] Error updating VelocityCTD JAR: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun updateServerTemplates(templatesDir: File, newJar: File) {
        templatesDir.listFiles()?.forEach { templateDir ->
            if (templateDir.isDirectory) {
                val isServerTemplate = !File(templateDir, "velocity.toml").exists() &&
                        !File(templateDir, "config.yml").exists()

                if (isServerTemplate) {
                    val serverJar = File(templateDir, "server.jar")

                    if (serverJar.exists() && config.templates.enableTemplateBackup) {
                        val backupFile = File(templateDir, "server.jar.backup-${System.currentTimeMillis()}")
                        Files.copy(serverJar.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        println("[AutoManager] Backup created: ${templateDir.name}/server.jar.backup")
                    }

                    Files.copy(newJar.toPath(), serverJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    println("[AutoManager] Updated ${templateDir.name}/server.jar")
                }
            }
        }
    }

    private fun updateProxyTemplates(templatesDir: File, newJar: File) {
        templatesDir.listFiles()?.forEach { templateDir ->
            if (templateDir.isDirectory) {
                val isProxyTemplate = File(templateDir, "velocity.toml").exists() ||
                        File(templateDir, "config.yml").exists()

                if (isProxyTemplate) {
                    val velocityJar = File(templateDir, "velocity.jar")

                    if (velocityJar.exists() && config.templates.enableTemplateBackup) {
                        val backupFile = File(templateDir, "velocity.jar.backup-${System.currentTimeMillis()}")
                        Files.copy(velocityJar.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        println("[AutoManager] Backup created: ${templateDir.name}/velocity.jar.backup")
                    }

                    Files.copy(newJar.toPath(), velocityJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    println("[AutoManager] Updated ${templateDir.name}/velocity.jar")
                }
            }
        }
    }

    private fun sanitizeVersion(version: String): String {
        return version.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
    }

    private fun createDefaultConfig(): AutoManagerConfig {
        return AutoManagerConfig(
            enableAutomation = true,
            enableServerVersionUpdates = true,
            enablePluginUpdates = true,
            enableTemplateSync = true,
            enableNotifications = false,
            enableBackup = true,
            updateInterval = "04:00",
            updateTime = "04:00",
            serverSoftware = listOf("paper", "leaf", "velocityctd"),
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

    private fun checkIfUpdateNeeded(): Boolean {
        val lastUpdateFile = File(DirectoryPaths.paths.storagePath + "last_update.txt")

        return if (lastUpdateFile.exists()) {
            val lastUpdate = lastUpdateFile.readText().toLongOrNull() ?: 0L
            val dayInMillis = 24 * 60 * 60 * 1000
            System.currentTimeMillis() - lastUpdate > dayInMillis
        } else {
            true
        }
    }

    fun saveConfig() {
        try {
            configFile.parentFile.mkdirs()
            JsonLib.fromObject(AutoManagerConfig.toJson(config)).saveAsFile(configFile)
        } catch (e: Exception) {
            println("[AutoManager] Error saving config: ${e.message}")
        }
    }

    suspend fun forceUpdate(): Boolean {
        println("[AutoManager] Force update requested")

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