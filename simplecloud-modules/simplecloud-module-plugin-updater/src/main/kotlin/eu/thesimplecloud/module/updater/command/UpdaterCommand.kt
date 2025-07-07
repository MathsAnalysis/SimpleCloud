package eu.thesimplecloud.module.updater.command

import eu.thesimplecloud.launcher.console.command.CommandType
import eu.thesimplecloud.launcher.console.command.ICommandHandler
import eu.thesimplecloud.launcher.console.command.annotations.Command
import eu.thesimplecloud.launcher.console.command.annotations.CommandSubPath
import eu.thesimplecloud.launcher.startup.Launcher
import eu.thesimplecloud.module.updater.bootstrap.PluginUpdaterModule
import eu.thesimplecloud.module.updater.utils.LoggingUtils
import kotlinx.coroutines.runBlocking

@Command("updater", CommandType.CONSOLE_AND_INGAME, "cloud.command.updater")
class UpdaterCommand(private val module: PluginUpdaterModule) : ICommandHandler {

    companion object {
        private const val TAG = "UpdaterCommand"
    }

    init {
        LoggingUtils.init(TAG, "UpdaterCommand registered")
    }

    @CommandSubPath("info", "Shows updater information")
    fun handleInfo() {
        LoggingUtils.debug(TAG, "Info command executed")

        try {
            val stats = module.getStats()
            val sender = Launcher.instance.consoleSender

            sender.sendMessage("§7=========== §bAutoUpdater Info §7===========")
            sender.sendMessage("§7Status: ${if (stats["enabled"] as Boolean) "§aEnabled" else "§cDisabled"}")
            sender.sendMessage("§7Debug Mode: ${if (stats["debug_mode"] as Boolean) "§aEnabled" else "§cDisabled"}")
            sender.sendMessage("§7Next Update: §b${stats["next_update"]}")
            sender.sendMessage("§7Update Time: §b${stats["update_time"]}")
            sender.sendMessage("§7Update Interval: §b${stats["update_interval"]}")
            sender.sendMessage("§7Configured Plugins: §b${stats["configured_plugins"]}")
            sender.sendMessage("§7Enabled Plugins: §b${stats["enabled_plugins"]}")
            sender.sendMessage("§7Server Software: §b${stats["server_software"]}")

            val automationFeatures = stats["automation_features"] as Map<String, Boolean>
            sender.sendMessage("§7")
            sender.sendMessage("§7=== §bAutomation Features §7===")
            sender.sendMessage("§7Server Version Updates: ${if (automationFeatures["server_version_updates"] == true) "§aEnabled" else "§cDisabled"}")
            sender.sendMessage("§7Plugin Updates: ${if (automationFeatures["plugin_updates"] == true) "§aEnabled" else "§cDisabled"}")
            sender.sendMessage("§7Template Sync: ${if (automationFeatures["template_sync"] == true) "§aEnabled" else "§cDisabled"}")
            sender.sendMessage("§7Notifications: ${if (automationFeatures["notifications"] == true) "§aEnabled" else "§cDisabled"}")
            sender.sendMessage("§7Backup: ${if (automationFeatures["backup"] == true) "§aEnabled" else "§cDisabled"}")
            sender.sendMessage("§7======================================")

            LoggingUtils.debug(TAG, "Info command completed successfully")
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error executing info command: ${e.message}", e)
            Launcher.instance.consoleSender.sendMessage("§cError retrieving updater information: ${e.message}")
        }
    }

    @CommandSubPath("versions", "Shows current server versions")
    fun handleVersions() {
        LoggingUtils.debug(TAG, "Versions command executed")

        try {
            val versions = module.checkVersions()
            val sender = Launcher.instance.consoleSender

            sender.sendMessage("§7========= §bServer Versions §7=========")

            if (versions.isEmpty()) {
                sender.sendMessage("§7No server versions available")
                LoggingUtils.debug(TAG, "No server versions found")
            } else {
                versions.forEach { (name, version) ->
                    sender.sendMessage("§7$name: §b$version")
                }
                LoggingUtils.debug(TAG, "Displayed ${versions.size} server versions")
            }

            sender.sendMessage("§7==================================")

            LoggingUtils.debug(TAG, "Versions command completed successfully")
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error executing versions command: ${e.message}", e)
            Launcher.instance.consoleSender.sendMessage("§cError retrieving server versions: ${e.message}")
        }
    }

    @CommandSubPath("force", "Forces an immediate update")
    fun handleForceUpdate() {
        LoggingUtils.info(TAG, "Force update command executed")

        val sender = Launcher.instance.consoleSender

        try {
            sender.sendMessage("§7Starting forced update...")
            LoggingUtils.debug(TAG, "Initiating force update...")

            val startTime = System.currentTimeMillis()

            runBlocking {
                val success = module.forceUpdate()
                val duration = System.currentTimeMillis() - startTime

                if (success) {
                    sender.sendMessage("§aForced update completed successfully! (${duration}ms)")
                    LoggingUtils.info(TAG, "Force update completed successfully in ${duration}ms")
                } else {
                    sender.sendMessage("§cForced update failed! Check logs for details.")
                    LoggingUtils.error(TAG, "Force update failed after ${duration}ms")
                }
            }
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error executing force update: ${e.message}", e)
            sender.sendMessage("§cError during forced update: ${e.message}")
        }
    }

