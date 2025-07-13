package eu.thesimplecloud.module.updater.updater

import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PluginUpdater(
    private val okHttpClient: OkHttpClient
) {
    private val pluginsPath = File(DirectoryPaths.paths.storagePath + "plugins")
    private val bukkitPluginsPath = File(pluginsPath, "bukkit")
    private val velocityPluginsPath = File(pluginsPath, "velocity")
    private val universalPluginsPath = File(pluginsPath, "universal")

    init {
        bukkitPluginsPath.mkdirs()
        velocityPluginsPath.mkdirs()
        universalPluginsPath.mkdirs()
    }

    enum class PluginPlatform {
        BUKKIT,
        VELOCITY,
        UNIVERSAL
    }

    data class PluginConfig(
        val name: String,
        val enabled: Boolean = true,
        val platforms: List<PluginPlatform>,
        val downloadUrl: String? = null,
        val githubRepo: String? = null,
        val spigotResourceId: String? = null,
        val hangarSlug: String? = null,
        val modrinthId: String? = null,
        val customUrl: String? = null,
        val fileName: String? = null
    )

    data class PluginInfo(
        val name: String,
        val version: String,
        val downloadUrl: String,
        val platform: PluginPlatform,
        val fileName: String
    )

    suspend fun updateAllPlugins(plugins: List<PluginConfig>): Map<String, Boolean> = withContext(Dispatchers.IO) {
        println("[PluginUpdater] === STARTING PLUGIN UPDATES ===")
        val results = mutableMapOf<String, Boolean>()

        plugins.filter { it.enabled }.forEach { plugin ->
            println("[PluginUpdater] Updating ${plugin.name}...")
            results[plugin.name] = updatePlugin(plugin)
        }

        syncPluginsToTemplates()

        println("[PluginUpdater] === PLUGIN UPDATES COMPLETED ===")
        results
    }

    private suspend fun updatePlugin(config: PluginConfig): Boolean = withContext(Dispatchers.IO) {
        val pluginInfoList = try {
            when {
                config.githubRepo != null -> fetchFromGitHub(config)
                config.spigotResourceId != null -> fetchFromSpigot(config)
                config.hangarSlug != null -> fetchFromHangar(config)
                config.modrinthId != null -> fetchFromModrinth(config)
                config.customUrl != null -> fetchCustomUrl(config)
                else -> {
                    println("[PluginUpdater] No source configured for ${config.name}")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            println("[PluginUpdater] Error fetching ${config.name}: ${e.message}")
            emptyList()
        }

        if (pluginInfoList.isEmpty()) {
            println("[PluginUpdater] Failed to fetch info for ${config.name}")
            return@withContext false
        }

        var success = true
        pluginInfoList.forEach { info ->
            try {
                if (!downloadPlugin(info)) {
                    success = false
                }
            } catch (e: Exception) {
                println("[PluginUpdater] Error downloading ${info.name}: ${e.message}")
                success = false
            }
        }

        success
    }

    private suspend fun fetchFromGitHub(config: PluginConfig): List<PluginInfo> = withContext(Dispatchers.IO) {
        val parts = config.githubRepo!!.split("/")
        if (parts.size != 2) {
            println("[PluginUpdater] Invalid GitHub repo format: ${config.githubRepo}")
            return@withContext emptyList()
        }

        val (owner, repo) = parts
        val url = "https://api.github.com/repos/$owner/$repo/releases/latest"
        println("[PluginUpdater] Fetching from GitHub: $url")

        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "SimpleCloud-PluginUpdater/2.0")
            .addHeader("Accept", "application/vnd.github.v3+json")
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            println("[PluginUpdater] GitHub API error for ${config.name}: HTTP ${response.code}")
            return@withContext emptyList()
        }

        val json = JSONObject(response.body?.string() ?: return@withContext emptyList())
        val assets = json.getJSONArray("assets")
        val version = json.getString("tag_name")

        println("[PluginUpdater] Found GitHub release ${config.name} v$version with ${assets.length()} assets")

        val results = mutableListOf<PluginInfo>()

        when {
            config.name.contains("LuckPerms") -> {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    val downloadUrl = asset.getString("browser_download_url")

                    println("[PluginUpdater] - Asset: $name")

                    val matchesName = when {
                        config.name.contains("Velocity") && name.contains("Velocity", ignoreCase = true)-> true
                        config.name.contains("Bukkit") && name.contains("Bukkit", ignoreCase = true) -> true
                        else -> false
                    }

                    if (matchesName && name.endsWith(".jar") && !name.contains("loader", ignoreCase = true)) {
                        results.add(PluginInfo(
                            name = config.name,
                            version = version,
                            downloadUrl = downloadUrl,
                            platform = config.platforms.first(),
                            fileName = config.fileName ?: name
                        ))
                        break
                    }
                }
            }
            config.name.contains("spark") -> {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    val downloadUrl = asset.getString("browser_download_url")

                    println("[PluginUpdater] - Asset: $name")

                    val matchesName = when {
                        config.name.contains("velocity") && name.contains("velocity", ignoreCase = true) -> true
                        !config.name.contains("velocity") && name.contains("bukkit", ignoreCase = true) -> true
                        else -> false
                    }

                    if (matchesName && name.endsWith(".jar")) {
                        results.add(PluginInfo(
                            name = config.name,
                            version = version,
                            downloadUrl = downloadUrl,
                            platform = config.platforms.first(),
                            fileName = config.fileName ?: name
                        ))
                        break
                    }
                }
            }
            else -> {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    val downloadUrl = asset.getString("browser_download_url")

                    println("[PluginUpdater] - Asset: $name")

                    if (name.endsWith(".jar") && !name.contains("sources") && !name.contains("javadoc")) {
                        results.add(PluginInfo(
                            name = config.name,
                            version = version,
                            downloadUrl = downloadUrl,
                            platform = config.platforms.first(),
                            fileName = config.fileName ?: name
                        ))
                        break
                    }
                }
            }
        }

        if (results.isEmpty()) {
            println("[PluginUpdater] No suitable assets found for ${config.name}")
        }

        results
    }

    private suspend fun fetchFromSpigot(config: PluginConfig): List<PluginInfo> = withContext(Dispatchers.IO) {
        val resourceId = config.spigotResourceId!!
        val request = Request.Builder()
            .url("https://api.spiget.org/v2/resources/$resourceId/versions/latest")
            .addHeader("User-Agent", "SimpleCloud-PluginUpdater/2.0")
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) return@withContext emptyList()

        val json = JSONObject(response.body?.string() ?: return@withContext emptyList())
        val version = json.getString("name")

        if (PluginPlatform.BUKKIT in config.platforms) {
            listOf(PluginInfo(
                name = config.name,
                version = version,
                downloadUrl = "https://api.spiget.org/v2/resources/$resourceId/download",
                platform = PluginPlatform.BUKKIT,
                fileName = config.fileName ?: "${config.name}.jar"
            ))
        } else {
            emptyList()
        }
    }

    private suspend fun fetchFromHangar(config: PluginConfig): List<PluginInfo> = withContext(Dispatchers.IO) {
        val slug = config.hangarSlug!!
        val request = Request.Builder()
            .url("https://hangar.papermc.io/api/v1/projects/$slug/versions?limit=1")
            .addHeader("User-Agent", "SimpleCloud-PluginUpdater/2.0")
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) return@withContext emptyList()

        val json = JSONObject(response.body?.string() ?: return@withContext emptyList())
        val versions = json.getJSONArray("result")
        if (versions.length() == 0) return@withContext emptyList()

        val latestVersion = versions.getJSONObject(0)
        val version = latestVersion.getString("name")
        val downloads = latestVersion.getJSONObject("downloads")

        val results = mutableListOf<PluginInfo>()

        config.platforms.forEach { platform ->
            val platformKey = when (platform) {
                PluginPlatform.BUKKIT -> "PAPER"
                PluginPlatform.VELOCITY -> "VELOCITY"
                PluginPlatform.UNIVERSAL -> return@forEach
            }

            if (downloads.has(platformKey)) {
                val download = downloads.getJSONObject(platformKey)
                val downloadUrl = download.getString("downloadUrl")

                val finalUrl = if (downloadUrl.startsWith("http")) {
                    downloadUrl
                } else {
                    "https://hangar.papermc.io$downloadUrl"
                }

                results.add(PluginInfo(
                    name = config.name,
                    version = version,
                    downloadUrl = finalUrl,
                    platform = platform,
                    fileName = config.fileName ?: "${config.name}-$version.jar"
                ))
            }
        }

        results
    }

    private suspend fun fetchFromModrinth(config: PluginConfig): List<PluginInfo> = withContext(Dispatchers.IO) {
        val projectId = config.modrinthId!!
        val request = Request.Builder()
            .url("https://api.modrinth.com/v2/project/$projectId/version")
            .addHeader("User-Agent", "SimpleCloud-PluginUpdater/2.0")
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) return@withContext emptyList()

        val versions = JSONArray(response.body?.string() ?: return@withContext emptyList())
        if (versions.length() == 0) return@withContext emptyList()

        val latestVersion = versions.getJSONObject(0)
        val version = latestVersion.getString("version_number")
        val files = latestVersion.getJSONArray("files")

        val results = mutableListOf<PluginInfo>()

        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i)
            val fileName = file.getString("filename")
            val downloadUrl = file.getString("url")

            if (!fileName.endsWith(".jar")) continue

            val loaders = latestVersion.getJSONArray("loaders")
            val platform = when {
                loaders.toString().contains("velocity") -> PluginPlatform.VELOCITY
                loaders.toString().contains("bukkit") ||
                        loaders.toString().contains("spigot") ||
                        loaders.toString().contains("paper") -> PluginPlatform.BUKKIT
                else -> PluginPlatform.UNIVERSAL
            }

            if (platform in config.platforms || PluginPlatform.UNIVERSAL in config.platforms) {
                results.add(PluginInfo(
                    name = config.name,
                    version = version,
                    downloadUrl = downloadUrl,
                    platform = platform,
                    fileName = config.fileName ?: fileName
                ))
            }
        }

        results
    }

    private suspend fun fetchCustomUrl(config: PluginConfig): List<PluginInfo> = withContext(Dispatchers.IO) {
        val url = config.customUrl!!
        val fileName = config.fileName ?: url.substringAfterLast("/")

        config.platforms.map { platform ->
            PluginInfo(
                name = config.name,
                version = "custom",
                downloadUrl = url,
                platform = platform,
                fileName = fileName
            )
        }
    }

    private suspend fun downloadPlugin(info: PluginInfo): Boolean = withContext(Dispatchers.IO) {
        val targetDir = when (info.platform) {
            PluginPlatform.BUKKIT -> bukkitPluginsPath
            PluginPlatform.VELOCITY -> velocityPluginsPath
            PluginPlatform.UNIVERSAL -> universalPluginsPath
        }

        val targetFile = File(targetDir, info.fileName)

        targetDir.listFiles()?.forEach { file ->
            if (file.name.startsWith(info.name.substringBefore("-")) &&
                file.name.endsWith(".jar") &&
                file != targetFile) {
                println("[PluginUpdater] Deleting old version: ${file.name}")
                file.delete()
            }
        }

        println("[PluginUpdater] Downloading ${info.name} v${info.version} for ${info.platform}")
        println("[PluginUpdater] URL: ${info.downloadUrl}")
        println("[PluginUpdater] Target: ${targetFile.absolutePath}")

        try {
            val request = Request.Builder()
                .url(info.downloadUrl)
                .addHeader("User-Agent", "SimpleCloud-PluginUpdater/2.0")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                println("[PluginUpdater] Failed to download ${info.name}: HTTP ${response.code}")
                return@withContext false
            }

            response.body?.use { body ->
                targetFile.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val bytes = input.copyTo(output)
                        println("[PluginUpdater] Downloaded ${info.name}: ${bytes / 1024 / 1024} MB")
                    }
                }
            }

            println("[PluginUpdater] Successfully downloaded ${info.name}")
            true
        } catch (e: Exception) {
            println("[PluginUpdater] Error downloading ${info.name}: ${e.message}")
            false
        }
    }

    fun syncPluginsToTemplates() {
        val templatesPath = File(DirectoryPaths.paths.templatesPath)

        templatesPath.listFiles()?.forEach { templateDir ->
            if (!templateDir.isDirectory) return@forEach

            val pluginsDir = File(templateDir, "plugins")
            pluginsDir.mkdirs()

            val templateType = getTemplateType(templateDir)

            when (templateType) {
                "VELOCITY", "VELOCITYCTD" -> {
                    copyPluginsToTemplate(velocityPluginsPath, pluginsDir)
                    copyPluginsToTemplate(universalPluginsPath, pluginsDir)
                }
                "LEAF", "PAPER", "SPIGOT" -> {
                    copyPluginsToTemplate(bukkitPluginsPath, pluginsDir)
                    copyPluginsToTemplate(universalPluginsPath, pluginsDir)
                }
            }

            println("[PluginUpdater] Synced plugins to template: ${templateDir.name}")
        }
    }

    private fun copyPluginsToTemplate(sourceDir: File, targetDir: File) {
        sourceDir.listFiles()?.forEach { plugin ->
            if (plugin.isFile && plugin.name.endsWith(".jar")) {
                val targetFile = File(targetDir, plugin.name)
                if (!targetFile.exists() || targetFile.lastModified() < plugin.lastModified()) {
                    plugin.copyTo(targetFile, overwrite = true)
                    println("[PluginUpdater] Copied ${plugin.name} to ${targetDir.path}")
                }
            }
        }
    }

    private fun getTemplateType(templateDir: File): String? {
        val configFile = File(templateDir.parentFile.parentFile, "groups/${templateDir.name}.json")
        if (!configFile.exists()) return null

        val content = configFile.readText()
        return when {
            content.contains("VELOCITYCTD") -> "VELOCITYCTD"
            content.contains("VELOCITY") -> "VELOCITY"
            content.contains("LEAF") -> "LEAF"
            content.contains("PAPER") -> "PAPER"
            content.contains("SPIGOT") -> "SPIGOT"
            else -> null
        }
    }
}