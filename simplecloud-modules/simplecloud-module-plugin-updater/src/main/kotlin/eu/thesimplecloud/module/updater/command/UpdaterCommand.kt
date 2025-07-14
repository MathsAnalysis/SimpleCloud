package eu.thesimplecloud.module.updater.command

import eu.thesimplecloud.api.CloudAPI
import eu.thesimplecloud.api.command.ICommandSender
import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.launcher.console.command.CommandType
import eu.thesimplecloud.launcher.console.command.ICommandHandler
import eu.thesimplecloud.launcher.console.command.annotations.Command
import eu.thesimplecloud.launcher.console.command.annotations.CommandSubPath
import eu.thesimplecloud.module.updater.bootstrap.UpdaterModule
import kotlinx.coroutines.runBlocking
import java.io.File

@Command("updater", CommandType.CONSOLE_AND_INGAME, "updater", ["update", "autoupdate"])
class UpdaterCommand(
    private val module: UpdaterModule
) : ICommandHandler {

    @CommandSubPath("force", "Force immediate update")
    fun forceUpdate(commandSender: ICommandSender) {
        commandSender.sendMessage("§6Starting forced update...")
        module.forceUpdate()
        commandSender.sendMessage("§aUpdate process started. Check console for progress.")
    }

    @CommandSubPath("force servers", "Force update server JARs only")
    fun forceUpdateServers(commandSender: ICommandSender) {
        commandSender.sendMessage("§6Starting forced server JAR update...")
        module.forceUpdateServers()
        commandSender.sendMessage("§aServer JAR update process started.")
    }

    @CommandSubPath("force plugins", "Force update plugins only")
    fun forceUpdatePlugins(commandSender: ICommandSender) {
        commandSender.sendMessage("§6Starting forced plugin update...")
        module.forceUpdatePlugins()
        commandSender.sendMessage("§aPlugin update process started.")
    }

    @CommandSubPath("sync templates", "Force sync JARs and plugins to templates")
    fun syncTemplates(commandSender: ICommandSender) {
        commandSender.sendMessage("§6Starting manual template synchronization...")

        try {
            module.forceSyncToTemplates()
            commandSender.sendMessage("§aTemplate synchronization completed successfully!")
        } catch (e: Exception) {
            commandSender.sendMessage("§cError during template synchronization: ${e.message}")
        }
    }

    @CommandSubPath("sync jars", "Force sync JARs to templates only")
    fun syncJars(commandSender: ICommandSender) {
        commandSender.sendMessage("§6Starting JAR synchronization to templates...")

        try {
            module.getServerVersionUpdater().syncJarsToTemplates()
            commandSender.sendMessage("§aJAR synchronization completed successfully!")
        } catch (e: Exception) {
            commandSender.sendMessage("§cError during JAR synchronization: ${e.message}")
        }
    }

    @CommandSubPath("status", "Show updater status")
    fun status(commandSender: ICommandSender) {
        try {
            val status = module.getStatus()
            val config = module.getConfig()

            commandSender.sendMessage("§6=== Updater Status ===")
            commandSender.sendMessage("§aEnabled: ${status.enabled}")
            commandSender.sendMessage("§aUpdate Server JARs: ${config.updateServerJars}")
            commandSender.sendMessage("§aUpdate Plugins: ${config.updatePlugins}")
            commandSender.sendMessage("§aSync Plugins to Templates: ${config.syncPluginsToTemplates}")
            commandSender.sendMessage("§aUpdate Interval: ${config.updateIntervalHours} hours")
            commandSender.sendMessage("§aConfigured Plugins: ${config.plugins.size}")
            commandSender.sendMessage("§aEnabled Plugins: ${config.plugins.count { it.enabled }}")

        } catch (e: Exception) {
            commandSender.sendMessage("§cError getting status: ${e.message}")
        }
    }

    @CommandSubPath("check", "Check for available updates")
    fun checkUpdates(commandSender: ICommandSender) {
        commandSender.sendMessage("§6Checking for available updates...")

        runBlocking {
            try {
                val updates = module.checkForUpdates()

                commandSender.sendMessage("§6=== Available Updates ===")
                if (updates.isEmpty()) {
                    commandSender.sendMessage("§aNo server JARs found to check")
                } else {
                    updates.forEach { (name, info) ->
                        commandSender.sendMessage("§a$name:")
                        commandSender.sendMessage("  §7Current: ${info.currentVersion}")
                        commandSender.sendMessage("  §7Latest: ${info.latestVersion}")
                    }
                }

            } catch (e: Exception) {
                commandSender.sendMessage("§cError checking updates: ${e.message}")
            }
        }
    }

    @CommandSubPath("status templates", "Show template synchronization status")
    fun statusTemplates(commandSender: ICommandSender) {
        try {
            val templatesPath = File(DirectoryPaths.paths.templatesPath)
            val minecraftJarsPath = File(DirectoryPaths.paths.minecraftJarsPath)

            commandSender.sendMessage("§6=== Template Synchronization Status ===")
            commandSender.sendMessage("§aTemplates path: ${templatesPath.absolutePath}")
            commandSender.sendMessage("§aMinecraft JARs path: ${minecraftJarsPath.absolutePath}")

            commandSender.sendMessage("§6Available JARs in minecraftjars:")
            val jars = minecraftJarsPath.listFiles()?.filter { it.name.endsWith(".jar") } ?: emptyList()
            if (jars.isEmpty()) {
                commandSender.sendMessage("§7  No JAR files found")
            } else {
                jars.forEach { jar ->
                    commandSender.sendMessage("§7  - ${jar.name} (${jar.length()} bytes)")
                }
            }

            commandSender.sendMessage("§6Template Status:")
            val templates = templatesPath.listFiles()?.filter { it.isDirectory } ?: emptyList()
            if (templates.isEmpty()) {
                commandSender.sendMessage("§7  No templates found")
            } else {
                templates.forEach { templateDir ->
                    val jarCount = templateDir.listFiles()?.count { it.name.endsWith(".jar") } ?: 0
                    val hasServerJar = File(templateDir, "server.jar").exists()

                    commandSender.sendMessage("§a${templateDir.name}:")
                    commandSender.sendMessage("  §7JAR files: $jarCount")
                    commandSender.sendMessage("  §7Has server.jar: $hasServerJar")
                }
            }

        } catch (e: Exception) {
            commandSender.sendMessage("§cError getting template status: ${e.message}")
        }
    }

    @CommandSubPath("status jars", "Show JAR status in minecraftjars")
    fun statusJars(commandSender: ICommandSender) {
        try {
            val minecraftJarsPath = File(DirectoryPaths.paths.minecraftJarsPath)

            commandSender.sendMessage("§6=== JAR Status ===")
            commandSender.sendMessage("§aMinecraft JARs path: ${minecraftJarsPath.absolutePath}")

            if (!minecraftJarsPath.exists()) {
                commandSender.sendMessage("§cMinecraft JARs directory does not exist!")
                return
            }

            val jarTypes = mapOf(
                "LEAF_" to "Leaf",
                "PAPER_" to "Paper",
                "VELOCITY_" to "Velocity",
                "VELOCITYCTD_" to "VelocityCTD"
            )

            jarTypes.forEach { (prefix, name) ->
                val latestJar = module.getJarManager().getLatestJar(prefix)
                if (latestJar != null) {
                    commandSender.sendMessage("§a$name: ${latestJar.name} (${latestJar.length()} bytes)")
                } else {
                    commandSender.sendMessage("§7$name: Not found")
                }
            }

        } catch (e: Exception) {
            commandSender.sendMessage("§cError getting JAR status: ${e.message}")
        }
    }

    @CommandSubPath("status versions", "Show registered service versions")
    fun statusVersions(commandSender: ICommandSender) {
        try {
            val serviceVersionHandler = CloudAPI.instance.getServiceVersionHandler()
            val versions = serviceVersionHandler.getAllVersions()

            commandSender.sendMessage("§6=== Registered Service Versions ===")

            val leafVersions = versions.filter { it.name.startsWith("Leaf") }
            val paperVersions = versions.filter { it.name.startsWith("Paper") }
            val velocityVersions = versions.filter { it.name.startsWith("Velocity") }

            commandSender.sendMessage("§aLeaf versions (${leafVersions.size}):")
            leafVersions.forEach { commandSender.sendMessage("  §7- ${it.name}") }

            commandSender.sendMessage("§aPaper versions (${paperVersions.size}):")
            paperVersions.forEach { commandSender.sendMessage("  §7- ${it.name}") }

            commandSender.sendMessage("§aVelocity versions (${velocityVersions.size}):")
            velocityVersions.forEach { commandSender.sendMessage("  §7- ${it.name}") }

            commandSender.sendMessage("§6Total versions: ${versions.size}")

        } catch (e: Exception) {
            commandSender.sendMessage("§cError getting service versions: ${e.message}")
        }
    }

    @CommandSubPath("config", "Show current configuration")
    fun showConfig(commandSender: ICommandSender) {
        try {
            val config = module.getConfig()

            commandSender.sendMessage("§6=== Updater Configuration ===")
            commandSender.sendMessage("§aEnabled: ${config.enabled}")
            commandSender.sendMessage("§aUpdate Server JARs: ${config.updateServerJars}")
            commandSender.sendMessage("§aUpdate Plugins: ${config.updatePlugins}")
            commandSender.sendMessage("§aSync Plugins to Templates: ${config.syncPluginsToTemplates}")
            commandSender.sendMessage("§aUpdate Interval: ${config.updateIntervalHours} hours")

            commandSender.sendMessage("§6Server Versions:")
            commandSender.sendMessage("  §aUpdate Leaf: ${config.serverVersions.updateLeaf}")
            commandSender.sendMessage("  §aUpdate Paper: ${config.serverVersions.updatePaper}")
            commandSender.sendMessage("  §aUpdate Velocity: ${config.serverVersions.updateVelocity}")
            commandSender.sendMessage("  §aUpdate VelocityCTD: ${config.serverVersions.updateVelocityCtd}")

            commandSender.sendMessage("§6Configured Plugins (${config.plugins.size}):")
            config.plugins.forEach { plugin ->
                val status = if (plugin.enabled) "§aEnabled" else "§7Disabled"
                commandSender.sendMessage("  §7- ${plugin.name}: $status")
            }

        } catch (e: Exception) {
            commandSender.sendMessage("§cError showing config: ${e.message}")
        }
    }
}