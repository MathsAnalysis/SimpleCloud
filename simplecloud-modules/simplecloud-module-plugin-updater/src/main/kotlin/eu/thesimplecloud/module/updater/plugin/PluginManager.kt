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
import java.util.concurrent.TimeUnit

class PluginManager(private val config: AutoManagerConfig) {

    private fun logger(message: String) {
        LoggingUtils.log("[PluginManager] $message")
    }

    private fun loggerAlways(message: String) {
        LoggingUtils.logAlways("[PluginManager] $message")
    }

    private val pluginsDirectory = File(DirectoryPaths.paths.storagePath + "plugins")
    private val pluginVersionsFile = File(DirectoryPaths.paths.storagePath + "plugin-versions.json")
    private val logsDirectory = File(DirectoryPaths.paths.modulesPath + "automanager/logs")
    private val logFile = File(logsDirectory, "plugin-manager-${System.currentTimeMillis()}.log")
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    init {
        if (!pluginsDirectory.exists()) {
            pluginsDirectory.mkdirs()
        }
        if (!logsDirectory.exists()) {
            logsDirectory.mkdirs()
        }
        log("PluginManager initialized at ${java.time.LocalDateTime.now()}")
        log("Plugins directory: ${pluginsDirectory.absolutePath}")
        log("Config: enablePluginUpdates=${config.enablePluginUpdates}, plugins count=${config.plugins.size}")
    }

    private fun log(message: String) {
        val timestamp = java.time.LocalDateTime.now()
        val logMessage = "[$timestamp] $message"
        println("[PluginManager] $message")

        try {
            logFile.appendText("$logMessage\n")
        } catch (e: Exception) {
            println("[PluginManager] Failed to write to log file: ${e.message}")
        }
    }

