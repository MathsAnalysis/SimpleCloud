package eu.thesimplecloud.module.updater.manager


import eu.thesimplecloud.api.external.ICloudModule
import eu.thesimplecloud.module.updater.api.AutoManagerAPI
import eu.thesimplecloud.module.updater.config.AutoManagerConfig
import eu.thesimplecloud.module.updater.thread.UpdateScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

class PluginUpdaterModule : ICloudModule {

    companion object {
        lateinit var instance: PluginUpdaterModule
            private set

        val INSTANCE: PluginUpdaterModule
            get() = instance
    }

    private lateinit var config: AutoManagerConfig
    private lateinit var serverVersionManager: ServerVersionManager
    private lateinit var pluginManager: PluginManager
    private lateinit var templateManager: TemplateManager
    private lateinit var updateScheduler: UpdateScheduler
    private lateinit var api: AutoManagerAPI

    private val moduleScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onEnable() {
        instance = this

        try {
            loadConfiguration()
            initializeManagers()
            startAutomationSystem()
            registerAPI()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDisable() {
        try {
            moduleScope.cancel()
            updateScheduler.shutdown()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadConfiguration() {
        val configFile = File("modules/automanager", "config.yml")
        config = AutoManagerConfig.load(configFile)
    }

    private fun initializeManagers() {
        serverVersionManager = ServerVersionManager(this, config)
        pluginManager = PluginManager(this, config)
        templateManager = TemplateManager(this, config)
        updateScheduler = UpdateScheduler(this, config)
    }

    private fun startAutomationSystem() {
        if (config.enableAutomation) {
            updateScheduler.start()
        }
    }

    private fun registerAPI() {
        api = AutoManagerAPI(this)
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
            }

            success
        } catch (e: Exception) {
            false
        }
    }

    fun getServerVersionManager() = serverVersionManager
    fun getPluginManager() = pluginManager
    fun getTemplateManager() = templateManager
    fun getUpdateScheduler() = updateScheduler
    fun getConfig() = config
    fun getAPI() = api

    fun reloadConfig() {
        try {
            loadConfiguration()
            updateScheduler.updateConfig(config)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}