package eu.thesimplecloud.module.updater.bootstrap

import eu.thesimplecloud.api.CloudAPI
import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.api.external.ICloudModule
import eu.thesimplecloud.api.service.version.ServiceVersion
import eu.thesimplecloud.api.service.version.loader.LocalServiceVersionHandler
import eu.thesimplecloud.api.service.version.type.ServiceAPIType
import eu.thesimplecloud.base.manager.serviceversion.ManagerServiceVersionHandler
import eu.thesimplecloud.launcher.startup.Launcher
import eu.thesimplecloud.module.updater.command.UpdaterCommand
import eu.thesimplecloud.module.updater.config.UpdaterConfig
import eu.thesimplecloud.module.updater.manager.JarManager
import eu.thesimplecloud.module.updater.updater.PluginUpdater
import eu.thesimplecloud.module.updater.updater.ServerVersionUpdater
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class UpdaterModule : ICloudModule {

    private lateinit var config: UpdaterConfig
    private lateinit var jarManager: JarManager
    private lateinit var serverVersionUpdater: ServerVersionUpdater
    private lateinit var pluginUpdater: PluginUpdater
    private lateinit var localServiceVersionHandler: LocalServiceVersionHandler

    private val updateScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var updateJob: Job? = null

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun onEnable() {
        println("[UpdaterModule] Starting...")

        loadConfig()
        initializeComponents()
        registerCommands()

        if (config.enabled) {
            updateScope.launch {
                delay(5000)
                println("[UpdaterModule] Running initial update...")
                runUpdate()
            }

            startUpdateScheduler()
        }
    }

    override fun onDisable() {
        updateJob?.cancel()
        updateScope.cancel()
        serverVersionUpdater.shutdown()
    }

    private fun loadConfig() {
        val configFile = File(DirectoryPaths.paths.modulesPath + "updater/config.json")
        config = if (configFile.exists()) {
            UpdaterConfig.fromFile(configFile)
        } else {
            UpdaterConfig.createDefault().also { it.save(configFile) }
        }
    }

    private fun initializeComponents() {
        jarManager = JarManager(okHttpClient)
        serverVersionUpdater = ServerVersionUpdater(jarManager)
        pluginUpdater = PluginUpdater(okHttpClient)
        localServiceVersionHandler = LocalServiceVersionHandler()
    }

    private fun startUpdateScheduler() {
        println("[UpdaterModule] Starting update scheduler (every ${config.updateIntervalHours} hours)")

        updateJob = updateScope.launch {
            while (isActive) {
                delay(config.updateIntervalHours * 60 * 60 * 1000L)

                println("[UpdaterModule] Running scheduled update...")
                runUpdate()
            }
        }
    }

    private suspend fun runUpdate() {
        println("[UpdaterModule] === STARTING UPDATE PROCESS ===")

        var updateResult = false

        if (config.updateServerJars) {
            println("[UpdaterModule] Updating server JARs...")
            val results = serverVersionUpdater.updateAllServerJars()

            println("[UpdaterModule] Server JAR Update Results:")
            results.forEach { (type, success) ->
                println("[UpdaterModule] - $type: ${if (success) "SUCCESS ✓" else "FAILED ✗"}")
                if (success) updateResult = true
            }

            if (updateResult) {
                registerServiceVersions()
            }

            println("[UpdaterModule] Syncing JARs to templates...")
            serverVersionUpdater.syncJarsToTemplates()
        }

        if (config.updatePlugins) {
            println("[UpdaterModule] Updating plugins...")
            val pluginResults = pluginUpdater.updateAllPlugins(config.plugins)

            println("[UpdaterModule] Plugin Update Results:")
            pluginResults.forEach { (name, success) ->
                println("[UpdaterModule] - $name: ${if (success) "SUCCESS ✓" else "FAILED ✗"}")
            }

            if (config.syncPluginsToTemplates) {
                println("[UpdaterModule] Syncing plugins to templates...")
                pluginUpdater.syncPluginsToTemplates()
            }
        }

        println("[UpdaterModule] === UPDATE PROCESS COMPLETED ===")
    }

    private fun registerServiceVersions() {
        try {
            println("[UpdaterModule] Registering service versions...")

            registerJarVersions("LEAF_", ServiceAPIType.SPIGOT, false)
            registerJarVersions("PAPER_", ServiceAPIType.SPIGOT, true)
            registerJarVersions("VELOCITY_", ServiceAPIType.VELOCITY, false)
            registerJarVersions("VELOCITYCTD_", ServiceAPIType.VELOCITY, false)

            reloadServiceVersions()

            println("[UpdaterModule] Service version registration completed")
        } catch (e: Exception) {
            println("[UpdaterModule] Error registering service versions: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun registerJarVersions(prefix: String, serviceAPIType: ServiceAPIType, isPaperClip: Boolean) {
        jarManager.getLatestJar(prefix)?.let { latestJar ->
            val version = extractVersionFromJarName(latestJar.name, prefix)
            if (version != null) {
                registerVersion(prefix, version, latestJar.toURI().toString(), serviceAPIType, isPaperClip)
            }
        }
    }

    private fun registerVersion(prefix: String, version: String, downloadUrl: String, serviceAPIType: ServiceAPIType, isPaperClip: Boolean) {
        try {
            val formattedVersion = version.replace(".", "_").replace("-", "_")
            val serviceName = "$prefix$formattedVersion"

            val serviceVersion = ServiceVersion(
                name = serviceName,
                serviceAPIType = serviceAPIType,
                downloadURL = downloadUrl,
                isPaperClip = isPaperClip
            )
            localServiceVersionHandler.saveServiceVersion(serviceVersion)
            println("[UpdaterModule] Registered version: $serviceName")

        } catch (e: Exception) {
            println("[UpdaterModule] Error registering version $prefix$version: ${e.message}")
        }
    }

    private fun reloadServiceVersions() {
        try {
            val serviceVersionHandler = CloudAPI.instance.getServiceVersionHandler()
            if (serviceVersionHandler is ManagerServiceVersionHandler) {
                serviceVersionHandler.reloadServiceVersions()
                println("[UpdaterModule] Service versions reloaded successfully")
            }
        } catch (e: Exception) {
            println("[UpdaterModule] Error reloading service versions: ${e.message}")
        }
    }

    private fun extractVersionFromJarName(jarName: String, prefix: String): String? {
        return try {
            jarName.substringAfter(prefix)
                .substringBefore(".jar")
                .replace("_", ".")
        } catch (e: Exception) {
            null
        }
    }

    private fun registerCommands() {
        Launcher.instance.commandManager.registerCommand(this, UpdaterCommand(this))
    }

    fun forceUpdate() {
        updateScope.launch {
            runUpdate()
        }
    }

    fun forceUpdateServers() {
        updateScope.launch {
            if (config.updateServerJars) {
                serverVersionUpdater.updateAllServerJars()
                registerServiceVersions()
                serverVersionUpdater.syncJarsToTemplates()
                println("[UpdaterModule] Server update completed")
            }
        }
    }

    fun forceUpdatePlugins() {
        updateScope.launch {
            if (config.updatePlugins) {
                pluginUpdater.updateAllPlugins(config.plugins)
                if (config.syncPluginsToTemplates) {
                    pluginUpdater.syncPluginsToTemplates()
                }
                println("[UpdaterModule] Plugin update completed")
            }
        }
    }

    fun forceSyncToTemplates() {
        try {
            println("[UpdaterModule] Starting manual template synchronization...")
            serverVersionUpdater.syncJarsToTemplates()
            if (config.syncPluginsToTemplates) {
                pluginUpdater.syncPluginsToTemplates()
            }
            println("[UpdaterModule] Manual template synchronization completed")
        } catch (e: Exception) {
            println("[UpdaterModule] Error during manual template synchronization: ${e.message}")
            e.printStackTrace()
        }
    }

    fun forceRegisterVersions() {
        registerServiceVersions()
    }

    fun getConfig(): UpdaterConfig = config
    fun getServerVersionUpdater(): ServerVersionUpdater = serverVersionUpdater
    fun getJarManager(): JarManager = jarManager
    fun getPluginUpdater(): PluginUpdater = pluginUpdater

    fun getStatus(): UpdateStatus {
        return UpdateStatus(
            enabled = config.enabled,
            lastUpdate = System.currentTimeMillis(),
            nextUpdate = System.currentTimeMillis() + (config.updateIntervalHours * 60 * 60 * 1000L)
        )
    }

    suspend fun checkForUpdates(): Map<String, UpdateInfo> = withContext(Dispatchers.IO) {
        val updates = mutableMapOf<String, UpdateInfo>()

        val jarTypes = mapOf(
            "LEAF_" to "Leaf",
            "PAPER_" to "Paper",
            "VELOCITY_" to "Velocity",
            "VELOCITYCTD_" to "VelocityCtd"
        )

        jarTypes.forEach { (prefix, displayName) ->
            jarManager.getLatestJar(prefix)?.let { currentJar ->
                val currentVersion = extractVersionFromJarName(currentJar.name, prefix) ?: "unknown"
                updates[displayName] = UpdateInfo(currentVersion, "Check for latest")
            }
        }

        updates
    }

    data class UpdateStatus(
        val enabled: Boolean,
        val lastUpdate: Long,
        val nextUpdate: Long
    )

    data class UpdateInfo(
        val currentVersion: String,
        val latestVersion: String
    )

    companion object {
        @JvmStatic
        lateinit var instance: UpdaterModule
            private set
    }

    init {
        instance = this
    }
}