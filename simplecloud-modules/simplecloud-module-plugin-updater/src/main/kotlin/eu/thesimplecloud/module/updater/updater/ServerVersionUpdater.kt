package eu.thesimplecloud.module.updater.updater

import eu.thesimplecloud.module.updater.manager.JarManager
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ServerVersionUpdater(
    private val jarManager: JarManager
) {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
        
    private val updateScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    data class ServerJar(
        val type: String,
        val version: String,
        val build: String?,
        val url: String,
        val jarName: String
    )
    
    suspend fun updateAllServerJars(): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, Boolean>()
        
        val updateJobs = listOf(
            async { updateLeaf() },
            async { updatePaper() },
            async { updateVelocity() },
            async { updateVelocityCTD() }
        )
        
        updateJobs.forEachIndexed { index, job ->
            val type = when(index) {
                0 -> "LEAF"
                1 -> "PAPER"
                2 -> "VELOCITY"
                3 -> "VELOCITYCTD"
                else -> "UNKNOWN"
            }
            results[type] = try {
                job.await()
            } catch (e: Exception) {
                false
            }
        }
        
        results
    }
    
    private suspend fun updateLeaf(): Boolean = withContext(Dispatchers.IO) {
        try {
            val latestRelease = fetchLatestGithubRelease("Winds-Studio", "Leaf")
            latestRelease?.let { jar ->
                jarManager.cleanupOldVersions("LEAF_")
                jarManager.downloadJar(jar.url, jar.jarName)
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun updatePaper(): Boolean = withContext(Dispatchers.IO) {
        try {
            val paperVersion = "1.21.7"
            val latestBuild = fetchLatestPaperBuild(paperVersion)
            
            latestBuild?.let { build ->
                val jarName = "PAPER_${paperVersion}_${build}.jar"
                val url = "https://api.papermc.io/v2/projects/paper/versions/$paperVersion/builds/$build/downloads/paper-$paperVersion-$build.jar"
                
                jarManager.cleanupOldVersions("PAPER_")
                jarManager.downloadJar(url, jarName)
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun updateVelocity(): Boolean = withContext(Dispatchers.IO) {
        try {
            val velocityVersion = "3.4.0-SNAPSHOT"
            val latestBuild = fetchLatestVelocityBuild(velocityVersion)
            
            latestBuild?.let { build ->
                val jarName = "VELOCITY_${velocityVersion.replace(".", "_")}_${build}.jar"
                val url = "https://api.papermc.io/v2/projects/velocity/versions/$velocityVersion/builds/$build/downloads/velocity-$velocityVersion-$build.jar"
                
                jarManager.cleanupOldVersions("VELOCITY_")
                jarManager.downloadJar(url, jarName)
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun updateVelocityCTD(): Boolean = withContext(Dispatchers.IO) {
        try {
            val jarName = "VELOCITYCTD_Releases.jar"
            val url = "https://github.com/GemstoneGG/Velocity-CTD/releases/download/Releases/velocity-proxy-3.4.0-SNAPSHOT-all.jar"
            
            jarManager.cleanupOldVersions("VELOCITYCTD_")
            jarManager.downloadJar(url, jarName)
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun fetchLatestGithubRelease(owner: String, repo: String): ServerJar? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/releases/latest")
                .addHeader("User-Agent", "SimpleCloud-Updater")
                .build()
                
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            
            val json = JSONObject(response.body?.string() ?: return@withContext null)
            val tagName = json.getString("tag_name")
            val version = if (tagName.startsWith("ver-")) tagName.substring(4) else tagName
            val assets = json.getJSONArray("assets")
            
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                
                if (name.endsWith(".jar") && !name.contains("sources") && !name.contains("javadoc")) {
                    return@withContext ServerJar(
                        type = "LEAF",
                        version = version,
                        build = null,
                        url = asset.getString("browser_download_url"),
                        jarName = "LEAF_$version.jar"
                    )
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun fetchLatestPaperBuild(version: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.papermc.io/v2/projects/paper/versions/$version/builds")
                .addHeader("User-Agent", "SimpleCloud-Updater")
                .build()
                
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            
            val json = JSONObject(response.body?.string() ?: return@withContext null)
            val builds = json.getJSONArray("builds")
            
            if (builds.length() > 0) {
                val latestBuild = builds.getJSONObject(builds.length() - 1)
                return@withContext latestBuild.getInt("build").toString()
            }
            null
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun fetchLatestVelocityBuild(version: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.papermc.io/v2/projects/velocity/versions/$version/builds")
                .addHeader("User-Agent", "SimpleCloud-Updater")
                .build()
                
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            
            val json = JSONObject(response.body?.string() ?: return@withContext null)
            val builds = json.getJSONArray("builds")
            
            if (builds.length() > 0) {
                val latestBuild = builds.getJSONObject(builds.length() - 1)
                return@withContext latestBuild.getInt("build").toString()
            }
            null
        } catch (e: Exception) {
            null
        }
    }
    
    fun shutdown() {
        updateScope.cancel()
    }
}