package eu.thesimplecloud.module.updater.manager

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import eu.thesimplecloud.api.CloudAPI
import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.api.service.version.ServiceVersion
import eu.thesimplecloud.api.service.version.loader.LocalServiceVersionHandler
import eu.thesimplecloud.api.service.version.type.ServiceAPIType
import eu.thesimplecloud.base.manager.serviceversion.ManagerServiceVersionHandler
import eu.thesimplecloud.jsonlib.JsonLib
import eu.thesimplecloud.module.updater.config.AutoManagerConfig
import kotlinx.coroutines.*
import java.io.File
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class ServerVersionManager(
    private val config: AutoManagerConfig
) {

    data class ServerVersionEntry(
        val name: String,
        val type: String,
        val isPaperclip: Boolean,
        val latestVersion: String,
        val downloadLinks: List<VersionDownload>
    )

    data class VersionDownload(
        val version: String,
        val link: String
    )

    private val serverVersionsFile = File(DirectoryPaths.paths.storagePath + "onlineServiceVersions.json")
    private val registeredVersions = ConcurrentHashMap<String, ServiceVersion>()

    private val versionsCache = ConcurrentHashMap<String, ServerVersionEntry>()
    private val supervisorJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + supervisorJob)

    suspend fun updateAllVersions(): Boolean = withContext(Dispatchers.IO) {
        return@withContext supervisorJob.runCatching {
            logger("Starting version update...")

            logger("=== Starting version update process ===")
            logger("External sources: ${config.externalSources}")
            logger("Server software: ${config.serverSoftware}")
            logger("Enable server version updates: ${config.enableServerVersionUpdates}")


            val updatedEntries = updateConfiguredSoftware()
            addVelocityCTDEntry(updatedEntries)

            // Download and merge external server versions
            val externalEntries = downloadExternalServerVersions()
            val allEntries = mergeAllServerVersions(updatedEntries, externalEntries)

            // Register the versions in SimpleCloud system
            registerAllVersions(allEntries)

            saveServerVersions(allEntries)
            updateCache(allEntries)

            logger("Version update completed. Updated ${allEntries.size} entries (${updatedEntries.size} local + ${externalEntries.size} external)")
            true
        }.getOrElse { error ->
            logger("Fatal error during version update: ${error.message}")
            error.printStackTrace()
            false
        }
    }

    private suspend fun updateConfiguredSoftware(): MutableList<ServerVersionEntry> {
        val results = config.serverSoftware.map { software ->
            coroutineScope.async { updateSoftwareAsync(software) }
        }.awaitAll()

        return results.filterNotNull().toMutableList()
    }

    private suspend fun updateSoftwareAsync(software: String): ServerVersionEntry? {
        return try {
            logger("Updating $software...")
            val entry = when (software.lowercase()) {
                "paper" -> updatePaper()
                "leaf" -> updateLeaf()
                else -> {
                    logger("Unknown software: $software")
                    null
                }
            }

            entry?.let {
                logger("Updated $software to version ${it.latestVersion}")
            }
            entry
        } catch (e: Exception) {
            logger("Error updating $software: ${e.message}")
            null
        }
    }

    private suspend fun updatePaper(): ServerVersionEntry = withContext(Dispatchers.IO) {
        try {
            val versions = fetchPaperVersions()
            val latestVersion = versions.lastOrNull() ?: "1.21.4"
            val downloadLinks = createPaperDownloadLinks(versions)

            ServerVersionEntry("Paper", "SERVER", true, latestVersion, downloadLinks)
        } catch (e: Exception) {
            logger("Error updating Paper: ${e.message}")
            createFallbackPaperEntry()
        }
    }


    private suspend fun updateLeaf(): ServerVersionEntry = withContext(Dispatchers.IO) {
        try {
            logger("Fetching Leaf versions with automatic build detection...")

            // Try to get latest versions from GitHub API first
            val githubVersions = fetchGitHubLeafVersions()
            val knownVersions = getKnownLeafVersions()

            // Merge GitHub versions with known working versions
            val allVersions = mergeLeafVersions(githubVersions, knownVersions)
            val finalVersions = allVersions.values.sortedWith { a, b ->
                compareVersions(b.version, a.version)
            }.take(10)

            if (finalVersions.isEmpty()) {
                return@withContext createFallbackLeafEntry()
            }

            val latestVersion = finalVersions.firstOrNull()?.version ?: "1.21.7"

            logger("Successfully configured ${finalVersions.size} Leaf versions")
            logger("Latest version: $latestVersion")

            ServerVersionEntry("Leaf", "SERVER", false, latestVersion, finalVersions)

        } catch (e: Exception) {
            logger("Error configuring Leaf versions: ${e.message}")
            createFallbackLeafEntry()
        }
    }

    private fun getKnownLeafVersions(): List<VersionDownload> = listOf(
        // Fallback URLs that are known to work
        VersionDownload("1.21.6", "https://s3.mcjars.app/leaf/1.21.6/19/server.jar?minecraft_version=1.21.6&build=19"),
        VersionDownload("1.21.5", "https://s3.mcjars.app/leaf/1.21.5/62/server.jar?minecraft_version=1.21.5&build=62"),
        VersionDownload("1.21.4", "https://s3.mcjars.app/leaf/1.21.4/502/server.jar?minecraft_version=1.21.4&build=502"),
        VersionDownload("1.21.3", "https://s3.mcjars.app/leaf/1.21.3/1.21.3-e380e4b5/server.jar?minecraft_version=1.21.3&build=1"),
        VersionDownload("1.21.1", "https://s3.mcjars.app/leaf/1.21.1/1.21.1-24093972/server.jar?minecraft_version=1.21.1&build=1"),
        VersionDownload("1.20.4", "https://s3.mcjars.app/leaf/1.20.4/1.20.4-6d332992/server.jar?minecraft_version=1.20.4&build=1")
    )

    private fun mergeLeafVersions(
        githubVersions: List<VersionDownload>,
        knownVersions: List<VersionDownload>
    ): Map<String, VersionDownload> {
        val allVersions = mutableMapOf<String, VersionDownload>()

        // Start with known versions as base
        knownVersions.forEach { version ->
            allVersions[version.version] = version
            logger("Added known version: ${version.version}")
        }

        // Override with GitHub versions if they exist (they are more up-to-date)
        githubVersions.filter { isModernLeafVersion(it.version) }.forEach { githubVersion ->
            allVersions[githubVersion.version] = githubVersion
            logger("Updated with GitHub version: ${githubVersion.version}")
        }

        logger("Total merged versions: ${allVersions.size} (${githubVersions.size} from GitHub, ${knownVersions.size} known)")

        return allVersions
    }

    private suspend fun fetchGitHubLeafVersions(): List<VersionDownload> = withContext(Dispatchers.IO) {
        try {
            val response = URL("https://api.github.com/repos/Winds-Studio/Leaf/releases?per_page=15").readText()
            val jsonLib = JsonLib.fromJsonString(response)
            val releases = jsonLib.jsonElement as? JsonArray ?: return@withContext emptyList()

            logger("Found ${releases.size()} Leaf releases on GitHub")
            return@withContext releases.mapNotNull { releaseElement ->
                extractLeafVersionFromGitHubRelease(releaseElement as? JsonObject)
            }
        } catch (e: Exception) {
            logger("GitHub API request failed: ${e.message}")
            emptyList()
        }
    }

    private fun extractLeafVersionFromGitHubRelease(release: JsonObject?): VersionDownload? {
        if (release == null) return null

        val tagName = release.get("tag_name")?.asString ?: return null
        val isPrerelease = release.get("prerelease")?.asBoolean ?: false
        val isDraft = release.get("draft")?.asBoolean ?: false

        if (isDraft || isPrerelease) {
            logger("Skipping prerelease/draft: $tagName")
            return null
        }

        val version = tagName.removePrefix("ver-").removePrefix("v")
        if (!isModernLeafVersion(version)) {
            logger("Skipping non-modern version: $version")
            return null
        }

        val assets = release.getAsJsonArray("assets")
        if (assets != null && assets.size() > 0) {
            val jarAssets = mutableListOf<Pair<String, String>>()

            for (i in 0 until assets.size()) {
                val asset = assets[i] as? JsonObject
                val assetName = asset?.get("name")?.asString
                val downloadUrl = asset?.get("browser_download_url")?.asString

                if (assetName != null && downloadUrl != null && assetName.endsWith(".jar")) {
                    jarAssets.add(Pair(assetName, downloadUrl))
                }
            }

            // Prioritize jar files with build numbers
            val priorityJar = jarAssets.firstOrNull { it.first.matches(Regex("leaf-$version-\\d+\\.jar")) }
                ?: jarAssets.firstOrNull { it.first == "leaf-$version.jar" }
                ?: jarAssets.firstOrNull()

            if (priorityJar != null) {
                logger("Found GitHub release for $version: ${priorityJar.first}")
                return VersionDownload(version, priorityJar.second)
            }
        }

        logger("No suitable jar asset found for GitHub release: $version")
        return null
    }


    private suspend fun fetchPaperVersions(): List<String> = withContext(Dispatchers.IO) {
        val response = URL("https://api.papermc.io/v2/projects/paper").readText()
        val data = JsonLib.fromJsonString(response)
        return@withContext data.getAsJsonArray("versions")?.map { it.asString } ?: emptyList()
    }

    private suspend fun createPaperDownloadLinks(versions: List<String>): List<VersionDownload> {
        return versions.takeLast(10).reversed().map { version ->
            coroutineScope.async { createPaperVersionDownload(version) }
        }.awaitAll()
    }

    private suspend fun createPaperVersionDownload(version: String): VersionDownload = withContext(Dispatchers.IO) {
        val buildsResponse = URL("https://api.papermc.io/v2/projects/paper/versions/$version").readText()
        val buildsData = JsonLib.fromJsonString(buildsResponse)
        val builds = buildsData.getAsJsonArray("builds")!!
        val latestBuild = builds.last().asString

        VersionDownload(
            version = version,
            link = "https://api.papermc.io/v2/projects/paper/versions/$version/builds/$latestBuild/downloads/paper-$version-$latestBuild.jar"
        )
    }

    private fun isModernLeafVersion(version: String): Boolean {
        return when {
            version.startsWith("1.21") -> true
            version.startsWith("1.20") -> true
            version.matches(Regex("\\d{6}")) -> false
            version.contains("purpur", ignoreCase = true) -> false
            version.contains("Mirai", ignoreCase = true) -> false
            version.contains("Prismarine", ignoreCase = true) -> false
            else -> extractMinecraftVersion(version)?.let {
                it.startsWith("1.21") || it.startsWith("1.20")
            } ?: false
        }
    }

    private fun extractMinecraftVersion(version: String): String? {
        return try {
            val versionRegex = Regex("(1\\.(2[01])(\\.\\d+)?)")
            versionRegex.find(version)?.value
        } catch (e: Exception) {
            null
        }
    }

    internal suspend fun downloadServiceVersionJar(serviceVersion: ServiceVersion) = withContext(Dispatchers.IO) {
        try {
            val jarFile = File(DirectoryPaths.paths.minecraftJarsPath + serviceVersion.name + ".jar")

            if (jarFile.exists()) {
                logger("JAR already exists for ${serviceVersion.name}")
                return@withContext
            }

            logger("Downloading JAR for ${serviceVersion.name} from: ${serviceVersion.downloadURL}")
            jarFile.parentFile.mkdirs()

            val connection = URL(serviceVersion.downloadURL).openConnection()
            connection.setRequestProperty("User-Agent", "SimpleCloud-AutoUpdater/1.0")
            connection.setRequestProperty("Accept", "application/java-archive,application/octet-stream,*/*")
            connection.connectTimeout = 30000
            connection.readTimeout = 60000

            // Check response code
            val responseCode = if (connection is java.net.HttpURLConnection) {
                connection.responseCode
            } else {
                200
            }

            if (responseCode == 404) {
                logger("File not found (404) for ${serviceVersion.name}: ${serviceVersion.downloadURL}")
                return@withContext
            }

            if (responseCode !in 200..299) {
                logger("Bad response code $responseCode for ${serviceVersion.name}: ${serviceVersion.downloadURL}")
                return@withContext
            }

            connection.getInputStream().use { input ->
                jarFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (jarFile.exists() && jarFile.length() > 1000000) {
                logger("Successfully downloaded ${serviceVersion.name} (${jarFile.length()} bytes)")
            } else {
                logger("Download completed but file seems too small: ${jarFile.length()} bytes")
                jarFile.delete()
            }

        } catch (e: Exception) {
            logger("Failed to download JAR for ${serviceVersion.name}: ${e.message}")
            e.printStackTrace()
        }
    }



    private suspend fun downloadExternalServerVersions(): List<ServerVersionEntry> = withContext(Dispatchers.IO) {
        val externalEntries = mutableListOf<ServerVersionEntry>()

        config.externalSources.forEach { sourceUrl ->
            try {
                logger("Downloading external server versions from: $sourceUrl")
                val response = URL(sourceUrl).readText()
                val jsonLib = JsonLib.fromJsonString(response)
                val servers = jsonLib.getAsJsonArray("servers")

                if (servers != null) {
                    for (i in 0 until servers.size()) {
                        val serverElement = servers[i] as? JsonObject ?: continue
                        val entry = parseServerVersionEntry(serverElement)
                        entry?.let {
                            externalEntries.add(it)
                            logger("Added external server version: ${it.name} (${it.downloadLinks.size} versions)")
                        }
                    }
                }
            } catch (e: Exception) {
                logger("Failed to download external server versions from $sourceUrl: ${e.message}")
            }
        }

        logger("Downloaded ${externalEntries.size} external server version entries")
        return@withContext externalEntries
    }

    private fun mergeAllServerVersions(
        localEntries: List<ServerVersionEntry>,
        externalEntries: List<ServerVersionEntry>
    ): List<ServerVersionEntry> {
        val allEntries = mutableListOf<ServerVersionEntry>()
        val processedNames = mutableSetOf<String>()

        // Add local entries first (they take priority)
        localEntries.forEach { entry ->
            allEntries.add(entry)
            processedNames.add(entry.name.lowercase())
            logger("Added local server version: ${entry.name}")
        }

        // Add external entries if they don't conflict with local ones
        externalEntries.forEach { entry ->
            if (!processedNames.contains(entry.name.lowercase())) {
                allEntries.add(entry)
                processedNames.add(entry.name.lowercase())
                logger("Added external server version: ${entry.name}")
            } else {
                logger("Skipped external server version ${entry.name} (local version takes priority)")
            }
        }

        logger("Merged server versions: ${allEntries.size} total (${localEntries.size} local + ${externalEntries.size} external)")
        return allEntries
    }

    private suspend fun registerAllVersions(entries: List<ServerVersionEntry>) = withContext(Dispatchers.IO) {
        entries.forEach { entry ->
            when (entry.name.lowercase()) {
                "leaf" -> {
                    logger("Registering ${entry.downloadLinks.size} Leaf versions...")
                    registerLeafVersions(entry.downloadLinks)
                }

                "velocityctd" -> {
                    logger("Registering VelocityCTD versions...")
                    registerVelocityCTDVersions(entry.downloadLinks)
                }

                "paper" -> {
                    logger("Registering ${entry.downloadLinks.size} Paper versions...")
                    registerGenericVersions("PAPER", entry.type, entry.isPaperclip, entry.downloadLinks)
                }

                "purpur" -> {
                    logger("Registering ${entry.downloadLinks.size} Purpur versions...")
                    registerGenericVersions("PURPUR", entry.type, entry.isPaperclip, entry.downloadLinks)
                }

                "vanilla" -> {
                    logger("Registering ${entry.downloadLinks.size} Vanilla versions...")
                    registerGenericVersions("VANILLA", entry.type, entry.isPaperclip, entry.downloadLinks)
                }

                "waterfall" -> {
                    logger("Registering ${entry.downloadLinks.size} Waterfall versions...")
                    registerGenericVersions("WATERFALL", entry.type, entry.isPaperclip, entry.downloadLinks)
                }

                "velocity" -> {
                    logger("Registering ${entry.downloadLinks.size} Velocity versions...")
                    registerGenericVersions("VELOCITY", entry.type, entry.isPaperclip, entry.downloadLinks)
                }

                "spigot" -> {
                    logger("Registering ${entry.downloadLinks.size} Spigot versions...")
                    registerGenericVersions("SPIGOT", entry.type, entry.isPaperclip, entry.downloadLinks)
                }

                "fabric" -> {
                    logger("Registering ${entry.downloadLinks.size} Fabric versions...")
                    registerGenericVersions("FABRIC", entry.type, entry.isPaperclip, entry.downloadLinks)
                }

                "bungeecord" -> {
                    logger("Registering ${entry.downloadLinks.size} BungeeCord versions...")
                    registerGenericVersions("BUNGEECORD", entry.type, entry.isPaperclip, entry.downloadLinks)
                }

                else -> {
                    logger("Registering ${entry.downloadLinks.size} ${entry.name} versions (generic)...")
                    registerGenericVersions(entry.name.uppercase(), entry.type, entry.isPaperclip, entry.downloadLinks)
                }
            }
        }
    }

    private suspend fun registerGenericVersions(
        softwareName: String,
        type: String,
        isPaperclip: Boolean,
        versions: List<VersionDownload>
    ) = withContext(Dispatchers.IO) {
        logger("Registering ${versions.size} $softwareName versions...")

        val serviceVersionHandler = CloudAPI.instance.getServiceVersionHandler()
        val localServiceVersionHandler = LocalServiceVersionHandler()
        var registeredCount = 0

        val serviceAPIType = when (type.uppercase()) {
            "SERVER" -> ServiceAPIType.SPIGOT
            "PROXY" -> {
                when (softwareName.uppercase()) {
                    "VELOCITY" -> ServiceAPIType.VELOCITY
                    else -> ServiceAPIType.BUNGEECORD
                }
            }

            else -> ServiceAPIType.SPIGOT
        }

        versions.forEach { version ->
            val versionName = sanitizeVersionName("${softwareName.uppercase()}_${version.version}")

            try {
                val existingVersion = serviceVersionHandler.getServiceVersionByName(versionName)

                if (existingVersion == null) {
                    val serviceVersion = ServiceVersion(
                        name = versionName,
                        serviceAPIType = serviceAPIType,
                        downloadURL = version.link,
                        isPaperClip = isPaperclip
                    )

                    localServiceVersionHandler.saveServiceVersion(serviceVersion)
                    registeredVersions[versionName] = serviceVersion

                    // DOWNLOAD JAR IMMEDIATELY (only for non-paperclip)
                    if (!isPaperclip) {
                        downloadServiceVersionJar(serviceVersion)
                    }

                    registeredCount++
                    logger("Registered $softwareName version: $versionName")
                } else {
                    logger("$softwareName version already exists: $versionName")
                    // Still try to download if JAR is missing and not paperclip
                    if (!isPaperclip) {
                        val jarFile = File(DirectoryPaths.paths.minecraftJarsPath + existingVersion.name + ".jar")
                        if (!jarFile.exists()) {
                            downloadServiceVersionJar(existingVersion)
                        }
                    }
                }
            } catch (e: Exception) {
                logger("Failed to register $softwareName version $versionName: ${e.message}")
            }
        }

        if (registeredCount > 0) {
            val managerServiceVersionHandler = serviceVersionHandler as ManagerServiceVersionHandler
            managerServiceVersionHandler.reloadServiceVersions()
        }

        logger("$softwareName registration complete. Registered: $registeredCount versions")
    }

    private suspend fun registerLeafVersions(versions: List<VersionDownload>) = withContext(Dispatchers.IO) {
        logger("Registering ${versions.size} Leaf versions...")

        val serviceVersionHandler = CloudAPI.instance.getServiceVersionHandler()
        val localServiceVersionHandler = LocalServiceVersionHandler()
        var registeredCount = 0

        versions.forEach { version ->
            val versionName = sanitizeVersionName("LEAF_${version.version}")

            try {
                val existingVersion = serviceVersionHandler.getServiceVersionByName(versionName)

                if (existingVersion == null) {
                    // Create new version
                    val serviceVersion = ServiceVersion(
                        name = versionName,
                        serviceAPIType = ServiceAPIType.SPIGOT,
                        downloadURL = version.link,
                        isPaperClip = false
                    )

                    localServiceVersionHandler.saveServiceVersion(serviceVersion)
                    registeredVersions[versionName] = serviceVersion

                    downloadServiceVersionJar(serviceVersion)
                    registeredCount++
                    logger("Registered new Leaf version: $versionName -> ${version.link}")

                } else {
                    // Check if existing version has different URL (auto-update builds)
                    if (existingVersion.downloadURL != version.link) {
                        logger("Updating build URL for existing version: $versionName")
                        logger("Old URL: ${existingVersion.downloadURL}")
                        logger("New URL: ${version.link}")

                        // Create updated version with new URL
                        val updatedVersion = ServiceVersion(
                            name = versionName,
                            serviceAPIType = ServiceAPIType.SPIGOT,
                            downloadURL = version.link,
                            isPaperClip = false
                        )

                        // Save the updated version
                        localServiceVersionHandler.saveServiceVersion(updatedVersion)
                        registeredVersions[versionName] = updatedVersion

                        // Remove old JAR and download new one
                        val jarFile = File(DirectoryPaths.paths.minecraftJarsPath + versionName + ".jar")
                        if (jarFile.exists()) {
                            jarFile.delete()
                            logger("Removed old JAR for update")
                        }

                        downloadServiceVersionJar(updatedVersion)
                        logger("Updated Leaf version: $versionName -> ${version.link}")
                        registeredCount++
                    } else {
                        logger("Leaf version already exists with current URL: $versionName")
                    }

                    // Always check if JAR file exists
                    val jarFile = File(DirectoryPaths.paths.minecraftJarsPath + versionName + ".jar")
                    if (!jarFile.exists()) {
                        val versionToDownload = ServiceVersion(
                            name = versionName,
                            serviceAPIType = ServiceAPIType.SPIGOT,
                            downloadURL = version.link,
                            isPaperClip = false
                        )
                        downloadServiceVersionJar(versionToDownload)
                    }
                }
            } catch (e: Exception) {
                logger("Failed to register Leaf version $versionName: ${e.message}")
                e.printStackTrace()
            }
        }

        if (registeredCount > 0) {
            val managerServiceVersionHandler = serviceVersionHandler as ManagerServiceVersionHandler
            managerServiceVersionHandler.reloadServiceVersions()
        }

        logger("Leaf registration complete. Updated/Registered: $registeredCount versions")
    }

    private suspend fun registerVelocityCTDVersions(versions: List<VersionDownload>) = withContext(Dispatchers.IO) {
        logger("Registering ${versions.size} VelocityCTD versions...")

        val serviceVersionHandler = CloudAPI.instance.getServiceVersionHandler()
        val localServiceVersionHandler = LocalServiceVersionHandler()
        var registeredCount = 0

        versions.forEach { version ->
            val versionName = sanitizeVersionName("VELOCITYCTD_${version.version}")

            try {
                val existingVersion = serviceVersionHandler.getServiceVersionByName(versionName)

                if (existingVersion == null) {
                    val serviceVersion = ServiceVersion(
                        name = versionName,
                        serviceAPIType = ServiceAPIType.VELOCITY,
                        downloadURL = version.link,
                        isPaperClip = false
                    )

                    localServiceVersionHandler.saveServiceVersion(serviceVersion)
                    registeredVersions[versionName] = serviceVersion

                    // INLINE DOWNLOAD LOGIC
                    try {
                        val jarFile = File(DirectoryPaths.paths.minecraftJarsPath + serviceVersion.name + ".jar")

                        if (!jarFile.exists()) {
                            logger("Downloading JAR for ${serviceVersion.name} from: ${serviceVersion.downloadURL}")

                            jarFile.parentFile.mkdirs()

                            val connection = URL(serviceVersion.downloadURL).openConnection()
                            connection.setRequestProperty("User-Agent", "SimpleCloud-AutoUpdater/1.0")
                            connection.connectTimeout = 30000
                            connection.readTimeout = 60000

                            connection.getInputStream().use { input ->
                                jarFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }

                            logger("Successfully downloaded ${serviceVersion.name} (${jarFile.length()} bytes)")
                        }
                    } catch (e: Exception) {
                        logger("Failed to download JAR for ${serviceVersion.name}: ${e.message}")
                    }

                    registeredCount++
                    logger("Registered VelocityCTD version: $versionName -> ${version.link}")
                } else {
                    logger("VelocityCTD version already exists: $versionName")
                }
            } catch (e: Exception) {
                logger("Failed to register VelocityCTD version $versionName: ${e.message}")
                e.printStackTrace()
            }
        }

        if (registeredCount > 0) {
            val managerServiceVersionHandler = serviceVersionHandler as ManagerServiceVersionHandler
            managerServiceVersionHandler.reloadServiceVersions()
        }

        logger("VelocityCTD registration complete. Registered: $registeredCount versions")
    }

    private fun compareVersions(version1: String, version2: String): Int {
        return try {
            logger("Comparing versions: $version1 vs $version2")

            // Split versions into parts
            val v1Parts = version1.split(".").map { part ->
                // Handle numbers and non-numbers
                part.toIntOrNull() ?: 0
            }
            val v2Parts = version2.split(".").map { part ->
                part.toIntOrNull() ?: 0
            }

            val maxLength = maxOf(v1Parts.size, v2Parts.size)

            for (i in 0 until maxLength) {
                val v1Part = v1Parts.getOrNull(i) ?: 0
                val v2Part = v2Parts.getOrNull(i) ?: 0
                val comparison = v1Part.compareTo(v2Part)

                if (comparison != 0) {
                    logger("  Part $i: $v1Part vs $v2Part = $comparison")
                    return comparison
                }
            }

            logger("  Versions are equal")
            0
        } catch (e: Exception) {
            logger("Error comparing versions $version1 vs $version2: ${e.message}")
            version1.compareTo(version2)
        }
    }

    private fun sanitizeVersionName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9_]"), "_")
            .replace(Regex("_{2,}"), "_")
            .trimEnd('_')
    }

    private fun createFallbackPaperEntry(): ServerVersionEntry {
        return ServerVersionEntry(
            "Paper", "SERVER", true, "1.21.4",
            listOf(VersionDownload("1.21.4", "https://api.papermc.io/v2/projects/paper/versions/1.21.4/builds/550/downloads/paper-1.21.4-550.jar"))
        )
    }

    private fun createFallbackLeafEntry(): ServerVersionEntry {
        return ServerVersionEntry(
            "Leaf", "SERVER", false, "1.21.7",
            listOf(
                VersionDownload("1.21.7", "https://github.com/Winds-Studio/Leaf/releases/download/ver-1.21.7/leaf-1.21.7-10.jar"),
                VersionDownload("1.21.6", "https://s3.mcjars.app/leaf/1.21.6/19/server.jar?minecraft_version=1.21.6&build=19"),
                VersionDownload("1.21.5", "https://s3.mcjars.app/leaf/1.21.5/62/server.jar?minecraft_version=1.21.5&build=62")
            )
        )
    }
    private fun createVelocityCTDEntry(): ServerVersionEntry {
        return ServerVersionEntry(
            name = "VelocityCTD",
            type = "PROXY",
            isPaperclip = false,
            latestVersion = "3.4.0-SNAPSHOT",
            downloadLinks = listOf(
                VersionDownload(
                    version = "3.4.0-SNAPSHOT",
                    link = "https://github.com/GemstoneGG/Velocity-CTD/releases/download/Releases/velocity-proxy-3.4.0-SNAPSHOT-all.jar"
                )
            )
        )
    }

    private fun addVelocityCTDEntry(entries: MutableList<ServerVersionEntry>) {
        entries.add(createVelocityCTDEntry())
    }

    private fun saveServerVersions(entries: List<ServerVersionEntry>) {
        try {
            serverVersionsFile.parentFile.mkdirs()

            if (serverVersionsFile.exists() && config.enableBackup) {
                val backupFile = File(serverVersionsFile.parentFile, "server_versions.json.backup")
                serverVersionsFile.copyTo(backupFile, overwrite = true)
                logger("Created backup: ${backupFile.name}")
            }

            val jsonData = JsonLib.empty()

            // Add external sources from config
            jsonData.append("externalSources", config.externalSources)

            val serversArray = entries.map { entry ->
                JsonLib.empty()
                    .append("name", entry.name)
                    .append("type", entry.type)
                    .append("isPaperclip", entry.isPaperclip)
                    .append("latestVersion", entry.latestVersion)
                    .append("downloadLinks", entry.downloadLinks.map { download ->
                        JsonLib.empty()
                            .append("version", download.version)
                            .append("link", download.link)
                    })
            }

            jsonData.append("servers", serversArray)
            jsonData.saveAsFile(serverVersionsFile)

            logger("Saved ${entries.size} server versions to file with ${config.externalSources.size} external sources")
        } catch (e: Exception) {
            logger("Error saving server versions: ${e.message}")
            throw e
        }
    }

    private fun updateCache(entries: List<ServerVersionEntry>) {
        versionsCache.clear()
        entries.forEach { entry ->
            versionsCache[entry.name.lowercase()] = entry
        }
    }

    fun getCurrentVersions(): List<ServerVersionEntry> {
        return if (versionsCache.isNotEmpty()) {
            versionsCache.values.toList()
        } else {
            loadVersionsFromFile()
        }
    }

    private fun loadVersionsFromFile(): List<ServerVersionEntry> {
        return try {
            if (!serverVersionsFile.exists()) {
                logger("Server versions file not found, returning empty list")
                return emptyList()
            }

            val jsonLib = JsonLib.fromJsonFile(serverVersionsFile)!!
            val servers = jsonLib.getAsJsonArray("servers")!!
            val entries = mutableListOf<ServerVersionEntry>()

            for (i in 0 until servers.size()) {
                val serverElement = servers[i] as? JsonObject ?: continue
                val entry = parseServerVersionEntry(serverElement)
                entry?.let { entries.add(it) }
            }

            logger("Loaded ${entries.size} server versions from file")
            updateCache(entries)
            entries
        } catch (e: Exception) {
            logger("Error loading server versions: ${e.message}")
            emptyList()
        }
    }

    private fun parseServerVersionEntry(serverElement: JsonObject): ServerVersionEntry? {
        return try {
            val name = serverElement.get("name")?.asString ?: return null
            val type = serverElement.get("type")?.asString ?: return null
            val isPaperclip = serverElement.get("isPaperclip")?.asBoolean ?: false
            val latestVersion = serverElement.get("latestVersion")?.asString ?: return null

            val downloadLinksArray = serverElement.getAsJsonArray("downloadLinks")
            val downloadLinks = mutableListOf<VersionDownload>()

            downloadLinksArray?.forEach { linkElement ->
                val linkObj = linkElement as? JsonObject ?: return@forEach
                val version = linkObj.get("version")?.asString ?: return@forEach
                val link = linkObj.get("link")?.asString ?: return@forEach
                downloadLinks.add(VersionDownload(version, link))
            }

            ServerVersionEntry(name, type, isPaperclip, latestVersion, downloadLinks)
        } catch (e: Exception) {
            logger("Error parsing server version entry: ${e.message}")
            null
        }
    }


    private fun getReliableLeafVersions(): List<VersionDownload> = listOf(
        VersionDownload("1.21.7", "https://github.com/Winds-Studio/Leaf/releases/download/ver-1.21.7/leaf-1.21.7-1.jar"),
        VersionDownload("1.21.6", "https://s3.mcjars.app/leaf/1.21.6/19/server.jar?minecraft_version=1.21.6&build=19"),
        VersionDownload("1.21.5", "https://s3.mcjars.app/leaf/1.21.5/62/server.jar?minecraft_version=1.21.5&build=62"),
        VersionDownload("1.21.4", "https://s3.mcjars.app/leaf/1.21.4/502/server.jar?minecraft_version=1.21.4&build=502"),
        VersionDownload("1.21.3", "https://s3.mcjars.app/leaf/1.21.3/1.21.3-e380e4b5/server.jar?minecraft_version=1.21.3&build=1"),
        VersionDownload("1.21.1", "https://s3.mcjars.app/leaf/1.21.1/1.21.1-24093972/server.jar?minecraft_version=1.21.1&build=1"),
        VersionDownload("1.20.4", "https://s3.mcjars.app/leaf/1.20.4/1.20.4-6d332992/server.jar?minecraft_version=1.20.4&build=1")
    )


    private fun logger(message: String) {
        println("[ServerVersionManager] $message")
    }
}