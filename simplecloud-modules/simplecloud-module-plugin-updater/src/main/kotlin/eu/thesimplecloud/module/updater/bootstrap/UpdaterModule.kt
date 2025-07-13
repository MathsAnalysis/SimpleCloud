package eu.thesimplecloud.module.updater

import eu.thesimplecloud.api.external.ICloudModule
import eu.thesimplecloud.launcher.startup.Launcher
import eu.thesimplecloud.module.updater.command.UpdaterCommand
import eu.thesimplecloud.module.updater.config.UpdaterConfig
import eu.thesimplecloud.module.updater.updater.config.UpdaterConfigLoader
import eu.thesimplecloud.module.updater.updater.scheduler.UpdateScheduler
import eu.thesimplecloud.module.updater.updater.service.UpdateService
import kotlinx.coroutines.*

class UpdaterModule : ICloudModule {

    lateinit var config: UpdaterConfig
        private set

    lateinit var updateService: UpdateService
        private set

    private lateinit var configLoader: UpdaterConfigLoader
    private lateinit var updateScheduler: UpdateScheduler

    private val moduleScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onEnable() {
        instance = this

        println("[UpdaterModule] Inizializzazione modulo...")

        configLoader = UpdaterConfigLoader()
        config = configLoader.loadConfig()

        updateService = UpdateService(config)

        Launcher.instance.commandManager.registerCommand(this, UpdaterCommand(this))

        if (config.enabled) {
            updateScheduler = UpdateScheduler(this)
            updateScheduler.start()

            moduleScope.launch {
                delay(5000)
                println("[UpdaterModule] Esecuzione aggiornamento iniziale...")
                updateService.runFullUpdate()
            }
        }

        println("[UpdaterModule] Modulo caricato con successo!")
    }

    override fun onDisable() {
        println("[UpdaterModule] Arresto modulo...")

        if (::updateScheduler.isInitialized) {
            updateScheduler.stop()
        }

        moduleScope.cancel()

        println("[UpdaterModule] Modulo arrestato correttamente")
    }

    fun reloadConfig() {
        config = configLoader.loadConfig()
        updateService.updateConfig(config)

        if (::updateScheduler.isInitialized) {
            updateScheduler.stop()
        }

        if (config.enabled) {
            updateScheduler = UpdateScheduler(this)
            updateScheduler.start()
        }
    }

    fun saveConfig() {
        configLoader.saveConfig(config)
    }

    companion object {
        lateinit var instance: UpdaterModule
            private set
    }
}