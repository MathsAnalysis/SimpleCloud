package eu.thesimplecloud.module.updater.manager

import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.jsonlib.JsonLib
import eu.thesimplecloud.module.updater.config.AutoManagerConfig
import eu.thesimplecloud.module.updater.plugin.PluginInfo
import eu.thesimplecloud.module.updater.utils.LoggingUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URL
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class PluginManager(private val config: AutoManagerConfig) {

    companion object {
        private const val TAG = "PluginManager"
    }

    private val pluginsDirectory = File(DirectoryPaths.paths.storagePath + "plugins")
    private val pluginVersionsFile = File(DirectoryPaths.paths.storagePath + "plugin-versions.json")
    private val logsDirectory = File(DirectoryPaths.paths.storagePath + "modules/plugin-updater/logs")
    private val logFile = File(logsDirectory, "plugin-manager-${System.currentTimeMillis()}.log")

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.networking.connectTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(config.networking.readTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .writeTimeout(config.networking.writeTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .followRedirects(config.networking.followRedirects)
        .followSslRedirects(config.networking.enableHttps)
        .build()

    init {
        LoggingUtils.init(TAG, "Initializing PluginManager...")
        initializeDirectories()
        logInitialStats()
    }

    private fun initializeDirectories() {
        LoggingUtils.debug(TAG, "Ensuring necessary directories exist...")

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
                    when (pluginConfig.name.lowercase()) {
                        "luckperms" -> updateLuckPerms(pluginConfig)
                        "spark" -> updateSpark(pluginConfig)
                        "floodgate" -> updateFloodgate(pluginConfig)
                        "geyser" -> updateGeyser(pluginConfig)
                        "protocollib" -> updateProtocolLib(pluginConfig)
                        "placeholderapi" -> updatePlaceholderAPI(pluginConfig)
                        else -> {
                            if (pluginConfig.customUrl != null) {
                                updateCustomPlugin(pluginConfig)
                            } else {
                                null
                            }
                        }
                    }
                }
            }

            LoggingUtils.debug(TAG, "Plugin info created: ${pluginInfo?.toLogSummary()}")
            pluginInfo

        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error updating plugin ${pluginConfig.name}: ${e.message}", e)
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

    private suspend fun updateFloodgate(pluginConfig: AutoManagerConfig.PluginConfig): PluginInfo =
        withContext(Dispatchers.IO) {
            val platforms = mutableMapOf<String, String>()

            pluginConfig.platforms.forEach { platform ->
                val mappedPlatform = when (platform) {
                    "bukkit" -> "spigot"
                    "bungeecord" -> "bungee"
                    else -> platform
                }

                val downloadUrl = "https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/floodgate-$mappedPlatform.jar"
                platforms[platform] = downloadUrl
            }

            PluginInfo(
                name = "Floodgate",
                version = "latest",
                platforms = platforms,
                lastUpdated = System.currentTimeMillis().toString()
            )
        }

    private suspend fun updateGeyser(pluginConfig: AutoManagerConfig.PluginConfig): PluginInfo =
        withContext(Dispatchers.IO) {
            val response = URL("https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest").readText()
            val data = JsonLib.Companion.fromJsonString(response)!!

            val version = data.getString("version")!!
            val downloads = data.getProperty("downloads")!!.getAsJsonArray("downloads")!!

            val platforms = mutableMapOf<String, String>()

            pluginConfig.platforms.forEach { platform ->
                val mappedPlatform = when (platform) {
                    "bukkit" -> "spigot"
                    "bungeecord" -> "bungee"
                    else -> platform
                }

                val download = downloads.find { download ->
                    val name = download.getAsJsonObject().get("name").asString
                    name.contains(mappedPlatform, true)
                }

                download?.let {
                    val downloadPath = it.getAsJsonObject().get("path").asString
                    val downloadUrl = "https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/$downloadPath"
                    platforms[platform] = downloadUrl
                }
            }

            PluginInfo(
                name = "Geyser",
                version = version,
                platforms = platforms,
                lastUpdated = System.currentTimeMillis().toString()
            )
        }

    private suspend fun updateLuckPerms(pluginConfig: AutoManagerConfig.PluginConfig): PluginInfo =
        withContext(Dispatchers.IO) {
            val response = URL("https://metadata.luckperms.net/data/downloads").readText()
            val data = JsonLib.fromJsonString(response)!!

            val version = data.getString("version")!!
            val downloads = data.getProperty("downloads")!!

            val platforms = mutableMapOf<String, String>()

            pluginConfig.platforms.forEach { platform ->
                val mappedPlatform = when (platform) {
                    "bukkit" -> "bukkit"
                    "bungeecord" -> "bungee"
                    "velocity" -> "velocity"
                    else -> platform
                }

                try {
                    val url = downloads.getString(mappedPlatform)
                    if (url != null) {
                        platforms[platform] = url
                    }
                } catch (e: Exception) {
                    LoggingUtils.debug(TAG, "Platform $mappedPlatform not found for LuckPerms")
                }
            }

            PluginInfo(
                name = "LuckPerms",
                version = version,
                platforms = platforms,
                lastUpdated = System.currentTimeMillis().toString()
            )
        }

    private suspend fun updateSpark(pluginConfig: AutoManagerConfig.PluginConfig): PluginInfo =
        withContext(Dispatchers.IO) {
            val platforms = mutableMapOf<String, String>()

            pluginConfig.platforms.forEach { platform ->
                val url = when (platform) {
                    "bukkit" -> "https://ci.lucko.me/job/spark/lastSuccessfulBuild/artifact/spark-bukkit/build/libs/spark-bukkit.jar"
                    "bungeecord" -> "https://ci.lucko.me/job/spark/lastSuccessfulBuild/artifact/spark-bungeecord/build/libs/spark-bungeecord.jar"
                    "velocity" -> "https://ci.lucko.me/job/spark/lastSuccessfulBuild/artifact/spark-velocity/build/libs/spark-velocity.jar"
                    else -> null
                }

                url?.let { platforms[platform] = it }
            }

            PluginInfo(
                name = "Spark",
                version = "latest",
                platforms = platforms,
                lastUpdated = System.currentTimeMillis().toString()
            )
        }

    private suspend fun updateProtocolLib(pluginConfig: AutoManagerConfig.PluginConfig): PluginInfo =
        withContext(Dispatchers.IO) {
            val response = URL("https://api.github.com/repos/dmulloy2/ProtocolLib/releases/latest").readText()

            val versionMatch = "\"tag_name\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(response)
            val version = versionMatch?.groupValues?.get(1) ?: "unknown"

            val platforms = mutableMapOf<String, String>()

            val jarUrlMatch = "\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.jar)\"".toRegex().find(response)
            jarUrlMatch?.let {
                val downloadUrl = it.groupValues[1]
                if (pluginConfig.platforms.contains("bukkit")) {
                    platforms["bukkit"] = downloadUrl
                }
            }

            PluginInfo(
                name = "ProtocolLib",
                version = version,
                platforms = platforms,
                lastUpdated = System.currentTimeMillis().toString()
            )
        }

    private suspend fun updatePlaceholderAPI(pluginConfig: AutoManagerConfig.PluginConfig): PluginInfo =
        withContext(Dispatchers.IO) {
            val response = URL("https://api.github.com/repos/PlaceholderAPI/PlaceholderAPI/releases/latest").readText()
            val data = JsonLib.Companion.fromJsonString(response)!!

            val version = data.getString("tag_name")!!
            val assets = data.getAsJsonArray("assets")!!

            val platforms = mutableMapOf<String, String>()

            pluginConfig.platforms.forEach { platform ->
                if (platform == "bukkit") {
                    val asset = assets.find { asset ->
                        val name = asset.getAsJsonObject().get("name").asString
                        name.contains("PlaceholderAPI", true) && name.endsWith(".jar") && !name.contains("javadoc")
                    }

                    asset?.let {
                        val downloadUrl = it.getAsJsonObject().get("browser_download_url").asString
                        platforms[platform] = downloadUrl
                    }
                }
            }

            PluginInfo(
                name = "PlaceholderAPI",
                version = version,
                platforms = platforms,
                lastUpdated = System.currentTimeMillis().toString()
            )
        }

    private suspend fun updateCustomPlugin(pluginConfig: AutoManagerConfig.PluginConfig): PluginInfo? =
        withContext(Dispatchers.IO) {
            val customUrl = pluginConfig.customUrl ?: return@withContext null

            try {
                val platforms = mutableMapOf<String, String>()

                pluginConfig.platforms.forEach { platform ->
                    platforms[platform] = customUrl
                }

                val version = extractVersionFromUrl(customUrl) ?: "custom-${System.currentTimeMillis()}"

                PluginInfo(
                    name = pluginConfig.name,
                    version = version,
                    platforms = platforms,
                    lastUpdated = System.currentTimeMillis().toString()
                )
            } catch (e: Exception) {
                null
            }
        }

    private fun extractVersionFromUrl(url: String): String? {
        val filename = url.substringAfterLast("/")
        val versionRegex = Regex("""(\d+\.\d+(?:\.\d+)?(?:-[a-zA-Z0-9]+)?)""")
        return versionRegex.find(filename)?.value
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
                        .addHeader("User-Agent", config.networking.userAgent)
                        .build()

                    val response = okHttpClient.newCall(request).execute()

                    if (!response.isSuccessful) {
                        throw Exception("HTTP ${response.code}: ${response.message}")
                    }

                    val responseBody = response.body ?: throw Exception("Empty response body")

                    targetFile.outputStream().use { output ->
                        responseBody.byteStream().use { input ->
                            val buffer = ByteArray(config.performance.bufferSizeBytes)
                            var bytesRead: Int
                            var totalBytesRead = 0L

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead

                                if (config.performance.enableProgressReporting && totalBytesRead % (1024 * 100) == 0L) {
                                    LoggingUtils.debug(TAG, "Downloaded ${totalBytesRead / 1024} KB")
                                }
                            }
                        }
                    }

                    if (targetFile.length() > config.performance.maxFileSizeBytes) {
                        targetFile.delete()
                        throw Exception("File too large: ${targetFile.length()} bytes")
                    }

                    if (config.performance.enableChecksumValidation && !isValidJarFile(targetFile)) {
                        targetFile.delete()
                        throw Exception("Invalid JAR file")
                    }

                    LoggingUtils.info(TAG, "Successfully downloaded: $pluginName/$platform (${targetFile.length() / 1024} KB)")
                } else {
                    LoggingUtils.debug(TAG, "File already exists and is recent: ${targetFile.name}")
                }

            } catch (e: Exception) {
                LoggingUtils.error(TAG, "Error downloading $pluginName/$platform: ${e.message}", e)
                throw e
            }
        }
    }

    private fun shouldDownloadFile(file: File, url: String): Boolean {
        if (!file.exists()) return true

        val lastModified = file.lastModified()
        val hoursSinceModified = (System.currentTimeMillis() - lastModified) / (1000 * 60 * 60)

        return hoursSinceModified > 24
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

        config.plugins.filter { it.enabled }.forEach { pluginConfig ->
            pluginConfig.platforms.forEach { platform ->
                val platformDir = File(pluginsDirectory, "${pluginConfig.name}/$platform")
                if (!platformDir.exists()) {
                    platformDir.mkdirs()
                    LoggingUtils.debug(TAG, "Created directory: ${platformDir.absolutePath}")
                }
            }
        }

        LoggingUtils.debugSuccess(TAG, "creating plugin directory structure")
    }

    fun getPluginInfo(pluginName: String): PluginInfo? {
        val pluginConfig = config.plugins.find { it.name.equals(pluginName, ignoreCase = true) }
        return if (pluginConfig != null) {
            null
        } else {
            null
        }
    }

    fun getAllPluginInfo(): Map<String, PluginInfo> {
        return emptyMap()
    }
}