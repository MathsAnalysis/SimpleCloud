package eu.thesimplecloud.module.updater.docker

import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.jsonlib.JsonLib
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class DockerJarUpdater {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val versionCacheFile = File(DirectoryPaths.paths.storagePath + "docker_versions.json")
    private val minecraftJarsPath = File(DirectoryPaths.paths.minecraftJarsPath)
    private val templatesPath = File(DirectoryPaths.paths.templatesPath)

    private val dockerSources = mapOf(
        "LEAF" to DockerSource(
            "leaf-server",
            "latest",
            "https://api.github.com/repos/Winds-Studio/Leaf/releases/latest",
            "https://github.com/Winds-Studio/Leaf/releases/download/ver-{version}/leaf-{version}.jar"
        ),
        "PAPER" to DockerSource(
            "paper-server",
            "latest",
            "https://api.papermc.io/v2/projects/paper/versions/1.21.7/builds",
            "https://api.papermc.io/v2/projects/paper/versions/1.21.7/builds/{build}/downloads/paper-1.21.7-{build}.jar"
        ),
        "VELOCITY" to DockerSource(
            "velocity-proxy",
            "latest",
            "https://api.papermc.io/v2/projects/velocity/versions/3.4.0-SNAPSHOT/builds",
            "https://api.papermc.io/v2/projects/velocity/versions/3.4.0-SNAPSHOT/builds/{build}/downloads/velocity-3.4.0-SNAPSHOT-{build}.jar"
        ),
        "VELOCITYCTD" to DockerSource(
            "velocityctd-proxy",
            "latest",
            "https://api.github.com/repos/GemstoneGG/Velocity-CTD/releases/latest",
            "https://github.com/GemstoneGG/Velocity-CTD/releases/download/Releases/velocity-proxy-3.4.0-SNAPSHOT-all.jar"
        )
    )

    private val updateInterval = TimeUnit.HOURS.toMillis(2)

    data class DockerSource(
        val imageName: String,
        val tag: String,
        val versionApi: String,
        val downloadUrl: String
    )

    data class VersionInfo(
        val version: String,
        val build: String? = null,
        val timestamp: Long,
        val downloadUrl: String,
        val checksum: String? = null
    )

    fun startDockerMonitoring() {
        println("[DockerJarUpdater] Starting Docker-based JAR monitoring")

        if (!isDockerAvailable()) {
            println("[DockerJarUpdater] Docker is not available, falling back to direct updates")
            return
        }

        minecraftJarsPath.mkdirs()
        templatesPath.mkdirs()

        GlobalScope.launch {
            while (true) {
                try {
                    performDockerVersionCheck()
                    delay(updateInterval)
                } catch (e: Exception) {
                    println("[DockerJarUpdater] Error in Docker monitoring: ${e.message}")
                    e.printStackTrace()
                    delay(TimeUnit.MINUTES.toMillis(10))
                }
            }
        }

        println("[DockerJarUpdater] Docker monitoring started (checking every 2 hours)")
    }

    private fun isDockerAvailable(): Boolean {
        return try {
            val process = ProcessBuilder("docker", "version").start()
            val exitCode = process.waitFor()
            println("[DockerJarUpdater] Docker is available (exit code: $exitCode)")
            exitCode == 0
        } catch (e: Exception) {
            println("[DockerJarUpdater] Docker is not available: ${e.message}")
            false
        }
    }

    private suspend fun performDockerVersionCheck() {
        println("[DockerJarUpdater] === Starting Docker version check ===")

        val cachedVersions = loadCachedVersions()
        var updateNeeded = false

        for ((serverType, dockerSource) in dockerSources) {
            try {
                println("[DockerJarUpdater] Checking $serverType versions...")

                val latestVersion = getLatestVersionFromAPI(dockerSource)
                val cachedVersion = cachedVersions[serverType]

                if (latestVersion != null && shouldUpdate(latestVersion, cachedVersion)) {
                    println("[DockerJarUpdater] Update needed for $serverType:")
                    println("  Current: ${cachedVersion?.version ?: "none"}")
                    println("  Latest: ${latestVersion.version}")

                    updateNeeded = true

                    cleanupOldJars(serverType)

                    if (downloadWithDocker(serverType, dockerSource, latestVersion)) {
                        updateTemplatesForServer(serverType, latestVersion)

                        cachedVersions[serverType] = latestVersion

                        println("[DockerJarUpdater] Successfully updated $serverType to ${latestVersion.version}")
                    } else {
                        println("[DockerJarUpdater] Failed to update $serverType")
                    }
                } else {
                    println("[DockerJarUpdater] $serverType is up to date (${cachedVersion?.version ?: "unknown"})")
                }

            } catch (e: Exception) {
                println("[DockerJarUpdater] Error checking $serverType: ${e.message}")
                e.printStackTrace()
            }
        }

        if (updateNeeded) {
            saveCachedVersions(cachedVersions)
            println("[DockerJarUpdater] Version cache updated")
        }

        println("[DockerJarUpdater] === Docker version check completed ===")
    }

    private suspend fun getLatestVersionFromAPI(dockerSource: DockerSource): VersionInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(dockerSource.versionApi)
                .addHeader("User-Agent", "SimpleCloud-DockerUpdater/1.0")
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (response.isSuccessful) {
                val jsonResponse = response.body?.string()
                if (jsonResponse != null) {
                    parseVersionResponse(jsonResponse, dockerSource)
                } else null
            } else {
                println("[DockerJarUpdater] API request failed: ${response.code}")
                null
            }
        } catch (e: Exception) {
            println("[DockerJarUpdater] Error fetching version info: ${e.message}")
            null
        }
    }

    private fun parseVersionResponse(jsonResponse: String, dockerSource: DockerSource): VersionInfo? {
        return try {
            val data = JsonLib.fromJsonString(jsonResponse)

            when (dockerSource.imageName) {
                "leaf-server" -> {
                    val tagName = data?.getString("tag_name")?.removePrefix("ver-")
                    val downloadUrl = dockerSource.downloadUrl.replace("{version}", tagName ?: "")

                    VersionInfo(
                        version = tagName ?: "unknown",
                        timestamp = System.currentTimeMillis(),
                        downloadUrl = downloadUrl
                    )
                }
                "paper-server" -> {
                    val buildsArray = data?.getAsJsonArray("builds")
                    val latestBuild = buildsArray?.last()?.asJsonObject?.get("build")?.asString
                    val downloadUrl = dockerSource.downloadUrl.replace("{build}", latestBuild ?: "")

                    VersionInfo(
                        version = "1.21.7",
                        build = latestBuild,
                        timestamp = System.currentTimeMillis(),
                        downloadUrl = downloadUrl
                    )
                }
                "velocity-proxy" -> {
                    val buildsArray = data?.getAsJsonArray("builds")
                    val latestBuild = buildsArray?.last()?.asJsonObject?.get("build")?.asString
                    val downloadUrl = dockerSource.downloadUrl.replace("{build}", latestBuild ?: "")

                    VersionInfo(
                        version = "3.4.0-SNAPSHOT",
                        build = latestBuild,
                        timestamp = System.currentTimeMillis(),
                        downloadUrl = downloadUrl
                    )
                }
                "velocityctd-proxy" -> {
                    val tagName = data?.getString("tag_name")

                    VersionInfo(
                        version = tagName ?: "Releases",
                        timestamp = System.currentTimeMillis(),
                        downloadUrl = dockerSource.downloadUrl
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            println("[DockerJarUpdater] Error parsing version response: ${e.message}")
            null
        }
    }

    private fun shouldUpdate(latestVersion: VersionInfo, cachedVersion: VersionInfo?): Boolean {
        if (cachedVersion == null) return true

        return when {
            latestVersion.build != null && cachedVersion.build != null -> {
                latestVersion.build != cachedVersion.build
            }
            latestVersion.version != cachedVersion.version -> true
            else -> false
        }
    }

    private fun cleanupOldJars(serverType: String) {
        println("[DockerJarUpdater] Cleaning up old $serverType JAR files")

        minecraftJarsPath.listFiles()?.forEach { file ->
            if (file.name.startsWith("${serverType}_") && file.name.endsWith(".jar")) {
                println("[DockerJarUpdater] Deleting old JAR: ${file.name}")
                file.delete()
            }
        }

        templatesPath.listFiles()?.forEach { templateDir ->
            if (templateDir.isDirectory) {
                templateDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("${serverType.lowercase()}-") && file.name.endsWith(".jar")) {
                        println("[DockerJarUpdater] Deleting old template JAR: ${templateDir.name}/${file.name}")
                        file.delete()
                    }
                }
            }
        }
    }

    private suspend fun downloadWithDocker(serverType: String, dockerSource: DockerSource, versionInfo: VersionInfo): Boolean {
        return try {
            println("[DockerJarUpdater] Downloading $serverType using Docker container")

            val jarName = "${serverType}_${sanitizeVersion(versionInfo.version)}.jar"
            val targetFile = File(minecraftJarsPath, jarName)

            val dockerCommand = arrayOf(
                "docker", "run", "--rm",
                "-v", "${minecraftJarsPath.absolutePath}:/output",
                "alpine/curl:latest",
                "sh", "-c",
                "curl -L -o /output/$jarName '${versionInfo.downloadUrl}' && ls -la /output/"
            )

            val process = ProcessBuilder(*dockerCommand).start()
            val exitCode = process.waitFor()

            val success = exitCode == 0

            if (success) {
                println("[DockerJarUpdater] Successfully downloaded $serverType via Docker")

                if (targetFile.exists() && targetFile.length() > 0) {
                    println("[DockerJarUpdater] JAR file verified: ${targetFile.name} (${targetFile.length() / 1024}KB)")
                    return true
                } else {
                    println("[DockerJarUpdater] JAR file verification failed")
                    return false
                }
            } else {
                println("[DockerJarUpdater] Docker download failed for $serverType (exit code: $exitCode)")
                return false
            }

        } catch (e: Exception) {
            println("[DockerJarUpdater] Error in Docker download: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private fun updateTemplatesForServer(serverType: String, versionInfo: VersionInfo) {
        println("[DockerJarUpdater] Updating templates for $serverType")

        val jarFileName = "${serverType}_${sanitizeVersion(versionInfo.version)}.jar"
        val sourceJar = File(minecraftJarsPath, jarFileName)

        if (!sourceJar.exists()) {
            println("[DockerJarUpdater] Source JAR not found: $jarFileName")
            return
        }

        val templatesUsingServer = findTemplatesUsingServer(serverType)

        templatesUsingServer.forEach { templateDir ->
            try {
                val targetJar = File(templateDir, jarFileName)
                sourceJar.copyTo(targetJar, overwrite = true)
                println("[DockerJarUpdater] Updated template ${templateDir.name} with $jarFileName")
            } catch (e: Exception) {
                println("[DockerJarUpdater] Error updating template ${templateDir.name}: ${e.message}")
            }
        }
    }

    private fun findTemplatesUsingServer(serverType: String): List<File> {
        val matchingTemplates = mutableListOf<File>()

        val groupsDir = File(DirectoryPaths.paths.storagePath + "groups")
        if (groupsDir.exists()) {
            groupsDir.listFiles()?.forEach { groupFile ->
                if (groupFile.name.endsWith(".json")) {
                    try {
                        val groupData = JsonLib.fromJsonFile(groupFile)
                        val serviceVersion = groupData?.getString("serviceVersion")
                        val templateName = groupData?.getString("templateName")

                        if (serviceVersion?.startsWith("${serverType}_") == true && templateName != null) {
                            val templateDir = File(templatesPath, templateName)
                            if (templateDir.exists() && templateDir.isDirectory) {
                                matchingTemplates.add(templateDir)
                            }
                        }
                    } catch (e: Exception) {
                        println("[DockerJarUpdater] Error reading group file ${groupFile.name}: ${e.message}")
                    }
                }
            }
        }

        return matchingTemplates
    }

    private fun loadCachedVersions(): MutableMap<String, VersionInfo> {
        return try {
            if (versionCacheFile.exists()) {
                val json = JsonLib.fromJsonFile(versionCacheFile)
                val cache = mutableMapOf<String, VersionInfo>()

                json?.jsonElement?.asJsonObject?.keySet()?.forEach { key ->
                    val versionData = json.getProperty(key)
                    if (versionData != null) {
                        cache[key] = VersionInfo(
                            version = versionData.getString("version") ?: "",
                            build = versionData.getString("build"),
                            timestamp = versionData.getLong("timestamp") ?: 0,
                            downloadUrl = versionData.getString("downloadUrl") ?: "",
                            checksum = versionData.getString("checksum")
                        )
                    }
                }

                cache
            } else {
                mutableMapOf()
            }
        } catch (e: Exception) {
            println("[DockerJarUpdater] Error loading cached versions: ${e.message}")
            mutableMapOf()
        }
    }

    private fun saveCachedVersions(versions: Map<String, VersionInfo>) {
        try {
            val cacheData = mutableMapOf<String, Any>()

            versions.forEach { (key, versionInfo) ->
                cacheData[key] = mapOf(
                    "version" to versionInfo.version,
                    "build" to versionInfo.build,
                    "timestamp" to versionInfo.timestamp,
                    "downloadUrl" to versionInfo.downloadUrl,
                    "checksum" to versionInfo.checksum
                ).filterValues { it != null }
            }

            versionCacheFile.parentFile.mkdirs()
            versionCacheFile.writeText(JsonLib.fromObject(cacheData).toString())

        } catch (e: Exception) {
            println("[DockerJarUpdater] Error saving cached versions: ${e.message}")
        }
    }

    private fun sanitizeVersion(version: String): String {
        return version.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
    }

    fun getVersionStatus(): Map<String, Any> {
        val cachedVersions = loadCachedVersions()
        val status = mutableMapOf<String, Any>()

        dockerSources.forEach { (serverType, dockerSource) ->
            val cached = cachedVersions[serverType]
            status[serverType] = mapOf(
                "image" to dockerSource.imageName,
                "currentVersion" to (cached?.version ?: "unknown"),
                "currentBuild" to (cached?.build ?: "unknown"),
                "lastUpdated" to (cached?.timestamp ?: 0),
                "dockerAvailable" to isDockerAvailable()
            )
        }

        return status
    }

    suspend fun forceUpdateAll() {
        println("[DockerJarUpdater] Force updating all servers via Docker")

        if (versionCacheFile.exists()) {
            versionCacheFile.delete()
        }

        performDockerVersionCheck()

        println("[DockerJarUpdater] Force update completed")
    }
}