    @CommandSubPath("reload", "Reloads the configuration")
    fun handleReload() {
        LoggingUtils.info(TAG, "Reload command executed")

        val sender = Launcher.instance.consoleSender

        try {
            sender.sendMessage("§7Reloading configuration...")
            LoggingUtils.debug(TAG, "Initiating configuration reload...")

            val startTime = System.currentTimeMillis()

            module.onDisable()
            module.onEnable()

            val duration = System.currentTimeMillis() - startTime

            sender.sendMessage("§aConfiguration reloaded successfully! (${duration}ms)")
            LoggingUtils.info(TAG, "Configuration reload completed successfully in ${duration}ms")

        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error reloading configuration: ${e.message}", e)
            sender.sendMessage("§cError reloading configuration: ${e.message}")
        }
    }

    @CommandSubPath("status", "Shows detailed status information")
    fun handleStatus() {
        LoggingUtils.debug(TAG, "Status command executed")

        try {
            val sender = Launcher.instance.consoleSender

            val moduleStats = module.getStats()
            val schedulerStats = module.getUpdateScheduler().getStats()
            val pluginStats = module.getPluginManager().getStats()
            val templateStats = module.getTemplateManager().getStats()

            sender.sendMessage("§7========== §bDetailed Status §7==========")

            sender.sendMessage("§7")
            sender.sendMessage("§e§lModule Status:")
            sender.sendMessage("§7  Enabled: ${if (moduleStats["enabled"] as Boolean) "§aYes" else "§cNo"}")
            sender.sendMessage("§7  Debug Mode: ${if (moduleStats["debug_mode"] as Boolean) "§aEnabled" else "§cDisabled"}")

            sender.sendMessage("§7")
            sender.sendMessage("§e§lScheduler Status:")
            sender.sendMessage("§7  Running: ${if (schedulerStats["is_running"] as Boolean) "§aYes" else "§cNo"}")
            sender.sendMessage("§7  Next Update: §b${schedulerStats["next_update_formatted"]}")
            sender.sendMessage("§7  Time Until Next: §b${schedulerStats["time_until_next_minutes"]} minutes")

            sender.sendMessage("§7")
            sender.sendMessage("§e§lPlugin Manager:")
            sender.sendMessage("§7  Total Configured: §b${pluginStats["total_configured"]}")
            sender.sendMessage("§7  Enabled: §b${pluginStats["enabled"]}")
            sender.sendMessage("§7  Downloaded: §b${pluginStats["downloaded"]}")

            sender.sendMessage("§7")
            sender.sendMessage("§e§lTemplate Manager:")
            sender.sendMessage("§7  Total Templates: §b${templateStats["total_templates"]}")

            val templateTypes = templateStats["template_types"] as Map<String, Int>
            if (templateTypes.isNotEmpty()) {
                sender.sendMessage("§7  Template Types:")
                templateTypes.forEach { (type, count) ->
                    sender.sendMessage("§7    $type: §b$count")
                }
            }

            sender.sendMessage("§7=====================================")

            LoggingUtils.debug(TAG, "Status command completed successfully")
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error executing status command: ${e.message}", e)
            Launcher.instance.consoleSender.sendMessage("§cError retrieving status information: ${e.message}")
        }
    }

    @CommandSubPath("debug", "Toggles debug mode or shows debug information")
    fun handleDebug() {
        LoggingUtils.debug(TAG, "Debug command executed")

        try {
            val sender = Launcher.instance.consoleSender
            val config = module.getConfig()

            sender.sendMessage("§7========== §bDebug Information §7==========")
            sender.sendMessage("§7Debug Mode: ${if (config.enableDebug) "§aEnabled" else "§cDisabled"}")

            if (config.enableDebug) {
                sender.sendMessage("§7")
                sender.sendMessage("§7Debug information is being logged to console.")
                sender.sendMessage("§7Check the console output for detailed debug messages.")

                sender.sendMessage("§7")
                sender.sendMessage("§e§lCurrent Debug Stats:")

                val moduleStats = module.getStats()
                sender.sendMessage("§7  Module enabled: ${moduleStats["enabled"]}")
                sender.sendMessage("§7  Configured plugins: ${moduleStats["configured_plugins"]}")
                sender.sendMessage("§7  Enabled plugins: ${moduleStats["enabled_plugins"]}")

                sender.sendMessage("§7")
                sender.sendMessage("§7Showing plugin directory structure in console...")
                module.getPluginManager().debugPluginStructure()
            } else {
                sender.sendMessage("§7")
                sender.sendMessage("§cDebug mode is disabled.")
                sender.sendMessage("§7To enable debug mode, edit the configuration file")
                sender.sendMessage("§7and set 'enableDebug' to true, then reload.")
            }

            sender.sendMessage("§7=======================================")

            LoggingUtils.debug(TAG, "Debug command completed successfully")
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error executing debug command: ${e.message}", e)
            Launcher.instance.consoleSender.sendMessage("§cError retrieving debug information: ${e.message}")
        }
    }

    @CommandSubPath("help", "Shows available commands")
    fun handleHelp() {
        LoggingUtils.debug(TAG, "Help command executed")

        val sender = Launcher.instance.consoleSender

        sender.sendMessage("§7========== §bUpdater Commands §7==========")
        sender.sendMessage("§e/updater info §7- Shows general updater information")
        sender.sendMessage("§e/updater status §7- Shows detailed status information")
        sender.sendMessage("§e/updater versions §7- Shows current server versions")
        sender.sendMessage("§e/updater force §7- Forces an immediate update")
        sender.sendMessage("§e/updater reload §7- Reloads the configuration")
        sender.sendMessage("§e/updater debug §7- Shows debug information")
        sender.sendMessage("§e/updater help §7- Shows this help message")
        sender.sendMessage("§7=====================================")

        LoggingUtils.debug(TAG, "Help command completed")
    }
}