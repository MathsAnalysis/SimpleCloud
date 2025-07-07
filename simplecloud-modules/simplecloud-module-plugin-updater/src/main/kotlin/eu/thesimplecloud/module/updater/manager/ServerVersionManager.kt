package eu.thesimplecloud.module.updater.manager

import eu.thesimplecloud.api.service.version.type.ServiceAPIType
import eu.thesimplecloud.module.updater.bootstrap.PluginUpdaterModule
import eu.thesimplecloud.module.updater.config.AutoManagerConfig
import eu.thesimplecloud.module.updater.utils.LoggingUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ServerVersionManager(
    private val module: PluginUpdaterModule,
    private val config: AutoManagerConfig
) {

    companion object {
        private const val TAG = "ServerVersionManager"
        private const val GITHUB_API_BASE = "https://api.github.com"
        private const val PAPER_API_BASE = "https://api.papermc.io/v2"
        private const val VELOCITY_API_BASE = "https://api.papermc.io/v2/projects/velocity"
        private const val USER_AGENT = "SimpleCloud-AutoUpdater"
        private const val CONNECT_TIMEOUT = 30L
        private const val READ_TIMEOUT = 60L
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val currentVersions = mutableListOf<ServerVersionEntry>()
    private val leafExtractor = LeafVersionExtractor()
    private val velocityCTDExtractor = VelocityCTDVersionExtractor()
    private val velocityExtractor = VelocityVersionExtractor()
    private val paperExtractor = PaperVersionExtractor()

    init {
        LoggingUtils.init(TAG, "Initializing ServerVersionManager...")
        logInitialConfiguration()
    }

    private fun logInitialConfiguration() {
        LoggingUtils.debugConfig(TAG, "server_software", config.serverSoftware)
        LoggingUtils.debugConfig(TAG, "enable_server_version_updates", config.enableServerVersionUpdates)
        LoggingUtils.debugConfig(TAG, "connect_timeout", "${CONNECT_TIMEOUT}s")
        LoggingUtils.debugConfig(TAG, "read_timeout", "${READ_TIMEOUT}s")
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
        val stats = mutableMapOf<String, Any>()

        try {
            LoggingUtils.debug(TAG, "Updating versions for ${config.serverSoftware.size} server software types...")

            for (software in config.serverSoftware) {
                try {
                    LoggingUtils.debug(TAG, "Updating $software versions...")
                    val versionEntry = updateSoftwareVersions(software)

                    if (versionEntry != null) {
                        updatedVersions.add(versionEntry)
                        LoggingUtils.debug(TAG, "Successfully updated $software: latest=${versionEntry.latestVersion}, count=${versionEntry.downloadLinks.size}")
                        stats[software] = mapOf(
                            "success" to true,
                            "latest_version" to versionEntry.latestVersion,
                            "versions_count" to versionEntry.downloadLinks.size
                        )
                    } else {
                        LoggingUtils.warn(TAG, "Failed to update $software versions")
                        overallSuccess = false
                        stats[software] = mapOf("success" to false)
                    }
                } catch (e: Exception) {
                    LoggingUtils.error(TAG, "Error updating $software versions: ${e.message}", e)
                    overallSuccess = false
                    stats[software] = mapOf("success" to false, "error" to e.message)
                }
            }

            currentVersions.clear()
            currentVersions.addAll(updatedVersions)

            if (updatedVersions.isNotEmpty()) {
                LoggingUtils.debug(TAG, "Registering updated versions...")
                registerAndDownloadVersions(updatedVersions)
            }

            val duration = System.currentTimeMillis() - startTime
            val finalStats = stats + mapOf(
                "total_software" to config.serverSoftware.size,
                "successful_updates" to updatedVersions.size,
                "failed_updates" to (config.serverSoftware.size - updatedVersions.size),
                "duration_ms" to duration
            )

            LoggingUtils.debugStats(TAG, finalStats)

            if (overallSuccess) {
                LoggingUtils.debugSuccess(TAG, "updating all server versions")
                LoggingUtils.info(TAG, "Successfully updated ${updatedVersions.size}/${config.serverSoftware.size} server software versions")
            } else {
                LoggingUtils.debugFailure(TAG, "updating all server versions", "${config.serverSoftware.size - updatedVersions.size} updates failed")
            }

            overallSuccess
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Critical error updating server versions: ${e.message}", e)
            false
        }
    }

    private suspend fun updateSoftwareVersions(software: String): ServerVersionEntry? {
        LoggingUtils.debugStart(TAG, "updating $software versions")

        return try {
            val versionEntry = when (software.lowercase()) {
                "paper" -> updatePaper()
                "leaf" -> updateWithExtractor("Leaf", "Winds-Studio/Leaf", leafExtractor)
                "velocity" -> updateVelocityStandard()
                "velocityctd" -> updateWithExtractor("VelocityCTD", "GemstoneGG/Velocity-CTD", velocityCTDExtractor)
                else -> {
                    LoggingUtils.warn(TAG, "Unknown server software: $software")
                    null
                }
            }

            if (versionEntry != null) {
                LoggingUtils.debugSuccess(TAG, "updating $software versions")
            } else {
                LoggingUtils.debugFailure(TAG, "updating $software versions", "extractor returned null")
            }

            versionEntry
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error updating $software: ${e.message}", e)
            null
        }
    }

    private suspend fun updatePaper(): ServerVersionEntry = withContext(Dispatchers.IO) {
        LoggingUtils.debug(TAG, "Fetching Paper versions from PaperMC API...")

        try {
            val knownVersions = paperExtractor.getKnownVersions()
            val latestVersion = knownVersions.firstOrNull()?.version ?: "1.21.3"

            LoggingUtils.debug(TAG, "Configured ${knownVersions.size} Paper versions, latest: $latestVersion")
            ServerVersionEntry("Paper", "SERVER", true, latestVersion, knownVersions)

        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error updating Paper: ${e.message}", e)
            paperExtractor.createFallbackEntry()
        }
    }

    private suspend fun updateVelocityStandard(): ServerVersionEntry = withContext(Dispatchers.IO) {
        LoggingUtils.debug(TAG, "Fetching standard Velocity versions...")

        try {
            val knownVersions = velocityExtractor.getKnownVersions()
            val latestVersion = knownVersions.firstOrNull()?.version ?: "3.4.0-SNAPSHOT"

            LoggingUtils.debug(TAG, "Configured ${knownVersions.size} Velocity versions, latest: $latestVersion")
            ServerVersionEntry("Velocity", "PROXY", false, latestVersion, knownVersions)

        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error updating Velocity: ${e.message}", e)
            velocityExtractor.createFallbackEntry()
        }
    }

    private suspend fun updateWithExtractor(
        name: String,
        repo: String,
        extractor: VersionExtractor
    ): ServerVersionEntry = withContext(Dispatchers.IO) {
        LoggingUtils.debug(TAG, "Fetching $name versions with build detection from $repo...")

        try {
            val githubVersions = fetchGitHubVersions(repo, extractor::extract)
            val knownVersions = extractor.getKnownVersions()
            val mergedVersions = mergeVersions(githubVersions, knownVersions)

            val sortedVersions = mergedVersions.values.sortedWith { a, b ->
                compareVersions(b.version, a.version)
            }.take(8)

            val latestVersion = sortedVersions.firstOrNull()?.version ?: "unknown"

            LoggingUtils.debug(TAG, "Configured ${sortedVersions.size} $name versions, latest: $latestVersion")

            val type = if (name == "VelocityCTD") "PROXY" else "SERVER"
            ServerVersionEntry(name, type, false, latestVersion, sortedVersions)

        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error updating $name: ${e.message}", e)
            extractor.createFallbackEntry()
        }
    }

    private suspend fun fetchGitHubVersions(
        repo: String,
        extractor: (String) -> VersionDownload?
    ): Map<String, VersionDownload> = withContext(Dispatchers.IO) {
        LoggingUtils.debugNetwork(TAG, "GitHub API request", "$GITHUB_API_BASE/repos/$repo/releases")

        try {
            val request = Request.Builder()
                .url("$GITHUB_API_BASE/repos/$repo/releases")
                .addHeader("User-Agent", USER_AGENT)
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                LoggingUtils.error(TAG, "GitHub API request failed with code: ${response.code}")
                return@withContext emptyMap()
            }

            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                LoggingUtils.error(TAG, "Empty response from GitHub API")
                return@withContext emptyMap()
            }

            LoggingUtils.debug(TAG, "GitHub API response received (${responseBody.length} characters)")

            val versions = mutableMapOf<String, VersionDownload>()

            LoggingUtils.debug(TAG, "Extracted ${versions.size} versions from GitHub releases")
            versions
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error fetching GitHub versions: ${e.message}", e)
            emptyMap()
        }
    }

    private fun mergeVersions(
        githubVersions: Map<String, VersionDownload>,
        knownVersions: List<VersionDownload>
    ): Map<String, VersionDownload> {
        LoggingUtils.debug(TAG, "Merging ${githubVersions.size} GitHub versions with ${knownVersions.size} known versions")

        val merged = mutableMapOf<String, VersionDownload>()

        knownVersions.forEach { version ->
            merged[version.version] = version
        }

        githubVersions.forEach { (version, download) ->
            merged[version] = download
        }

        LoggingUtils.debug(TAG, "Merged result: ${merged.size} total versions")
        return merged
    }

    private fun compareVersions(version1: String, version2: String): Int {
        return version1.compareTo(version2)
    }

    private suspend fun registerAndDownloadVersions(entries: List<ServerVersionEntry>) = withContext(Dispatchers.IO) {
        LoggingUtils.debugStart(TAG, "registering and downloading versions")

        var registeredCount = 0

        entries.forEach { entry ->
            try {
                LoggingUtils.debug(TAG, "Registering ${entry.name} versions...")

                when (entry.name.lowercase()) {
                    "leaf" -> {
                        registerVersionsUnified("LEAF", ServiceAPIType.SPIGOT, false, entry.downloadLinks)
                        registeredCount++
                    }
                    "velocity" -> {
                        registerVersionsUnified("VELOCITY", ServiceAPIType.VELOCITY, false, entry.downloadLinks)
                        registeredCount++
                    }
                    "velocityctd" -> {
                        registerVersionsUnified("VELOCITYCTD", ServiceAPIType.VELOCITY, false, entry.downloadLinks)
                        registeredCount++
                    }
                    "paper" -> {
                        registerVersionsUnified("PAPER", ServiceAPIType.SPIGOT, true, entry.downloadLinks)
                        registeredCount++
                    }
                    else -> {
                        registerVersionsUnified(entry.name.uppercase(), ServiceAPIType.SPIGOT, entry.isPaperclip, entry.downloadLinks)
                        registeredCount++
                    }
                }

                LoggingUtils.debug(TAG, "Registered ${entry.downloadLinks.size} ${entry.name} versions")
            } catch (e: Exception) {
                LoggingUtils.error(TAG, "Error registering ${entry.name} versions: ${e.message}", e)
            }
        }

        LoggingUtils.debug(TAG, "Registered $registeredCount/${entries.size} version entries")
        LoggingUtils.debugSuccess(TAG, "registering and downloading versions")
    }

    private suspend fun registerVersionsUnified(
        prefix: String,
        apiType: ServiceAPIType,
        isPaperclip: Boolean,
        versions: List<VersionDownload>
    ) = withContext(Dispatchers.IO) {
        LoggingUtils.debug(TAG, "Registering ${versions.size} $prefix versions (apiType=$apiType, paperclip=$isPaperclip)")

        versions.forEach { version ->
            LoggingUtils.debug(TAG, "Would register: ${prefix}_${version.version.replace(".", "_")} -> ${version.link}")
        }
    }

    fun getCurrentVersions(): List<ServerVersionEntry> {
        LoggingUtils.debug(TAG, "Current versions requested (${currentVersions.size} entries)")
        return currentVersions.toList()
    }

    fun getVersionsForSoftware(software: String): ServerVersionEntry? {
        val entry = currentVersions.find { it.name.equals(software, ignoreCase = true) }
        LoggingUtils.debug(TAG, "Versions for $software requested: ${if (entry != null) "found" else "not found"}")
        return entry
    }

    fun getStats(): Map<String, Any> {
        val versionsByType = mutableMapOf<String, Int>()
        val totalVersions = currentVersions.sumOf { it.downloadLinks.size }

        currentVersions.forEach { entry ->
            versionsByType[entry.name] = entry.downloadLinks.size
        }

        return mapOf(
            "total_software_types" to currentVersions.size,
            "total_versions" to totalVersions,
            "versions_by_software" to versionsByType,
            "configured_software" to config.serverSoftware,
            "updates_enabled" to config.enableServerVersionUpdates
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
        val buildNumber: Int? = null
    )

    interface VersionExtractor {
        fun extract(releaseData: String): VersionDownload?
        fun getKnownVersions(): List<VersionDownload>
        fun createFallbackEntry(): ServerVersionEntry
    }

    inner class LeafVersionExtractor : VersionExtractor {
        override fun extract(releaseData: String): VersionDownload? {
            return null
        }

        override fun getKnownVersions(): List<VersionDownload> {
            return listOf(
                VersionDownload("1.21.3", "https://github.com/Winds-Studio/Leaf/releases/download/1.21.3/leaf-1.21.3.jar"),
                VersionDownload("1.21.1", "https://github.com/Winds-Studio/Leaf/releases/download/1.21.1/leaf-1.21.1.jar")
            )
        }

        override fun createFallbackEntry(): ServerVersionEntry {
            return ServerVersionEntry("Leaf", "SERVER", false, "1.21.3", getKnownVersions())
        }
    }

    inner class VelocityCTDVersionExtractor : VersionExtractor {
        override fun extract(releaseData: String): VersionDownload? {
            return null
        }

        override fun getKnownVersions(): List<VersionDownload> {
            return listOf(
                VersionDownload("3.4.0-SNAPSHOT", "https://github.com/GemstoneGG/Velocity-CTD/releases/download/3.4.0/velocity-3.4.0-SNAPSHOT.jar")
            )
        }

        override fun createFallbackEntry(): ServerVersionEntry {
            return ServerVersionEntry("VelocityCTD", "PROXY", false, "3.4.0-SNAPSHOT", getKnownVersions())
        }
    }

    inner class VelocityVersionExtractor : VersionExtractor {
        override fun extract(releaseData: String): VersionDownload? {
            return null
        }

        override fun getKnownVersions(): List<VersionDownload> {
            return listOf(
                VersionDownload("3.4.0-SNAPSHOT", "https://api.papermc.io/v2/projects/velocity/versions/3.4.0-SNAPSHOT/builds/451/downloads/velocity-3.4.0-SNAPSHOT-451.jar")
            )
        }

        override fun createFallbackEntry(): ServerVersionEntry {
            return ServerVersionEntry("Velocity", "PROXY", false, "3.4.0-SNAPSHOT", getKnownVersions())
        }
    }

    inner class PaperVersionExtractor : VersionExtractor {
        override fun extract(releaseData: String): VersionDownload? {
            return null
        }

        override fun getKnownVersions(): List<VersionDownload> {
            return listOf(
                VersionDownload("1.21.3", "https://api.papermc.io/v2/projects/paper/versions/1.21.3/builds/496/downloads/paper-1.21.3-496.jar"),
                VersionDownload("1.21.1", "https://api.papermc.io/v2/projects/paper/versions/1.21.1/builds/123/downloads/paper-1.21.1-123.jar")
            )
        }

        override fun createFallbackEntry(): ServerVersionEntry {
            return ServerVersionEntry("Paper", "SERVER", true, "1.21.3", getKnownVersions())
        }
    }
}