package eu.thesimplecloud.module.updater.command

import eu.thesimplecloud.api.command.ICommandSender
import eu.thesimplecloud.launcher.console.command.CommandType
import eu.thesimplecloud.launcher.console.command.ICommandHandler
import eu.thesimplecloud.launcher.console.command.annotations.Command
import eu.thesimplecloud.launcher.console.command.annotations.CommandSubPath
import eu.thesimplecloud.module.updater.UpdaterModule
import eu.thesimplecloud.module.updater.updater.manager.PluginManager
import eu.thesimplecloud.module.updater.updater.manager.ServerJarManager
import eu.thesimplecloud.module.updater.updater.manager.TemplateSyncManager
import kotlinx.coroutines.runBlocking

@Command("updater", CommandType.CONSOLE_AND_INGAME, "updater")
class UpdaterCommand(
    private val module: UpdaterModule
) : ICommandHandler {

    @CommandSubPath("", "Mostra informazioni sul modulo")
    fun handleHelp(commandSender: ICommandSender) {
        commandSender.sendMessage("=== SimpleCloud Updater ===")
        commandSender.sendMessage("/updater status - Mostra stato")
        commandSender.sendMessage("/updater force - Forza aggiornamento completo")
        commandSender.sendMessage("/updater force servers - Aggiorna solo server JARs")
        commandSender.sendMessage("/updater force plugins - Aggiorna solo plugins")
        commandSender.sendMessage("/updater sync - Sincronizza con template")
        commandSender.sendMessage("/updater reload - Ricarica configurazione")
    }

    @CommandSubPath("status", "Mostra stato updater")
    fun handleStatus(commandSender: ICommandSender) {
        val config = module.config
        commandSender.sendMessage("=== Stato Updater ===")
        commandSender.sendMessage("Abilitato: ${config.enabled}")
        commandSender.sendMessage("Intervallo: ${config.updateIntervalHours} ore")
        commandSender.sendMessage("Server JARs: ${config.serverJars.enabled}")
        commandSender.sendMessage("Plugins: ${config.plugins.enabled}")
    }

    @CommandSubPath("force", "Forza aggiornamento completo")
    fun handleForce(commandSender: ICommandSender) {
        commandSender.sendMessage("Avvio aggiornamento forzato...")
        runBlocking {
            module.updateService.runFullUpdate()
        }
        commandSender.sendMessage("Aggiornamento completato!")
    }

    @CommandSubPath("force servers", "Aggiorna solo server JARs")
    fun handleForceServers(commandSender: ICommandSender) {
        commandSender.sendMessage("Aggiornamento server JARs...")
        runBlocking {
            val manager = ServerJarManager()
            val results = manager.updateAll(module.config.serverJars)

            commandSender.sendMessage("=== Risultati ===")
            results.forEach { (name, success) ->
                commandSender.sendMessage("$name: ${if (success) "SUCCESS" else "FAILED"}")
            }

            TemplateSyncManager().syncServerJars()
        }
    }

    @CommandSubPath("force plugins", "Aggiorna solo plugins")
    fun handleForcePlugins(commandSender: ICommandSender) {
        commandSender.sendMessage("Aggiornamento plugins...")
        runBlocking {
            val manager = PluginManager()
            val results = manager.updateAll(module.config.plugins)

            commandSender.sendMessage("=== Risultati ===")
            results.forEach { (name, success) ->
                commandSender.sendMessage("$name: ${if (success) "SUCCESS" else "FAILED"}")
            }

            if (module.config.plugins.syncToTemplates) {
                TemplateSyncManager().syncPlugins()
            }
        }
    }

    @CommandSubPath("sync", "Sincronizza con template")
    fun handleSync(commandSender: ICommandSender) {
        commandSender.sendMessage("Sincronizzazione con template...")
        val syncManager = TemplateSyncManager()
        syncManager.syncServerJars()
        syncManager.syncPlugins()
        commandSender.sendMessage("Sincronizzazione completata!")
    }

    @CommandSubPath("reload", "Ricarica configurazione")
    fun handleReload(commandSender: ICommandSender) {
        commandSender.sendMessage("Ricaricamento configurazione...")
        module.reloadConfig()
        commandSender.sendMessage("Configurazione ricaricata!")
    }
}