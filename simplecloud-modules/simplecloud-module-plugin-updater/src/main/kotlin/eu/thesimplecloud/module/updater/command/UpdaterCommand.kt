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

    @CommandSubPath("force servers", "Force update only server JARs")
    fun forceUpdateServers(commandSender: ICommandSender) {
        commandSender.sendMessage("§6Starting forced server JAR update...")
        module.forceUpdateServers()
        commandSender.sendMessage("§aServer JAR update process started. Check console for progress.")
    }

    @CommandSubPath("force plugins", "Force update only plugins")
    fun forceUpdatePlugins(commandSender: ICommandSender) {
        commandSender.sendMessage("§6Starting forced plugin update...")
        module.forceUpdatePlugins()
        commandSender.sendMessage("§aPlugin update process started. Check console for progress.")
    }

    @CommandSubPath("sync", "Force sync JARs and plugins to templates")
    fun forceSyncToTemplates(commandSender: ICommandSender) {
        commandSender.sendMessage("§6Starting forced template synchronization...")
        try {
            module.forceSyncToTemplates()
            commandSender.sendMessage("§aTemplate synchronization completed successfully!")
        } catch (e: Exception) {
            commandSender.sendMessage("§cError during template synchronization: ${e.message}")
        }
    }

    @CommandSubPath("register-versions", "Force register service versions from existing JARs")
    fun forceRegisterVersions(commandSender: ICommandSender) {
        commandSender.sendMessage("§6Starting forced service version registration...")

        try {
            module.forceRegisterVersions()
            commandSender.sendMessage("§aService version registration completed. Check console for details.")
        } catch (e: Exception) {
            commandSender.sendMessage("§cError during service version registration: ${e.message}")
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
            val config = module.getConfig()
            val status = module.getStatus()

            commandSender.sendMessage("§6=== Updater Status ===")
            commandSender.sendMessage("§aEnabled: ${config.enabled}")
            commandSender.sendMessage("§aUpdate Server JARs: ${config.updateServerJars}")
            commandSender.sendMessage("§aUpdate Plugins: ${config.updatePlugins}")
            commandSender.sendMessage("§aSync Plugins to Templates: ${config.syncPluginsToTemplates}")
            commandSender.sendMessage("§aUpdate Interval: ${config.updateIntervalHours} hours")
            commandSender.sendMessage("§aConfigured Plugins: ${config.plugins.size}")
            commandSender.sendMessage("§aEnabled Plugins: ${config.plugins.count { it.enabled }}")

            commandSender.sendMessage("§6=== Update Timing ===")
            val lastUpdateFormatted = java.time.Instant.ofEpochMilli(status.lastUpdate)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val nextUpdateFormatted = java.time.Instant.ofEpochMilli(status.nextUpdate)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

            commandSender.sendMessage("§aLast Update: $lastUpdateFormatted")
            commandSender.sendMessage("§aNext Update: $nextUpdateFormatted")

        } catch (e: Exception) {
            commandSender.sendMessage("§cError getting status: ${e.message}")
        }
    }

    @CommandSubPath("check", "Check for available updates")
    fun checkForUpdates(commandSender: ICommandSender) {
        commandSender.sendMessage("§6Checking for available updates...")

        try {
            runBlocking {
                val updates = module.checkForUpdates()

                if (updates.isEmpty()) {
                    commandSender.sendMessage("§aNo update information available.")
                    return@runBlocking
                }

                commandSender.sendMessage("§6=== Available Updates ===")
                updates.forEach { (name, info) ->
                    commandSender.sendMessage("§a$name:")
                    commandSender.sendMessage("  §7Current: ${info.currentVersion}")
                    commandSender.sendMessage("  §7Latest: ${info.latestVersion}")
                }
            }
        } catch (e: Exception) {
            commandSender.sendMessage("§cError checking for updates: ${e.message}")
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
                templates.forEach { template ->
                    val jarCount = template.listFiles()?.count { it.name.endsWith(".jar") } ?: 0
                    commandSender.sendMessage("§7  - ${template.name}: $jarCount JAR(s)")
                }
            }

        } catch (e: Exception) {
            commandSender.sendMessage("§cError getting template status: ${e.message}")
        }
    }

    @CommandSubPath("status versions", "Show registered service versions")
    fun statusVersions(commandSender: ICommandSender) {
        try {
            val serviceVersionHandler = CloudAPI.instance.getServiceVersionHandler()
            val allVersions = serviceVersionHandler.getAllVersions()

            commandSender.sendMessage("§6=== Registered Service Versions ===")
            commandSender.sendMessage("§aTotal versions: ${allVersions.size}")

            val updaterVersions = allVersions.filter {
                it.name.startsWith("LEAF_") || it.name.startsWith("PAPER_") ||
                        it.name.startsWith("VELOCITY_") || it.name.startsWith("VELOCITYCTD_")
            }

            if (updaterVersions.isEmpty()) {
                commandSender.sendMessage("§7No updater-managed versions found")
            } else {
                commandSender.sendMessage("§6Updater-managed versions (${updaterVersions.size}):")
                updaterVersions.groupBy { version ->
                    when {
                        version.name.startsWith("LEAF_") -> "Leaf"
                        version.name.startsWith("PAPER_") -> "Paper"
                        version.name.startsWith("VELOCITY_") -> "Velocity"
                        version.name.startsWith("VELOCITYCTD_") -> "VelocityCTD"
                        else -> "Other"
                    }
                }.forEach { (type, versions) ->
                    commandSender.sendMessage("§a$type (${versions.size}):")
                    versions.forEach { version ->
                        commandSender.sendMessage("  §7- ${version.name}")
                    }
                }
            }

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

    @CommandSubPath("debug", "Show debug information")
    fun debug(commandSender: ICommandSender) {
        try {
            commandSender.sendMessage("§6=== Debug Information ===")

            commandSender.sendMessage("§6Important Paths:")
            commandSender.sendMessage("  §7Storage: ${DirectoryPaths.paths.storagePath}")
            commandSender.sendMessage("  §7Minecraft JARs: ${DirectoryPaths.paths.minecraftJarsPath}")
            commandSender.sendMessage("  §7Templates: ${DirectoryPaths.paths.templatesPath}")

            val localVersionsFile = File(DirectoryPaths.paths.storagePath + "localServiceVersions.json")
            commandSender.sendMessage("§6Configuration Files:")
            commandSender.sendMessage("  §7localServiceVersions.json: ${if (localVersionsFile.exists()) "§aExists (${localVersionsFile.length()} bytes)" else "§cMissing"}")

            val serviceVersionHandler = CloudAPI.instance.getServiceVersionHandler()
            commandSender.sendMessage("§6Service Version Handler:")
            commandSender.sendMessage("  §7Type: ${serviceVersionHandler::class.java.simpleName}")
            commandSender.sendMessage("  §7Total versions: ${serviceVersionHandler.getAllVersions().size}")

            commandSender.sendMessage("§6=== End Debug ===")

        } catch (e: Exception) {
            commandSender.sendMessage("§cError getting debug info: ${e.message}")
            e.printStackTrace()
        }
    }

    @CommandSubPath("sync plugins", "Force sync only plugins to templates")
    fun syncPlugins(commandSender: ICommandSender) {
        commandSender.sendMessage("§6Starting plugin synchronization to templates...")

        try {
            val pluginUpdater = module.getPluginUpdater()
            if (module.getConfig().syncPluginsToTemplates) {
                pluginUpdater.syncPluginsToTemplates()
                commandSender.sendMessage("§aPlugin synchronization completed successfully!")
            } else {
                commandSender.sendMessage("§7Plugin sync to templates is disabled in configuration.")
            }
        } catch (e: Exception) {
            commandSender.sendMessage("§cError during plugin synchronization: ${e.message}")
        }
    }

    @CommandSubPath("status plugins", "Show plugin status and information")
    fun statusPlugins(commandSender: ICommandSender) {
        try {
            val config = module.getConfig()
            val pluginUpdater = module.getPluginUpdater()

            commandSender.sendMessage("§6=== Plugin Status ===")
            commandSender.sendMessage("§aPlugin updates enabled: ${config.updatePlugins}")
            commandSender.sendMessage("§aSync to templates enabled: ${config.syncPluginsToTemplates}")
            commandSender.sendMessage("§aTotal configured plugins: ${config.plugins.size}")
            commandSender.sendMessage("§aEnabled plugins: ${config.plugins.count { it.enabled }}")

            commandSender.sendMessage("§6Plugin Details:")
            config.plugins.forEach { plugin ->
                val status = if (plugin.enabled) "§aEnabled" else "§7Disabled"
                commandSender.sendMessage("  §7- ${plugin.name}: $status")
                commandSender.sendMessage("    §7Platforms: ${plugin.platforms.joinToString(", ")}")
                plugin.downloadUrl?.let { url ->
                    commandSender.sendMessage("    §7URL: $url")
                }
                plugin.fileName?.let { fileName ->
                    commandSender.sendMessage("    §7File: $fileName")
                }
            }

        } catch (e: Exception) {
            commandSender.sendMessage("§cError getting plugin status: ${e.message}")
        }
    }

    @CommandSubPath("test plugins", "Test plugin download connections")
    fun testPlugins(commandSender: ICommandSender) {
        commandSender.sendMessage("§6Testing plugin download connections...")

        try {
            val config = module.getConfig()
            val enabledPlugins = config.plugins.filter { it.enabled }

            if (enabledPlugins.isEmpty()) {
                commandSender.sendMessage("§7No enabled plugins to test.")
                return
            }

            commandSender.sendMessage("§6Testing ${enabledPlugins.size} enabled plugins:")

            enabledPlugins.forEach { plugin ->
                commandSender.sendMessage("§7Testing ${plugin.name}...")
                try {
                    val url = java.net.URL(plugin.downloadUrl)
                    val connection = url.openConnection()
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.connect()

                    commandSender.sendMessage("  §a✓ ${plugin.name}: Connection successful")
                } catch (e: Exception) {
                    commandSender.sendMessage("  §c✗ ${plugin.name}: ${e.message}")
                }
            }

        } catch (e: Exception) {
            commandSender.sendMessage("§cError testing plugins: ${e.message}")
        }
    }

    @CommandSubPath("status jars", "Show JAR file status")
    fun statusJars(commandSender: ICommandSender) {
        try {
            val jarManager = module.getJarManager()

            commandSender.sendMessage("§6=== JAR Status ===")

            val jarTypes = mapOf(
                "LEAF_" to "Leaf",
                "PAPER_" to "Paper",
                "VELOCITY_" to "Velocity",
                "VELOCITYCTD_" to "VelocityCTD"
            )

            jarTypes.forEach { (prefix, name) ->
                val latestJar = jarManager.getLatestJar(prefix)
                if (latestJar != null && latestJar.exists()) {
                    val sizeInMB = latestJar.length() / (1024 * 1024)
                    commandSender.sendMessage("§a$name: ${latestJar.name} (${sizeInMB}MB)")
                } else {
                    commandSender.sendMessage("§7$name: Not found")
                }
            }

        } catch (e: Exception) {
            commandSender.sendMessage("§cError getting JAR status: ${e.message}")
        }
    }
}