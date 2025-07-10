package eu.thesimplecloud.module.updater.updater

import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class PluginUpdater(
    private val okHttpClient: OkHttpClient
) {
    private val pluginsPath = File(DirectoryPaths.paths.storagePath + "plugins")
    private val templatesPath = File(DirectoryPaths.paths.templatesPath)
    
    init {
        pluginsPath.mkdirs()
    }
    
    data class PluginDownload(
        val name: String,
        val platform: String,
        val url: String,
        val targetPath: String
    )
    
    suspend fun updatePlugins(plugins: List<PluginConfig>): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, Boolean>()
        
        plugins.filter { it.enabled }.forEach { plugin ->
            results[plugin.name] = try {
                downloadPlugin(plugin)
            } catch (e: Exception) {
                false
            }
        }
        
        results
    }
    
    private suspend fun downloadPlugin(config: PluginConfig): Boolean = withContext(Dispatchers.IO) {
        val downloads = getPluginDownloads(config)
        var success = true
        
        downloads.forEach { download ->
            val targetFile = File(download.targetPath)
            targetFile.parentFile.mkdirs()
            
            if (!downloadFile(download.url, targetFile)) {
                success = false
            }
        }
        
        success
    }
    
    private fun getPluginDownloads(config: PluginConfig): List<PluginDownload> {
        val downloads = mutableListOf<PluginDownload>()
        
        when (config.name.lowercase()) {
            "spark" -> {
                config.platforms.forEach { platform ->
                    val url = when (platform) {
                        "bukkit" -> "https://ci.lucko.me/job/spark/lastSuccessfulBuild/artifact/spark-bukkit/build/libs/spark-bukkit.jar"
                        "velocity" -> "https://ci.lucko.me/job/spark/lastSuccessfulBuild/artifact/spark-velocity/build/libs/spark-velocity.jar"
                        else -> null
                    }
                    
                    url?.let {
                        downloads.add(PluginDownload(
                            name = config.name,
                            platform = platform,
                            url = it,
                            targetPath = "${pluginsPath.absolutePath}/${config.name}/$platform/spark-$platform.jar"
                        ))
                    }
                }
            }
            "luckperms" -> {
                config.platforms.forEach { platform ->
                    val platformName = when (platform) {
                        "bukkit" -> "bukkit"
                        "velocity" -> "velocity"
                        else -> null
                    }
                    
                    platformName?.let {
                        downloads.add(PluginDownload(
                            name = config.name,
                            platform = platform,
                            url = "https://download.luckperms.net/1550/$platformName/loader/LuckPerms-$platformName-5.4.139.jar",
                            targetPath = "${pluginsPath.absolutePath}/${config.name}/$platform/LuckPerms-$platform.jar"
                        ))
                    }
                }
            }
        }
        
        config.customUrls.forEach { (platform, url) ->
            downloads.add(PluginDownload(
                name = config.name,
                platform = platform,
                url = url,
                targetPath = "${pluginsPath.absolutePath}/${config.name}/$platform/${config.name}-$platform.jar"
            ))
        }
        
        return downloads
    }
    
    private suspend fun downloadFile(url: String, targetFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "SimpleCloud-PluginUpdater")
                .build()
                
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext false
            
            response.body?.use { body ->
                targetFile.outputStream().use { output ->
                    body.byteStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }
            
            targetFile.exists() && targetFile.length() > 0
        } catch (e: Exception) {
            false
        }
    }
    
    fun syncPluginsToTemplates() {
        templatesPath.listFiles()?.forEach { templateDir ->
            if (!templateDir.isDirectory) return@forEach
            
            val templateType = getTemplateType(templateDir)
            val pluginsDir = File(templateDir, "plugins")
            pluginsDir.mkdirs()
            
            copyPluginsToTemplate(templateType, pluginsDir)
        }
    }
    
    private fun getTemplateType(templateDir: File): String {
        return when {
            templateDir.name.contains("velocity", ignoreCase = true) -> "velocity"
            else -> "bukkit"
        }
    }
    
    private fun copyPluginsToTemplate(templateType: String, targetPluginsDir: File) {
        pluginsPath.listFiles()?.forEach { pluginDir ->
            if (!pluginDir.isDirectory) return@forEach
            
            val platformDir = File(pluginDir, templateType)
            if (platformDir.exists() && platformDir.isDirectory) {
                platformDir.listFiles()?.forEach { jarFile ->
                    if (jarFile.name.endsWith(".jar")) {
                        jarFile.copyTo(File(targetPluginsDir, jarFile.name), overwrite = true)
                    }
                }
            }
        }
    }
}