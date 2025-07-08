package eu.thesimplecloud.module.updater.bootstrap

import eu.thesimplecloud.api.CloudAPI
import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.api.external.ICloudModule
import eu.thesimplecloud.api.service.ICloudService
import eu.thesimplecloud.jsonlib.JsonLib
import eu.thesimplecloud.launcher.startup.Launcher
import eu.thesimplecloud.module.updater.api.UpdaterAPI
import eu.thesimplecloud.module.updater.command.UpdaterCommand
import eu.thesimplecloud.module.updater.config.AutoManagerConfig
import eu.thesimplecloud.module.updater.manager.PluginManager
import eu.thesimplecloud.module.updater.manager.ServerVersionManager
import eu.thesimplecloud.module.updater.manager.ServiceVersionRegistrar
import eu.thesimplecloud.module.updater.manager.TemplateManager
import eu.thesimplecloud.module.updater.thread.UpdateScheduler
import eu.thesimplecloud.module.updater.updater.AutomaticJarUpdater
import eu.thesimplecloud.module.updater.utils.LoggingUtils
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

class PluginUpdaterModule : ICloudModule {

    companion object {
        private const val TAG = "AutoManager"

        @JvmStatic
        lateinit var instance: PluginUpdaterModule
            private set
    }

    private val configFile = File(DirectoryPaths.paths.modulesPath + "automanager/auto-manager-config.json")
    private val lastVersionsFile = File(DirectoryPaths.paths.storagePath + "last_versions.json")
    private val pendingRestartsFile = File(DirectoryPaths.paths.storagePath + "pending_restarts.json")

    private lateinit var config: AutoManagerConfig
    private lateinit var serverVersionManager: ServerVersionManager
    private lateinit var pluginManager: PluginManager
    private lateinit var templateManager: TemplateManager
    private lateinit var updateScheduler: UpdateScheduler
    private lateinit var updaterAPI: UpdaterAPI
    private lateinit var serviceVersionRegistrar: ServiceVersionRegistrar
    private lateinit var automaticJarUpdater: AutomaticJarUpdater

    private val moduleScope = CoroutineScope(Dispatchers.IO)
    private val updatedTemplates = mutableSetOf<String>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    init {
        instance = this
    }

    override fun onEnable() {
        LoggingUtils.init(TAG, "Initializing AutoManager module...")

        try {
            loadConfig()
            initializeManagers()

            runBlocking {
                registerServiceVersions()
            }

            registerCommands()

            if (config.enableAutomation) {
                scheduleUpdates()
                startAutomaticJarUpdater()
                LoggingUtils.info(TAG, "Automation started")
            } else {
                LoggingUtils.info(TAG, "Automation is disabled in configuration")
            }

            moduleScope.launch {
                delay(10000)
                performInitialUpdateCheck()
            }

            LoggingUtils.info(TAG, "Module loaded successfully!")
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error during initialization: ${e.message}", e)
        }
    }

    override fun onDisable() {
        LoggingUtils.info(TAG, "Shutting down module...")

        try {
            if (::updateScheduler.isInitialized) {
                updateScheduler.shutdown()
            }
            moduleScope.cancel()
            httpClient.dispatcher.executorService.shutdown()
            LoggingUtils.info(TAG, "Module shut down correctly")
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error during shutdown: ${e.message}", e)
        }
    }

    private fun loadConfig() {
        LoggingUtils.debug(TAG, "Looking for config in: ${configFile.absolutePath}")

        config = if (configFile.exists()) {
            try {
                LoggingUtils.debug(TAG, "Config found, loading...")
                val jsonData = JsonLib.fromJsonFile(configFile)!!
                AutoManagerConfig.fromJson(jsonData)
            } catch (e: Exception) {
                LoggingUtils.error(TAG, "Error loading config, using default: ${e.message}", e)
                createDefaultConfig()
            }
        } else {
            LoggingUtils.debug(TAG, "Config not found, creating default")
            createDefaultConfig()
        }

        saveConfig()
        LoggingUtils.info(TAG, "Config loaded with ${config.plugins.size} configured plugins")
    }

