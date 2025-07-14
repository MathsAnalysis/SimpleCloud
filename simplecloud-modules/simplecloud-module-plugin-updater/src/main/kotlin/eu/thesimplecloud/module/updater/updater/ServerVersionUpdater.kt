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
            val type = when (index) {
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

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null

            val jsonArray = JSONArray(response.body.string())

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
                        (name.contains("leaf") || name.contains("server"))
                    ) {
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
    }

    private suspend fun updatePaper(): Boolean = withContext(Dispatchers.IO) {
        val paperVersion = "1.21.4"
        val latestBuild = fetchLatestPaperBuild(paperVersion) ?: return@withContext false

        val jarName = "PAPER_${paperVersion.replace(".", "_")}_$latestBuild.jar"
        val url = "https://api.papermc.io/v2/projects/paper/versions/$paperVersion/builds/$latestBuild/downloads/paper-$paperVersion-$latestBuild.jar"

        jarManager.cleanupOldVersions("PAPER_", keepLatest = 0)
        jarManager.downloadJar(url, jarName)
    }

    private suspend fun fetchLatestPaperBuild(version: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.papermc.io/v2/projects/paper/versions/$version/builds")
            .addHeader("User-Agent", "SimpleCloud-Updater")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null

            val json = JSONObject(response.body!!.string())
            val builds = json.getJSONArray("builds")

            if (builds.length() > 0) {
                val latestBuild = builds.getJSONObject(builds.length() - 1)
                return@withContext latestBuild.getInt("build").toString()
            }
            null
        }
    }

    private suspend fun updateVelocity(): Boolean = withContext(Dispatchers.IO) {
        val velocityVersion = "3.4.0-SNAPSHOT"
        val latestBuild = fetchLatestVelocityBuild(velocityVersion) ?: return@withContext false

        val jarName = "VELOCITY_${velocityVersion.replace(".", "_")}_$latestBuild.jar"
        val url = "https://api.papermc.io/v2/projects/velocity/versions/$velocityVersion/builds/$latestBuild/downloads/velocity-$velocityVersion-$latestBuild.jar"

        jarManager.cleanupOldVersions("VELOCITY_", keepLatest = 0)
        jarManager.downloadJar(url, jarName)
    }

    private suspend fun fetchLatestVelocityBuild(version: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.papermc.io/v2/projects/velocity/versions/$version/builds")
            .addHeader("User-Agent", "SimpleCloud-Updater")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null

            val json = JSONObject(response.body.string())
            val builds = json.getJSONArray("builds")

            if (builds.length() > 0) {
                val latestBuild = builds.getJSONObject(builds.length() - 1)
                return@withContext latestBuild.getInt("build").toString()
            }
            null
        }
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

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null

            val jsonArray = JSONArray(response.body.string())

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
    }

    fun syncJarsToTemplates() {
        println("[ServerVersionUpdater] Starting JAR synchronization to templates...")
        val templatesPath = File(DirectoryPaths.paths.templatesPath)

        templatesPath.listFiles()?.forEach { templateDir ->
            if (!templateDir.isDirectory) return@forEach

            println("[ServerVersionUpdater] Processing template: ${templateDir.name}")

            templateDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("LEAF_") ||
                    file.name.startsWith("PAPER_") ||
                    file.name.startsWith("VELOCITY_") ||
                    file.name.startsWith("VELOCITYCTD_") ||
                    file.name.lowercase().contains("paperclip") ||
                    file.name == "server.jar"
                ) {
                    file.delete()
                    println("[ServerVersionUpdater] Deleted old jar: ${file.name}")
                }
            }

            val templateType = getTemplateServerType(templateDir)
            println("[ServerVersionUpdater] Template ${templateDir.name} type: $templateType")

            templateType?.let { prefix ->
                jarManager.getLatestJar(prefix)?.let { latestJar ->
                    val specificJarTarget = File(templateDir, latestJar.name)
                    latestJar.copyTo(specificJarTarget, overwrite = true)
                    println("[ServerVersionUpdater] Copied ${latestJar.name} to ${templateDir.name}")

                    if (prefix != "VELOCITYCTD_") {
                        val serverJarTarget = File(templateDir, "server.jar")
                        latestJar.copyTo(serverJarTarget, overwrite = true)
                        println("[ServerVersionUpdater] Copied ${latestJar.name} as server.jar to ${templateDir.name}")
                    }
                } ?: run {
                    println("[ServerVersionUpdater] No jar found for prefix: $prefix")
                }
            } ?: run {
                println("[ServerVersionUpdater] Could not determine template type for: ${templateDir.name}")
            }
        }

        println("[ServerVersionUpdater] JAR synchronization to templates completed")
    }

    private fun getTemplateServerType(templateDir: File): String? {
        val configFile = File(templateDir.parentFile.parentFile, "groups/${templateDir.name}.json")
        if (!configFile.exists()) {
            // Fallback: check for existing jars in the template
            templateDir.listFiles()?.forEach { file ->
                when {
                    file.name.startsWith("LEAF_") || file.name.lowercase().contains("leaf") -> {
                        println("[ServerVersionUpdater] Found existing Leaf jar in template: ${templateDir.name}")
                        return "LEAF_"
                    }

                    file.name.startsWith("PAPER_") || file.name.lowercase().contains("paper") -> {
                        println("[ServerVersionUpdater] Found existing Paper jar in template: ${templateDir.name}")
                        return "PAPER_"
                    }

                    file.name.startsWith("VELOCITYCTD_") -> {
                        println("[ServerVersionUpdater] Found existing VelocityCTD jar in template: ${templateDir.name}")
                        return "VELOCITYCTD_"
                    }

                    file.name.startsWith("VELOCITY_") || file.name.lowercase().contains("velocity") -> {
                        println("[ServerVersionUpdater] Found existing Velocity jar in template: ${templateDir.name}")
                        return "VELOCITY_"
                    }
                }
            }

            println("[ServerVersionUpdater] Could not determine server type for template: ${templateDir.name}")
            return null
        }

        val content = configFile.readText()
        return when {
            content.contains("VELOCITYCTD") -> "VELOCITYCTD_"
            content.contains("VELOCITY") -> "VELOCITY_"
            content.contains("LEAF") -> "LEAF_"
            content.contains("PAPER") -> "PAPER_"
            else -> null
        }
    }

    fun shutdown() {
        updateScope.cancel()
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
        okHttpClient.cache?.close()
        println("[ServerVersionUpdater] Update scope and HTTP client shut down")
    }
}