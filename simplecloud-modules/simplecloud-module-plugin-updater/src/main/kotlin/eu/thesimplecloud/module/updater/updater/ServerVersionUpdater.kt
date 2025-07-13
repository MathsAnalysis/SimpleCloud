package eu.thesimplecloud.module.updater.updater

import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.module.updater.manager.JarManager
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class ServerVersionUpdater(
    private val jarManager: JarManager
) {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
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
        println("[ServerVersionUpdater] Starting server JARs update...")
        val results = mutableMapOf<String, Boolean>()

        cleanupPaperclip()

        val updateJobs = listOf(
            async {
                println("[ServerVersionUpdater] Updating Leaf...")
                updateLeaf()
            },
            async {
                println("[ServerVersionUpdater] Updating Paper...")
                updatePaper()
            },
            async {
                println("[ServerVersionUpdater] Updating Velocity...")
                updateVelocity()
            },
            async {
                println("[ServerVersionUpdater] Updating VelocityCtd...")
                updateVelocityCTD()
            }
        )

        updateJobs.forEachIndexed { index, job ->
            val type = when(index) {
                0 -> "LEAF"
                1 -> "PAPER"
                2 -> "VELOCITY"
                3 -> "VELOCITYCTD"
                else -> "UNKNOWN"
            }
            results[type] = job.await()
        }

        syncJarsToTemplates()
        results
    }

    private fun cleanupPaperclip() {
        val minecraftJarsPath = File(DirectoryPaths.paths.minecraftJarsPath)
        minecraftJarsPath.listFiles()?.forEach { file ->
            if (file.name.lowercase().contains("paperclip")) {
                file.delete()
            }
        }
    }

    private suspend fun updateLeaf(): Boolean = withContext(Dispatchers.IO) {
        val latestRelease = fetchLatestLeafRelease()

        if (latestRelease != null) {
            println("[ServerVersionUpdater] Found Leaf version: ${latestRelease.version}")
            jarManager.cleanupOldVersions("LEAF_", keepLatest = 0)
            return@withContext jarManager.downloadJar(latestRelease.url, latestRelease.jarName)
        }

        println("[ServerVersionUpdater] No Leaf release found")
        false
    }

    private suspend fun fetchLatestLeafRelease(): ServerJar? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/Winds-Studio/Leaf/releases")
            .addHeader("User-Agent", "SimpleCloud-Updater/2.0")
            .addHeader("Accept", "application/vnd.github.v3+json")
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) return@withContext null

        val jsonArray = JSONArray(response.body?.string() ?: return@withContext null)

        for (i in 0 until jsonArray.length()) {
            val release = jsonArray.getJSONObject(i)

            if (release.optBoolean("prerelease", false) || release.optBoolean("draft", false)) {
                continue
            }

            val tagName = release.getString("tag_name")
            val version = when {
                tagName.startsWith("ver-") -> tagName.substring(4)
                tagName.startsWith("v") -> tagName.substring(1)
                else -> tagName
            }

            val assets = release.getJSONArray("assets")

            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                val name = asset.getString("name")

                if (name.endsWith(".jar") &&
                    !name.contains("sources") &&
                    !name.contains("javadoc") &&
                    (name.contains("leaf") || name.contains("server"))) {

                    return@withContext ServerJar(
                        type = "LEAF",
                        version = version,
                        build = null,
                        url = asset.getString("browser_download_url"),
                        jarName = "LEAF_${version.replace(".", "_")}.jar"
                    )
                }
            }
        }
        null
    }

    private suspend fun updatePaper(): Boolean = withContext(Dispatchers.IO) {
        val paperVersion = "1.21.4"
        val latestBuild = fetchLatestPaperBuild(paperVersion) ?: return@withContext false

        val jarName = "PAPER_${paperVersion.replace(".", "_")}_$latestBuild.jar"
        val url = "https://api.papermc.io/v2/projects/paper/versions/$paperVersion/builds/$latestBuild/downloads/paper-$paperVersion-$latestBuild.jar"

        jarManager.cleanupOldVersions("PAPER_", keepLatest = 0)
        jarManager.downloadJar(url, jarName)
    }

    private suspend fun updateVelocity(): Boolean = withContext(Dispatchers.IO) {
        val velocityVersion = "3.4.0-SNAPSHOT"
        val latestBuild = fetchLatestVelocityBuild(velocityVersion) ?: return@withContext false

        val jarName = "VELOCITY_${velocityVersion.replace(".", "_")}_$latestBuild.jar"
        val url = "https://api.papermc.io/v2/projects/velocity/versions/$velocityVersion/builds/$latestBuild/downloads/velocity-$velocityVersion-$latestBuild.jar"

        jarManager.cleanupOldVersions("VELOCITY_", keepLatest = 0)
        jarManager.downloadJar(url, jarName)
    }

    private suspend fun updateVelocityCTD(): Boolean = withContext(Dispatchers.IO) {
        val release = fetchLatestVelocityCTDRelease()

        if (release != null) {
            println("[ServerVersionUpdater] Found VelocityCtd version: ${release.version}")
            jarManager.cleanupOldVersions("VELOCITYCTD_", keepLatest = 0)
            return@withContext jarManager.downloadJar(release.url, release.jarName)
        }

        println("[ServerVersionUpdater] No VelocityCtd release found")
        false
    }

    private suspend fun fetchLatestVelocityCTDRelease(): ServerJar? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/GemstoneGG/Velocity-CTD/releases")
            .addHeader("User-Agent", "SimpleCloud-Updater/2.0")
            .addHeader("Accept", "application/vnd.github.v3+json")
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) return@withContext null

        val jsonArray = JSONArray(response.body?.string() ?: return@withContext null)

        if (jsonArray.length() > 0) {
            val release = jsonArray.getJSONObject(0)
            val tagName = release.getString("tag_name")
            val assets = release.getJSONArray("assets")

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")

                if (name.endsWith("-all.jar")) {
                    val version = if (name.contains("SNAPSHOT")) {
                        name.substringAfter("proxy-").substringBefore("-all.jar")
                    } else tagName

                    return@withContext ServerJar(
                        type = "VELOCITYCTD",
                        version = version,
                        build = null,
                        url = asset.getString("browser_download_url"),
                        jarName = "VELOCITYCTD_${version.replace(".", "_")}.jar"
                    )
                }
            }
        }
        null
    }

    private suspend fun fetchLatestPaperBuild(version: String): String? = withContext(Dispatchers.IO) {
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
    }

    private suspend fun fetchLatestVelocityBuild(version: String): String? = withContext(Dispatchers.IO) {
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
    }

    fun syncJarsToTemplates() {
        val templatesPath = File(DirectoryPaths.paths.templatesPath)

        templatesPath.listFiles()?.forEach { templateDir ->
            if (!templateDir.isDirectory) return@forEach

            templateDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("LEAF_") ||
                    file.name.startsWith("PAPER_") ||
                    file.name.startsWith("VELOCITY_") ||
                    file.name.startsWith("VELOCITYCTD_") ||
                    file.name.lowercase().contains("paperclip") ||
                    file.name == "server.jar") {
                    file.delete()
                }
            }

            val templateType = getTemplateServerType(templateDir)
            templateType?.let { prefix ->
                jarManager.getLatestJar(prefix)?.let { latestJar ->
                    latestJar.copyTo(File(templateDir, latestJar.name), overwrite = true)

                    if (prefix != "VELOCITYCTD_") {
                        latestJar.copyTo(File(templateDir, "server.jar"), overwrite = true)
                    }
                }
            }
        }
    }

    private fun getTemplateServerType(templateDir: File): String? {
        val configFile = File(templateDir.parentFile.parentFile, "groups/${templateDir.name}.json")
        if (!configFile.exists()) return null

        val content = configFile.readText()
        return when {
            content.contains("LEAF_") -> "LEAF_"
            content.contains("PAPER_") -> "PAPER_"
            content.contains("VELOCITY_") -> "VELOCITY_"
            content.contains("VELOCITYCTD_") -> "VELOCITYCTD_"
            else -> null
        }
    }

    fun shutdown() {
        updateScope.cancel()
    }
}