    suspend fun ensureAllPluginsDownloaded(): Boolean {
        return try {
            log("Starting plugin download check")
            log("Configured plugins: ${config.plugins.map { it.name }}")
            var success = true

            config.plugins.forEach { pluginConfig ->
                try {
                    log("Checking plugin: ${pluginConfig.name} (enabled=${pluginConfig.enabled})")
                    if (!pluginConfig.enabled) {
                        log("Plugin ${pluginConfig.name} is disabled, skipping")
                        return@forEach
                    }

                    if (!isPluginAlreadyDownloaded(pluginConfig)) {
                        log("Plugin ${pluginConfig.name} needs to be downloaded")
                        val pluginInfo = updatePlugin(pluginConfig)
                        if (pluginInfo != null) {
                            log("Plugin info retrieved for ${pluginConfig.name}: version=${pluginInfo.version}")
                            downloadPluginFiles(pluginConfig.name, pluginInfo)
                            log("Successfully downloaded ${pluginConfig.name}")
                        } else {
                            log("ERROR: Failed to get plugin info for ${pluginConfig.name}")
                            success = false
                        }
                    } else {
                        log("Plugin ${pluginConfig.name} is already downloaded")
                    }
                } catch (e: Exception) {
                    log("ERROR downloading ${pluginConfig.name}: ${e.message}")
                    e.printStackTrace()
                    success = false
                }
            }

            log("Plugin download check completed. Success: $success")
            success
        } catch (e: Exception) {
            log("ERROR in ensureAllPluginsDownloaded: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private fun isPluginAlreadyDownloaded(pluginConfig: AutoManagerConfig.PluginConfig): Boolean {
        val result = pluginConfig.platforms.any { platform ->
            val platformDir = File(pluginsDirectory, "${pluginConfig.name}/$platform")
            val exists = platformDir.exists() && platformDir.listFiles()?.any {
                it.isFile && it.extension == "jar"
            } == true
            log("Checking if ${pluginConfig.name}/$platform exists: $exists")
            exists
        }
        return result
    }

    private suspend fun updatePlugin(pluginConfig: AutoManagerConfig.PluginConfig): PluginInfo? {
        return when (pluginConfig.name.lowercase()) {
            "luckperms" -> updateLuckPerms(pluginConfig)
            "spark" -> updateSpark(pluginConfig)
            "floodgate" -> updateFloodgate(pluginConfig)
            "geyser" -> updateGeyser(pluginConfig)
//            "protocollib" -> updateProtocolLib(pluginConfig)
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

    private suspend fun updateLuckPerms(pluginConfig: AutoManagerConfig.PluginConfig): PluginInfo =
        withContext(Dispatchers.IO) {
            try {
                val response = URL("https://metadata.luckperms.net/data/downloads").readText()
                val data = JsonLib.fromJsonString(response)!!

                val version = data.getString("version") ?: "unknown"
                val downloadsObj = data.getProperty("downloads")

                val platforms = mutableMapOf<String, String>()

                if (downloadsObj != null) {
                    pluginConfig.platforms.forEach { platform ->
                        val mappedPlatform = when (platform) {
                            "bukkit" -> "bukkit"
                            "bungeecord" -> "bungee"
                            "velocity" -> "velocity"
                            else -> platform
                        }

                        try {
                            val url = downloadsObj.getString(mappedPlatform)
                            if (url != null && url.isNotEmpty()) {
                                platforms[platform] = url
                                log("Found download URL for $platform: $url")
                            }
                        } catch (e: Exception) {
                            log("Platform $mappedPlatform not found for LuckPerms: ${e.message}")
                        }
                    }
                }

                PluginInfo(
                    name = "LuckPerms",
                    version = version,
                    platforms = platforms,
                    lastUpdated = System.currentTimeMillis().toString()
                )
            } catch (e: Exception) {
                log("ERROR: Failed to fetch LuckPerms metadata: ${e.message}")
                throw e
            }
        }

    private suspend fun updateSpark(pluginConfig: AutoManagerConfig.PluginConfig): PluginInfo =
        withContext(Dispatchers.IO) {
            val platforms = mutableMapOf<String, String>()

            pluginConfig.platforms.forEach { platform ->
                when (platform) {
                    "bukkit" -> {
                        val urls = listOf(
                            "https://cdn.lucko.me/spark-1.10.73-bukkit.jar",
                            "https://ci.lucko.me/job/spark/438/artifact/spark-bukkit/build/libs/spark-1.10.73-bukkit.jar"
                        )

                        for (url in urls) {
                            if (testUrl(url)) {
                                platforms[platform] = url
                                log("Spark URL for bukkit found: $url")
                                break
                            }
                        }
                    }
                    "velocity" -> {
                        val urls = listOf(
                            "https://cdn.lucko.me/spark-1.10.73-velocity.jar",
                            "https://ci.lucko.me/job/spark/438/artifact/spark-velocity/build/libs/spark-1.10.73-velocity.jar"
                        )

                        for (url in urls) {
                            if (testUrl(url)) {
                                platforms[platform] = url
                                log("Spark URL for velocity found: $url")
                                break
                            }
                        }
                    }
                    "bungeecord" -> {
                        val urls = listOf(
                            "https://cdn.lucko.me/spark-1.10.73-bungeecord.jar",
                            "https://ci.lucko.me/job/spark/438/artifact/spark-bungeecord/build/libs/spark-1.10.73-bungeecord.jar"
                        )

                        for (url in urls) {
                            if (testUrl(url)) {
                                platforms[platform] = url
                                log("Spark URL for bungeecord found: $url")
                                break
                            }
                        }
                    }
                }
            }

            PluginInfo(
                name = "Spark",
                version = "1.10.73",
                platforms = platforms,
                lastUpdated = System.currentTimeMillis().toString()
            )
        }

    private fun testUrl(url: String): Boolean {
        return try {
            val connection = URL(url).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "SimpleCloud-PluginManager/1.0")
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode == 200
        } catch (e: Exception) {
            false
        }
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
        log("Starting download for $pluginName")
        log("Available platforms: ${pluginInfo.platforms.keys}")

        pluginInfo.platforms.forEach { (platform, url) ->
            try {
                val platformDir = File(pluginsDirectory, "$pluginName/$platform")
                platformDir.mkdirs()
                log("Created directory: ${platformDir.absolutePath}")

                platformDir.listFiles()?.filter { it.extension == "jar" }?.forEach { oldFile ->
                    log("Removing old version: ${oldFile.name}")
                    oldFile.delete()
                }

                val targetFileName = when {
                    pluginName.equals("Spark", ignoreCase = true) -> "spark.jar"
                    pluginName.equals("LuckPerms", ignoreCase = true) -> "LuckPerms.jar"
//                    pluginName.equals("ProtocolLib", ignoreCase = true) -> "ProtocolLib.jar"
                    pluginName.equals("PlaceholderAPI", ignoreCase = true) -> "PlaceholderAPI.jar"
                    pluginName.equals("Floodgate", ignoreCase = true) -> "Floodgate.jar"
                    pluginName.equals("Geyser", ignoreCase = true) -> "Geyser.jar"
                    else -> {
                        val originalFilename = url.substringAfterLast("/").substringBefore("?")
                        if (originalFilename.endsWith(".jar")) {
                            "$pluginName.jar"
                        } else {
                            originalFilename
                        }
                    }
                }

                val targetFile = File(platformDir, targetFileName)
                log("Target file: ${targetFile.absolutePath}")

                if (shouldDownloadFile(targetFile, url)) {
                    log("Downloading $pluginName for $platform from $url")

                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "SimpleCloud-PluginManager/1.0")
                        .build()

                    val response = okHttpClient.newCall(request).execute()

                    if (!response.isSuccessful) {
                        log("ERROR: HTTP ${response.code}: ${response.message}")
                        throw Exception("HTTP ${response.code}: ${response.message}")
                    }

                    response.body?.let { responseBody ->
                        val contentLength = responseBody.contentLength()
                        log("Download size: ${contentLength / 1024} KB")

                        targetFile.outputStream().use { output ->
                            responseBody.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
                                var totalBytesRead = 0L

                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    totalBytesRead += bytesRead
                                    if (totalBytesRead % (1024 * 100) == 0L) {
                                        log("Downloaded ${totalBytesRead / 1024} KB / ${contentLength / 1024} KB")
                                    }
                                }
                            }
                        }
                    } ?: throw Exception("Empty response body")

                    if (targetFile.length() > 100 * 1024 * 1024) {
                        targetFile.delete()
                        log("ERROR: File too large: ${targetFile.length()} bytes")
                        throw Exception("File too large: ${targetFile.length()} bytes")
                    }

                    if (!isValidJarFile(targetFile)) {
                        targetFile.delete()
                        log("ERROR: Invalid JAR file")
                        throw Exception("Invalid JAR file")
                    }

                    log("Successfully downloaded: $pluginName/$platform (${targetFile.length() / 1024} KB)")
                    log("Saved as: ${targetFile.name}")

                    val versionFile = File(platformDir, "version.txt")
                    versionFile.writeText("${pluginInfo.version}\n${System.currentTimeMillis()}")

                } else {
                    log("File already exists and is recent: ${targetFile.name}")
                }

            } catch (e: Exception) {
                log("ERROR downloading $pluginName/$platform: ${e.message}")
                e.printStackTrace()
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
        return try {
            val bytes = file.readBytes()
            bytes.size >= 4 &&
                    bytes[0] == 0x50.toByte() &&
                    bytes[1] == 0x4B.toByte() &&
                    (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte()) &&
                    (bytes[3] == 0x04.toByte() || bytes[3] == 0x06.toByte() || bytes[3] == 0x08.toByte())
        } catch (e: Exception) {
            false
        }
    }

    private fun savePluginManifest(plugins: Map<String, PluginInfo>) {
        try {
            if (pluginVersionsFile.exists() && config.enableBackup) {
                val backupFile = File(pluginVersionsFile.parentFile, "plugin-versions.json.backup")
                pluginVersionsFile.copyTo(backupFile, overwrite = true)
            }

            val manifest = mapOf(
                "version" to "1.0.0",
                "last_updated" to System.currentTimeMillis().toString(),
                "plugins" to plugins
            )

            JsonLib.empty()
                .append("manifest", manifest)
                .saveAsFile(pluginVersionsFile)

        } catch (e: Exception) {
            log("ERROR saving plugin manifest: ${e.message}")
        }
    }
}