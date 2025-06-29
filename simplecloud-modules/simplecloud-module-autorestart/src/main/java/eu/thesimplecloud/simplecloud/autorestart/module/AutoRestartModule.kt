package eu.thesimplecloud.simplecloud.autorestart.module

import eu.thesimplecloud.api.external.ICloudModule
import eu.thesimplecloud.launcher.startup.Launcher
import eu.thesimplecloud.simplecloud.autorestart.command.AutoRestartCommand
import eu.thesimplecloud.simplecloud.autorestart.config.AutoRestartConfig
import eu.thesimplecloud.simplecloud.autorestart.config.loader.AutoRestartConfigLoader
import eu.thesimplecloud.simplecloud.autorestart.server.core.AutoRestartManager

class AutoRestartModule : ICloudModule {

    private lateinit var configLoader: AutoRestartConfigLoader
    private lateinit var config: AutoRestartConfig
    lateinit var autoRestartManager: AutoRestartManager

    override fun onEnable() {
        instance = this
        
        configLoader = AutoRestartConfigLoader()
        config = configLoader.loadConfig()
        
        autoRestartManager = AutoRestartManager(config)
        
        Launcher.instance.commandManager.registerCommand(this, AutoRestartCommand(this))
        
        if (config.enabled) {
            autoRestartManager.startScheduler()
        }
    }

    override fun onDisable() {
        autoRestartManager.shutdown()
    }

    fun reloadConfig() {
        config = configLoader.loadConfig()
        autoRestartManager.updateConfig(config)
    }

    fun saveConfig() {
        configLoader.saveConfig(config)
    }

    companion object {
        lateinit var instance: AutoRestartModule
            private set
    }
}