    private fun initializeManagers() {
        LoggingUtils.debug(TAG, "Initializing managers...")

        serverVersionManager = ServerVersionManager(this, config)
        pluginManager = PluginManager(config)
        templateManager = TemplateManager(this, config)
        updateScheduler = UpdateScheduler(this, config)
        updaterAPI = UpdaterAPI(serverVersionManager, pluginManager, templateManager)
        serviceVersionRegistrar = ServiceVersionRegistrar()
        automaticJarUpdater = AutomaticJarUpdater(this)

        LoggingUtils.info(TAG, "All managers initialized successfully")
    }

    private fun startAutomaticJarUpdater() {
        try {
            automaticJarUpdater.startAutomaticMonitoring()
            LoggingUtils.info(TAG, "AutomaticJarUpdater started successfully")
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error starting AutomaticJarUpdater: ${e.message}", e)
        }
    }

    fun getAutomaticJarUpdater(): AutomaticJarUpdater = automaticJarUpdater

    private suspend fun registerServiceVersions() {
        LoggingUtils.debug(TAG, "Registering service versions...")

        try {
            val updated = serverVersionManager.updateAllVersions()
            if (!updated) {
                LoggingUtils.error(TAG, "Failed to update server versions from APIs")
                return
            }

            val versions = serverVersionManager.getCurrentVersions()
            LoggingUtils.debug(TAG, "Retrieved ${versions.size} version entries")

            val leafVersions = versions.find { it.name == "Leaf" }
            if (leafVersions != null && leafVersions.downloadLinks.isNotEmpty()) {
                LoggingUtils.debug(TAG, "Registering ${leafVersions.downloadLinks.size} Leaf versions...")
                serviceVersionRegistrar.registerLeafVersions(leafVersions.downloadLinks)
            }

            val velocityCTD = versions.find { it.name == "VelocityCTD" }
            if (velocityCTD != null && velocityCTD.downloadLinks.isNotEmpty()) {
                LoggingUtils.debug(TAG, "Registering ${velocityCTD.downloadLinks.size} VelocityCTD versions...")
                velocityCTD.downloadLinks.forEach {
                    serviceVersionRegistrar.registerVelocityCTDVersion(it)
                }
            }

            LoggingUtils.info(TAG, "Service version registration complete")
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error registering service versions: ${e.message}", e)
        }
    }

    private fun registerCommands() {
        try {
            LoggingUtils.debug(TAG, "Registering commands...")
            val commandManager = Launcher.instance.commandManager
            commandManager.registerCommand(this, UpdaterCommand(this))
            LoggingUtils.info(TAG, "Commands registered successfully")
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Failed to register commands: ${e.message}", e)
        }
    }

    private fun scheduleUpdates() {
        LoggingUtils.debug(TAG, "Setting up update scheduling...")

        if (config.updateTime.isNotEmpty()) {
            LoggingUtils.debug(TAG, "Using time-based scheduling: ${config.updateTime}")
            scheduleNextUpdate()
        } else {
            LoggingUtils.debug(TAG, "Using interval-based scheduling: ${config.updateInterval}")
            updateScheduler.start()
        }
    }

    private fun scheduleNextUpdate() {
        try {
            val updateTime = LocalTime.parse(config.updateTime)
            val now = LocalDateTime.now()
            var nextUpdate = now.with(updateTime)

            if (nextUpdate.isBefore(now)) {
                nextUpdate = nextUpdate.plusDays(1)
            }

            val delayMillis = now.until(nextUpdate, ChronoUnit.MILLIS)
            LoggingUtils.info(TAG, "Next update scheduled at: $nextUpdate (in ${delayMillis / 1000 / 60} minutes)")

            moduleScope.launch {
                delay(delayMillis)
                performScheduledUpdate()
                scheduleNextUpdate()
            }
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error scheduling update: ${e.message}", e)
            updateScheduler.start()
        }
    }

