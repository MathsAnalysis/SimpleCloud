package eu.thesimplecloud.module.updater.command

import eu.thesimplecloud.launcher.console.command.CommandType
import eu.thesimplecloud.launcher.console.command.ICommandHandler
import eu.thesimplecloud.launcher.console.command.annotations.Command
import eu.thesimplecloud.launcher.console.command.annotations.CommandSubPath
import eu.thesimplecloud.launcher.startup.Launcher
import eu.thesimplecloud.module.updater.bootstrap.PluginUpdaterModule
import kotlinx.coroutines.runBlocking

@Command("updater", CommandType.CONSOLE_AND_INGAME, "cloud.command.updater")
class UpdaterCommand(private val module: PluginUpdaterModule) : ICommandHandler {

    @CommandSubPath("info", "Shows updater information")
    fun handleInfo() {
        val stats = module.getStats()
        
        Launcher.instance.consoleSender.sendMessage("§7=========== §bAutoUpdater Info §7===========")
        Launcher.instance.consoleSender.sendMessage("§7Status: ${if (stats["enabled"] as Boolean) "§aEnabled" else "§cDisabled"}")
        Launcher.instance.consoleSender.sendMessage("§7Next Update: §b${stats["next_update"]}")
        Launcher.instance.consoleSender.sendMessage("§7Update Time: §b${stats["update_time"]}")
        Launcher.instance.consoleSender.sendMessage("§7Configured Plugins: §b${stats["configured_plugins"]}")
        Launcher.instance.consoleSender.sendMessage("§7Enabled Plugins: §b${stats["enabled_plugins"]}")
        Launcher.instance.consoleSender.sendMessage("§7Server Software: §b${stats["server_software"]}")
        Launcher.instance.consoleSender.sendMessage("§7======================================")
    }
    
    @CommandSubPath("versions", "Shows current server versions")
    fun handleVersions() {
        val versions = module.checkVersions()
        
        Launcher.instance.consoleSender.sendMessage("§7========= §bServer Versions §7=========")
        versions.forEach { (name, version) ->
            Launcher.instance.consoleSender.sendMessage("§7$name: §b$version")
        }
        Launcher.instance.consoleSender.sendMessage("§7==================================")
    }
    
    @CommandSubPath("force", "Forces an immediate update")
    fun handleForceUpdate() {
        Launcher.instance.consoleSender.sendMessage("§7Starting forced update...")
        
        runBlocking {
            val success = module.forceUpdate()
            
            if (success) {
                Launcher.instance.consoleSender.sendMessage("§aForced update completed successfully!")
            } else {
                Launcher.instance.consoleSender.sendMessage("§cForced update failed! Check logs for details.")
            }
        }
    }
    
    @CommandSubPath("reload", "Reloads the configuration")
    fun handleReload() {
        try {
            // Re-initialize the module
            module.onDisable()
            module.onEnable()
            
            Launcher.instance.consoleSender.sendMessage("§aConfiguration reloaded successfully!")
        } catch (e: Exception) {
            Launcher.instance.consoleSender.sendMessage("§cFailed to reload configuration: ${e.message}")
        }
    }
    
    @CommandSubPath("enable", "Enables automatic updates")
    fun handleEnable() {
        module.getConfig().enableAutomation = true
        Launcher.instance.consoleSender.sendMessage("§aAutomatic updates enabled!")
    }
    
    @CommandSubPath("disable", "Disables automatic updates")
    fun handleDisable() {
        module.getConfig().enableAutomation = false
        module.getUpdateScheduler().shutdown()
        Launcher.instance.consoleSender.sendMessage("§cAutomatic updates disabled!")
    }
}