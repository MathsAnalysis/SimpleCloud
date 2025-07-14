package eu.thesimplecloud.module.updater.command

import eu.thesimplecloud.api.command.ICommandSender
import eu.thesimplecloud.launcher.console.command.CommandType
import eu.thesimplecloud.launcher.console.command.ICommandHandler
import eu.thesimplecloud.launcher.console.command.annotations.Command
import eu.thesimplecloud.launcher.console.command.annotations.CommandArgument
import eu.thesimplecloud.launcher.console.command.annotations.CommandSubPath
import eu.thesimplecloud.module.updater.bootstrap.UpdaterModule
import kotlinx.coroutines.runBlocking

@Command("updater", CommandType.CONSOLE_AND_INGAME, "updater", ["update", "autoupdate"])
class UpdaterCommand(
    private val module: UpdaterModule
) : ICommandHandler {

    @CommandSubPath("force", "Force immediate update")
    fun forceUpdate(commandSender: ICommandSender) {
        commandSender.sendMessage("Starting forced update...")
        module.forceUpdate()
        commandSender.sendMessage("Update process started. Check console for progress.")
    }

    @CommandSubPath("force servers", "Force update server JARs only")
    fun forceUpdateServers(commandSender: ICommandSender) {
        commandSender.sendMessage("Starting forced server JAR update...")
        module.forceUpdateServers()
        commandSender.sendMessage("Server JAR update process started.")
    }

    @CommandSubPath("force plugins", "Force update plugins only")
    fun forceUpdatePlugins(commandSender: ICommandSender) {
        commandSender.sendMessage("Starting forced plugin update...")
        module.forceUpdatePlugins()
        commandSender.sendMessage("Plugin update process started.")
    }

    @CommandSubPath("status", "Show updater status")
    fun showStatus(commandSender: ICommandSender) {
        val status = module.getStatus()
        commandSender.sendMessage("=== Updater Status ===")
        commandSender.sendMessage("Enabled: ${status.enabled}")
        commandSender.sendMessage("Last Update: ${java.util.Date(status.lastUpdate)}")
        commandSender.sendMessage("Next Update: ${java.util.Date(status.nextUpdate)}")
    }

    @CommandSubPath("check", "Check for available updates")
    fun checkUpdates(commandSender: ICommandSender) {
        commandSender.sendMessage("Checking for updates...")
        runBlocking {
            val results = module.checkForUpdates()
            commandSender.sendMessage("=== Available Updates ===")
            results.forEach { (software, info) ->
                commandSender.sendMessage("$software: ${info.currentVersion} -> ${info.latestVersion}")
            }
        }
    }

    @CommandSubPath("download <software>", "Download specific server software")
    fun downloadSpecific(
        commandSender: ICommandSender,
        @CommandArgument("software") software: String
    ) {
        commandSender.sendMessage("Downloading $software...")
        runBlocking {
            val success = module.downloadSpecific(software)
            if (success) {
                commandSender.sendMessage("Successfully downloaded $software")
            } else {
                commandSender.sendMessage("Failed to download $software")
            }
        }
    }

    @CommandSubPath("clean", "Clean old JAR versions")
    fun cleanOldVersions(commandSender: ICommandSender) {
        commandSender.sendMessage("Cleaning old JAR versions...")
        module.cleanOldVersions()
        commandSender.sendMessage("Old versions cleaned successfully")
    }

    @CommandSubPath("sync", "Sync JARs and plugins to templates")
    fun syncTemplates(commandSender: ICommandSender) {
        commandSender.sendMessage("Syncing JARs and plugins to templates...")
        module.syncToTemplates()
        commandSender.sendMessage("Templates synchronized successfully")
    }

    @CommandSubPath("list plugins", "List configured plugins")
    fun listPlugins(commandSender: ICommandSender) {
        val config = module.getConfig()
        commandSender.sendMessage("=== Configured Plugins ===")
        config.plugins.forEach { plugin ->
            val status = if (plugin.enabled) "ENABLED" else "DISABLED"
            val platforms = plugin.platforms.joinToString(", ")
            commandSender.sendMessage("- ${plugin.name} [$status] (${platforms})")
        }
    }
}