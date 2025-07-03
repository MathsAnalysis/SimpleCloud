package eu.thesimplecloud.module.updater.manager

import eu.thesimplecloud.api.CloudAPI
import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.jsonlib.JsonLib
import eu.thesimplecloud.module.updater.bootstrap.PluginUpdaterModule
import eu.thesimplecloud.module.updater.config.AutoManagerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

class PluginManager(
    private val module: PluginUpdaterModule,
    private val config: AutoManagerConfig
) {

    private val pluginsDirectory = File(DirectoryPaths.Companion.paths.modulesPath + "auto-plugins/")
    private val pluginVersionsFile = File(DirectoryPaths.Companion.paths.storagePath + "plugin-versions.json")

    data class PluginInfo(
        val name: String,
        val version: String,
        val platforms: Map<String, String>,
        val lastUpdated: String
    )

    init {
        if (!pluginsDirectory.exists()) {
            pluginsDirectory.mkdirs()
        }
    }

    suspend fun updateAllPlugins(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            ensureAllPluginsDownloaded()

            val updatedPlugins = mutableMapOf<String, PluginInfo>()
            var hasErrors = false

            config.plugins.filter { it.enabled }.forEach { pluginConfig ->
                try {
                    val pluginInfo = updatePlugin(pluginConfig)
                    if (pluginInfo != null) {
                        updatedPlugins[pluginConfig.name] = pluginInfo
                        downloadPluginFiles(pluginConfig.name, pluginInfo)
                    }
                } catch (e: Exception) {
                    println("[PluginManager] Errore aggiornamento ${pluginConfig.name}: ${e.message}")
                    hasErrors = true
                }
            }

            savePluginManifest(updatedPlugins)
            updateTemplatesWithPlugins()

            !hasErrors
        } catch (e: Exception) {
            println("[PluginManager] Errore updateAllPlugins: ${e.message}")
            false
        }
    }

    suspend fun ensureAllPluginsDownloaded(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            var success = true

            config.plugins.filter { it.enabled }.forEach { pluginConfig ->
                try {
                    if (!isPluginAlreadyDownloaded(pluginConfig)) {
                        val pluginInfo = updatePlugin(pluginConfig)
                        if (pluginInfo != null) {
                            downloadPluginFiles(pluginConfig.name, pluginInfo)
                        } else {
                            success = false
                        }
                    }
                } catch (e: Exception) {
                    println("[PluginManager] Errore download ${pluginConfig.name}: ${e.message}")
                    success = false
                }
            }

            success
        } catch (e: Exception) {
            println("[PluginManager] Errore ensureAllPluginsDownloaded: ${e.message}")
            false
        }
    }

    private fun isPluginAlreadyDownloaded(pluginConfig: AutoManagerConfig.PluginConfig): Boolean {
        return pluginConfig.platforms.any { platform ->
            val platformDir = File(pluginsDirectory, "${pluginConfig.name}/$platform")
            platformDir.exists() && platformDir.listFiles()?.any {
                it.isFile && it.extension == "jar"
            } == true
        }
    }

    private suspend fun updatePlugin(pluginConfig: AutoManagerConfig.PluginConfig): PluginInfo? {
        return when (pluginConfig.name.lowercase()) {
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

    private suspend fun updateLuckPerms(pluginConfig: AutoManagerConfig.PluginConfig): PluginInfo =
        withContext(Dispatchers.IO) {
            val response = URL("https://metadata.luckperms.net/data/downloads").readText()
            val data = JsonLib.Companion.fromJsonString(response)!!

            val version = data.getString("version")!!
            val downloads = data.getProperty("downloads")!!

            val platforms = mutableMapOf<String, String>()

            pluginConfig.platforms.forEach { platform ->
                when (platform) {
                    "bukkit" -> {
                        val bukkitUrl = downloads.getString("bukkit")
                        if (bukkitUrl != null) platforms[platform] = bukkitUrl
                    }

                    "velocity" -> {
                        val velocityUrl = downloads.getString("velocity")
                        if (velocityUrl != null) platforms[platform] = velocityUrl
                    }

                    "bungeecord" -> {
                        val bungeeUrl = downloads.getString("bungeecord")
                        if (bungeeUrl != null) platforms[platform] = bungeeUrl
                    }
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
            val response = URL("https://api.github.com/repos/lucko/spark/releases/latest").readText()
            val data = JsonLib.Companion.fromJsonString(response)!!

            val version = data.getString("tag_name")!!.removePrefix("v")
            val assets = data.getAsJsonArray("assets")!!

            val platforms = mutableMapOf<String, String>()

            pluginConfig.platforms.forEach { platform ->
                val assetName = when (platform) {
                    "bukkit" -> "spark-bukkit"
                    "velocity" -> "spark-velocity"
                    "bungeecord" -> "spark-bungeecord"
                    else -> return@forEach
                }

                val asset = assets.find { asset ->
                    val name = asset.getAsJsonObject().get("name").asString
                    name.contains(assetName, true) && name.endsWith(".jar")
                }

                asset?.let {
                    val downloadUrl = it.getAsJsonObject().get("browser_download_url").asString
                    platforms[platform] = downloadUrl
                }
            }

            PluginInfo(
                name = "Spark",
                version = version,
                platforms = platforms,
                lastUpdated = System.currentTimeMillis().toString()
            )
        }

    private suspend fun updateFloodgate(pluginConfig: AutoManagerConfig.PluginConfig): PluginInfo =
        withContext(Dispatchers.IO) {
            val response = URL("https://api.github.com/repos/GeyserMC/Floodgate/releases/latest").readText()
            val data = JsonLib.Companion.fromJsonString(response)!!

            val version = data.getString("tag_name")!!.removePrefix("v")
            val assets = data.getAsJsonArray("assets")!!

            val platforms = mutableMapOf<String, String>()

            pluginConfig.platforms.forEach { platform ->
                val assetName = when (platform) {
                    "bukkit" -> "floodgate-bukkit"
                    "velocity" -> "floodgate-velocity"
                    "bungeecord" -> "floodgate-bungee"
                    else -> return@forEach
                }

                val asset = assets.find { asset ->
                    val name = asset.getAsJsonObject().get("name").asString
                    name.contains(assetName, true) && name.endsWith(".jar")
                }

                asset?.let {
                    val downloadUrl = it.getAsJsonObject().get("browser_download_url").asString
                    platforms[platform] = downloadUrl
                }
            }

            PluginInfo(
                name = "Floodgate",
                version = version,
                platforms = platforms,
                lastUpdated = System.currentTimeMillis().toString()
            )
        }

    private suspend fun updateGeyser(pluginConfig: AutoManagerConfig.PluginConfig): PluginInfo =
        withContext(Dispatchers.IO) {
            val response = URL("https://api.github.com/repos/GeyserMC/Geyser/releases/latest").readText()
            val data = JsonLib.Companion.fromJsonString(response)!!

            val version = data.getString("tag_name")!!.removePrefix("v")
            val assets = data.getAsJsonArray("assets")!!

            val platforms = mutableMapOf<String, String>()

            pluginConfig.platforms.forEach { platform ->
                val assetName = when (platform) {
                    "bukkit" -> "Geyser-Spigot"
                    "velocity" -> "Geyser-Velocity"
                    "bungeecord" -> "Geyser-BungeeCord"
                    else -> return@forEach
                }

                val asset = assets.find { asset ->
                    val name = asset.getAsJsonObject().get("name").asString
                    name.contains(assetName, true) && name.endsWith(".jar")
                }

                asset?.let {
                    val downloadUrl = it.getAsJsonObject().get("browser_download_url").asString
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
            val data = JsonLib.Companion.fromJsonString(response)!!

            val version = data.getString("tag_name")!!
            val assets = data.getAsJsonArray("assets")!!

            val platforms = mutableMapOf<String, String>()

            pluginConfig.platforms.forEach { platform ->
                if (platform == "bukkit") {
                    val asset = assets.find { asset ->
                        val name = asset.getAsJsonObject().get("name").asString
                        name.contains("ProtocolLib", true) && name.endsWith(".jar")
                    }

                    asset?.let {
                        val downloadUrl = it.getAsJsonObject().get("browser_download_url").asString
                        platforms[platform] = downloadUrl
                    }
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
        pluginInfo.platforms.forEach { (platform, url) ->
            try {
                val platformDir = File(pluginsDirectory, "$pluginName/$platform")
                platformDir.mkdirs()

                val filename = url.substringAfterLast("/")
                val targetFile = File(platformDir, filename)

                if (shouldDownloadFile(targetFile, url)) {
                    println("[PluginManager] Download $pluginName per $platform")

                    URL(url).openStream().use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    if (targetFile.length() > 100 * 1024 * 1024) { // 100MB limit
                        targetFile.delete()
                        throw Exception("File troppo grande: ${targetFile.length()} bytes")
                    }

                    if (!isValidJarFile(targetFile)) {
                        targetFile.delete()
                        throw Exception("File JAR non valido")
                    }

                    println("[PluginManager] Download completato: $pluginName/$platform")
                }

            } catch (e: Exception) {
                println("[PluginManager] Errore download $pluginName/$platform: ${e.message}")
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

            JsonLib.Companion.empty()
                .append("manifest", manifest)
                .saveAsFile(pluginVersionsFile)

        } catch (e: Exception) {
            println("[PluginManager] Errore salvataggio manifest: ${e.message}")
            throw e
        }
    }

    private suspend fun updateTemplatesWithPlugins() = withContext(Dispatchers.IO) {
        try {
            val templateManager = CloudAPI.Companion.instance.getTemplateManager()
            val allTemplates = templateManager.getAllCachedObjects()

            allTemplates.forEach { template ->
                val updater = template.getUpdater()
                var shouldUpdate = false

                when {
                    isServerTemplate(template.getName()) -> {
                        val serverPlugins = getServerPluginsForTemplate(template.getName())
                        serverPlugins.forEach { pluginName ->
                            if (!updater.getModuleNamesToCopy().contains(pluginName)) {
                                updater.addModuleNameToCopy(pluginName)
                                shouldUpdate = true
                            }
                        }
                    }

                    isProxyTemplate(template.getName()) -> {
                        val proxyPlugins = getProxyPluginsForTemplate(template.getName())
                        proxyPlugins.forEach { pluginName ->
                            if (!updater.getModuleNamesToCopy().contains(pluginName)) {
                                updater.addModuleNameToCopy(pluginName)
                                shouldUpdate = true
                            }
                        }
                    }
                }

                if (shouldUpdate) {
                    updater.update()
                }
            }
        } catch (e: Exception) {
            println("[PluginManager] Errore updateTemplatesWithPlugins: ${e.message}")
        }
    }

    private fun isServerTemplate(templateName: String): Boolean {
        return templateName.contains("server", true) ||
               templateName.contains("lobby", true) ||
               templateName.contains("survival", true) ||
               templateName.contains("creative", true)
    }

    private fun isProxyTemplate(templateName: String): Boolean {
        return templateName.contains("proxy", true) ||
               templateName.contains("bungee", true) ||
               templateName.contains("velocity", true)
    }

    private fun getServerPluginsForTemplate(templateName: String): List<String> {
        return config.plugins.filter { plugin ->
            plugin.enabled && plugin.platforms.contains("bukkit")
        }.map { it.name }
    }

    private fun getProxyPluginsForTemplate(templateName: String): List<String> {
        return config.plugins.filter { plugin ->
            plugin.enabled && (plugin.platforms.contains("velocity") || plugin.platforms.contains("bungeecord"))
        }.map { it.name }
    }

    fun getCurrentPlugins(): Map<String, PluginInfo> {
        return try {
            if (pluginVersionsFile.exists()) {
                val data = JsonLib.Companion.fromJsonFile(pluginVersionsFile)!!
                val manifest = data.getProperty("manifest")!!
                val pluginsObj = manifest.getProperty("plugins")!!

                pluginsObj.jsonElement.asJsonObject.keySet().associate { key ->
                    val pluginObj = pluginsObj.getProperty(key)!!
                    key to PluginInfo(
                        name = pluginObj.getString("name")!!,
                        version = pluginObj.getString("version")!!,
                        platforms = emptyMap(),
                        lastUpdated = pluginObj.getString("lastUpdated")!!
                    )
                }
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            println("[PluginManager] Errore getCurrentPlugins: ${e.message}")
            emptyMap()
        }
    }

    suspend fun updateSinglePlugin(pluginName: String): Boolean {
        val pluginConfig = config.plugins.find { it.name.equals(pluginName, true) }
        if (pluginConfig == null) {
            return false
        }

        return try {
            val pluginInfo = updatePlugin(pluginConfig)
            if (pluginInfo != null) {
                downloadPluginFiles(pluginConfig.name, pluginInfo)

                val currentPlugins = getCurrentPlugins().toMutableMap()
                currentPlugins[pluginConfig.name] = pluginInfo
                savePluginManifest(currentPlugins)

                true
            } else {
                false
            }
        } catch (e: Exception) {
            println("[PluginManager] Errore updateSinglePlugin $pluginName: ${e.message}")
            false
        }
    }
}