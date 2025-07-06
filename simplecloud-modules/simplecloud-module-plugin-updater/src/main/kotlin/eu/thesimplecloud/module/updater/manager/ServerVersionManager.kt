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
import eu.thesimplecloud.module.updater.bootstrap.PluginUpdaterModule
import eu.thesimplecloud.module.updater.config.AutoManagerConfig
import kotlinx.coroutines.*
import java.io.File
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class ServerVersionManager(
    private val module: PluginUpdaterModule,
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

    companion object {
        private const val GITHUB_API_BASE = "https://api.github.com/repos"
        private const val PAPER_API_BASE = "https://api.papermc.io/v2/projects/paper"
        private const val USER_AGENT = "SimpleCloud-AutoUpdater/1.0"
        private const val CONNECT_TIMEOUT = 30000
        private const val READ_TIMEOUT = 60000
        private const val MIN_JAR_SIZE = 1000000
    }

    private val serverVersionsFile = File(DirectoryPaths.paths.storagePath + "onlineServiceVersions.json")
    private val registeredVersions = ConcurrentHashMap<String, ServiceVersion>()
    private val versionsCache = ConcurrentHashMap<String, ServerVersionEntry>()
    private val supervisorJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + supervisorJob)

    suspend fun updateAllVersions(): Boolean = withContext(Dispatchers.IO) {
        return@withContext supervisorJob.runCatching {
            logger("Starting unified version update...")

            val updatedEntries = updateConfiguredSoftware()
            val externalEntries = downloadExternalServerVersions()
            val allEntries = mergeAllServerVersions(updatedEntries, externalEntries)

            registerAndDownloadVersions(allEntries)
            saveServerVersions(allEntries)
            updateCache(allEntries)

            logger("Version update completed. Updated ${allEntries.size} entries")
            true
        }.getOrElse { error ->
            logger("Error during version update: ${error.message}")
            false
        }
    }

    private suspend fun unifiedDownload(
        url: String,
        targetFile: File,
        description: String = "file"
    ): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            logger("Downloading $description from: $url")
            targetFile.parentFile.mkdirs()

            val connection = createConnection(url)
            val responseCode = getResponseCode(connection)

            if (responseCode !in 200..299) {
                logger("Bad response $responseCode for $description")
                return@withContext false
            }

            connection.getInputStream().use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val success = targetFile.exists() && targetFile.length() > MIN_JAR_SIZE
            if (success) {
                logger("Downloaded $description (${targetFile.length()} bytes)")
            } else {
                logger("Download failed: file too small (${targetFile.length()} bytes)")
                targetFile.delete()
            }

            success
        } catch (e: Exception) {
            logger("Download failed for $description: ${e.message}")
            if (targetFile.exists()) targetFile.delete()
            false
        }
    }

    private fun createConnection(url: String): java.net.URLConnection {
        val connection = URL(url).openConnection()
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        return connection
    }

    private fun getResponseCode(connection: java.net.URLConnection): Int {
        return if (connection is java.net.HttpURLConnection) {
            connection.responseCode
        } else 200
    }

    private suspend fun fetchGitHubVersions(
        repo: String,
        extractor: (JsonObject) -> VersionDownload?
    ): List<VersionDownload> = withContext(Dispatchers.IO) {
        try {
            val response = URL("$GITHUB_API_BASE/$repo/releases?per_page=10").readText()
            val releases = JsonLib.fromJsonString(response).jsonElement as? JsonArray
                ?: return@withContext emptyList()

            logger("Found ${releases.size()} releases for $repo")
            return@withContext releases.mapNotNull {
                extractor(it as? JsonObject ?: return@mapNotNull null)
            }
        } catch (e: Exception) {
            logger("GitHub API failed for $repo: ${e.message}")
            emptyList()
        }
    }

    private interface VersionExtractor {
        fun extract(release: JsonObject): VersionDownload?
        fun getKnownVersions(): List<VersionDownload>
        fun createFallbackEntry(): ServerVersionEntry
    }

    private val leafExtractor = object : VersionExtractor {
        override fun extract(release: JsonObject): VersionDownload? {
            val tagName = release.get("tag_name")?.asString ?: return null
            val isPrerelease = release.get("prerelease")?.asBoolean ?: false
            val isDraft = release.get("draft")?.asBoolean ?: false

            if (isDraft || isPrerelease) return null

            val version = tagName.removePrefix("ver-").removePrefix("v")
            if (!isModernVersion(version)) return null

            val assets = release.getAsJsonArray("assets") ?: return null

            for (i in 0 until assets.size()) {
                val asset = assets[i] as? JsonObject ?: continue
                val name = asset.get("name")?.asString ?: continue
                val url = asset.get("browser_download_url")?.asString ?: continue

                if (name.matches(Regex("leaf-$version-\\d+\\.jar"))) {
                    logger("Found Leaf release: $version -> $name")
                    return VersionDownload(version, url)
                }
            }
            return null
        }

        override fun getKnownVersions(): List<VersionDownload> = listOf(
            VersionDownload(
                "1.21.6",
                "https://s3.mcjars.app/leaf/1.21.6/19/server.jar?minecraft_version=1.21.6&build=19"
            ),
            VersionDownload(
                "1.21.5",
                "https://s3.mcjars.app/leaf/1.21.5/62/server.jar?minecraft_version=1.21.5&build=62"
            ),
            VersionDownload(
                "1.21.4",
                "https://s3.mcjars.app/leaf/1.21.4/502/server.jar?minecraft_version=1.21.4&build=502"
            )
        )

        override fun createFallbackEntry(): ServerVersionEntry = ServerVersionEntry(
            "Leaf", "SERVER", false, "1.21.7",
            listOf(
                VersionDownload(
                    "1.21.7",
                    "https://github.com/Winds-Studio/Leaf/releases/download/ver-1.21.7/leaf-1.21.7-10.jar"
                ),
                VersionDownload(
                    "1.21.6",
                    "https://s3.mcjars.app/leaf/1.21.6/19/server.jar?minecraft_version=1.21.6&build=19"
                )
            )
        )
    }

    private val velocityExtractor = object : VersionExtractor {
        override fun extract(release: JsonObject): VersionDownload? {
            return null
        }

        override fun getKnownVersions(): List<VersionDownload> = listOf(
            VersionDownload("3.4.0-SNAPSHOT", "https://fill-data.papermc.io/v1/objects/72ad2e6712caadcca8cd13bcc9f64c04a9ba28e8ea7f1f82b06420868c2957a2/velocity-3.4.0-SNAPSHOT-515.jar")
        )

        override fun createFallbackEntry(): ServerVersionEntry = ServerVersionEntry(
            "Velocity", "PROXY", false, "3.4.0-SNAPSHOT",
            listOf(
                VersionDownload("3.4.0-SNAPSHOT", "https://fill-data.papermc.io/v1/objects/72ad2e6712caadcca8cd13bcc9f64c04a9ba28e8ea7f1f82b06420868c2957a2/velocity-3.4.0-SNAPSHOT-515.jar")
            )
        )
    }

    private val velocityCTDExtractor = object : VersionExtractor {
        override fun extract(release: JsonObject): VersionDownload? {
            val tagName = release.get("tag_name")?.asString ?: return null
            val isPrerelease = release.get("prerelease")?.asBoolean ?: false
            val isDraft = release.get("draft")?.asBoolean ?: false

            if (isDraft || isPrerelease) return null

            val version = when {
                tagName.startsWith("v") -> tagName.removePrefix("v")
                tagName.equals("Releases", ignoreCase = true) -> "3.4.0-SNAPSHOT"
                tagName.contains("3.4.0") -> "3.4.0-SNAPSHOT"
                else -> tagName
            }

            val assets = release.getAsJsonArray("assets") ?: return null

            for (i in 0 until assets.size()) {
                val asset = assets[i] as? JsonObject ?: continue
                val name = asset.get("name")?.asString ?: continue
                val url = asset.get("browser_download_url")?.asString ?: continue

                if (name.contains("velocity-proxy") && name.contains("all") && name.endsWith(".jar")) {
                    logger("Found VelocityCTD release: $version -> $name")
                    return VersionDownload(version, url)
                }
            }
            return null
        }

        override fun getKnownVersions(): List<VersionDownload> = listOf(
            VersionDownload("3.4.0-SNAPSHOT", "https://github.com/GemstoneGG/Velocity-CTD/releases/download/Releases/velocity-proxy-3.4.0-SNAPSHOT-all.jar")
        )

        override fun createFallbackEntry(): ServerVersionEntry = ServerVersionEntry(
            "VelocityCTD", "PROXY", false, "3.4.0-SNAPSHOT",
            listOf(
                VersionDownload("3.4.0-SNAPSHOT", "https://github.com/GemstoneGG/Velocity-CTD/releases/download/Releases/velocity-proxy-3.4.0-SNAPSHOT-all.jar")
            )
        )
    }

    private suspend fun updateConfiguredSoftware(): MutableList<ServerVersionEntry> {
        val results = config.serverSoftware.map { software ->
            coroutineScope.async { updateSoftwareUnified(software) }
        }.awaitAll()

        return results.filterNotNull().toMutableList()
    }

    private suspend fun updateSoftwareUnified(software: String): ServerVersionEntry? {
        return try {
            logger("Updating $software...")
            when (software.lowercase()) {
                "paper" -> updatePaper()
                "leaf" -> updateWithExtractor("Leaf", "Winds-Studio/Leaf", leafExtractor)
                "velocity" -> updateVelocityStandard()
                "velocityctd" -> updateWithExtractor("VelocityCTD", "GemstoneGG/Velocity-CTD", velocityCTDExtractor)
                else -> null
            }
        } catch (e: Exception) {
            logger("Error updating $software: ${e.message}")
            null
        }
    }

    private suspend fun updateVelocityStandard(): ServerVersionEntry = withContext(Dispatchers.IO) {
        try {
            logger("Fetching standard Velocity versions...")

            val knownVersions = velocityExtractor.getKnownVersions()
            val latestVersion = knownVersions.firstOrNull()?.version ?: "3.4.0-SNAPSHOT"

            logger("Configured ${knownVersions.size} Velocity versions, latest: $latestVersion")
            ServerVersionEntry("Velocity", "PROXY", false, latestVersion, knownVersions)

        } catch (e: Exception) {
            logger("Error updating Velocity: ${e.message}")
            velocityExtractor.createFallbackEntry()
        }
    }

    private suspend fun updateWithExtractor(
        name: String,
        repo: String,
        extractor: VersionExtractor
    ): ServerVersionEntry = withContext(Dispatchers.IO) {
        try {
            logger("Fetching $name versions with build detection...")

            val githubVersions = fetchGitHubVersions(repo, extractor::extract)
            val knownVersions = extractor.getKnownVersions()
            val mergedVersions = mergeVersions(githubVersions, knownVersions)

            val sortedVersions = mergedVersions.values.sortedWith { a, b ->
                compareVersions(b.version, a.version)
            }.take(8)

            val latestVersion = sortedVersions.firstOrNull()?.version ?: "unknown"

            logger("Configured ${sortedVersions.size} $name versions, latest: $latestVersion")

            val type = if (name == "VelocityCTD") "PROXY" else "SERVER"
            ServerVersionEntry(name, type, false, latestVersion, sortedVersions)

        } catch (e: Exception) {
            logger("Error updating $name: ${e.message}")
            extractor.createFallbackEntry()
        }
    }

    private suspend fun registerAndDownloadVersions(entries: List<ServerVersionEntry>) = withContext(Dispatchers.IO) {
        entries.forEach { entry ->
            when (entry.name.lowercase()) {
                "leaf" -> registerVersionsUnified("LEAF", ServiceAPIType.SPIGOT, false, entry.downloadLinks)
                "velocity" -> registerVersionsUnified("VELOCITY", ServiceAPIType.VELOCITY, false, entry.downloadLinks)
                "velocityctd" -> registerVersionsUnified("VELOCITYCTD", ServiceAPIType.VELOCITY, false, entry.downloadLinks)
                "paper" -> registerVersionsUnified("PAPER", ServiceAPIType.SPIGOT, true, entry.downloadLinks)
                else -> registerVersionsUnified(entry.name.uppercase(), ServiceAPIType.SPIGOT, entry.isPaperclip, entry.downloadLinks)
            }
        }
    }

    private suspend fun registerVersionsUnified(
        prefix: String,
        apiType: ServiceAPIType,
        isPaperclip: Boolean,
        versions: List<VersionDownload>
    ) = withContext(Dispatchers.IO) {
        logger("Registering ${versions.size} $prefix versions...")

        val serviceVersionHandler = CloudAPI.instance.getServiceVersionHandler()
        val localServiceVersionHandler = LocalServiceVersionHandler()
        var updatedCount = 0

        versions.forEach { version ->
            val versionName = sanitizeVersionName("${prefix}_${version.version}")

            try {
                val existingVersion = serviceVersionHandler.getServiceVersionByName(versionName)
                val jarFile = File(DirectoryPaths.paths.minecraftJarsPath + versionName + ".jar")

                val needsUpdate = when {
                    existingVersion == null -> {
                        logger("New version: $versionName")
                        true
                    }
                    existingVersion.downloadURL != version.link -> {
                        logger("URL changed for $versionName")
                        logger("Old: ${existingVersion.downloadURL}")
                        logger("New: ${version.link}")
                        if (jarFile.exists()) jarFile.delete()
                        true
                    }
                    !jarFile.exists() -> {
                        logger("JAR missing for $versionName")
                        true
                    }
                    else -> {
                        logger("Skipping $versionName (already up to date)")
                        false
                    }
                }

                if (needsUpdate) {
                    val serviceVersion = ServiceVersion(
                        name = versionName,
                        serviceAPIType = apiType,
                        downloadURL = version.link,
                        isPaperClip = isPaperclip
                    )

                    localServiceVersionHandler.saveServiceVersion(serviceVersion)
                    registeredVersions[versionName] = serviceVersion

                    if (!isPaperclip || prefix == "VELOCITY" || prefix == "VELOCITYCTD") {
                        val downloadSuccess = unifiedDownload(version.link, jarFile, versionName)
                        if (downloadSuccess) {
                            updatedCount++
                            logger("Updated $prefix version: $versionName")
                        } else {
                            logger("Failed to download $prefix version: $versionName")
                        }
                    } else {
                        updatedCount++
                        logger("Registered $prefix version: $versionName (paperclip)")
                    }
                }

            } catch (e: Exception) {
                logger("Failed to register $prefix version $versionName: ${e.message}")
            }
        }

        if (updatedCount > 0) {
            val managerServiceVersionHandler = serviceVersionHandler as ManagerServiceVersionHandler
            managerServiceVersionHandler.reloadServiceVersions()
            logger("$prefix registration complete. Updated: $updatedCount versions")
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

    private suspend fun fetchPaperVersions(): List<String> = withContext(Dispatchers.IO) {
        val response = URL(PAPER_API_BASE).readText()
        val data = JsonLib.fromJsonString(response)
        return@withContext data.getAsJsonArray("versions")?.map { it.asString } ?: emptyList()
    }

    private suspend fun createPaperDownloadLinks(versions: List<String>): List<VersionDownload> {
        return versions.takeLast(10).reversed().map { version ->
            coroutineScope.async { createPaperVersionDownload(version) }
        }.awaitAll()
    }

    private suspend fun createPaperVersionDownload(version: String): VersionDownload = withContext(Dispatchers.IO) {
        val buildsResponse = URL("$PAPER_API_BASE/versions/$version").readText()
        val buildsData = JsonLib.fromJsonString(buildsResponse)
        val builds = buildsData.getAsJsonArray("builds")!!
        val latestBuild = builds.last().asString

        VersionDownload(
            version = version,
            link = "$PAPER_API_BASE/versions/$version/builds/$latestBuild/downloads/paper-$version-$latestBuild.jar"
        )
    }

    private fun createFallbackPaperEntry(): ServerVersionEntry {
        return ServerVersionEntry(
            "Paper", "SERVER", true, "1.21.4",
            listOf(VersionDownload("1.21.4", "https://api.papermc.io/v2/projects/paper/versions/1.21.4/builds/550/downloads/paper-1.21.4-550.jar"))
        )
    }

    private fun mergeVersions(
        githubVersions: List<VersionDownload>,
        knownVersions: List<VersionDownload>
    ): Map<String, VersionDownload> {
        val allVersions = mutableMapOf<String, VersionDownload>()

        knownVersions.forEach { version ->
            allVersions[version.version] = version
        }

        githubVersions.forEach { githubVersion ->
            allVersions[githubVersion.version] = githubVersion
        }

        return allVersions
    }

    private fun isModernVersion(version: String): Boolean {
        return version.startsWith("1.21") || version.startsWith("1.20")
    }

    private fun compareVersions(version1: String, version2: String): Int {
        val v1Parts = version1.split(".").map { it.toIntOrNull() ?: 0 }
        val v2Parts = version2.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLength = maxOf(v1Parts.size, v2Parts.size)

        for (i in 0 until maxLength) {
            val v1Part = v1Parts.getOrNull(i) ?: 0
            val v2Part = v2Parts.getOrNull(i) ?: 0
            val comparison = v1Part.compareTo(v2Part)
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun sanitizeVersionName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9_]"), "_")
            .replace(Regex("_{2,}"), "_")
            .trimEnd('_')
    }

    private suspend fun downloadExternalServerVersions(): List<ServerVersionEntry> = withContext(Dispatchers.IO) {
        val externalEntries = mutableListOf<ServerVersionEntry>()

        config.externalSources.forEach { sourceUrl ->
            try {
                val response = URL(sourceUrl).readText()
                val jsonLib = JsonLib.fromJsonString(response)
                val servers = jsonLib.getAsJsonArray("servers")

                if (servers != null) {
                    for (i in 0 until servers.size()) {
                        val serverElement = servers[i] as? JsonObject ?: continue
                        val entry = parseServerVersionEntry(serverElement)
                        entry?.let { externalEntries.add(it) }
                    }
                }
            } catch (e: Exception) {
                logger("Failed to download external server versions from $sourceUrl: ${e.message}")
            }
        }

        return@withContext externalEntries
    }

    private fun mergeAllServerVersions(
        localEntries: List<ServerVersionEntry>,
        externalEntries: List<ServerVersionEntry>
    ): List<ServerVersionEntry> {
        val allEntries = mutableListOf<ServerVersionEntry>()
        val processedNames = mutableSetOf<String>()

        localEntries.forEach { entry ->
            allEntries.add(entry)
            processedNames.add(entry.name.lowercase())
        }

        externalEntries.forEach { entry ->
            if (!processedNames.contains(entry.name.lowercase())) {
                allEntries.add(entry)
                processedNames.add(entry.name.lowercase())
            }
        }

        return allEntries
    }

    private fun saveServerVersions(entries: List<ServerVersionEntry>) {
        try {
            serverVersionsFile.parentFile.mkdirs()

            val jsonData = JsonLib.empty()
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

        } catch (e: Exception) {
            logger("Error saving server versions: ${e.message}")
        }
    }

    private fun updateCache(entries: List<ServerVersionEntry>) {
        versionsCache.clear()
        entries.forEach { entry ->
            versionsCache[entry.name.lowercase()] = entry
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
            null
        }
    }

    fun getCurrentVersions(): List<ServerVersionEntry> {
        return versionsCache.values.toList()
    }

    fun getVersionByName(name: String): ServerVersionEntry? {
        return versionsCache[name.lowercase()]
    }

    fun cleanup() {
        supervisorJob.cancel()
    }

    private fun logger(message: String) {
        println("[ServerVersionManager] $message")
    }
}