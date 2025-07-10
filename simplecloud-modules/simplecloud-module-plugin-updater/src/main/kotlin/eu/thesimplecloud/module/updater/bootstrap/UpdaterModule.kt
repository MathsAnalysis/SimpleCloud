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
        .build()
    
    override fun onEnable() {
        loadConfig()
        initializeComponents()
        
        if (config.enabled) {
            startUpdateScheduler()
        }
        
        registerCommands()
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
        updateJob = updateScope.launch {
            while (isActive) {
                runUpdate()
                delay(config.updateIntervalHours * 60 * 60 * 1000L)
            }
        }
    }
    
    private suspend fun runUpdate() {
        try {
            if (config.updateServerJars) {
                val serverResults = serverVersionUpdater.updateAllServerJars()
                serverResults.forEach { (type, success) ->
                    if (success) {
                        println("Updated $type successfully")
                    }
                }
            }
            
            if (config.updatePlugins) {
                val pluginResults = pluginUpdater.updatePlugins(config.plugins)
                pluginResults.forEach { (name, success) ->
                    if (success) {
                        println("Updated plugin $name successfully")
                    }
                }
                
                if (config.syncPluginsToTemplates) {
                    pluginUpdater.syncPluginsToTemplates()
                }
            }
        } catch (e: Exception) {
            println("Update failed: ${e.message}")
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
    
    fun getStatus(): UpdateStatus {
        return UpdateStatus(
            enabled = config.enabled,
            lastUpdate = System.currentTimeMillis(),
            nextUpdate = System.currentTimeMillis() + (config.updateIntervalHours * 60 * 60 * 1000L)
        )
    }
    
    data class UpdateStatus(
        val enabled: Boolean,
        val lastUpdate: Long,
        val nextUpdate: Long
    )
}