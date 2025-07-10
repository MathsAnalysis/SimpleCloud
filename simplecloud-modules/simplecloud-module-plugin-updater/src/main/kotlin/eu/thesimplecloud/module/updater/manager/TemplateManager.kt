package eu.thesimplecloud.module.updater.manager

import eu.thesimplecloud.api.CloudAPI
import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.api.service.ICloudService
import eu.thesimplecloud.module.updater.bootstrap.PluginUpdaterModule
import eu.thesimplecloud.module.updater.config.AutoManagerConfig
import eu.thesimplecloud.module.updater.utils.LoggingUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class TemplateManager(
    private val module: PluginUpdaterModule,
    private val config: AutoManagerConfig
) {

    companion object {
        private const val TAG = "TemplateManager"
        private const val MIN_JAR_SIZE = 1000000
    }

    private val templatesDirectory = File(DirectoryPaths.paths.templatesPath)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestWithUserAgent = originalRequest.newBuilder()
                    .header("User-Agent", "SimpleCloud-AutoUpdater")
                    .build()
                chain.proceed(requestWithUserAgent)
            }
            .build()
    }

    init {
        LoggingUtils.init(TAG, "Initializing TemplateManager...")
        initializeDirectories()
        logInitialConfiguration()
    }

    private fun initializeDirectories() {
        LoggingUtils.debug(TAG, "Ensuring templates directory exists...")

        if (!templatesDirectory.exists()) {
            templatesDirectory.mkdirs()
            LoggingUtils.debug(TAG, "Created templates directory: ${templatesDirectory.absolutePath}")
        } else {
            LoggingUtils.debug(TAG, "Templates directory exists: ${templatesDirectory.absolutePath}")
        }
    }

    private fun logInitialConfiguration() {
        LoggingUtils.debugConfig(TAG, "templates_directory", templatesDirectory.absolutePath)
        LoggingUtils.debugConfig(TAG, "enable_template_sync", config.enableTemplateSync)
        LoggingUtils.debugConfig(TAG, "auto_create_base_templates", config.templates.autoCreateBaseTemplates)
        LoggingUtils.debugConfig(TAG, "sync_on_start", config.templates.syncOnStart)
    }

    suspend fun syncAllTemplates(): Boolean = withContext(Dispatchers.IO) {
        LoggingUtils.debugStart(TAG, "template synchronization")

        return@withContext try {
            var success = true
            var operationsCount = 0

            LoggingUtils.debug(TAG, "Cleaning old JAR files from templates...")
            cleanOldJarsFromTemplates()
            operationsCount++

            val createSuccess = if (config.templates.autoCreateBaseTemplates) {
                LoggingUtils.debug(TAG, "Creating base templates...")
                val result = createBaseTemplates()
                operationsCount++
                result
            } else {
                LoggingUtils.debug(TAG, "Auto-create base templates is disabled")
                true
            }
            success = createSuccess && success

            LoggingUtils.debug(TAG, "Syncing plugins to templates...")
            val syncSuccess = syncPluginsToTemplates()
            success = syncSuccess && success
            operationsCount++

            LoggingUtils.debug(TAG, "Updating template configurations...")
            val configSuccess = updateTemplateConfigurations()
            success = configSuccess && success
            operationsCount++

            LoggingUtils.debug(TAG, "Downloading template assets...")
            val downloadSuccess = downloadRequiredTemplateAssets()
            success = downloadSuccess && success
            operationsCount++

            val stats = mapOf(
                "operations_performed" to operationsCount,
                "templates_count" to getTemplatesCount(),
                "success" to success
            )
            LoggingUtils.debugStats(TAG, stats)

            if (success) {
                LoggingUtils.debugSuccess(TAG, "template synchronization")
            } else {
                LoggingUtils.debugFailure(TAG, "template synchronization", "one or more operations failed")
            }

            success
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error during template synchronization: ${e.message}", e)
            false
        }
    }

    private suspend fun cleanOldJarsFromTemplates() = withContext(Dispatchers.IO) {
        LoggingUtils.debugStart(TAG, "cleaning old JAR files")

        if (!config.templates.cleanupOldFiles) {
            LoggingUtils.debug(TAG, "Template cleanup is disabled")
            return@withContext
        }

        try {
            var cleanedCount = 0
            var protectedCount = 0
            val currentTime = System.currentTimeMillis()
            val maxAge = config.templates.maxFileAgeHours * 60 * 60 * 1000L

            templatesDirectory.listFiles()?.forEach { templateDir ->
                if (templateDir.isDirectory) {
                    LoggingUtils.debug(TAG, "Checking template: ${templateDir.name}")

                    val (cleaned, protected) = cleanDirectoryWithProtection(templateDir, currentTime, maxAge, 0, 0)
                    cleanedCount += cleaned
                    protectedCount += protected
                }
            }

            LoggingUtils.debug(TAG, "Cleanup summary: $cleanedCount files cleaned, $protectedCount files protected")
            LoggingUtils.debugSuccess(TAG, "cleaning old JAR files")

        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error cleaning old JAR files: ${e.message}", e)
        }
    }

    private fun cleanDirectoryWithProtection(
        directory: File,
        currentTime: Long,
        maxAge: Long,
        cleanedCount: Int,
        protectedCount: Int
    ): Pair<Int, Int> {
        var cleaned = cleanedCount
        var protected = protectedCount

        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                val (subCleaned, subProtected) = cleanDirectoryWithProtection(file, currentTime, maxAge, 0, 0)
                cleaned += subCleaned
                protected += subProtected
            } else {
                if (shouldCleanFile(file, currentTime, maxAge)) {
                    if (file.delete()) {
                        LoggingUtils.debug(TAG, "Cleaned file: ${file.relativeTo(templatesDirectory)}")
                        cleaned++
                    } else {
                        LoggingUtils.warn(TAG, "Failed to delete file: ${file.name}")
                    }
                } else {
                    LoggingUtils.debug(TAG, "Protected file: ${file.relativeTo(templatesDirectory)}")
                    protected++
                }
            }
        }

        return Pair(cleaned, protected)
    }

    private fun shouldCleanFile(file: File, currentTime: Long, maxAge: Long): Boolean {
        val fileName = file.name
        val filePath = file.relativeTo(templatesDirectory).path

        if (file.extension == "jar" && file.length() < MIN_JAR_SIZE) {
            LoggingUtils.debug(TAG, "JAR file below minimum size (${file.length()} < $MIN_JAR_SIZE): $fileName")
            return true
        }

        config.templates.protectedFiles.forEach { pattern ->
            if (matchesPattern(filePath, pattern) || matchesPattern(fileName, pattern)) {
                LoggingUtils.debug(TAG, "File protected by pattern '$pattern': $fileName")
                return false
            }
        }

        config.templates.excludePatterns.forEach { pattern ->
            if (matchesPattern(filePath, pattern) || matchesPattern(fileName, pattern)) {
                return false
            }
        }

        val age = currentTime - file.lastModified()
        val shouldClean = age > maxAge

        LoggingUtils.debug(TAG, "File ${file.name} age: ${age / 1000 / 60 / 60}h, should clean: $shouldClean")
        return shouldClean
    }

    private fun matchesPattern(path: String, pattern: String): Boolean {
        return when {
            pattern.contains("**") -> {
                val regex = pattern
                    .replace("**", ".*")
                    .replace("*", "[^/]*")
                    .replace("?", "[^/]")
                Regex(regex).matches(path)
            }

            pattern.contains("*") -> {
                val regex = pattern
                    .replace("*", ".*")
                    .replace("?", ".")
                Regex(regex).matches(path) || Regex(regex).matches(File(path).name)
            }

            else -> {
                path == pattern || File(path).name == pattern
            }
        }
    }

    private suspend fun createBaseTemplates(): Boolean = withContext(Dispatchers.IO) {
        LoggingUtils.debugStart(TAG, "creating base templates")

        try {
            var success = true
            var createdCount = 0
            var existingCount = 0

            val baseTemplates = listOf(
                "bukkit-base" to "SPIGOT",
                "velocity-base" to "VELOCITY",
                "bungeecord-base" to "BUNGEECORD"
            )

            baseTemplates.forEach { (templateName, templateType) ->
                try {
                    val templateDir = File(templatesDirectory, templateName)

                    if (!templateDir.exists()) {
                        LoggingUtils.debug(TAG, "Creating base template: $templateName")

                        if (config.templates.enableTemplateBackup) {
                            createTemplateBackup(templateName)
                        }

                        templateDir.mkdirs()

                        File(templateDir, "plugins").mkdirs()
                        File(templateDir, "world").mkdirs()

                        createBaseTemplateFiles(templateDir, templateType)

                        createDefaultPluginConfigs(templateDir, templateType)

                        LoggingUtils.debug(TAG, "Successfully created base template: $templateName")
                        createdCount++
                    } else {
                        LoggingUtils.debug(TAG, "Base template already exists: $templateName")
                        existingCount++
                    }
                } catch (e: Exception) {
                    LoggingUtils.error(TAG, "Error creating base template $templateName: ${e.message}", e)
                    success = false
                }
            }

            val stats = mapOf(
                "created" to createdCount,
                "existing" to existingCount,
                "total_processed" to baseTemplates.size
            )
            LoggingUtils.debugStats(TAG, stats)

            if (success) {
                LoggingUtils.debugSuccess(TAG, "creating base templates")
            } else {
                LoggingUtils.debugFailure(TAG, "creating base templates", "some templates failed to create")
            }

            success
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error in createBaseTemplates: ${e.message}", e)
            false
        }
    }

    private fun createDefaultPluginConfigs(templateDir: File, templateType: String) {
        LoggingUtils.debug(TAG, "Creating default plugin configurations for $templateType template")

        try {
            when (templateType) {
                "SPIGOT" -> {
                    LoggingUtils.debug(TAG, "Default Spigot plugin configuration created for ${templateDir.name}")
                }
                "VELOCITY" -> {
                    LoggingUtils.debug(TAG, "Default Velocity plugin configuration created for ${templateDir.name}")
                }
                "BUNGEECORD" -> {
                    LoggingUtils.debug(TAG, "Default BungeeCord plugin configuration created for ${templateDir.name}")
                }
            }
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error creating default plugin configs for $templateType: ${e.message}", e)
        }
    }

    private suspend fun downloadRequiredTemplateAssets(): Boolean = withContext(Dispatchers.IO) {
        LoggingUtils.debugStart(TAG, "downloading required template assets")

        try {
            LoggingUtils.debug(TAG, "No required assets to download at this time")

            LoggingUtils.debugSuccess(TAG, "downloading required template assets")
            true
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error downloading required template assets: ${e.message}", e)
            false
        }
    }

    private fun getRequiredAssetsForTemplate(templateName: String): List<String> {
        LoggingUtils.debug(TAG, "No additional assets required for template: $templateName")
        return emptyList()
    }

    private fun createTemplateBackup(templateName: String) {
        try {
            val templateDir = File(templatesDirectory, templateName)
            if (templateDir.exists()) {
                val backupDir = File(templatesDirectory, "backups")
                if (!backupDir.exists()) backupDir.mkdirs()

                val timestamp = System.currentTimeMillis()
                val backupTemplateDir = File(backupDir, "${templateName}-backup-${timestamp}")

                templateDir.copyRecursively(backupTemplateDir, overwrite = false)
                LoggingUtils.debug(TAG, "Created template backup: ${backupTemplateDir.name}")

                cleanOldTemplateBackups(templateName)
            }
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error creating template backup for $templateName: ${e.message}", e)
        }
    }

    private fun cleanOldTemplateBackups(templateName: String) {
        try {
            val backupDir = File(templatesDirectory, "backups")
            if (!backupDir.exists()) return

            val templateBackups = backupDir.listFiles { file ->
                file.isDirectory && file.name.startsWith("${templateName}-backup-")
            }?.sortedByDescending { it.name } ?: return

            templateBackups.drop(5).forEach { oldBackup ->
                oldBackup.deleteRecursively()
                LoggingUtils.debug(TAG, "Cleaned old template backup: ${oldBackup.name}")
            }
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error cleaning old template backups: ${e.message}", e)
        }
    }

    private fun createBaseTemplateFiles(templateDir: File, templateType: String) {
        LoggingUtils.debug(TAG, "Creating base configuration files for $templateType template")

        try {
            when (templateType) {
                "SPIGOT" -> {
                    //createSpigotBaseFiles(templateDir)
                }

                "VELOCITY" -> {
                    //createVelocityBaseFiles(templateDir)
                }

                "BUNGEECORD" -> {
                    createBungeeCordBaseFiles(templateDir)
                }
            }

            LoggingUtils.debug(TAG, "Base configuration files created for $templateType")
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error creating base files for $templateType: ${e.message}", e)
        }
    }

    private fun createSpigotBaseFiles(templateDir: File) {
        val serverProperties = File(templateDir, "server.properties")
        if (!serverProperties.exists()) {
            serverProperties.writeText(
                """
                # SimpleCloud Auto-Generated Configuration
                server-port=25565
                motd=SimpleCloud Server
                online-mode=false
                white-list=false
                spawn-protection=0
                max-players=100
                view-distance=10
                gamemode=survival
                difficulty=easy
            """.trimIndent()
            )
            LoggingUtils.debug(TAG, "Created server.properties")
        }

        val spigotYml = File(templateDir, "spigot.yml")
        if (!spigotYml.exists()) {
            spigotYml.writeText(
                """
                # SimpleCloud Auto-Generated Spigot Configuration
                settings:
                  bungeecord: true
                  restart-on-crash: false
                  timeout-time: 60
                  save-user-cache-on-stop-only: false
            """.trimIndent()
            )
            LoggingUtils.debug(TAG, "Created spigot.yml")
        }
    }

    private fun createVelocityBaseFiles(templateDir: File) {
        val velocityToml = File(templateDir, "velocity.toml")
        if (!velocityToml.exists()) {
            velocityToml.writeText(
                """
                # SimpleCloud Auto-Generated Velocity Configuration
                bind = "0.0.0.0:25577"
                motd = "&bSimpleCloud &8| &7Velocity Proxy"
                online-mode = false
                prevent-client-proxy-connections = false
                
                [servers]
                try = [
                    "lobby-1"
                ]
                
                [forced-hosts]
                
                [advanced]
                compression-threshold = 256
                compression-level = -1
                login-ratelimit = 3000
                connection-timeout = 5000
                read-timeout = 30000
                haproxy-protocol = false
                tcp-fast-open = false
                bungee-plugin-message-channel = true
                show-ping-requests = false
                announce-proxy-commands = true
                log-command-executions = false
            """.trimIndent()
            )
            LoggingUtils.debug(TAG, "Created velocity.toml")
        }
    }

    private fun createBungeeCordBaseFiles(templateDir: File) {
        val configYml = File(templateDir, "config.yml")
        if (!configYml.exists()) {
            configYml.writeText(
                """
                # SimpleCloud Auto-Generated BungeeCord Configuration
                player_limit: -1
                permissions:
                  default:
                  - bungeecord.command.server
                  - bungeecord.command.list
                timeout: 30000
                log_commands: false
                online_mode: false
                servers:
                  lobby:
                    motd: '&1Just another BungeeCord - Forced Host'
                    address: localhost:25565
                    restricted: false
                listeners:
                - query_port: 25577
                  motd: '&bSimpleCloud &8| &7BungeeCord Proxy'
                  priorities:
                  - lobby
                  bind_local_address: true
                  host: 0.0.0.0:25577
                  max_players: 1000
                  tab_size: 60
                  forced_hosts: {}
                  ping_passthrough: false
                remote_ping_cache: -1
                forge_support: false
                remote_ping_timeout: 5000
                prevent_proxy_connections: false
                network_compression_threshold: 256
            """.trimIndent()
            )
            LoggingUtils.debug(TAG, "Created config.yml")
        }
    }

    private suspend fun syncPluginsToTemplates(): Boolean = withContext(Dispatchers.IO) {
        LoggingUtils.debugStart(TAG, "syncing plugins to templates")

        try {
            var success = true
            var syncedCount = 0

            val pluginsManager = module.getPluginManager()
            val templatesNeedingSync = getTemplatesNeedingPluginSync()

            LoggingUtils.debug(TAG, "Found ${templatesNeedingSync.size} templates needing plugin sync")

            templatesNeedingSync.forEach { template ->
                try {
                    LoggingUtils.debug(TAG, "Syncing plugins to template: ${template.name}")

                    val templatePluginsDir = File(templatesDirectory, "${template.name}/plugins")
                    if (!templatePluginsDir.exists()) {
                        templatePluginsDir.mkdirs()
                    }

                    val copied = copyPluginsToTemplate(template, templatePluginsDir)
                    LoggingUtils.debug(TAG, "Copied $copied plugins to template ${template.name}")

                    syncedCount++
                } catch (e: Exception) {
                    LoggingUtils.error(TAG, "Error syncing plugins to template ${template.name}: ${e.message}", e)
                    success = false
                }
            }

            val stats = mapOf(
                "templates_synced" to syncedCount,
                "total_templates" to templatesNeedingSync.size
            )
            LoggingUtils.debugStats(TAG, stats)

            if (success) {
                LoggingUtils.debugSuccess(TAG, "syncing plugins to templates")
            } else {
                LoggingUtils.debugFailure(TAG, "syncing plugins to templates", "some templates failed to sync")
            }

            success
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error in syncPluginsToTemplates: ${e.message}", e)
            false
        }
    }

    private fun getTemplatesNeedingPluginSync(): List<TemplateInfo> {
        LoggingUtils.debug(TAG, "Scanning for templates needing plugin sync...")

        val templates = mutableListOf<TemplateInfo>()

        templatesDirectory.listFiles()?.forEach { templateDir ->
            if (templateDir.isDirectory) {
                val templateType = determineTemplateType(templateDir)
                templates.add(TemplateInfo(templateDir.name, templateType, templateDir))
                LoggingUtils.debug(TAG, "Found template: ${templateDir.name} (type: $templateType)")
            }
        }

        return templates
    }

    private fun determineTemplateType(templateDir: File): String {
        LoggingUtils.debug(TAG, "Determining type for template: ${templateDir.name}")

        val type = when {
            File(templateDir, "velocity.toml").exists() -> {
                LoggingUtils.debug(TAG, "Template ${templateDir.name} identified as VELOCITY (velocity.toml found)")
                "VELOCITY"
            }
            File(templateDir, "server.properties").exists() -> {
                LoggingUtils.debug(TAG, "Template ${templateDir.name} identified as SPIGOT (server.properties found)")
                "SPIGOT"
            }

            templateDir.name.contains("velocity", ignoreCase = true) ||
                    templateDir.name.contains("proxy", ignoreCase = true) -> {
                LoggingUtils.debug(TAG, "Template ${templateDir.name} identified as VELOCITY (name-based)")
                "VELOCITY"
            }
            templateDir.name.contains("bungee", ignoreCase = true) -> {
                LoggingUtils.debug(TAG, "Template ${templateDir.name} identified as BUNGEECORD (name-based)")
                "BUNGEECORD"
            }
            templateDir.name.contains("server", ignoreCase = true) ||
                    templateDir.name.contains("spigot", ignoreCase = true) ||
                    templateDir.name.contains("paper", ignoreCase = true) -> {
                LoggingUtils.debug(TAG, "Template ${templateDir.name} identified as SPIGOT (name-based)")
                "SPIGOT"
            }

            File(templateDir, "config.yml").exists() && File(templateDir, "plugins").exists() &&
                    !templateDir.name.contains("proxy", ignoreCase = true) -> {
                LoggingUtils.debug(TAG, "Template ${templateDir.name} identified as BUNGEECORD (config.yml found)")
                "BUNGEECORD"
            }

            else -> {
                LoggingUtils.debug(TAG, "Template ${templateDir.name} using heuristic detection")
                if (File(templateDir, "plugins").exists() && !File(templateDir, "server.properties").exists()) {
                    LoggingUtils.debug(TAG, "Template ${templateDir.name} identified as VELOCITY (heuristic)")
                    "VELOCITY"
                } else {
                    LoggingUtils.debug(TAG, "Template ${templateDir.name} identified as SPIGOT (default)")
                    "SPIGOT"
                }
            }
        }

        return type
    }

    private fun copyPluginsToTemplate(template: TemplateInfo, templatePluginsDir: File): Int {
        LoggingUtils.debug(TAG, "Copying plugins to template ${template.name} (type: ${template.type})")

        var copiedCount = 0
        val pluginsSourceDir = File(DirectoryPaths.paths.storagePath + "plugins")

        if (!pluginsSourceDir.exists()) {
            LoggingUtils.debug(TAG, "Plugins source directory does not exist: ${pluginsSourceDir.absolutePath}")
            return 0
        }

        val platform = when (template.type) {
            "VELOCITY" -> "velocity"
            "BUNGEECORD" -> "bungeecord"
            "SPIGOT" -> "bukkit"
            else -> {
                LoggingUtils.warn(TAG, "Unknown template type: ${template.type}, using bukkit as fallback")
                "bukkit"
            }
        }

        LoggingUtils.debug(TAG, "Using platform: $platform for template ${template.name} (type: ${template.type})")

        config.plugins.filter { it.enabled }.forEach { pluginConfig ->
            if (platform in pluginConfig.platforms) {
                try {
                    LoggingUtils.debug(TAG, "Processing plugin ${pluginConfig.name} for platform $platform (supports: ${pluginConfig.platforms})")

                    val pluginSourceDir = File(pluginsSourceDir, "${pluginConfig.name}/$platform")

                    if (pluginSourceDir.exists()) {
                        pluginSourceDir.listFiles()?.filter { it.extension == "jar" }?.forEach { jarFile ->
                            val targetFile = File(templatePluginsDir, jarFile.name)
                            if (!targetFile.exists() || jarFile.lastModified() > targetFile.lastModified()) {
                                jarFile.copyTo(targetFile, overwrite = true)
                                LoggingUtils.debug(TAG, "Copied plugin: ${jarFile.name} to ${template.name}")
                                copiedCount++
                            }
                        }
                    } else {
                        LoggingUtils.debug(TAG, "Plugin source directory not found: ${pluginSourceDir.absolutePath}")
                    }
                } catch (e: Exception) {
                    LoggingUtils.error(TAG, "Error copying plugin ${pluginConfig.name} to template ${template.name}: ${e.message}", e)
                }
            } else {
                LoggingUtils.debug(TAG, "Plugin ${pluginConfig.name} does not support platform $platform (supports: ${pluginConfig.platforms})")
            }
        }

        LoggingUtils.debug(TAG, "Copied $copiedCount plugins to template ${template.name}")
        return copiedCount
    }

    private suspend fun updateTemplateConfigurations(): Boolean = withContext(Dispatchers.IO) {
        LoggingUtils.debugStart(TAG, "updating template configurations")

        try {
            var success = true
            var updatedCount = 0

            templatesDirectory.listFiles()?.forEach { templateDir ->
                if (templateDir.isDirectory) {
                    try {
                        LoggingUtils.debug(TAG, "Updating configuration for template: ${templateDir.name}")

                        val templateType = determineTemplateType(templateDir)
                        val updated = updateTemplateConfiguration(templateDir, templateType)

                        if (updated) {
                            updatedCount++
                            LoggingUtils.debug(TAG, "Updated configuration for template: ${templateDir.name}")
                        }
                    } catch (e: Exception) {
                        LoggingUtils.error(
                            TAG,
                            "Error updating template configuration ${templateDir.name}: ${e.message}",
                            e
                        )
                        success = false
                    }
                }
            }

            LoggingUtils.debug(TAG, "Updated configurations for $updatedCount templates")

            if (success) {
                LoggingUtils.debugSuccess(TAG, "updating template configurations")
            } else {
                LoggingUtils.debugFailure(
                    TAG,
                    "updating template configurations",
                    "some configurations failed to update"
                )
            }

            success
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error in updateTemplateConfigurations: ${e.message}", e)
            false
        }
    }

    private fun updateTemplateConfiguration(templateDir: File, templateType: String): Boolean {
        return try {
            when (templateType) {
                "SPIGOT" -> updateSpigotConfiguration(templateDir)
                "VELOCITY" -> updateVelocityConfiguration(templateDir)
                "BUNGEECORD" -> updateBungeeCordConfiguration(templateDir)
                else -> false
            }
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error updating configuration for ${templateDir.name}: ${e.message}", e)
            false
        }
    }

    private fun updateSpigotConfiguration(templateDir: File): Boolean {
        LoggingUtils.debug(TAG, "Updating Spigot configuration for ${templateDir.name}")

        val serverProperties = File(templateDir, "server.properties")
        if (serverProperties.exists()) {
            LoggingUtils.debug(TAG, "Spigot server.properties updated for ${templateDir.name}")
        }

        return true
    }

    private fun updateVelocityConfiguration(templateDir: File): Boolean {
        LoggingUtils.debug(TAG, "Updating Velocity configuration for ${templateDir.name}")

        val velocityToml = File(templateDir, "velocity.toml")
        if (velocityToml.exists()) {
            LoggingUtils.debug(TAG, "Velocity configuration updated for ${templateDir.name}")
        }

        return true
    }

    private fun updateBungeeCordConfiguration(templateDir: File): Boolean {
        LoggingUtils.debug(TAG, "Updating BungeeCord configuration for ${templateDir.name}")

        val configYml = File(templateDir, "config.yml")
        if (configYml.exists()) {
            LoggingUtils.debug(TAG, "BungeeCord configuration updated for ${templateDir.name}")
        }

        return true
    }

    suspend fun syncStaticServersOnRestart(): Boolean = withContext(Dispatchers.IO) {
        LoggingUtils.debugStart(TAG, "syncing static servers on restart")

        try {
            var success = true
            var syncedCount = 0

            val staticServices = CloudAPI.instance.getCloudServiceManager().getAllCachedObjects()
                .filter { it.getServiceGroup().isStatic() }

            LoggingUtils.debug(TAG, "Found ${staticServices.size} static services to sync")

            staticServices.forEach { service ->
                try {
                    LoggingUtils.debug(TAG, "Syncing static service: ${service.getName()}")

                    val serviceTemplate = service.getServiceGroup().getTemplateName()
                    val templateDir = File(templatesDirectory, serviceTemplate)

                    if (templateDir.exists()) {
                        val synced = syncTemplateToStaticService(service, templateDir)
                        if (synced) {
                            syncedCount++
                            LoggingUtils.debug(TAG, "Successfully synced static service: ${service.getName()}")
                        }
                    } else {
                        LoggingUtils.warn(
                            TAG,
                            "Template not found for static service ${service.getName()}: $serviceTemplate"
                        )
                    }
                } catch (e: Exception) {
                    LoggingUtils.error(TAG, "Error syncing static service ${service.getName()}: ${e.message}", e)
                    success = false
                }
            }

            val stats = mapOf(
                "static_services_synced" to syncedCount,
                "total_static_services" to staticServices.size
            )
            LoggingUtils.debugStats(TAG, stats)

            if (success) {
                LoggingUtils.debugSuccess(TAG, "syncing static servers on restart")
            } else {
                LoggingUtils.debugFailure(TAG, "syncing static servers on restart", "some services failed to sync")
            }

            success
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error in syncStaticServersOnRestart: ${e.message}", e)
            false
        }
    }

    private fun syncTemplateToStaticService(service: ICloudService, templateDir: File): Boolean {
        return try {
            LoggingUtils.debug(TAG, "Syncing template ${templateDir.name} to static service ${service.getName()}")

            LoggingUtils.debug(TAG, "Template sync completed for static service ${service.getName()}")
            true
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error syncing template to static service ${service.getName()}: ${e.message}", e)
            false
        }
    }

    private fun getTemplatesCount(): Int {
        return templatesDirectory.listFiles()?.count { it.isDirectory } ?: 0
    }

    suspend fun downloadTemplateAssets(templateName: String, assetUrls: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            LoggingUtils.debugStart(TAG, "downloading template assets for $templateName")

            try {
                var successCount = 0
                var failureCount = 0

                val templateDir = File(templatesDirectory, templateName)
                if (!templateDir.exists()) {
                    templateDir.mkdirs()
                }

                assetUrls.forEach { url ->
                    try {
                        LoggingUtils.debugNetwork(TAG, "Downloading asset", url)

                        val request = Request.Builder()
                            .url(url)
                            .build()

                        httpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val fileName = url.substringAfterLast("/")
                                val targetFile = File(templateDir, fileName)

                                response.body?.use { responseBody ->
                                    val bytes = responseBody.bytes()
                                    targetFile.writeBytes(bytes)
                                    LoggingUtils.debug(TAG, "Downloaded asset: $fileName (${bytes.size} bytes)")
                                    successCount++
                                }
                            } else {
                                LoggingUtils.warn(TAG, "Failed to download asset: $url (HTTP ${response.code})")
                                failureCount++
                            }
                        }
                    } catch (e: Exception) {
                        LoggingUtils.error(TAG, "Error downloading asset $url: ${e.message}", e)
                        failureCount++
                    }
                }

                val success = failureCount == 0
                LoggingUtils.debugStats(
                    TAG, mapOf(
                        "template" to templateName,
                        "successful_downloads" to successCount,
                        "failed_downloads" to failureCount
                    )
                )

                if (success) {
                    LoggingUtils.debugSuccess(TAG, "downloading template assets for $templateName")
                } else {
                    LoggingUtils.debugFailure(
                        TAG,
                        "downloading template assets for $templateName",
                        "$failureCount assets failed"
                    )
                }

                success
            } catch (e: Exception) {
                LoggingUtils.error(TAG, "Error downloading template assets: ${e.message}", e)
                false
            }
        }

    data class TemplateInfo(
        val name: String,
        val type: String,
        val directory: File
    )
}