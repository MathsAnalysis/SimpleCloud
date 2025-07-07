package eu.thesimplecloud.module.updater.plugin

import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.module.updater.config.AutoManagerConfig
import eu.thesimplecloud.module.updater.utils.LoggingUtils
import java.io.File

class TemplatePluginManager(private val config: AutoManagerConfig) {

    companion object {
        private const val TAG = "TemplatePluginManager"
    }

    private val pluginsDirectory = File(DirectoryPaths.paths.storagePath + "plugins")
    private val templatesDirectory = File(DirectoryPaths.paths.templatesPath)

    init {
        LoggingUtils.init(TAG, "Initializing TemplatePluginManager...")
        initializeDirectories()
        logInitialConfiguration()
    }

    private fun initializeDirectories() {
        LoggingUtils.debug(TAG, "Ensuring necessary directories exist...")

        if (!pluginsDirectory.exists()) {
            pluginsDirectory.mkdirs()
            LoggingUtils.debug(TAG, "Created plugins directory: ${pluginsDirectory.absolutePath}")
        }

        if (!templatesDirectory.exists()) {
            templatesDirectory.mkdirs()
            LoggingUtils.debug(TAG, "Created templates directory: ${templatesDirectory.absolutePath}")
        }
    }

    private fun logInitialConfiguration() {
        LoggingUtils.debugConfig(TAG, "plugins_directory", pluginsDirectory.absolutePath)
        LoggingUtils.debugConfig(TAG, "templates_directory", templatesDirectory.absolutePath)
        LoggingUtils.debugConfig(TAG, "configured_plugins_count", config.plugins.size)
        LoggingUtils.debugConfig(TAG, "enabled_plugins_count", config.plugins.count { it.enabled })

        if (config.enableDebug) {
            val enabledPlugins = config.plugins.filter { it.enabled }.map { it.name }
            LoggingUtils.debugConfig(TAG, "enabled_plugins", enabledPlugins)
        }
    }

    fun createPluginDirectoryStructure() {
        LoggingUtils.debugStart(TAG, "creating plugin directory structure")

        var createdCount = 0
        var existingCount = 0

        try {
            LoggingUtils.debug(TAG, "Creating directory structure for ${config.plugins.size} plugins...")

            config.plugins.forEach { pluginConfig ->
                if (pluginConfig.enabled) {
                    val pluginDir = File(pluginsDirectory, pluginConfig.name)
                    LoggingUtils.debug(TAG, "Processing plugin: ${pluginConfig.name}")

                    pluginConfig.platforms.forEach { platform ->
                        val platformDir = File(pluginDir, platform)
                        if (!platformDir.exists()) {
                            platformDir.mkdirs()
                            LoggingUtils.debug(TAG, "Created directory: ${platformDir.absolutePath}")
                            createdCount++
                        } else {
                            LoggingUtils.debug(TAG, "Directory already exists: ${platformDir.absolutePath}")
                            existingCount++
                        }
                    }
                } else {
                    LoggingUtils.debug(TAG, "Plugin ${pluginConfig.name} is disabled, skipping directory creation")
                }
            }

            val stats = mapOf(
                "directories_created" to createdCount,
                "directories_existing" to existingCount,
                "total_plugins_processed" to config.plugins.size
            )
            LoggingUtils.debugStats(TAG, stats)

            LoggingUtils.debugSuccess(TAG, "creating plugin directory structure")
            LoggingUtils.info(TAG, "Plugin directory structure creation completed ($createdCount created, $existingCount existing)")

        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error creating plugin directory structure: ${e.message}", e)
        }
    }

    fun debugPluginStructure() {
        LoggingUtils.debug(TAG, "=== DEBUG: Plugin Directory Structure ===")

        if (!pluginsDirectory.exists()) {
            LoggingUtils.debug(TAG, "Plugins directory does not exist: ${pluginsDirectory.absolutePath}")
            return
        }

        try {
            var totalPlugins = 0
            var totalPlatforms = 0
            var totalJarFiles = 0

            pluginsDirectory.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    totalPlugins++
                    LoggingUtils.debug(TAG, "Plugin directory: ${file.name}")

                    file.listFiles()?.forEach { platformDir ->
                        if (platformDir.isDirectory) {
                            totalPlatforms++
                            val jarCount = platformDir.listFiles()?.count { it.extension == "jar" } ?: 0
                            totalJarFiles += jarCount

                            LoggingUtils.debug(TAG, "  Platform: ${platformDir.name} ($jarCount JAR files)")

                            if (config.enableDebug && jarCount > 0) {
                                platformDir.listFiles()?.filter { it.extension == "jar" }?.forEach { jar ->
                                    val sizeKB = jar.length() / 1024
                                    val lastModified = java.time.Instant.ofEpochMilli(jar.lastModified())
                                    LoggingUtils.debug(TAG, "    - ${jar.name} (${sizeKB}KB, modified: $lastModified)")
                                }
                            }
                        } else {
                            LoggingUtils.debug(TAG, "  File (should be in platform folder): ${platformDir.name}")
                        }
                    }
                } else {
                    LoggingUtils.debug(TAG, "File in plugins root (should be in platform folder): ${file.name}")
                }
            }

