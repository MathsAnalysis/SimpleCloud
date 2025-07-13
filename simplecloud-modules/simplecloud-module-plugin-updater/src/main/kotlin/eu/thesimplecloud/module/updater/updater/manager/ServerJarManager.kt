package eu.thesimplecloud.module.updater.updater.manager

import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.module.updater.config.UpdaterConfig
import eu.thesimplecloud.module.updater.downloader.Downloader
import eu.thesimplecloud.module.updater.api.GitHubAPI
import eu.thesimplecloud.module.updater.api.PaperAPI
import kotlinx.coroutines.*
import java.io.File

class ServerJarManager {
    
    private val minecraftJarsPath = File(DirectoryPaths.paths.minecraftJarsPath)
    private val downloader = Downloader()
    private val githubAPI = GitHubAPI()
    private val paperAPI = PaperAPI()
    
    init {
        minecraftJarsPath.mkdirs()
    }
    
    suspend fun updateAll(config: UpdaterConfig.ServerJarsConfig): Map<String, Boolean> = coroutineScope {
        val results = mutableMapOf<String, Boolean>()
        
        val jobs = mutableListOf<Deferred<Pair<String, Boolean>>>()
        
        if (config.updateLeaf) {
            jobs.add(async { "LEAF" to updateLeaf() })
        }
        
        if (config.updatePaper) {
            jobs.add(async { "PAPER" to updatePaper(config.paperVersion) })
        }
        
        if (config.updateVelocity) {
            jobs.add(async { "VELOCITY" to updateVelocity(config.velocityVersion) })
        }
        
        if (config.updateVelocityCtd) {
            jobs.add(async { "VELOCITYCTD" to updateVelocityCtd() })
        }
        
        jobs.awaitAll().forEach { (name, success) ->
            results[name] = success
        }
        
        results
    }
    
    private suspend fun updateLeaf(): Boolean {
        println("[ServerJarManager] Aggiornamento Leaf...")
        
        val release = githubAPI.getLatestRelease("Winds-Studio", "Leaf") ?: return false
        val asset = release.assets.find { it.name.endsWith(".jar") && !it.name.contains("sources") }
            ?: return false
        
        val fileName = "LEAF_${release.tagName.removePrefix("ver-")}.jar"
        cleanOldVersions("LEAF_")
        
        return downloader.downloadFile(asset.browserDownloadUrl, File(minecraftJarsPath, fileName))
    }
    
    private suspend fun updatePaper(version: String): Boolean {
        println("[ServerJarManager] Aggiornamento Paper $version...")
        
        val build = paperAPI.getLatestBuild("paper", version) ?: return false
        val downloadInfo = paperAPI.getDownloadInfo("paper", version, build) ?: return false
        
        val fileName = "PAPER_${version}_$build.jar"
        cleanOldVersions("PAPER_")
        
        return downloader.downloadFile(downloadInfo.url, File(minecraftJarsPath, fileName))
    }
    
    private suspend fun updateVelocity(version: String): Boolean {
        println("[ServerJarManager] Aggiornamento Velocity $version...")
        
        val build = paperAPI.getLatestBuild("velocity", version) ?: return false
        val downloadInfo = paperAPI.getDownloadInfo("velocity", version, build) ?: return false
        
        val fileName = "VELOCITY_${version}_$build.jar"
        cleanOldVersions("VELOCITY_")
        
        return downloader.downloadFile(downloadInfo.url, File(minecraftJarsPath, fileName))
    }
    
    private suspend fun updateVelocityCtd(): Boolean {
        println("[ServerJarManager] Aggiornamento VelocityCtd...")
        
        val release = githubAPI.getLatestRelease("GemstoneGG", "Velocity-CTD") ?: return false
        val asset = release.assets.find { it.name.endsWith("-all.jar") }
            ?: return false
        
        val fileName = "VELOCITYCTD_${release.tagName}.jar"
        cleanOldVersions("VELOCITYCTD_")
        
        return downloader.downloadFile(asset.browserDownloadUrl, File(minecraftJarsPath, fileName))
    }
    
    private fun cleanOldVersions(prefix: String) {
        minecraftJarsPath.listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".jar") }
            ?.forEach { it.delete() }
    }
    
    fun getLatestJar(prefix: String): File? {
        return minecraftJarsPath.listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".jar") }
            ?.maxByOrNull { it.lastModified() }
    }
}
