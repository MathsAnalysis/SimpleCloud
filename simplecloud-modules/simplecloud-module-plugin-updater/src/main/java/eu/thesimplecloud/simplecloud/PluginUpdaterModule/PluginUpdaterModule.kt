package eu.thesimplecloud.module.automanager

import eu.thesimplecloud.api.CloudAPI
import eu.thesimplecloud.api.external.ICloudModule
import eu.thesimplecloud.module.api.CloudModule
import eu.thesimplecloud.module.automanager.config.AutoManagerConfig
import eu.thesimplecloud.module.automanager.manager.ServerVersionManager
import eu.thesimplecloud.module.automanager.manager.PluginManager
import eu.thesimplecloud.module.automanager.manager.TemplateManager
import eu.thesimplecloud.module.automanager.manager.UpdateScheduler
import eu.thesimplecloud.module.automanager.api.AutoManagerAPI
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.TimeUnit

class AutoManagerModule : ICloudModule {

    companion object {
        lateinit var instance: AutoManagerModule
            private set

        val INSTANCE: AutoManagerModule
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

        loadConfiguration()

        initializeManagers()

        startAutomationSystem()

        registerAPI()
    }

    override fun onDisable() {

        moduleScope.cancel()
        updateScheduler.shutdown()
    }

    private fun loadConfiguration() {
        val configFile = File(getModuleFolder(), "config.yml")
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

        return success
    }

    fun getServerVersionManager() = serverVersionManager
    fun getPluginManager() = pluginManager
    fun getTemplateManager() = templateManager
    fun getUpdateScheduler() = updateScheduler
    fun getConfig() = config
    fun getAPI() = api

    fun reloadConfig() {
        loadConfiguration()
        updateScheduler.updateConfig(config)
    }
}