    private suspend fun performInitialUpdateCheck() {
        LoggingUtils.info(TAG, "=== PERFORMING INITIAL UPDATE CHECK ===")

        try {
            val hasExistingFiles = checkExistingServerFiles()
            val lastVersions = loadLastKnownVersions()

            if (!hasExistingFiles || lastVersions.isEmpty()) {
                LoggingUtils.info(TAG, "No existing files or version history found, forcing download...")
                performScheduledUpdate()
            } else {
                val currentVersions = serverVersionManager.getCurrentVersions()
                var needsUpdate = false

                for (versionEntry in currentVersions) {
                    val lastKnownVersion = lastVersions[versionEntry.name]
                    if (lastKnownVersion != versionEntry.latestVersion) {
                        LoggingUtils.info(TAG, "Version change detected for ${versionEntry.name}: $lastKnownVersion -> ${versionEntry.latestVersion}")
                        needsUpdate = true
                    }
                }

                if (needsUpdate) {
                    LoggingUtils.info(TAG, "Updates available, starting download...")
                    performScheduledUpdate()
                } else {
                    LoggingUtils.info(TAG, "All versions are up to date")
                }
            }
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error during initial update check: ${e.message}", e)
        }
    }

    private fun checkExistingServerFiles(): Boolean {
        val serverVersionsDir = File(DirectoryPaths.paths.storagePath + "server-versions")
        val leafDir = File(serverVersionsDir, "leaf")
        val velocityDir = File(serverVersionsDir, "velocityctd")

        val leafHasFiles = leafDir.exists() && leafDir.listFiles()?.any { it.name.endsWith(".jar") } == true
        val velocityHasFiles = velocityDir.exists() && velocityDir.listFiles()?.any { it.name.endsWith(".jar") } == true

        LoggingUtils.debug(TAG, "Existing files check - Leaf: $leafHasFiles, VelocityCTD: $velocityHasFiles")
        return leafHasFiles || velocityHasFiles
    }

    private fun loadLastKnownVersions(): Map<String, String> {
        return try {
            if (lastVersionsFile.exists()) {
                val jsonData = JsonLib.fromJsonFile(lastVersionsFile)!!
                val versions = jsonData.getObject("versions", Map::class.java) as Map<String, String>? ?: emptyMap()
                LoggingUtils.debug(TAG, "Loaded ${versions.size} last known versions")
                versions
            } else {
                LoggingUtils.debug(TAG, "No last known versions file found")
                emptyMap()
            }
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error loading last known versions: ${e.message}", e)
            emptyMap()
        }
    }

    private fun saveLastKnownVersions(versions: List<ServerVersionManager.ServerVersionEntry>) {
        try {
            val versionsMap = versions.associate { it.name to it.latestVersion }
            val jsonData = JsonLib.empty().append("versions", versionsMap)
            lastVersionsFile.parentFile.mkdirs()
            jsonData.saveAsFile(lastVersionsFile)
            LoggingUtils.debug(TAG, "Saved version information for ${versionsMap.size} server types")
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error saving last known versions: ${e.message}", e)
        }
    }

    private suspend fun performScheduledUpdate() {
        LoggingUtils.info(TAG, "=== SCHEDULED UPDATE STARTED ===")

        try {
            var success = true
            updatedTemplates.clear()

            if (config.enableServerVersionUpdates) {
                LoggingUtils.info(TAG, "Step 1: Updating server version metadata...")
                success = serverVersionManager.updateAllVersions() && success

                if (success) {
                    LoggingUtils.info(TAG, "Step 2: Registering service versions...")
                    registerServiceVersions()

                    LoggingUtils.info(TAG, "Step 3: Downloading server JAR files...")
                    success = downloadAllServerVersionsDirectly() && success

                    if (success) {
                        LoggingUtils.info(TAG, "Step 4: Updating JAR files in minecraftJars and templates...")
                        updateMinecraftJarsAndTemplates()

                        val currentVersions = serverVersionManager.getCurrentVersions()
                        saveLastKnownVersions(currentVersions)
                    }
                }
            }

            if (config.enablePluginUpdates) {
                LoggingUtils.info(TAG, "Step 5: Downloading plugins...")
                success = pluginManager.ensureAllPluginsDownloaded() && success
            }

            if (config.enableTemplateSync) {
                LoggingUtils.info(TAG, "Step 6: Syncing templates...")
                success = templateManager.syncAllTemplates() && success
                success = templateManager.syncStaticServersOnRestart() && success
            }

            if (success && updatedTemplates.isNotEmpty()) {
                LoggingUtils.info(TAG, "Step 7: Restarting services with updated JARs...")
                restartServicesWithUpdatedTemplates()
            }

            updateLastUpdateTimestamp()
            LoggingUtils.info(TAG, "=== SCHEDULED UPDATE COMPLETED: ${if (success) "SUCCESS" else "PARTIAL FAILURE"} ===")
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error during scheduled update: ${e.message}", e)
        }
    }

