package eu.thesimplecloud.module.updater.bootstrap

import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.api.external.ICloudModule
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

        if (config.updateServerJars) {
            println("[UpdaterModule] Updating server JARs...")
            val results = serverVersionUpdater.updateAllServerJars()

            println("[UpdaterModule] Server JAR Update Results:")
            results.forEach { (type, success) ->
                println("[UpdaterModule] - $type: ${if (success) "SUCCESS ✓" else "FAILED ✗"}")
            }
        }

        if (config.updatePlugins) {
            println("[UpdaterModule] Updating plugins...")
            val pluginResults = pluginUpdater.updateAllPlugins(config.plugins)

            println("[UpdaterModule] Plugin Update Results:")
            pluginResults.forEach { (name, success) ->
                println("[UpdaterModule] - $name: ${if (success) "SUCCESS ✓" else "FAILED ✗"}")
            }
        }

        println("[UpdaterModule] === UPDATE PROCESS COMPLETED ===")
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
                val results = serverVersionUpdater.updateAllServerJars()
                println("[UpdaterModule] Server update completed")
            }
        }
    }

    fun forceUpdatePlugins() {
        updateScope.launch {
            if (config.updatePlugins) {
                pluginUpdater.updateAllPlugins(config.plugins)
                println("[UpdaterModule] Plugin update completed")
            }
        }
    }

    fun getConfig(): UpdaterConfig = config

    fun getStatus(): UpdateStatus {
        return UpdateStatus(
            enabled = config.enabled,
            lastUpdate = System.currentTimeMillis(),
            nextUpdate = System.currentTimeMillis() + (config.updateIntervalHours * 60 * 60 * 1000L)
        )
    }

    suspend fun checkForUpdates(): Map<String, UpdateInfo> = withContext(Dispatchers.IO) {
        val updates = mutableMapOf<String, UpdateInfo>()

        jarManager.getLatestJar("LEAF_")?.let { currentJar ->
            val currentVersion = currentJar.name.substringAfter("LEAF_").substringBefore(".jar").replace("_", ".")
            updates["Leaf"] = UpdateInfo(currentVersion, "Check GitHub for latest")
        }

        jarManager.getLatestJar("VELOCITYCTD_")?.let { currentJar ->
            val currentVersion = currentJar.name.substringAfter("VELOCITYCTD_").substringBefore(".jar").replace("_", ".")
            updates["VelocityCtd"] = UpdateInfo(currentVersion, "Check GitHub for latest")
        }

        updates
    }

    suspend fun downloadSpecific(software: String): Boolean = withContext(Dispatchers.IO) {
        when (software.lowercase()) {
            "leaf" -> serverVersionUpdater.updateAllServerJars()["LEAF"] ?: false
            "velocityctd" -> serverVersionUpdater.updateAllServerJars()["VELOCITYCTD"] ?: false
            "paper" -> serverVersionUpdater.updateAllServerJars()["PAPER"] ?: false
            "velocity" -> serverVersionUpdater.updateAllServerJars()["VELOCITY"] ?: false
            else -> false
        }
    }

    fun cleanOldVersions() {
        jarManager.cleanupOldVersions("LEAF_", 1)
        jarManager.cleanupOldVersions("VELOCITYCTD_", 1)
        jarManager.cleanupOldVersions("PAPER_", 1)
        jarManager.cleanupOldVersions("VELOCITY_", 1)
    }

    fun syncToTemplates() {
        serverVersionUpdater.syncJarsToTemplates()
        pluginUpdater.syncPluginsToTemplates()
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
}