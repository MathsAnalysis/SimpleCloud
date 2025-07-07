package eu.thesimplecloud.module.updater.plugin

import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.jsonlib.JsonLib
import eu.thesimplecloud.module.updater.config.AutoManagerConfig
import eu.thesimplecloud.module.updater.utils.LoggingUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class PluginManager(private val config: AutoManagerConfig) {

    companion object {
        private const val TAG = "PluginManager"
        private const val MAX_FILE_SIZE = 100 * 1024 * 1024 // 100MB
        private const val UPDATE_INTERVAL_HOURS = 24
    }

    private val pluginsDirectory = File(DirectoryPaths.paths.storagePath + "plugins")
    private val pluginVersionsFile = File(DirectoryPaths.paths.storagePath + "plugin-versions.json")
    private val logsDirectory = File(DirectoryPaths.paths.storagePath + "modules/plugin-updater/logs")
    private val logFile = File(logsDirectory, "plugin-manager-${System.currentTimeMillis()}.log")

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    init {
        LoggingUtils.init(TAG, "Initializing PluginManager...")

        initializeDirectories()
        logInitialStats()
    }

    private fun initializeDirectories() {
        LoggingUtils.debug(TAG, "Creating necessary directories...")

        if (!pluginsDirectory.exists()) {
            pluginsDirectory.mkdirs()
            LoggingUtils.debug(TAG, "Created plugins directory: ${pluginsDirectory.absolutePath}")
        }

        if (!logsDirectory.exists()) {
            logsDirectory.mkdirs()
            LoggingUtils.debug(TAG, "Created logs directory: ${logsDirectory.absolutePath}")
        }
    }

    private fun logInitialStats() {
        LoggingUtils.debugConfig(TAG, "plugins_directory", pluginsDirectory.absolutePath)
        LoggingUtils.debugConfig(TAG, "enable_plugin_updates", config.enablePluginUpdates)
        LoggingUtils.debugConfig(TAG, "configured_plugins_count", config.plugins.size)
        LoggingUtils.debugConfig(TAG, "enabled_plugins_count", config.plugins.count { it.enabled })

        if (config.enableDebug) {
            val enabledPlugins = config.plugins.filter { it.enabled }.map { it.name }
            LoggingUtils.debugConfig(TAG, "enabled_plugins", enabledPlugins)
        }
    }

    private fun log(message: String) {
        val timestamp = LocalDateTime.now()
        val logMessage = "[$timestamp] $message"
        LoggingUtils.info(TAG, message)

        try {
            logFile.appendText("$logMessage\n")
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Failed to write to log file: ${e.message}", e)
        }
    }

    suspend fun ensureAllPluginsDownloaded(): Boolean {
        LoggingUtils.debugStart(TAG, "plugin download check")

        return try {
            LoggingUtils.debug(TAG, "Configured plugins: ${config.plugins.map { it.name }}")
            var success = true
            var downloadedCount = 0
            var skippedCount = 0
            var failedCount = 0

            config.plugins.forEach { pluginConfig ->
                try {
                    LoggingUtils.debug(TAG, "Checking plugin: ${pluginConfig.name} (enabled=${pluginConfig.enabled})")

                    if (!pluginConfig.enabled) {
                        LoggingUtils.debug(TAG, "Plugin ${pluginConfig.name} is disabled, skipping")
                        skippedCount++
                        return@forEach
                    }

                    if (!isPluginAlreadyDownloaded(pluginConfig)) {
                        LoggingUtils.debug(TAG, "Plugin ${pluginConfig.name} needs to be downloaded")

                        val pluginInfo = updatePlugin(pluginConfig)
                        if (pluginInfo != null) {
                            LoggingUtils.debug(TAG, "Plugin info retrieved for ${pluginConfig.name}: ${pluginInfo.toLogSummary()}")
                            downloadPluginFiles(pluginConfig.name, pluginInfo)
                            LoggingUtils.info(TAG, "Successfully downloaded ${pluginConfig.name}")
                            downloadedCount++
                        } else {
                            LoggingUtils.error(TAG, "Failed to get plugin info for ${pluginConfig.name}")
                            success = false
                            failedCount++
                        }
                    } else {
                        LoggingUtils.debug(TAG, "Plugin ${pluginConfig.name} is already downloaded")
                        skippedCount++
                    }
                } catch (e: Exception) {
                    LoggingUtils.error(TAG, "Error downloading ${pluginConfig.name}: ${e.message}", e)
                    success = false
                    failedCount++
                }
            }

            val stats = mapOf(
                "downloaded" to downloadedCount,
                "skipped" to skippedCount,
                "failed" to failedCount,
                "total" to config.plugins.size
            )

            LoggingUtils.debugStats(TAG, stats)

            if (success) {
                LoggingUtils.debugSuccess(TAG, "plugin download check")
            } else {
                LoggingUtils.debugFailure(TAG, "plugin download check", "$failedCount plugins failed")
            }

            success
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error in ensureAllPluginsDownloaded: ${e.message}", e)
            false
        }
    }

    private fun isPluginAlreadyDownloaded(pluginConfig: AutoManagerConfig.PluginConfig): Boolean {
        LoggingUtils.debug(TAG, "Checking if plugin ${pluginConfig.name} is already downloaded...")

        val result = pluginConfig.platforms.any { platform ->
            val platformDir = File(pluginsDirectory, "${pluginConfig.name}/$platform")
            val exists = platformDir.exists() && platformDir.listFiles()?.any {
                it.isFile && it.extension == "jar"
            } == true

            LoggingUtils.debug(TAG, "Platform ${pluginConfig.name}/$platform exists: $exists")
            exists
        }

        LoggingUtils.debug(TAG, "Plugin ${pluginConfig.name} already downloaded: $result")
        return result
    }

    private suspend fun updatePlugin(pluginConfig: AutoManagerConfig.PluginConfig): PluginInfo? = withContext(Dispatchers.IO) {
        LoggingUtils.debugStart(TAG, "plugin info retrieval for ${pluginConfig.name}")

        return@withContext try {
            val pluginInfo = when {
                pluginConfig.customUrl != null -> {
                    LoggingUtils.debug(TAG, "Using custom URL for ${pluginConfig.name}: ${pluginConfig.customUrl}")
                    getPluginInfoFromCustomUrl(pluginConfig.name, pluginConfig.customUrl!!, pluginConfig.platforms)
                }
                else -> {
                    LoggingUtils.debug(TAG, "Fetching plugin info from API for ${pluginConfig.name}")
                    getPluginInfoFromAPI(pluginConfig.name, pluginConfig.platforms)
                }
            }

            if (pluginInfo != null) {
                LoggingUtils.debugSuccess(TAG, "plugin info retrieval for ${pluginConfig.name}")
                if (config.enableDebug) {
                    LoggingUtils.debug(TAG, "Retrieved plugin info:\n${pluginInfo.toDebugString()}")
                } else {
                    LoggingUtils.debug(TAG, "Retrieved plugin info: ${pluginInfo.toLogSummary()}")
                }
            } else {
                LoggingUtils.debugFailure(TAG, "plugin info retrieval for ${pluginConfig.name}", "no plugin info returned")
            }

            pluginInfo
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error updating plugin ${pluginConfig.name}: ${e.message}", e)
            null
        }
    }

    private suspend fun getPluginInfoFromAPI(pluginName: String, platforms: List<String>): PluginInfo? = withContext(Dispatchers.IO) {
        LoggingUtils.debugNetwork(TAG, "API request", "plugin info for $pluginName")

        try {
            val apiUrl = "https://api.spiget.org/v2/search/resources/$pluginName?field=name"
            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("User-Agent", "SimpleCloud-AutoUpdater")
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                LoggingUtils.error(TAG, "API request failed with code: ${response.code}")
                return@withContext null
            }

            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                LoggingUtils.error(TAG, "Empty response from API")
                return@withContext null
            }

            LoggingUtils.debug(TAG, "API response received (${responseBody.length} characters)")

            // Create platforms map with correct structure
            val platformsMap = platforms.associateWith { platform ->
                "https://api.spiget.org/v2/resources/$pluginName/download"
            }

            // Use the PluginInfo.create factory method for consistency
            val pluginInfo = PluginInfo.create(
                name = pluginName,
                version = "latest",
                platforms = platformsMap,
                checksum = null,
                fileSize = null
            )

            LoggingUtils.debug(TAG, "Created PluginInfo: ${pluginInfo.toLogSummary()}")
            return@withContext pluginInfo

        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error fetching plugin info from API: ${e.message}", e)
            null
        }
    }

    private fun getPluginInfoFromCustomUrl(pluginName: String, customUrl: String, platforms: List<String>): PluginInfo {
        LoggingUtils.debugNetwork(TAG, "Custom URL", customUrl)

        val platformsMap = platforms.associateWith { customUrl }

        val pluginInfo = PluginInfo.create(
            name = pluginName,
            version = "custom",
            platforms = platformsMap,
            checksum = null,
            fileSize = null
        )

        LoggingUtils.debug(TAG, "Created custom PluginInfo: ${pluginInfo.toLogSummary()}")
        return pluginInfo
    }

    private suspend fun downloadPluginFiles(pluginName: String, pluginInfo: PluginInfo) = withContext(Dispatchers.IO) {
        LoggingUtils.debugStart(TAG, "downloading files for $pluginName")

        pluginInfo.platforms.forEach { (platform, url) ->
            try {
                LoggingUtils.debugNetwork(TAG, "Downloading", "$pluginName/$platform from $url")

                val platformDir = File(pluginsDirectory, "$pluginName/$platform")
                if (!platformDir.exists()) {
                    platformDir.mkdirs()
                    LoggingUtils.debug(TAG, "Created directory: ${platformDir.absolutePath}")
                }

                val targetFile = File(platformDir, "$pluginName-${pluginInfo.version}.jar")

                if (shouldDownloadFile(targetFile, url)) {
                    LoggingUtils.debug(TAG, "Downloading file: ${targetFile.name}")

                    val request = Request.Builder()
                        .url(url)
                        .addHeader("User-Agent", "SimpleCloud-AutoUpdater")
                        .build()

                    val response = okHttpClient.newCall(request).execute()

                    if (!response.isSuccessful) {
                        throw Exception("HTTP ${response.code}: ${response.message}")
                    }

                    val responseBody = response.body ?: throw Exception("Empty response body")

                    targetFile.writeBytes(responseBody.bytes())

                    if (targetFile.length() > MAX_FILE_SIZE) {
                        targetFile.delete()
                        throw Exception("File too large: ${targetFile.length()} bytes")
                    }

                    if (!isValidJarFile(targetFile)) {
                        targetFile.delete()
                        throw Exception("Invalid JAR file")
                    }

                    val fileSizeKB = targetFile.length() / 1024
                    LoggingUtils.debug(TAG, "Successfully downloaded: $pluginName/$platform (${fileSizeKB} KB)")
                } else {
                    LoggingUtils.debug(TAG, "File already exists and is recent: ${targetFile.name}")
                }

            } catch (e: Exception) {
                LoggingUtils.error(TAG, "Error downloading $pluginName/$platform: ${e.message}", e)
                throw e
            }
        }

        LoggingUtils.debugSuccess(TAG, "downloading files for $pluginName")
    }

    private fun shouldDownloadFile(file: File, url: String): Boolean {
        if (!file.exists()) {
            LoggingUtils.debug(TAG, "File does not exist, download needed: ${file.name}")
            return true
        }

        val lastModified = file.lastModified()
        val hoursSinceModified = (System.currentTimeMillis() - lastModified) / (1000 * 60 * 60)
        val shouldDownload = hoursSinceModified > UPDATE_INTERVAL_HOURS

        LoggingUtils.debug(TAG, "File ${file.name} last modified ${hoursSinceModified}h ago, should download: $shouldDownload")
        return shouldDownload
    }

    private fun isValidJarFile(file: File): Boolean {
        LoggingUtils.debug(TAG, "Validating JAR file: ${file.name}")

        return try {
            val bytes = file.readBytes()
            val isValid = bytes.size >= 4 &&
                    bytes[0] == 0x50.toByte() &&
                    bytes[1] == 0x4B.toByte() &&
                    (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte()) &&
                    (bytes[3] == 0x04.toByte() || bytes[3] == 0x06.toByte() || bytes[3] == 0x08.toByte())

            LoggingUtils.debug(TAG, "JAR validation result for ${file.name}: $isValid")
            isValid
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error validating JAR file ${file.name}: ${e.message}", e)
            false
        }
    }

    private fun savePluginManifest(plugins: Map<String, PluginInfo>) {
        LoggingUtils.debugStart(TAG, "saving plugin manifest")

        try {
            if (pluginVersionsFile.exists() && config.enableBackup) {
                val backupFile = File(pluginVersionsFile.parentFile, "plugin-versions.json.backup")
                pluginVersionsFile.copyTo(backupFile, overwrite = true)
                LoggingUtils.debug(TAG, "Created backup of plugin manifest")
            }

            val manifest = mapOf(
                "version" to "1.0.0",
                "last_updated" to System.currentTimeMillis().toString(),
                "plugins" to plugins
            )

            JsonLib.empty()
                .append("manifest", manifest)
                .saveAsFile(pluginVersionsFile)

            LoggingUtils.debugSuccess(TAG, "saving plugin manifest")
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error saving plugin manifest: ${e.message}", e)
        }
    }

    fun createPluginDirectoryStructure() {
        LoggingUtils.debugStart(TAG, "creating plugin directory structure")

        var createdCount = 0
        var existingCount = 0

        config.plugins.forEach { pluginConfig ->
            if (pluginConfig.enabled) {
                val pluginDir = File(pluginsDirectory, pluginConfig.name)

                pluginConfig.platforms.forEach { platform ->
                    val platformDir = File(pluginDir, platform)
                    if (!platformDir.exists()) {
                        platformDir.mkdirs()
                        LoggingUtils.debug(TAG, "Created directory: ${platformDir.absolutePath}")
                        createdCount++
                    } else {
                        existingCount++
                    }
                }
            }
        }

        LoggingUtils.debugSuccess(TAG, "creating plugin directory structure")
        LoggingUtils.info(TAG, "Plugin directory structure creation completed ($createdCount created, $existingCount existing)")
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
                                    val lastModified = Instant.ofEpochMilli(jar.lastModified())
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

    fun cleanupTemporaryFiles() {
        LoggingUtils.debugStart(TAG, "cleaning up temporary files")

        try {
            var cleanedCount = 0
            var cleanedSize = 0L

            // Clean temp directory if enabled
            if (config.performance.cleanupTempFiles) {
                val tempDir = File(config.performance.tempDirectory)
                if (tempDir.exists()) {
                    tempDir.listFiles()?.forEach { file ->
                        val size = file.length()
                        if (file.deleteRecursively()) {
                            cleanedCount++
                            cleanedSize += size
                            LoggingUtils.debug(TAG, "Cleaned temp file: ${file.name}")
                        }
                    }
                }
            }

            // Clean .tmp files in plugins directory
            cleanTmpFilesInDirectory(pluginsDirectory)?.let { (count, size) ->
                cleanedCount += count
                cleanedSize += size
            }

            // Clean old log files
            cleanOldLogFiles()?.let { (count, size) ->
                cleanedCount += count
                cleanedSize += size
            }

            val stats = mapOf(
                "files_cleaned" to cleanedCount,
                "size_cleaned_mb" to cleanedSize / 1024 / 1024
            )
            LoggingUtils.debugStats(TAG, stats)

            LoggingUtils.debugSuccess(TAG, "cleaning up temporary files")
            LoggingUtils.info(TAG, "Cleaned $cleanedCount temporary files (${cleanedSize / 1024 / 1024}MB)")

        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error cleaning temporary files: ${e.message}", e)
        }
    }

    private fun cleanTmpFilesInDirectory(directory: File): Pair<Int, Long>? {
        if (!directory.exists()) return null

        var count = 0
        var size = 0L

        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                cleanTmpFilesInDirectory(file)?.let { (subCount, subSize) ->
                    count += subCount
                    size += subSize
                }
            } else if (file.name.endsWith(".tmp") || file.name.endsWith(".temp")) {
                size += file.length()
                if (file.delete()) {
                    count++
                    LoggingUtils.debug(TAG, "Cleaned temp file: ${file.relativeTo(pluginsDirectory)}")
                }
            }
        }

        return Pair(count, size)
    }

    private fun cleanOldLogFiles(): Pair<Int, Long>? {
        if (!logsDirectory.exists()) return null

        var count = 0
        var size = 0L
        val maxAge = 7 * 24 * 60 * 60 * 1000L // 7 days
        val currentTime = System.currentTimeMillis()

        logsDirectory.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith("plugin-manager-")) {
                val age = currentTime - file.lastModified()
                if (age > maxAge) {
                    size += file.length()
                    if (file.delete()) {
                        count++
                        LoggingUtils.debug(TAG, "Cleaned old log file: ${file.name}")
                    }
                }
            }
        }

        return Pair(count, size)
    }

    fun getStats(): Map<String, Any> {
        val enabledPlugins = config.plugins.filter { it.enabled }
        val downloadedPlugins = enabledPlugins.filter { isPluginAlreadyDownloaded(it) }

        return mapOf(
            "total_configured" to config.plugins.size,
            "enabled" to enabledPlugins.size,
            "downloaded" to downloadedPlugins.size,
            "plugins_directory" to pluginsDirectory.absolutePath,
            "update_interval_hours" to UPDATE_INTERVAL_HOURS
        )
    }

}