package eu.thesimplecloud.module.updater.bootstrap

import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.api.external.ICloudModule
import eu.thesimplecloud.jsonlib.JsonLib
import eu.thesimplecloud.module.updater.api.UpdaterAPI
import eu.thesimplecloud.module.updater.config.AutoManagerConfig
import eu.thesimplecloud.module.updater.manager.PluginManager
import eu.thesimplecloud.module.updater.manager.ServerVersionManager
import eu.thesimplecloud.module.updater.manager.TemplateManager
import eu.thesimplecloud.module.updater.thread.UpdateScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File

class PluginUpdaterModule : ICloudModule {

    private val configFile = File(DirectoryPaths.paths.modulesPath + "automanager/auto-manager-config.json")

    private lateinit var config: AutoManagerConfig
    private lateinit var serverVersionManager: ServerVersionManager
    private lateinit var pluginManager: PluginManager
    private lateinit var templateManager: TemplateManager
    private lateinit var updateScheduler: UpdateScheduler
    private lateinit var updaterAPI: UpdaterAPI

    private val moduleScope = CoroutineScope(Dispatchers.IO)

    override fun onEnable() {
        println("[AutoManager] Inizializzazione modulo...")

        try {
            loadConfig()
            initializeManagers()

            if (config.enableAutomation) {
                updateScheduler.start()
                println("[AutoManager] Automazione avviata")
            }

            println("[AutoManager] Modulo caricato con successo!")
        } catch (e: Exception) {
            println("[AutoManager] Errore durante l'inizializzazione: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun onDisable() {
        println("[AutoManager] Spegnimento modulo...")

        try {
            if (::updateScheduler.isInitialized) {
                updateScheduler.shutdown()
            }
            println("[AutoManager] Modulo spento correttamente")
        } catch (e: Exception) {
            println("[AutoManager] Errore durante lo spegnimento: ${e.message}")
        }
    }

    private fun loadConfig() {
        println("[AutoManager] Cercando config in: ${configFile.absolutePath}")

        config = if (configFile.exists()) {
            try {
                println("[AutoManager] Config trovata, carico...")
                val jsonData = JsonLib.fromJsonFile(configFile)!!
                AutoManagerConfig.fromJson(jsonData)
            } catch (e: Exception) {
                println("[AutoManager] Errore caricamento config, uso default: ${e.message}")
                e.printStackTrace()
                createDefaultConfig()
            }
        } else {
            println("[AutoManager] Config non trovata, creo default")
            createDefaultConfig()
        }

        saveConfig()
        println("[AutoManager] Config caricata con ${config.plugins.size} plugin configurati")
    }

    private fun createDefaultConfig(): AutoManagerConfig {
        return AutoManagerConfig(
            enableAutomation = true,
            enableServerVersionUpdates = true,
            enablePluginUpdates = true,
            enableTemplateSync = true,
            enableNotifications = false,
            enableBackup = true,
            updateInterval = "6h",
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

    private fun saveConfig() {
        try {
            if (!configFile.parentFile.exists()) {
                configFile.parentFile.mkdirs()
                println("[AutoManager] Creata directory: ${configFile.parentFile.absolutePath}")
            }

            val jsonData = config.toJson()
            JsonLib.fromObject(jsonData).saveAsFile(configFile)
            println("[AutoManager] Config salvata in: ${configFile.absolutePath}")
        } catch (e: Exception) {
            println("[AutoManager] Errore salvataggio config: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun initializeManagers() {
        serverVersionManager = ServerVersionManager(this, config)
        pluginManager = PluginManager(this, config)
        templateManager = TemplateManager(this, config)
        updateScheduler = UpdateScheduler(this, config)
        updaterAPI = UpdaterAPI(this)
    }

    fun reloadConfig() {
        try {
            loadConfig()
            updateScheduler.updateConfig(config)
            println("[AutoManager] Config ricaricata")
        } catch (e: Exception) {
            println("[AutoManager] Errore ricarica config: ${e.message}")
        }
    }

    suspend fun runManualUpdate(): Boolean {
        return try {
            var success = true

            if (config.enableServerVersionUpdates) {
                success = serverVersionManager.updateAllVersions() && success
            }

            if (config.enablePluginUpdates) {
                success = pluginManager.updateAllPlugins() && success
            }

            if (config.enableTemplateSync) {
                success = templateManager.syncAllTemplates() && success
                success = templateManager.syncStaticServersOnRestart() && success
            }

            success
        } catch (e: Exception) {
            println("[AutoManager] Errore update manuale: ${e.message}")
            false
        }
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
            "scheduler_running" to updateScheduler.isRunning(),
            "next_update" to updateScheduler.getNextUpdateTime(),
            "configured_plugins" to config.plugins.size,
            "enabled_plugins" to config.plugins.count { it.enabled },
            "server_software" to config.serverSoftware.size,
            "update_interval" to config.updateInterval
        )
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