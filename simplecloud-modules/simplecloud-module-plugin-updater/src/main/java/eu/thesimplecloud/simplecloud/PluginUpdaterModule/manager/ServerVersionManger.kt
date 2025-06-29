package eu.thesimplecloud.module.automanager.manager

import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.jsonlib.JsonLib
import eu.thesimplecloud.module.automanager.AutoManagerModule
import eu.thesimplecloud.simplecloud.PluginUpdaterModule.config.AutoManagerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

class ServerVersionManager(
    private val module: AutoManagerModule,
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

        val updatedEntries = mutableListOf<ServerVersionEntry>()
        var hasErrors = false

        config.serverSoftware.forEach { software ->
            try {
                val entry = updateSoftware(software)
                if (entry != null) {
                    updatedEntries.add(entry)
                }
            } catch (e: Exception) {
                hasErrors = true
            }
        }

        updatedEntries.add(createVelocityCTDEntry())

        saveServerVersions(updatedEntries)

        !hasErrors

    }

    private suspend fun updateSoftware(software: String): ServerVersionEntry? {
        return when (software.lowercase()) {
            "paper" -> updatePaper()
            "purpur" -> updatePurpur()
            "leaf" -> updateLeaf()
            "folia" -> updateFolia()
            "velocity" -> updateVelocity()
            "bungeecord" -> updateBungeeCord()
            "waterfall" -> updateWaterfall()
            "fabric" -> updateFabric()
            "vanilla" -> updateVanilla()
            else -> {
                null
            }
        }
    }

    private suspend fun updatePaper(): ServerVersionEntry = withContext(Dispatchers.IO) {
        val response = URL("https://api.papermc.io/v2/projects/paper").readText()
        val data = JsonLib.fromJsonString(response)

        val versions = data.getAsJsonArray("versions")!!.map {
            it.toString().replace("\"", "")
        }
        val latestVersion = versions.last()

        val downloadLinks = versions.takeLast(10).map { version ->
            val buildsResponse = URL("https://api.papermc.io/v2/projects/paper/versions/$version").readText()
            val buildsData = JsonLib.fromJsonString(buildsResponse)!!
            val builds = buildsData.getAsJsonArray("builds")!!
            val latestBuild = builds.last().toString()

            VersionDownload(
                version = version,
                link = "https://api.papermc.io/v2/projects/paper/versions/$version/builds/$latestBuild/downloads/paper-$version-$latestBuild.jar"
            )
        }

        ServerVersionEntry("Paper", "SERVER", true, latestVersion, downloadLinks)
    }

    private suspend fun updateLeaf(): ServerVersionEntry = withContext(Dispatchers.IO) {
        val response = URL("https://api.github.com/repos/Winds-Studio/Leaf/releases").readText()
        val releases = JsonLib.fromJsonString(response)!!.getAsJsonArray("releases")!!

        val downloadLinks = releases.take(10).mapNotNull { release ->
            val releaseObj = release.asJsonObject
            val tagName = releaseObj.get("tag_name").asString
            val assets = releaseObj.getAsJsonArray("assets")

            val jarAsset = assets.find { asset ->
                val assetObj = asset.asJsonObject
                val name = assetObj.get("name").asString
                name.endsWith(".jar") && name.contains("leaf", true)
            }

            jarAsset?.let {
                val assetObj = it.asJsonObject
                val downloadUrl = assetObj.get("browser_download_url").asString
                val version = tagName.removePrefix("ver-")
                VersionDownload(version, downloadUrl)
            }
        }

        val latestVersion = downloadLinks.firstOrNull()?.version ?: "1.21.6"

        ServerVersionEntry("Leaf", "SERVER", true, latestVersion, downloadLinks)
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

    private suspend fun updatePurpur(): ServerVersionEntry = TODO("Implementare Purpur")
    private suspend fun updateFolia(): ServerVersionEntry = TODO("Implementare Folia")
    private suspend fun updateVelocity(): ServerVersionEntry = TODO("Implementare Velocity")
    private suspend fun updateBungeeCord(): ServerVersionEntry = TODO("Implementare BungeeCord")
    private suspend fun updateWaterfall(): ServerVersionEntry = TODO("Implementare Waterfall")
    private suspend fun updateFabric(): ServerVersionEntry = TODO("Implementare Fabric")
    private suspend fun updateVanilla(): ServerVersionEntry = TODO("Implementare Vanilla")

    private fun saveServerVersions(entries: List<ServerVersionEntry>) {
        if (serverVersionsFile.exists() && config.enableBackup) {
            val backupFile = File(serverVersionsFile.parentFile, "server_versions.json.backup")
            serverVersionsFile.copyTo(backupFile, overwrite = true)
        }

        JsonLib.empty()
            .append("servers", entries)
            .saveAsFile(serverVersionsFile)
    }

    fun getCurrentVersions(): List<ServerVersionEntry> {
        if (serverVersionsFile.exists()) {
            val data = JsonLib.fromJsonFile(serverVersionsFile)!!
            data.getAsJsonArray("servers")!!.map {
                it.asJsonObject.let { obj ->
                    ServerVersionEntry(
                        name = obj.get("name").asString,
                        type = obj.get("type").asString,
                        isPaperclip = obj.get("isPaperclip").asBoolean,
                        latestVersion = obj.get("latestVersion").asString,
                        downloadLinks = emptyList()
                    )
                }
            }
        }

        return emptyList()
    }
}