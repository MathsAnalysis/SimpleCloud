package eu.thesimplecloud.module.updater.plugin

import eu.thesimplecloud.api.CloudAPI
import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.api.service.ServiceType
import eu.thesimplecloud.api.servicegroup.ICloudServiceGroup
import eu.thesimplecloud.module.updater.config.AutoManagerConfig
import java.io.File

class TemplatePluginManager(private val config: AutoManagerConfig) {

    private val pluginsDirectory = File(DirectoryPaths.paths.storagePath + "plugins")
    private val templatesDirectory = File(DirectoryPaths.paths.templatesPath)
    private val logsDirectory = File(DirectoryPaths.paths.modulesPath + "automanager/logs")
    private val logFile = File(logsDirectory, "template-plugin-manager-${System.currentTimeMillis()}.log")

    init {
        if (!logsDirectory.exists()) {
            logsDirectory.mkdirs()
        }
    }

    private fun log(message: String) {
        val timestamp = java.time.LocalDateTime.now()
        val logMessage = "[$timestamp] $message"
        println("[TemplatePluginManager] $message")
        try {
            logFile.appendText("$logMessage\n")
        } catch (e: Exception) {
            println("[TemplatePluginManager] Failed to write to log: ${e.message}")
        }
    }

    fun syncPluginsToTemplates() {
        log("Starting plugin sync to templates")

        if (!pluginsDirectory.exists()) {
            log("Plugins directory not found: ${pluginsDirectory.absolutePath}")
            return
        }

        val serviceGroups = CloudAPI.instance.getCloudServiceGroupManager().getAllCachedObjects()

        serviceGroups.forEach { group ->
            syncPluginsToGroup(group)
        }

        syncPluginsToEveryTemplates()

        log("Plugin sync to templates completed")
    }

