package eu.thesimplecloud.module.updater.manager

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.jsonlib.JsonLib
import eu.thesimplecloud.module.updater.bootstrap.PluginUpdaterModule
import eu.thesimplecloud.module.updater.config.AutoManagerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

class ServerVersionManager(
    private val module: PluginUpdaterModule,
    private val config: AutoManagerConfig
) {

    private val serverVersionsFile = File(DirectoryPaths.paths.storagePath + "server_versions.json")

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

    suspend fun updateAllVersions(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            println("[ServerVersionManager] Starting version update...")
            val updatedEntries = mutableListOf<ServerVersionEntry>()
            var hasErrors = false

            // Update configured server software
            config.serverSoftware.forEach { software ->
                try {
                    println("[ServerVersionManager] Updating $software...")
                    val entry = updateSoftware(software)
                    if (entry != null) {
                        updatedEntries.add(entry)
                        println("[ServerVersionManager] Updated $software to version ${entry.latestVersion}")
                    }
                } catch (e: Exception) {
                    println("[ServerVersionManager] Error updating $software: ${e.message}")
                    hasErrors = true
                }
            }

            // Always add VelocityCTD
            updatedEntries.add(createVelocityCTDEntry())

            // Save all entries
            saveServerVersions(updatedEntries)
            println("[ServerVersionManager] Version update completed. Updated ${updatedEntries.size} entries")

            !hasErrors
        } catch (e: Exception) {
            println("[ServerVersionManager] Fatal error during version update: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private suspend fun updateSoftware(software: String): ServerVersionEntry? {
        return when (software.lowercase()) {
            "paper" -> updatePaper()
            "leaf" -> updateLeaf()
            else -> {
                println("[ServerVersionManager] Unknown software: $software")
                null
            }
        }
    }

    private suspend fun updatePaper(): ServerVersionEntry = withContext(Dispatchers.IO) {
        try {
            val response = URL("https://api.papermc.io/v2/projects/paper").readText()
            val data = JsonLib.fromJsonString(response)

            val versions = data.getAsJsonArray("versions")!!.map {
                it.asString
            }
            val latestVersion = versions.last()

            val downloadLinks = versions.takeLast(10).reversed().map { version ->
                val buildsResponse = URL("https://api.papermc.io/v2/projects/paper/versions/$version").readText()
                val buildsData = JsonLib.fromJsonString(buildsResponse)
                val builds = buildsData.getAsJsonArray("builds")!!
                val latestBuild = builds.last().asString

                VersionDownload(
                    version = version,
                    link = "https://api.papermc.io/v2/projects/paper/versions/$version/builds/$latestBuild/downloads/paper-$version-$latestBuild.jar"
                )
            }

            ServerVersionEntry("Paper", "SERVER", true, latestVersion, downloadLinks)
        } catch (e: Exception) {
            println("[ServerVersionManager] Error updating Paper: ${e.message}")
            // Return fallback
            ServerVersionEntry(
                "Paper",
                "SERVER",
                true,
                "1.21.4",
                listOf(
                    VersionDownload("1.21.4", "https://api.papermc.io/v2/projects/paper/versions/1.21.4/builds/550/downloads/paper-1.21.4-550.jar")
                )
            )
        }
    }

    private suspend fun updateLeaf(): ServerVersionEntry = withContext(Dispatchers.IO) {
        try {
            println("[ServerVersionManager] Fetching Leaf versions...")

            // Use the known working URLs from s3.mcjars.app
            val downloadLinks = listOf(
                VersionDownload("1.21.6", "https://s3.mcjars.app/leaf/1.21.6/19/server.jar?minecraft_version=1.21.6&build=19"),
                VersionDownload("1.21.5", "https://s3.mcjars.app/leaf/1.21.5/62/server.jar?minecraft_version=1.21.5&build=62"),
                VersionDownload("1.21.4", "https://s3.mcjars.app/leaf/1.21.4/502/server.jar?minecraft_version=1.21.4&build=502"),
                VersionDownload("1.21.3", "https://s3.mcjars.app/leaf/1.21.3/1.21.3-e380e4b5/server.jar?minecraft_version=1.21.3&build=1"),
                VersionDownload("1.21.1", "https://s3.mcjars.app/leaf/1.21.1/1.21.1-24093972/server.jar?minecraft_version=1.21.1&build=1"),
                VersionDownload("1.21", "https://s3.mcjars.app/leaf/1.21/1.21-a0295ff4/server.jar?minecraft_version=1.21&build=1"),
                VersionDownload("1.20.6", "https://s3.mcjars.app/leaf/1.20.6/1.20.6-becd099/server.jar?minecraft_version=1.20.6&build=1"),
                VersionDownload("1.20.4", "https://s3.mcjars.app/leaf/1.20.4/1.20.4-6d332992/server.jar?minecraft_version=1.20.4&build=1"),
                VersionDownload("1.20.1", "https://s3.mcjars.app/leaf/1.20.1/1.20.1-f01d66d5/server.jar?minecraft_version=1.20.1&build=1")
            )

            // Try to fetch latest from GitHub API as well (but use s3.mcjars.app URLs)
            try {
                val response = URL("https://api.github.com/repos/Winds-Studio/Leaf/releases?per_page=5").readText()
                val jsonLib = JsonLib.fromJsonString(response)
                val releases = jsonLib.jsonElement as? JsonArray

                if (releases != null && releases.size() > 0) {
                    println("[ServerVersionManager] Found ${releases.size()} Leaf releases on GitHub")

                    // Check if there's a newer version on GitHub
                    val latestRelease = releases[0]
                    if (latestRelease is JsonObject) {
                        val tagName = latestRelease.get("tag_name")?.asString
                        if (tagName != null) {
                            val version = tagName.removePrefix("ver-")
                            if (!downloadLinks.any { it.version == version }) {
                                println("[ServerVersionManager] Newer Leaf version found on GitHub: $version")
                                // You would need to determine the correct s3.mcjars.app URL for this version
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("[ServerVersionManager] GitHub API check failed: ${e.message}")
            }

            val latestVersion = downloadLinks.firstOrNull()?.version ?: "1.21.6"
            println("[ServerVersionManager] Leaf configured with ${downloadLinks.size} versions, latest: $latestVersion")

            ServerVersionEntry("Leaf", "SERVER", true, latestVersion, downloadLinks)

        } catch (e: Exception) {
            println("[ServerVersionManager] Error fetching Leaf versions: ${e.message}")
            e.printStackTrace()

            // Return fallback with at least one working version
            ServerVersionEntry(
                "Leaf",
                "SERVER",
                true,
                "1.21.6",
                listOf(
                    VersionDownload("1.21.6", "https://s3.mcjars.app/leaf/1.21.6/19/server.jar?minecraft_version=1.21.6&build=19")
                )
            )
        }
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

    private fun saveServerVersions(entries: List<ServerVersionEntry>) {
        try {
            // Create directory if it doesn't exist
            serverVersionsFile.parentFile.mkdirs()

            if (serverVersionsFile.exists() && config.enableBackup) {
                val backupFile = File(serverVersionsFile.parentFile, "server_versions.json.backup")
                serverVersionsFile.copyTo(backupFile, overwrite = true)
                println("[ServerVersionManager] Created backup: ${backupFile.name}")
            }

            val jsonData = JsonLib.empty()

            // Convert entries to JSON format
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

            println("[ServerVersionManager] Saved ${entries.size} server versions to file")
        } catch (e: Exception) {
            println("[ServerVersionManager] Error saving server versions: ${e.message}")
            throw e
        }
    }

    fun getCurrentVersions(): List<ServerVersionEntry> {
        return try {
            if (!serverVersionsFile.exists()) {
                println("[ServerVersionManager] Server versions file not found, returning empty list")
                return emptyList()
            }

            val jsonLib = JsonLib.fromJsonFile(serverVersionsFile)!!
            val servers = jsonLib.getAsJsonArray("servers")!!

            val entries = mutableListOf<ServerVersionEntry>()

            for (i in 0 until servers.size()) {
                val serverElement = servers[i]
                if (serverElement !is JsonObject) continue

                val name = serverElement.get("name")?.asString ?: continue
                val type = serverElement.get("type")?.asString ?: continue
                val isPaperclip = serverElement.get("isPaperclip")?.asBoolean ?: false
                val latestVersion = serverElement.get("latestVersion")?.asString ?: continue

                // Parse download links
                val downloadLinksArray = serverElement.getAsJsonArray("downloadLinks")
                val downloadLinks = mutableListOf<VersionDownload>()

                if (downloadLinksArray != null) {
                    for (j in 0 until downloadLinksArray.size()) {
                        val linkElement = downloadLinksArray[j]
                        if (linkElement !is JsonObject) continue

                        val version = linkElement.get("version")?.asString ?: continue
                        val link = linkElement.get("link")?.asString ?: continue

                        downloadLinks.add(VersionDownload(version, link))
                    }
                }

                entries.add(ServerVersionEntry(
                    name = name,
                    type = type,
                    isPaperclip = isPaperclip,
                    latestVersion = latestVersion,
                    downloadLinks = downloadLinks
                ))
            }

            println("[ServerVersionManager] Loaded ${entries.size} server versions from file")
            entries

        } catch (e: Exception) {
            println("[ServerVersionManager] Error loading server versions: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    fun getVersionByName(name: String): ServerVersionEntry? {
        return getCurrentVersions().find { it.name.equals(name, ignoreCase = true) }
    }

    suspend fun forceUpdateVersion(name: String): ServerVersionEntry? = withContext(Dispatchers.IO) {
        return@withContext when (name.lowercase()) {
            "leaf" -> updateLeaf()
            "paper" -> updatePaper()
            "velocityctd" -> createVelocityCTDEntry()
            else -> null
        }
    }
}