            val summary = mapOf(
                "total_plugin_directories" to totalPlugins,
                "total_platform_directories" to totalPlatforms,
                "total_jar_files" to totalJarFiles
            )
            LoggingUtils.debugStats(TAG, summary)

        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error debugging plugin structure: ${e.message}", e)
        }

        LoggingUtils.debug(TAG, "=== END DEBUG ===")
    }

    fun syncPluginsToTemplates(): Boolean {
        LoggingUtils.debugStart(TAG, "syncing plugins to templates")

        if (!pluginsDirectory.exists()) {
            LoggingUtils.warn(TAG, "Plugins directory does not exist, skipping sync")
            return true
        }

        if (!templatesDirectory.exists()) {
            LoggingUtils.warn(TAG, "Templates directory does not exist, skipping sync")
            return true
        }

        try {
            var success = true
            var templatesProcessed = 0
            var pluginsCopied = 0
            var errors = 0

            val templates = getAvailableTemplates()
            LoggingUtils.debug(TAG, "Found ${templates.size} templates to process")

            templates.forEach { template ->
                try {
                    LoggingUtils.debug(TAG, "Processing template: ${template.name} (type: ${template.type})")
                    templatesProcessed++

                    val templatePluginsDir = File(template.directory, "plugins")
                    if (!templatePluginsDir.exists()) {
                        templatePluginsDir.mkdirs()
                        LoggingUtils.debug(TAG, "Created plugins directory for template: ${template.name}")
                    }

                    val copiedCount = copyPluginsToTemplate(template, templatePluginsDir)
                    pluginsCopied += copiedCount

                    LoggingUtils.debug(TAG, "Copied $copiedCount plugins to template: ${template.name}")

                } catch (e: Exception) {
                    LoggingUtils.error(TAG, "Error processing template ${template.name}: ${e.message}", e)
                    success = false
                    errors++
                }
            }

            val stats = mapOf(
                "templates_processed" to templatesProcessed,
                "plugins_copied" to pluginsCopied,
                "errors" to errors,
                "success" to success
            )
            LoggingUtils.debugStats(TAG, stats)

            if (success) {
                LoggingUtils.debugSuccess(TAG, "syncing plugins to templates")
                LoggingUtils.info(TAG, "Successfully synced plugins to $templatesProcessed templates ($pluginsCopied plugins copied)")
            } else {
                LoggingUtils.debugFailure(TAG, "syncing plugins to templates", "$errors errors occurred")
            }

            return success

        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error syncing plugins to templates: ${e.message}", e)
            return false
        }
    }

    private fun getAvailableTemplates(): List<TemplateInfo> {
        LoggingUtils.debug(TAG, "Scanning for available templates...")

        val templates = mutableListOf<TemplateInfo>()

        try {
            templatesDirectory.listFiles()?.forEach { templateDir ->
                if (templateDir.isDirectory) {
                    val templateType = determineTemplateType(templateDir)
                    val templateInfo = TemplateInfo(templateDir.name, templateType, templateDir)
                    templates.add(templateInfo)

                    LoggingUtils.debug(TAG, "Found template: ${templateDir.name} (type: $templateType)")
                }
            }
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error scanning templates: ${e.message}", e)
        }

        LoggingUtils.debug(TAG, "Found ${templates.size} templates")
        return templates
    }

    private fun determineTemplateType(templateDir: File): String {
        LoggingUtils.debug(TAG, "Determining type for template: ${templateDir.name}")

        val type = when {
            File(templateDir, "velocity.toml").exists() -> {
                LoggingUtils.debug(TAG, "Template ${templateDir.name} identified as VELOCITY (velocity.toml found)")
                "VELOCITY"
            }
            File(templateDir, "config.yml").exists() && File(templateDir, "plugins").exists() -> {
                LoggingUtils.debug(TAG, "Template ${templateDir.name} identified as BUNGEECORD (config.yml found)")
                "BUNGEECORD"
            }
            File(templateDir, "server.properties").exists() -> {
                LoggingUtils.debug(TAG, "Template ${templateDir.name} identified as SPIGOT (server.properties found)")
                "SPIGOT"
            }
            templateDir.name.contains("velocity", ignoreCase = true) -> {
                LoggingUtils.debug(TAG, "Template ${templateDir.name} identified as VELOCITY (name-based)")
                "VELOCITY"
            }
            templateDir.name.contains("bungee", ignoreCase = true) -> {
                LoggingUtils.debug(TAG, "Template ${templateDir.name} identified as BUNGEECORD (name-based)")
                "BUNGEECORD"
            }
            else -> {
                LoggingUtils.debug(TAG, "Template ${templateDir.name} identified as SPIGOT (default)")
                "SPIGOT"
            }
        }

        return type
    }

    private fun copyPluginsToTemplate(template: TemplateInfo, templatePluginsDir: File): Int {
        LoggingUtils.debug(TAG, "Copying plugins to template ${template.name} (type: ${template.type})")

        var copiedCount = 0

        val platform = when (template.type) {
            "VELOCITY" -> "velocity"
            "BUNGEECORD" -> "bungee"
            else -> "bukkit"
        }

        LoggingUtils.debug(TAG, "Using platform: $platform for template ${template.name}")

        config.plugins.filter { it.enabled }.forEach { pluginConfig ->
            if (platform in pluginConfig.platforms) {
                try {
                    LoggingUtils.debug(TAG, "Processing plugin ${pluginConfig.name} for platform $platform")

                    val pluginSourceDir = File(pluginsDirectory, "${pluginConfig.name}/$platform")

                    if (pluginSourceDir.exists()) {
                        val jarFiles = pluginSourceDir.listFiles()?.filter { it.extension == "jar" } ?: emptyList()
                        LoggingUtils.debug(TAG, "Found ${jarFiles.size} JAR files for plugin ${pluginConfig.name}")

                        jarFiles.forEach { jarFile ->
                            try {
                                val targetFile = File(templatePluginsDir, jarFile.name)

                                if (!targetFile.exists() || jarFile.lastModified() > targetFile.lastModified()) {
                                    jarFile.copyTo(targetFile, overwrite = true)
                                    copiedCount++

                                    val sizeKB = targetFile.length() / 1024
                                    LoggingUtils.debug(TAG, "Copied plugin: ${jarFile.name} to ${template.name} (${sizeKB}KB)")
                                } else {
                                    LoggingUtils.debug(TAG, "Plugin ${jarFile.name} is up to date in ${template.name}")
                                }
                            } catch (e: Exception) {
                                LoggingUtils.error(TAG, "Error copying JAR file ${jarFile.name}: ${e.message}", e)
                            }
                        }
                    } else {
                        LoggingUtils.debug(TAG, "Plugin source directory not found: ${pluginSourceDir.absolutePath}")
                    }
                } catch (e: Exception) {
                    LoggingUtils.error(TAG, "Error processing plugin ${pluginConfig.name}: ${e.message}", e)
                }
            } else {
                LoggingUtils.debug(TAG, "Plugin ${pluginConfig.name} does not support platform $platform")
            }
        }

        LoggingUtils.debug(TAG, "Copied $copiedCount plugins to template ${template.name}")
        return copiedCount
    }

    fun validatePluginFiles(): Map<String, List<String>> {
        LoggingUtils.debugStart(TAG, "validating plugin files")

        val validationResults = mutableMapOf<String, List<String>>()

        try {
            config.plugins.filter { it.enabled }.forEach { pluginConfig ->
                val issues = mutableListOf<String>()

                LoggingUtils.debug(TAG, "Validating plugin: ${pluginConfig.name}")

                val pluginDir = File(pluginsDirectory, pluginConfig.name)
                if (!pluginDir.exists()) {
                    issues.add("Plugin directory does not exist")
                } else {
                    pluginConfig.platforms.forEach { platform ->
                        val platformDir = File(pluginDir, platform)
                        if (!platformDir.exists()) {
                            issues.add("Platform directory '$platform' does not exist")
                        } else {
                            val jarFiles = platformDir.listFiles()?.filter { it.extension == "jar" } ?: emptyList()
                            if (jarFiles.isEmpty()) {
                                issues.add("No JAR files found for platform '$platform'")
                            } else {
                                jarFiles.forEach { jarFile ->
                                    if (!isValidJarFile(jarFile)) {
                                        issues.add("Invalid JAR file: ${jarFile.name}")
                                    }
                                }
                            }
                        }
                    }
                }

                if (issues.isNotEmpty()) {
                    validationResults[pluginConfig.name] = issues
                    LoggingUtils.warn(TAG, "Plugin ${pluginConfig.name} has ${issues.size} validation issues")
                } else {
                    LoggingUtils.debug(TAG, "Plugin ${pluginConfig.name} validation passed")
                }
            }

            val stats = mapOf(
                "plugins_validated" to config.plugins.count { it.enabled },
                "plugins_with_issues" to validationResults.size,
                "total_issues" to validationResults.values.sumOf { it.size }
            )
            LoggingUtils.debugStats(TAG, stats)

            LoggingUtils.debugSuccess(TAG, "validating plugin files")

        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error during plugin validation: ${e.message}", e)
        }

        return validationResults
    }

    private fun isValidJarFile(file: File): Boolean {
        return try {
            val bytes = file.readBytes()
            bytes.size >= 4 &&
                    bytes[0] == 0x50.toByte() &&
                    bytes[1] == 0x4B.toByte() &&
                    (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte()) &&
                    (bytes[3] == 0x04.toByte() || bytes[3] == 0x06.toByte() || bytes[3] == 0x08.toByte())
        } catch (e: Exception) {
            LoggingUtils.debug(TAG, "Error validating JAR file ${file.name}: ${e.message}")
            false
        }
    }

    fun cleanupOldPluginFiles(maxAgeHours: Int = 168) {
        LoggingUtils.debugStart(TAG, "cleaning up old plugin files")

        try {
            var cleanedCount = 0
            val maxAgeMillis = maxAgeHours * 60 * 60 * 1000L
            val currentTime = System.currentTimeMillis()

            LoggingUtils.debug(TAG, "Cleaning files older than $maxAgeHours hours")

            config.plugins.forEach { pluginConfig ->
                val pluginDir = File(pluginsDirectory, pluginConfig.name)
                if (pluginDir.exists()) {
                    pluginConfig.platforms.forEach { platform ->
                        val platformDir = File(pluginDir, platform)
                        if (platformDir.exists()) {
                            platformDir.listFiles()?.filter { it.extension == "jar" }?.forEach { jarFile ->
                                val age = currentTime - jarFile.lastModified()
                                if (age > maxAgeMillis) {
                                    LoggingUtils.debug(TAG, "Removing old JAR file: ${jarFile.name} (age: ${age / 1000 / 60 / 60}h)")
                                    if (jarFile.delete()) {
                                        cleanedCount++
                                    } else {
                                        LoggingUtils.warn(TAG, "Failed to delete old JAR file: ${jarFile.name}")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            LoggingUtils.debug(TAG, "Cleaned up $cleanedCount old plugin files")
            LoggingUtils.debugSuccess(TAG, "cleaning up old plugin files")

        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error during plugin cleanup: ${e.message}", e)
        }
    }

    fun getStats(): Map<String, Any> {
        val pluginStats = mutableMapOf<String, Any>()
        val platformStats = mutableMapOf<String, Int>()
        var totalJarFiles = 0

        try {
            config.plugins.filter { it.enabled }.forEach { pluginConfig ->
                val pluginDir = File(pluginsDirectory, pluginConfig.name)
                var pluginJarCount = 0

                if (pluginDir.exists()) {
                    pluginConfig.platforms.forEach { platform ->
                        val platformDir = File(pluginDir, platform)
                        if (platformDir.exists()) {
                            val jarCount = platformDir.listFiles()?.count { it.extension == "jar" } ?: 0
                            pluginJarCount += jarCount
                            platformStats[platform] = platformStats.getOrDefault(platform, 0) + jarCount
                        }
                    }
                }

                pluginStats[pluginConfig.name] = pluginJarCount
                totalJarFiles += pluginJarCount
            }
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error calculating stats: ${e.message}", e)
        }

        return mapOf(
            "plugins_directory" to pluginsDirectory.absolutePath,
            "templates_directory" to templatesDirectory.absolutePath,
            "enabled_plugins" to config.plugins.count { it.enabled },
            "total_plugins" to config.plugins.size,
            "total_jar_files" to totalJarFiles,
            "jar_files_by_plugin" to pluginStats,
            "jar_files_by_platform" to platformStats,
            "available_templates" to getAvailableTemplates().size
        )
    }

    data class TemplateInfo(
        val name: String,
        val type: String,
        val directory: File
    )
}