    private suspend fun downloadAllServerVersionsDirectly(): Boolean = withContext(Dispatchers.IO) {
        LoggingUtils.debug(TAG, "Starting direct download of all server versions...")

        var overallSuccess = true
        val versions = serverVersionManager.getCurrentVersions()
        val serverVersionsDir = File(DirectoryPaths.paths.storagePath + "server-versions")
        serverVersionsDir.mkdirs()

        for (versionEntry in versions) {
            try {
                LoggingUtils.debug(TAG, "Processing ${versionEntry.name} versions...")

                val softwareDir = File(serverVersionsDir, versionEntry.name.lowercase())
                softwareDir.mkdirs()

                val latestVersion = versionEntry.downloadLinks.firstOrNull()
                if (latestVersion != null) {
                    val jarFile = File(softwareDir, "${versionEntry.name.lowercase()}-${latestVersion.version}.jar")

                    if (!jarFile.exists() || jarFile.length() == 0L) {
                        LoggingUtils.info(TAG, "Downloading ${versionEntry.name} ${latestVersion.version} from ${latestVersion.link}")

                        val downloadSuccess = downloadFile(latestVersion.link, jarFile)
                        if (downloadSuccess) {
                            LoggingUtils.info(TAG, "Successfully downloaded ${versionEntry.name} ${latestVersion.version} (${jarFile.length() / 1024}KB)")
                        } else {
                            LoggingUtils.error(TAG, "Failed to download ${versionEntry.name} ${latestVersion.version}")
                            overallSuccess = false
                        }
                    } else {
                        LoggingUtils.debug(TAG, "${versionEntry.name} ${latestVersion.version} already exists")
                    }
                }
            } catch (e: Exception) {
                LoggingUtils.error(TAG, "Error downloading ${versionEntry.name}: ${e.message}", e)
                overallSuccess = false
            }
        }

        return@withContext overallSuccess
    }