    private fun syncPluginsToGroup(group: ICloudServiceGroup) {
        val groupTemplate = group.getTemplateName()
        val templateDir = File(templatesDirectory, groupTemplate)

        if (!templateDir.exists()) {
            log("Template directory not found for ${group.getName()}: ${templateDir.absolutePath}")
            return
        }

        val pluginsDir = File(templateDir, "plugins")
        pluginsDir.mkdirs()

        val platform = when {
            group.getServiceType() == ServiceType.PROXY -> {
                when (group.getServiceVersion().name.lowercase()) {
                    in listOf("bungeecord", "waterfall", "hexacord", "flamecord") -> "bungeecord"
                    in listOf("velocity", "velocityctd") -> "velocity"
                    else -> {
                        log("Unknown proxy type for ${group.getName()}: ${group.getServiceVersion().name}")
                        return
                    }
                }
            }
            else -> "bukkit"
        }

        log("Syncing plugins to group ${group.getName()} (platform: $platform)")

        pluginsDirectory.listFiles()?.filter { it.isDirectory }?.forEach { pluginDir ->
            val pluginName = pluginDir.name

            val isEnabled = config.plugins.any {
                it.name.equals(pluginName, ignoreCase = true) && it.enabled
            }

            if (!isEnabled) {
                log("Skipping disabled plugin: $pluginName")
                return@forEach
            }

            val platformDir = File(pluginDir, platform)

            if (platformDir.exists() && platformDir.isDirectory) {
                platformDir.listFiles()?.filter { it.extension == "jar" }?.forEach { jarFile ->
                    try {
                        val targetFile = File(pluginsDir, jarFile.name)

                        if (!targetFile.exists() || jarFile.lastModified() > targetFile.lastModified()) {
                            log("Copying ${jarFile.name} to template ${groupTemplate}")
                            jarFile.copyTo(targetFile, overwrite = true)
                        } else {
                            log("Plugin ${jarFile.name} already up to date in template ${groupTemplate}")
                        }
                    } catch (e: Exception) {
                        log("ERROR copying plugin ${jarFile.name} to template: ${e.message}")
                    }
                }
            } else {
                log("Platform directory not found: ${platformDir.absolutePath}")
                if (platform == "velocity") {
                    log("Checking for BungeeCord compatibility for plugin $pluginName")
                    val bungeecordDir = File(pluginDir, "bungeecord")
                    if (bungeecordDir.exists() && bungeecordDir.isDirectory) {
                        log("WARNING: Using BungeeCord plugin for Velocity (may not be compatible): $pluginName")
                        bungeecordDir.listFiles()?.filter { it.extension == "jar" }?.forEach { jarFile ->
                            try {
                                val targetFile = File(pluginsDir, jarFile.name)
                                log("Copying BungeeCord plugin ${jarFile.name} for Velocity use (compatibility not guaranteed)")
                                jarFile.copyTo(targetFile, overwrite = true)
                            } catch (e: Exception) {
                                log("ERROR copying BungeeCord plugin ${jarFile.name}: ${e.message}")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun syncPluginsToEveryTemplates() {
//        val everyDir = File(templatesDirectory, "EVERY_SERVER")
//        if (everyDir.exists()) {
//            syncPluginsToDirectory(everyDir, "bukkit")
//        }

        val everyServerDir = File(templatesDirectory, "EVERY_SERVER")
        if (everyServerDir.exists()) {
            syncPluginsToDirectory(everyServerDir, "bukkit")
        }

        val everyProxyDir = File(templatesDirectory, "EVERY_PROXY")
        if (everyProxyDir.exists()) {
            syncPluginsToEveryProxyDirectory(everyProxyDir)
        }
    }

    private fun syncPluginsToEveryProxyDirectory(templateDir: File) {
        val pluginsDir = File(templateDir, "plugins")
        pluginsDir.mkdirs()

        log("Syncing plugins to ${templateDir.name} template (multi-platform proxy)")

        val proxyPlatforms = CloudAPI.instance.getCloudServiceGroupManager()
            .getAllCachedObjects()
            .filter { it.getServiceType() == ServiceType.PROXY }
            .map { group ->
                when (group.getServiceVersion().name.lowercase()) {
                    in listOf("velocity", "velocityctd") -> "velocity"
                    in listOf("bungeecord", "waterfall", "hexacord", "flamecord") -> "bungeecord"
                    else -> "bungeecord"
                }
            }.distinct()

        log("Detected proxy platforms in use: $proxyPlatforms")

        pluginsDirectory.listFiles()?.filter { it.isDirectory }?.forEach { pluginDir ->
            val pluginName = pluginDir.name

            val isEnabled = config.plugins.any {
                it.name.equals(pluginName, ignoreCase = true) && it.enabled
            }

            if (!isEnabled) {
                log("Skipping disabled plugin: $pluginName")
                return@forEach
            }

            var pluginCopied = false
            for (platform in proxyPlatforms) {
                val platformDir = File(pluginDir, platform)

                if (platformDir.exists() && platformDir.isDirectory) {
                    platformDir.listFiles()?.filter { it.extension == "jar" }?.forEach { jarFile ->
                        try {
                            val targetFile = File(pluginsDir, jarFile.name)

                            if (!targetFile.exists() || jarFile.lastModified() > targetFile.lastModified()) {
                                log("Copying ${jarFile.name} to ${templateDir.name} (platform: $platform)")
                                jarFile.copyTo(targetFile, overwrite = true)
                                pluginCopied = true
                            }
                        } catch (e: Exception) {
                            log("ERROR copying plugin ${jarFile.name}: ${e.message}")
                        }
                    }
                    if (pluginCopied) break
                }
            }

            if (!pluginCopied) {
                log("No compatible platform found for plugin $pluginName in EVERY_PROXY")
            }
        }
    }

    private fun syncPluginsToDirectory(templateDir: File, defaultPlatform: String) {
        val pluginsDir = File(templateDir, "plugins")
        pluginsDir.mkdirs()

        log("Syncing plugins to ${templateDir.name} template (platform: $defaultPlatform)")

        pluginsDirectory.listFiles()?.filter { it.isDirectory }?.forEach { pluginDir ->
            val pluginName = pluginDir.name

            val isEnabled = config.plugins.any {
                it.name.equals(pluginName, ignoreCase = true) && it.enabled
            }

            if (!isEnabled) {
                log("Skipping disabled plugin: $pluginName")
                return@forEach
            }

            val platformsToCheck = if (templateDir.name == "EVERY_PROXY") {
                listOf("bungeecord", "velocity")
            } else {
                listOf(defaultPlatform)
            }

            for (platform in platformsToCheck) {
                val platformDir = File(pluginDir, platform)

                if (platformDir.exists() && platformDir.isDirectory) {
                    platformDir.listFiles()?.filter { it.extension == "jar" }?.forEach { jarFile ->
                        try {
                            val targetFile = File(pluginsDir, jarFile.name)

                            if (!targetFile.exists() || jarFile.lastModified() > targetFile.lastModified()) {
                                log("Copying ${jarFile.name} to ${templateDir.name}")
                                jarFile.copyTo(targetFile, overwrite = true)
                            }
                        } catch (e: Exception) {
                            log("ERROR copying plugin ${jarFile.name}: ${e.message}")
                        }
                    }
                    break
                }
            }
        }
    }

    fun cleanOldPluginVersions() {
        log("Cleaning old plugin versions from templates")

        val allTemplateDirs = mutableListOf<File>()

        CloudAPI.instance.getCloudServiceGroupManager().getAllCachedObjects().forEach { group ->
            val templateDir = File(templatesDirectory, group.getTemplateName())
            if (templateDir.exists()) {
                allTemplateDirs.add(templateDir)
            }
        }

        listOf("EVERY_SERVER", "EVERY_PROXY").forEach { name ->
            val dir = File(templatesDirectory, name)
            if (dir.exists()) {
                allTemplateDirs.add(dir)
            }
        }

        allTemplateDirs.forEach { templateDir ->
            val pluginsDir = File(templateDir, "plugins")
            if (pluginsDir.exists()) {
                cleanPluginsDirectory(pluginsDir)
            }
        }
    }

    private fun cleanPluginsDirectory(pluginsDir: File) {
        log("Cleaning plugins directory: ${pluginsDir.absolutePath}")

        val pluginFiles = mutableMapOf<String, MutableList<File>>()

        pluginsDir.listFiles()?.filter { it.extension == "jar" }?.forEach { file ->
            val pluginName = extractPluginName(file.name)
            pluginFiles.getOrPut(pluginName) { mutableListOf() }.add(file)
        }

        pluginFiles.forEach { (pluginName, files) ->
            if (files.size > 1) {
                log("Found ${files.size} versions of $pluginName")
                files.sortByDescending { it.lastModified() }

                files.drop(1).forEach { oldFile ->
                    log("Removing old version: ${oldFile.name}")
                    oldFile.delete()
                }
            }
        }
    }

    private fun extractPluginName(filename: String): String {
        return when {
            filename.contains("LuckPerms", ignoreCase = true) -> "LuckPerms"
            filename.contains("spark", ignoreCase = true) -> "spark"
            filename.contains("ProtocolLib", ignoreCase = true) -> "ProtocolLib"
            filename.contains("PlaceholderAPI", ignoreCase = true) -> "PlaceholderAPI"
            filename.contains("Floodgate", ignoreCase = true) -> "Floodgate"
            filename.contains("Geyser", ignoreCase = true) -> "Geyser"
            filename.contains("Vault", ignoreCase = true) -> "Vault"
            else -> filename.substringBefore("-").substringBefore(".")
        }
    }

    fun createPluginDirectoryStructure() {
        log("Creating correct plugin directory structure...")

        config.plugins.forEach { pluginConfig ->
            if (pluginConfig.enabled) {
                val pluginDir = File(pluginsDirectory, pluginConfig.name)

                pluginConfig.platforms.forEach { platform ->
                    val platformDir = File(pluginDir, platform)
                    if (!platformDir.exists()) {
                        platformDir.mkdirs()
                        log("Created directory: ${platformDir.absolutePath}")
                    }
                }
            }
        }

        log("Plugin directory structure creation completed")
    }

    fun debugPluginStructure() {
        log("=== DEBUG: Plugin Directory Structure ===")

        if (!pluginsDirectory.exists()) {
            log("Plugins directory does not exist: ${pluginsDirectory.absolutePath}")
            return
        }

        pluginsDirectory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                log("Plugin directory: ${file.name}")
                file.listFiles()?.forEach { platformDir ->
                    if (platformDir.isDirectory) {
                        val jarCount = platformDir.listFiles()?.count { it.extension == "jar" } ?: 0
                        log("  Platform: ${platformDir.name} ($jarCount JAR files)")
                        platformDir.listFiles()?.filter { it.extension == "jar" }?.forEach { jar ->
                            log("    - ${jar.name}")
                        }
                    } else {
                        log("  File (should be in platform folder): ${platformDir.name}")
                    }
                }
            } else {
                log("File in plugins root (should be in platform folder): ${file.name}")
            }
        }

        log("=== END DEBUG ===")
    }
}