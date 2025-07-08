package eu.thesimplecloud.module.updater.manager

import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.jsonlib.JsonLib
import eu.thesimplecloud.module.updater.bootstrap.PluginUpdaterModule
import eu.thesimplecloud.module.updater.config.AutoManagerConfig
import eu.thesimplecloud.module.updater.utils.LoggingUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ServerVersionManager(
    private val module: PluginUpdaterModule,
    private val config: AutoManagerConfig
) {

    companion object {
        private const val TAG = "ServerVersionManager"
        private const val GITHUB_API_BASE = "https://api.github.com"
        private const val PAPER_API_BASE = "https://api.papermc.io/v2"
        private const val USER_AGENT = "SimpleCloud-AutoUpdater"
    }

    private val versionsDirectory = File(DirectoryPaths.paths.storagePath + config.serverVersions.downloadDirectory)
    private val versionManifestFile = File(versionsDirectory, "versions-manifest.json")
    private val currentVersions = mutableListOf<ServerVersionEntry>()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.networking.connectTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(config.networking.readTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .followRedirects(config.networking.followRedirects)
        .build()

    init {
        LoggingUtils.init(TAG, "Initializing ServerVersionManager...")
        initializeDirectories()
        loadVersionManifest()
        logInitialConfiguration()
    }

    private fun initializeDirectories() {
        LoggingUtils.debug(TAG, "Creating server versions directories...")

        if (!versionsDirectory.exists()) {
            versionsDirectory.mkdirs()
            LoggingUtils.debug(TAG, "Created versions directory: ${versionsDirectory.absolutePath}")
        }

        config.serverSoftware.forEach { software ->
            val softwareDir = File(versionsDirectory, software.lowercase())
            if (!softwareDir.exists()) {
                softwareDir.mkdirs()
                LoggingUtils.debug(TAG, "Created directory for $software: ${softwareDir.absolutePath}")
            }
        }
    }

    private fun logInitialConfiguration() {
        LoggingUtils.debugConfig(TAG, "versions_directory", versionsDirectory.absolutePath)
        LoggingUtils.debugConfig(TAG, "server_software", config.serverSoftware)
        LoggingUtils.debugConfig(TAG, "keep_old_versions", config.serverVersions.keepOldVersions)
        LoggingUtils.debugConfig(TAG, "max_versions_per_type", config.serverVersions.maxVersionsPerType)
        LoggingUtils.debugConfig(TAG, "auto_cleanup_enabled", config.serverVersions.autoCleanupEnabled)
    }

    suspend fun updateAllVersions(): Boolean = withContext(Dispatchers.IO) {
        LoggingUtils.debugStart(TAG, "updating all server versions")

        if (!config.enableServerVersionUpdates) {
            LoggingUtils.info(TAG, "Server version updates are disabled")
            return@withContext true
        }

        val startTime = System.currentTimeMillis()
        var overallSuccess = true
        val updatedVersions = mutableListOf<ServerVersionEntry>()

        try {
            for (software in config.serverSoftware) {
                try {
                    LoggingUtils.debug(TAG, "Processing $software versions...")
                    val versionEntry = updateSoftwareVersions(software)

                    if (versionEntry != null) {
                        updatedVersions.add(versionEntry)
                        downloadAndManageVersions(software, versionEntry)
                        LoggingUtils.info(TAG, "Successfully updated $software: ${versionEntry.latestVersion}")
                    } else {
                        LoggingUtils.warn(TAG, "Failed to update $software versions")
                        overallSuccess = false
                    }
                } catch (e: Exception) {
                    LoggingUtils.error(TAG, "Error updating $software: ${e.message}", e)
                    overallSuccess = false
                }
            }

            currentVersions.clear()
            currentVersions.addAll(updatedVersions)
            saveVersionManifest()

            if (updatedVersions.isNotEmpty()) {
                registerVersionsWithService(updatedVersions)
            }

            val duration = System.currentTimeMillis() - startTime
            LoggingUtils.debugStats(TAG, mapOf(
                "total_software" to config.serverSoftware.size,
                "successful_updates" to updatedVersions.size,
                "duration_ms" to duration
            ))

            if (overallSuccess) {
                LoggingUtils.debugSuccess(TAG, "updating all server versions")
            } else {
                LoggingUtils.debugFailure(TAG, "updating all server versions", "some updates failed")
            }

            overallSuccess
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Critical error updating server versions: ${e.message}", e)
            false
        }
    }

    private suspend fun updateSoftwareVersions(software: String): ServerVersionEntry? = withContext(Dispatchers.IO) {
        LoggingUtils.debugStart(TAG, "updating $software versions")

        return@withContext try {
            when (software.lowercase()) {
                "paper" -> updatePaperVersions()
                "leaf" -> updateLeafVersions()
                "velocity" -> updateVelocityVersions()
                "velocityctd" -> updateVelocityCTDVersions()
                else -> {
                    LoggingUtils.warn(TAG, "Unknown server software: $software")
                    null
                }
            }
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error updating $software versions: ${e.message}", e)
            null
        }
    }

    private suspend fun updatePaperVersions(): ServerVersionEntry = withContext(Dispatchers.IO) {
        LoggingUtils.debug(TAG, "Fetching Paper versions from PaperMC API...")

        try {
            val versionsUrl = "$PAPER_API_BASE/projects/paper"
            val response = makeApiRequest(versionsUrl)
            val projectData = JSONObject(response)
            val versions = projectData.getJSONArray("versions")

            val latestVersion = versions.getString(versions.length() - 1)
            LoggingUtils.debug(TAG, "Latest Paper version: $latestVersion")

            val buildsUrl = "$PAPER_API_BASE/projects/paper/versions/$latestVersion/builds"
            val buildsResponse = makeApiRequest(buildsUrl)
            val buildsData = JSONObject(buildsResponse)
            val builds = buildsData.getJSONArray("builds")

            val downloadLinks = mutableListOf<VersionDownload>()

            val buildCount = minOf(config.serverVersions.maxVersionsPerType, builds.length())
            for (i in builds.length() - buildCount until builds.length()) {
                val build = builds.getJSONObject(i)
                val buildNumber = build.getInt("build")
                val downloads = build.getJSONObject("downloads")
                val application = downloads.getJSONObject("application")
                val fileName = application.getString("name")

                val downloadUrl = "$PAPER_API_BASE/projects/paper/versions/$latestVersion/builds/$buildNumber/downloads/$fileName"

                downloadLinks.add(VersionDownload(
                    version = "$latestVersion-$buildNumber",
                    link = downloadUrl,
                    buildNumber = buildNumber,
                    checksum = application.optString("sha256", null)
                ))
            }

            LoggingUtils.debug(TAG, "Found ${downloadLinks.size} Paper builds")
            ServerVersionEntry("Paper", "SERVER", true, latestVersion, downloadLinks)

        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error fetching Paper versions: ${e.message}", e)
            createFallbackEntry("Paper", "1.21.3")
        }
    }

    private suspend fun updateLeafVersions(): ServerVersionEntry = withContext(Dispatchers.IO) {
        LoggingUtils.debug(TAG, "Fetching Leaf versions from GitHub...")

        try {
            val releasesUrl = "$GITHUB_API_BASE/repos/Winds-Studio/Leaf/releases"
            val response = makeApiRequest(releasesUrl)
            val releases = JSONArray(response)

            val downloadLinks = mutableListOf<VersionDownload>()
            val maxVersions = config.serverVersions.maxVersionsPerType

            for (i in 0 until minOf(maxVersions, releases.length())) {
                val release = releases.getJSONObject(i)

                if (release.getBoolean("draft") || release.getBoolean("prerelease")) {
                    continue
                }

                val tagName = release.getString("tag_name")
                val version = if (tagName.startsWith("v")) tagName.substring(1) else tagName
                val assets = release.getJSONArray("assets")

                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    val assetName = asset.getString("name")

                    if (assetName.endsWith(".jar") && !assetName.contains("sources") && !assetName.contains("javadoc")) {
                        val downloadUrl = asset.getString("browser_download_url")
                        val buildNumber = extractBuildNumberFromLeafVersion(version)

                        downloadLinks.add(VersionDownload(
                            version = version,
                            link = downloadUrl,
                            buildNumber = buildNumber,
                            checksum = null
                        ))
                        break
                    }
                }
            }

            val latestVersion = downloadLinks.firstOrNull()?.version ?: "1.21.3-git-unknown"
            LoggingUtils.debug(TAG, "Found ${downloadLinks.size} Leaf versions, latest: $latestVersion")

            ServerVersionEntry("Leaf", "SERVER", false, latestVersion, downloadLinks)

        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error fetching Leaf versions: ${e.message}", e)
            createFallbackEntry("Leaf", "1.21.3-git-a1b2c3d")
        }
    }

    private suspend fun updateVelocityVersions(): ServerVersionEntry = withContext(Dispatchers.IO) {
        LoggingUtils.debug(TAG, "Fetching Velocity versions from PaperMC API...")

        try {
            val versionsUrl = "$PAPER_API_BASE/projects/velocity"
            val response = makeApiRequest(versionsUrl)
            val projectData = JSONObject(response)
            val versions = projectData.getJSONArray("versions")

            val latestVersion = versions.getString(versions.length() - 1)
            LoggingUtils.debug(TAG, "Latest Velocity version: $latestVersion")

            val buildsUrl = "$PAPER_API_BASE/projects/velocity/versions/$latestVersion/builds"
            val buildsResponse = makeApiRequest(buildsUrl)
            val buildsData = JSONObject(buildsResponse)
            val builds = buildsData.getJSONArray("builds")

            val downloadLinks = mutableListOf<VersionDownload>()
            val buildCount = minOf(config.serverVersions.maxVersionsPerType, builds.length())

            for (i in builds.length() - buildCount until builds.length()) {
                val build = builds.getJSONObject(i)
                val buildNumber = build.getInt("build")
                val downloads = build.getJSONObject("downloads")
                val application = downloads.getJSONObject("application")
                val fileName = application.getString("name")

                val downloadUrl = "$PAPER_API_BASE/projects/velocity/versions/$latestVersion/builds/$buildNumber/downloads/$fileName"

                downloadLinks.add(VersionDownload(
                    version = "$latestVersion-$buildNumber",
                    link = downloadUrl,
                    buildNumber = buildNumber,
                    checksum = application.optString("sha256", null)
                ))
            }

            LoggingUtils.debug(TAG, "Found ${downloadLinks.size} Velocity builds")
            ServerVersionEntry("Velocity", "PROXY", false, latestVersion, downloadLinks)

        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error fetching Velocity versions: ${e.message}", e)
            createFallbackEntry("Velocity", "3.4.0-SNAPSHOT")
        }
    }

    private suspend fun updateVelocityCTDVersions(): ServerVersionEntry = withContext(Dispatchers.IO) {
        LoggingUtils.debug(TAG, "Fetching VelocityCTD versions from GitHub...")

        try {
            val releasesUrl = "$GITHUB_API_BASE/repos/GemstoneGG/Velocity-CTD/releases"
            val response = makeApiRequest(releasesUrl)
            val releases = JSONArray(response)

            val downloadLinks = mutableListOf<VersionDownload>()
            val maxVersions = config.serverVersions.maxVersionsPerType

            for (i in 0 until minOf(maxVersions, releases.length())) {
                val release = releases.getJSONObject(i)
                val tagName = release.getString("tag_name")
                val version = if (tagName.startsWith("v")) tagName.substring(1) else tagName
                val assets = release.getJSONArray("assets")

                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    val assetName = asset.getString("name")

                    if (assetName.endsWith(".jar") && !assetName.contains("sources")) {
                        val downloadUrl = asset.getString("browser_download_url")

                        downloadLinks.add(VersionDownload(
                            version = version,
                            link = downloadUrl,
                            buildNumber = extractBuildNumberFromVersion(version),
                            checksum = null
                        ))
                        break
                    }
                }
            }

            val latestVersion = downloadLinks.firstOrNull()?.version ?: "3.4.0-SNAPSHOT"
            LoggingUtils.debug(TAG, "Found ${downloadLinks.size} VelocityCTD versions, latest: $latestVersion")

            ServerVersionEntry("VelocityCTD", "PROXY", false, latestVersion, downloadLinks)

        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error fetching VelocityCTD versions: ${e.message}", e)
            createFallbackEntry("VelocityCTD", "3.4.0-SNAPSHOT")
        }
    }

    suspend fun downloadAndManageVersions(software: String, versionEntry: ServerVersionEntry) = withContext(Dispatchers.IO) {
        LoggingUtils.debugStart(TAG, "downloading and managing $software versions")

        val softwareDir = File(versionsDirectory, software.lowercase())
        softwareDir.mkdirs()

        var downloadedCount = 0
        var skippedCount = 0

        try {
            versionEntry.downloadLinks.forEach { version ->
                val jarFile = File(softwareDir, "${software.lowercase()}-${version.version}.jar")

                if (shouldDownloadVersion(jarFile, version)) {
                    LoggingUtils.debug(TAG, "Downloading $software ${version.version}...")

                    if (downloadVersionFile(version, jarFile)) {
                        downloadedCount++
                        LoggingUtils.info(TAG, "Downloaded $software ${version.version} (${jarFile.length() / 1024}KB)")
                    } else {
                        LoggingUtils.error(TAG, "Failed to download $software ${version.version}")
                    }
                } else {
                    skippedCount++
                    LoggingUtils.debug(TAG, "Skipping $software ${version.version} (already exists)")
                }
            }

            if (config.serverVersions.autoCleanupEnabled) {
                cleanupOldVersions(softwareDir, software)
            }

            LoggingUtils.debugStats(TAG, mapOf(
                "software" to software,
                "downloaded" to downloadedCount,
                "skipped" to skippedCount
            ))

            LoggingUtils.debugSuccess(TAG, "downloading and managing $software versions")

        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error downloading $software versions: ${e.message}", e)
            throw e
        }
    }

    private fun shouldDownloadVersion(jarFile: File, version: VersionDownload): Boolean {
        if (!jarFile.exists()) {
            LoggingUtils.debug(TAG, "File does not exist: ${jarFile.name}")
            return true
        }

        if (!config.serverVersions.enableVersionCaching) {
            LoggingUtils.debug(TAG, "Version caching disabled, re-downloading: ${jarFile.name}")
            return true
        }

        val hoursSinceModified = (System.currentTimeMillis() - jarFile.lastModified()) / (1000 * 60 * 60)
        val shouldDownload = hoursSinceModified > config.serverVersions.cacheExpirationHours

        LoggingUtils.debug(TAG, "File ${jarFile.name} age: ${hoursSinceModified}h, should download: $shouldDownload")
        return shouldDownload
    }

    private suspend fun downloadVersionFile(version: VersionDownload, targetFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (targetFile.exists() && config.enableBackup) {
                val backupFile = File(targetFile.parentFile, "${targetFile.name}.backup")
                targetFile.copyTo(backupFile, overwrite = true)
                LoggingUtils.debug(TAG, "Created backup: ${backupFile.name}")
            }

            val request = Request.Builder()
                .url(version.link)
                .addHeader("User-Agent", config.networking.userAgent)
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                LoggingUtils.error(TAG, "Download failed: HTTP ${response.code}")
                return@withContext false
            }

            val responseBody = response.body ?: run {
                LoggingUtils.error(TAG, "Empty response body")
                return@withContext false
            }

            val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
            tempFile.writeBytes(responseBody.bytes())

            if (tempFile.length() > config.performance.maxFileSizeBytes) {
                tempFile.delete()
                LoggingUtils.error(TAG, "File too large: ${tempFile.length()} bytes")
                return@withContext false
            }

            if (version.checksum != null && config.serverVersions.enableIntegrityChecks) {
                val fileChecksum = calculateSHA256(tempFile)
                if (fileChecksum != version.checksum) {
                    tempFile.delete()
                    LoggingUtils.error(TAG, "Checksum mismatch: expected ${version.checksum}, got $fileChecksum")
                    return@withContext false
                }
                LoggingUtils.debug(TAG, "Checksum validation passed")
            }

            tempFile.renameTo(targetFile)
            LoggingUtils.debug(TAG, "Successfully downloaded: ${targetFile.name}")

            true
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error downloading ${version.version}: ${e.message}", e)
            false
        }
    }

    private fun cleanupOldVersions(softwareDir: File, software: String) {
        if (!config.serverVersions.keepOldVersions) {
            LoggingUtils.debug(TAG, "Skipping cleanup - keepOldVersions is disabled")
            return
        }

        LoggingUtils.debugStart(TAG, "cleaning up old $software versions")

        try {
            val jarFiles = softwareDir.listFiles { _, name ->
                name.endsWith(".jar") && !name.endsWith(".backup")
            }?.toList() ?: emptyList()

            if (jarFiles.size <= config.serverVersions.preserveLatestVersions) {
                LoggingUtils.debug(TAG, "Not enough versions to clean up (${jarFiles.size} <= ${config.serverVersions.preserveLatestVersions})")
                return
            }

            val sortedFiles = jarFiles.sortedByDescending { it.lastModified() }
            val filesToDelete = sortedFiles.drop(config.serverVersions.preserveLatestVersions)

            filesToDelete.forEach { file ->
                val daysSinceModified = (System.currentTimeMillis() - file.lastModified()) / (1000 * 60 * 60 * 24)

                if (daysSinceModified > config.serverVersions.cleanupIntervalDays) {
                    if (file.delete()) {
                        LoggingUtils.debug(TAG, "Cleaned up old version: ${file.name}")
                    } else {
                        LoggingUtils.warn(TAG, "Failed to delete old version: ${file.name}")
                    }
                }
            }

            LoggingUtils.debugSuccess(TAG, "cleaning up old $software versions")

        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error cleaning up old versions: ${e.message}", e)
        }
    }

    private suspend fun makeApiRequest(url: String): String = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        repeat(config.networking.maxRetries) { attempt ->
            try {
                LoggingUtils.debugNetwork(TAG, "API request (attempt ${attempt + 1})", url)

                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", config.networking.userAgent)
                    .apply {
                        config.networking.customHeaders.forEach { (key, value) ->
                            addHeader(key, value)
                        }
                    }
                    .build()

                val response = okHttpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    LoggingUtils.debug(TAG, "API request successful (${body.length} characters)")
                    return@withContext body
                } else {
                    throw Exception("HTTP ${response.code}: ${response.message}")
                }
            } catch (e: Exception) {
                lastException = e
                LoggingUtils.debug(TAG, "API request attempt ${attempt + 1} failed: ${e.message}")

                if (attempt < config.networking.maxRetries - 1) {
                    LoggingUtils.debug(TAG, "Retrying in ${config.networking.retryDelaySeconds} seconds...")
                    kotlinx.coroutines.delay(config.networking.retryDelaySeconds * 1000L)
                }
            }
        }

        throw lastException ?: Exception("All API request attempts failed")
    }

    private fun registerVersionsWithService(versionEntries: List<ServerVersionEntry>) {
        LoggingUtils.debugStart(TAG, "registering versions with service")

        versionEntries.forEach { entry ->
            LoggingUtils.debug(TAG, "Registering ${entry.name} versions with service...")
        }

        LoggingUtils.debugSuccess(TAG, "registering versions with service")
    }

    private fun loadVersionManifest() {
        if (versionManifestFile.exists()) {
            try {
                val manifest = JsonLib.fromJsonFile(versionManifestFile)
                LoggingUtils.debug(TAG, "Loaded version manifest")
            } catch (e: Exception) {
                LoggingUtils.error(TAG, "Error loading version manifest: ${e.message}", e)
            }
        }
    }

    private fun saveVersionManifest() {
        try {
            val manifest = mapOf(
                "version" to "1.0.0",
                "last_updated" to System.currentTimeMillis(),
                "software_versions" to currentVersions.associate { entry ->
                    entry.name to mapOf(
                        "latest_version" to entry.latestVersion,
                        "type" to entry.type,
                        "versions_count" to entry.downloadLinks.size
                    )
                }
            )

            JsonLib.fromObject(manifest).saveAsFile(versionManifestFile)
            LoggingUtils.debug(TAG, "Saved version manifest")
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error saving version manifest: ${e.message}", e)
        }
    }

    private fun calculateSHA256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(config.performance.bufferSizeBytes)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun createFallbackEntry(name: String, version: String): ServerVersionEntry {
        LoggingUtils.debug(TAG, "Creating fallback entry for $name")
        return ServerVersionEntry(
            name = name,
            type = if (name.contains("velocity", ignoreCase = true)) "PROXY" else "SERVER",
            isPaperclip = name.equals("paper", ignoreCase = true),
            latestVersion = version,
            downloadLinks = emptyList()
        )
    }

    private fun extractBuildNumberFromLeafVersion(version: String): Int? {
        val gitRegex = Regex("""(\d+)\.(\d+)\.(\d+)-git-([a-f0-9]{7,})""")
        val match = gitRegex.find(version)

        return if (match != null) {
            val major = match.groupValues[1].toInt()
            val minor = match.groupValues[2].toInt()
            val patch = match.groupValues[3].toInt()
            (major * 100000) + (minor * 1000) + (patch * 10) + 1
        } else {
            extractBuildNumberFromVersion(version)
        }
    }

    private fun extractBuildNumberFromVersion(version: String): Int? {
        return version.split("-").lastOrNull()?.toIntOrNull()
    }

    fun getCurrentVersions(): List<ServerVersionEntry> = currentVersions.toList()

    fun getVersionsForSoftware(software: String): ServerVersionEntry? {
        return currentVersions.find { it.name.equals(software, ignoreCase = true) }
    }

    fun getStats(): Map<String, Any> {
        val totalVersions = currentVersions.sumOf { it.downloadLinks.size }
        val versionsByType = currentVersions.associate { it.name to it.downloadLinks.size }

        return mapOf(
            "versions_directory" to versionsDirectory.absolutePath,
            "total_software_types" to currentVersions.size,
            "total_versions" to totalVersions,
            "versions_by_software" to versionsByType,
            "keep_old_versions" to config.serverVersions.keepOldVersions,
            "max_versions_per_type" to config.serverVersions.maxVersionsPerType,
            "auto_cleanup_enabled" to config.serverVersions.autoCleanupEnabled
        )
    }

    data class ServerVersionEntry(
        val name: String,
        val type: String,
        val isPaperclip: Boolean,
        val latestVersion: String,
        val downloadLinks: List<VersionDownload>
    )

    data class VersionDownload(
        val version: String,
        val link: String,
        val buildNumber: Int? = null,
        val checksum: String? = null
    )
}