    private suspend fun downloadFile(url: String, targetFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "SimpleCloud-AutoUpdater/1.0")
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                LoggingUtils.error(TAG, "Download failed: HTTP ${response.code} for $url")
                return@withContext false
            }

            response.body?.let { responseBody ->
                targetFile.parentFile.mkdirs()

                val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")

                responseBody.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

                LoggingUtils.debug(TAG, "File downloaded successfully: ${targetFile.name}")
                return@withContext true
            } ?: run {
                LoggingUtils.error(TAG, "Empty response body for $url")
                return@withContext false
            }
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error downloading file from $url: ${e.message}", e)
            return@withContext false
        }
    }

    private suspend fun updateMinecraftJarsAndTemplates() = withContext(Dispatchers.IO) {
        val minecraftJarsDir = File(DirectoryPaths.paths.minecraftJarsPath)
        val serverVersionsDir = File(DirectoryPaths.paths.storagePath + "server-versions")
        val templatesDir = File(DirectoryPaths.paths.templatesPath)

        LoggingUtils.debug(TAG, "Updating JARs in minecraftJars: ${minecraftJarsDir.absolutePath}")
        LoggingUtils.debug(TAG, "Server versions dir: ${serverVersionsDir.absolutePath}")
        LoggingUtils.debug(TAG, "Templates dir: ${templatesDir.absolutePath}")

        if (!minecraftJarsDir.exists()) {
            minecraftJarsDir.mkdirs()
            LoggingUtils.debug(TAG, "Created minecraftJars directory")
        }

        updateLeafJarFiles(serverVersionsDir, minecraftJarsDir, templatesDir)
        updateVelocityCTDJarFiles(serverVersionsDir, minecraftJarsDir, templatesDir)
    }

    private fun updateLeafJarFiles(serverVersionsDir: File, minecraftJarsDir: File, templatesDir: File) {
        try {
            val leafDir = File(serverVersionsDir, "leaf")
            LoggingUtils.debug(TAG, "Looking for Leaf JARs in: ${leafDir.absolutePath}")

            if (!leafDir.exists()) {
                LoggingUtils.error(TAG, "Leaf versions directory not found at ${leafDir.absolutePath}")
                return
            }

            val jarFiles = leafDir.listFiles()?.filter { it.name.endsWith(".jar") && !it.name.endsWith(".backup") }
            LoggingUtils.debug(TAG, "Found ${jarFiles?.size ?: 0} Leaf JAR files")

            if (jarFiles.isNullOrEmpty()) {
                LoggingUtils.error(TAG, "No Leaf JAR files found in ${leafDir.absolutePath}")
                return
            }

            val latestLeafJar = jarFiles.maxByOrNull { it.lastModified() }
            LoggingUtils.debug(TAG, "Latest Leaf JAR: ${latestLeafJar?.name ?: "None"}")

            if (latestLeafJar != null) {
                LoggingUtils.info(TAG, "Latest Leaf JAR: ${latestLeafJar.name} (${latestLeafJar.length() / 1024}KB)")

                val versionFromName = extractVersionFromFilename(latestLeafJar.name) ?: "unknown"
                val targetJar = File(minecraftJarsDir, "LEAF_${sanitizeVersion(versionFromName)}.jar")

                Files.copy(latestLeafJar.toPath(), targetJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                LoggingUtils.info(TAG, "Updated minecraftJars: ${targetJar.name}")

                updateServerTemplates(templatesDir, latestLeafJar, "server.jar")
            }

        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error updating Leaf JAR: ${e.message}", e)
        }
    }

    private fun updateVelocityCTDJarFiles(serverVersionsDir: File, minecraftJarsDir: File, templatesDir: File) {
        try {
            val velocityCTDDir = File(serverVersionsDir, "velocityctd")
            LoggingUtils.debug(TAG, "Looking for VelocityCTD JARs in: ${velocityCTDDir.absolutePath}")

            if (!velocityCTDDir.exists()) {
                LoggingUtils.error(TAG, "VelocityCTD versions directory not found at ${velocityCTDDir.absolutePath}")
                return
            }

            val jarFiles = velocityCTDDir.listFiles()?.filter { it.name.endsWith(".jar") && !it.name.endsWith(".backup") }
            LoggingUtils.debug(TAG, "Found ${jarFiles?.size ?: 0} VelocityCTD JAR files")

            if (jarFiles.isNullOrEmpty()) {
                LoggingUtils.error(TAG, "No VelocityCTD JAR files found in ${velocityCTDDir.absolutePath}")
                return
            }

            val latestVelocityJar = jarFiles.maxByOrNull { it.lastModified() }
            if (latestVelocityJar != null) {
                LoggingUtils.info(TAG, "Latest VelocityCTD JAR: ${latestVelocityJar.name} (${latestVelocityJar.length() / 1024}KB)")

                val versionFromName = extractVersionFromFilename(latestVelocityJar.name) ?: "unknown"
                val targetJar = File(minecraftJarsDir, "VELOCITYCTD_${sanitizeVersion(versionFromName)}.jar")

                Files.copy(latestVelocityJar.toPath(), targetJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                LoggingUtils.info(TAG, "Updated minecraftJars: ${targetJar.name}")

                updateProxyTemplates(templatesDir, latestVelocityJar, "velocity.jar")
            }
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error updating VelocityCTD JAR: ${e.message}", e)
        }
    }

    private fun updateServerTemplates(templatesDir: File, newJar: File, targetFileName: String) {
        if (!templatesDir.exists()) {
            LoggingUtils.error(TAG, "Templates directory not found: ${templatesDir.absolutePath}")
            return
        }

        LoggingUtils.debug(TAG, "Updating server templates with ${newJar.name}")
        var templatesUpdated = 0

        val jarType = when {
            newJar.name.contains("leaf", ignoreCase = true) -> "LEAF"
            newJar.name.contains("paper", ignoreCase = true) -> "PAPER"
            else -> "UNKNOWN"
        }

        templatesDir.listFiles()?.forEach { templateDir ->
            if (templateDir.isDirectory) {
                val isServerTemplate = !File(templateDir, "velocity.toml").exists() &&
                        !File(templateDir, "config.yml").exists()

                if (isServerTemplate) {
                    val templateName = templateDir.name
                    val shouldUpdate = shouldUpdateTemplate(templateName, jarType)

                    if (shouldUpdate) {
                        val serverJar = File(templateDir, targetFileName)

                        if (serverJar.exists() && config.templates.enableTemplateBackup) {
                            val backupFile = File(templateDir, "$targetFileName.backup-${System.currentTimeMillis()}")
                            Files.copy(serverJar.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            LoggingUtils.debug(TAG, "Backup created: ${templateDir.name}/$targetFileName.backup")
                        }

                        Files.copy(newJar.toPath(), serverJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        LoggingUtils.info(TAG, "✅ Updated template JAR: ${templateDir.name}/$targetFileName ($jarType)")

                        updatedTemplates.add(templateDir.name)
                        templatesUpdated++
                    } else {
                        LoggingUtils.debug(TAG, "Skipped template ${templateDir.name} - uses different server type")
                    }
                }
            }
        }

        LoggingUtils.info(TAG, "Updated $templatesUpdated server templates with ${newJar.name}")
    }

    private fun updateProxyTemplates(templatesDir: File, newJar: File, targetFileName: String) {
        if (!templatesDir.exists()) {
            LoggingUtils.error(TAG, "Templates directory not found: ${templatesDir.absolutePath}")
            return
        }

        LoggingUtils.debug(TAG, "Updating proxy templates with ${newJar.name}")
        var templatesUpdated = 0

        val jarType = when {
            newJar.name.contains("velocity", ignoreCase = true) -> "VELOCITY"
            newJar.name.contains("waterfall", ignoreCase = true) -> "WATERFALL"
            else -> "UNKNOWN"
        }

        templatesDir.listFiles()?.forEach { templateDir ->
            if (templateDir.isDirectory) {
                val isProxyTemplate = File(templateDir, "velocity.toml").exists() ||
                        File(templateDir, "config.yml").exists()

                if (isProxyTemplate) {
                    val templateName = templateDir.name
                    val shouldUpdate = shouldUpdateTemplate(templateName, jarType)

                    if (shouldUpdate) {
                        val velocityJar = File(templateDir, targetFileName)

                        if (velocityJar.exists() && config.templates.enableTemplateBackup) {
                            val backupFile = File(templateDir, "$targetFileName.backup-${System.currentTimeMillis()}")
                            Files.copy(velocityJar.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            LoggingUtils.debug(TAG, "Backup created: ${templateDir.name}/$targetFileName.backup")
                        }

                        Files.copy(newJar.toPath(), velocityJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        LoggingUtils.info(TAG, "✅ Updated template JAR: ${templateDir.name}/$targetFileName ($jarType)")

                        updatedTemplates.add(templateDir.name)
                        templatesUpdated++
                    } else {
                        LoggingUtils.debug(TAG, "Skipped template ${templateDir.name} - uses different proxy type")
                    }
                }
            }
        }

        LoggingUtils.info(TAG, "Updated $templatesUpdated proxy templates with ${newJar.name}")
    }

    private fun shouldUpdateTemplate(templateName: String, jarType: String): Boolean {
        try {
            val serviceGroupManager = CloudAPI.instance.getCloudServiceGroupManager()
            val group = serviceGroupManager.getAllCachedObjects()
                .find { it.getTemplateName() == templateName }

            if (group == null) {
                LoggingUtils.debug(TAG, "No group found for template $templateName")
                return false
            }

            val serviceVersionName = group.getServiceVersion().name

            if (serviceVersionName.isEmpty()) {
                LoggingUtils.debug(TAG, "Group $templateName has no service version defined")
                return false
            }

            LoggingUtils.debug(TAG, "Template $templateName uses service version: $serviceVersionName")

            if (isMinecraftJarsServiceVersion(serviceVersionName)) {
                LoggingUtils.debug(TAG, "Template $templateName uses minecraftJars service version, skipping template update")
                return false
            }

            return when (jarType) {
                "LEAF" -> serviceVersionName.startsWith("LEAF_", ignoreCase = true)
                "PAPER" -> serviceVersionName.startsWith("PAPER_", ignoreCase = true)
                "VELOCITY" -> serviceVersionName.startsWith("VELOCITY_", ignoreCase = true) ||
                        serviceVersionName.startsWith("VELOCITYCTD_", ignoreCase = true)
                "WATERFALL" -> serviceVersionName.startsWith("WATERFALL_", ignoreCase = true)
                else -> {
                    LoggingUtils.debug(TAG, "Unknown JAR type: $jarType")
                    false
                }
            }
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error checking template compatibility for $templateName: ${e.message}", e)
            return false
        }
    }

    private fun isMinecraftJarsServiceVersion(serviceVersionName: String): Boolean {
        val minecraftJarsDir = File(DirectoryPaths.paths.minecraftJarsPath)
        if (!minecraftJarsDir.exists()) return false

        val jarFiles = minecraftJarsDir.listFiles()?.filter { it.name.endsWith(".jar") } ?: return false

        return jarFiles.any { jarFile ->
            val jarNameWithoutExtension = jarFile.nameWithoutExtension
            jarNameWithoutExtension.equals(serviceVersionName, ignoreCase = true)
        }
    }

    private fun extractVersionFromFilename(filename: String): String? {

        val patterns = listOf(
            Regex("leaf-(\\d+\\.\\d+\\.\\d+)"),
            Regex("velocity.*?-(\\d+\\.\\d+\\.\\d+)"),
            Regex("velocityctd-([^.]+)"),
            Regex("(\\d+\\.\\d+\\.\\d+)"),
            Regex("(\\d+\\.\\d+)")
        )

        for (pattern in patterns) {
            val match = pattern.find(filename)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        val fallbackPattern = Regex("[^-]+-([^.]+)")
        val fallbackMatch = fallbackPattern.find(filename)
        return fallbackMatch?.groupValues?.get(1)
    }

    private fun sanitizeVersion(version: String): String {

        val versionRegex = Regex("(\\d+\\.\\d+(?:\\.\\d+)?(?:-[a-zA-Z0-9]+)?)")
        val match = versionRegex.find(version)

        return if (match != null) {
            match.value.replace(".", "_").replace("-", "_")
        } else {
            version.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                .replace(".", "_")
                .replace("-", "_")
        }
    }

    private suspend fun restartServicesWithUpdatedTemplates() = withContext(Dispatchers.IO) {
        if (updatedTemplates.isEmpty()) {
            LoggingUtils.debug(TAG, "No templates were updated, skipping service restart")
            return@withContext
        }

        LoggingUtils.info(TAG, "🔄 Templates updated: ${updatedTemplates.joinToString(", ")}")

        try {
            val serviceManager = CloudAPI.instance.getCloudServiceManager()
            val servicesToRestart = mutableListOf<ICloudService>()

            for (templateName in updatedTemplates) {

                val servicesWithTemplate = serviceManager.getAllCachedObjects().filter {
                    it.getTemplateName() == templateName && it.isActive()
                }

                servicesToRestart.addAll(servicesWithTemplate)
                LoggingUtils.info(TAG, "Found ${servicesWithTemplate.size} active services using template $templateName")
            }

            if (servicesToRestart.isEmpty()) {
                LoggingUtils.info(TAG, "No active services found using updated templates")
                return@withContext
            }

            LoggingUtils.info(TAG, "🚀 Restarting ${servicesToRestart.size} services with updated JARs...")

            for (service in servicesToRestart) {
                try {
                    LoggingUtils.info(TAG, "🔄 Restarting service: ${service.getName()}")

                    service.shutdown().awaitUninterruptibly()
                    LoggingUtils.info(TAG, "⏹️ Stopped service: ${service.getName()}")

                    delay(3000)

                    service.start().awaitUninterruptibly()
                    LoggingUtils.info(TAG, "✅ Service ${service.getName()} restarted successfully")

                    delay(2000)

                } catch (e: Exception) {
                    LoggingUtils.error(TAG, "❌ Error restarting service ${service.getName()}: ${e.message}", e)
                }
            }

            savePendingRestarts(servicesToRestart.map { it.getName() })

            LoggingUtils.info(TAG, "🎉 Service restart process completed! All services should now be running with updated JARs.")
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error during service restart process: ${e.message}", e)
        }
    }

    private fun savePendingRestarts(serviceNames: List<String>) {
        try {
            val restartData = mapOf(
                "timestamp" to System.currentTimeMillis(),
                "services" to serviceNames,
                "templates" to updatedTemplates.toList()
            )

            val jsonData = JsonLib.fromObject(restartData)
            pendingRestartsFile.parentFile.mkdirs()
            jsonData.saveAsFile(pendingRestartsFile)

            LoggingUtils.debug(TAG, "Saved pending restart information for ${serviceNames.size} services")
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error saving pending restart information: ${e.message}", e)
        }
    }

    private fun createDefaultConfig(): AutoManagerConfig {
        return AutoManagerConfig(
            enableAutomation = true,
            enableServerVersionUpdates = true,
            enablePluginUpdates = true,
            enableTemplateSync = true,
            enableNotifications = false,
            enableBackup = true,
            updateInterval = "04:00",
            updateTime = "04:00",
            serverSoftware = listOf("paper", "leaf", "velocityctd"),
            plugins = listOf(
                AutoManagerConfig.PluginConfig(
                    name = "LuckPerms",
                    enabled = true,
                    platforms = listOf("bukkit", "velocity"),
                    customUrl = null
                ),
                AutoManagerConfig.PluginConfig(
                    name = "Spark",
                    enabled = true,
                    platforms = listOf("bukkit", "velocity"),
                    customUrl = null
                )
            ),
            templates = AutoManagerConfig.TemplateConfig(
                autoCreateBaseTemplates = true,
                syncOnStart = true,
                enableTemplateBackup = true
            )
        )
    }

    private fun checkIfUpdateNeeded(): Boolean {
        val lastUpdateFile = File(DirectoryPaths.paths.storagePath + "last_update.txt")

        return if (lastUpdateFile.exists()) {
            val lastUpdate = lastUpdateFile.readText().toLongOrNull() ?: 0L
            val dayInMillis = 24 * 60 * 60 * 1000
            System.currentTimeMillis() - lastUpdate > dayInMillis
        } else {
            true
        }
    }

    private fun updateLastUpdateTimestamp() {
        try {
            val lastUpdateFile = File(DirectoryPaths.paths.storagePath + "last_update.txt")
            lastUpdateFile.parentFile.mkdirs()
            lastUpdateFile.writeText(System.currentTimeMillis().toString())
            LoggingUtils.debug(TAG, "Updated last update timestamp")
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error updating timestamp: ${e.message}", e)
        }
    }

    fun saveConfig() {
        try {
            configFile.parentFile.mkdirs()
            JsonLib.fromObject(AutoManagerConfig.toJson(config)).saveAsFile(configFile)
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error saving config: ${e.message}", e)
        }
    }

    suspend fun forceUpdate(): Boolean {
        LoggingUtils.info(TAG, "🚀 Force update requested")

        return try {
            performScheduledUpdate()
            true
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error during force update: ${e.message}", e)
            false
        }
    }

    fun checkVersions(): Map<String, String> {
        val versions = mutableMapOf<String, String>()

        serverVersionManager.getCurrentVersions().forEach { entry ->
            versions[entry.name] = entry.latestVersion
        }

        return versions
    }

    fun getConfig(): AutoManagerConfig = config
    fun getServerVersionManager(): ServerVersionManager = serverVersionManager
    fun getPluginManager(): PluginManager = pluginManager
    fun getTemplateManager(): TemplateManager = templateManager
    fun getUpdateScheduler(): UpdateScheduler = updateScheduler
    fun getUpdaterAPI(): UpdaterAPI = updaterAPI
    fun isEnabled(): Boolean = config.enableAutomation

    fun getStats(): Map<String, Any> {
        return mapOf(
            "enabled" to config.enableAutomation,
            "next_update" to getNextUpdateTime(),
            "configured_plugins" to config.plugins.size,
            "enabled_plugins" to config.plugins.count { it.enabled },
            "server_software" to config.serverSoftware.size,
            "update_time" to config.updateTime,
            "last_known_versions" to loadLastKnownVersions(),
            "updated_templates" to updatedTemplates.toList()
        )
    }

    private fun getNextUpdateTime(): String {
        return if (config.updateTime.isNotEmpty()) {
            val updateTime = LocalTime.parse(config.updateTime)
            val now = LocalDateTime.now()
            var nextUpdate = now.with(updateTime)

            if (nextUpdate.isBefore(now)) {
                nextUpdate = nextUpdate.plusDays(1)
            }

            nextUpdate.toString()
        } else {
            "Using interval: ${config.updateInterval}"
        }
    }
}