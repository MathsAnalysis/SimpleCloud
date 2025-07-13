package eu.thesimplecloud.module.updater.config

import eu.thesimplecloud.jsonlib.JsonLib
import eu.thesimplecloud.module.updater.updater.PluginUpdater.PluginConfig
import eu.thesimplecloud.module.updater.updater.PluginUpdater.PluginPlatform
import java.io.File

data class UpdaterConfig(
    val enabled: Boolean = true,
    val updateServerJars: Boolean = true,
    val updatePlugins: Boolean = true,
    val updateIntervalHours: Int = 24,
    val syncPluginsToTemplates: Boolean = true,
    val serverVersions: ServerVersionsConfig = ServerVersionsConfig(),
    val plugins: List<PluginConfig> = getDefaultPlugins()
) {
    data class ServerVersionsConfig(
        val updateLeaf: Boolean = true,
        val updatePaper: Boolean = true,
        val updateVelocity: Boolean = true,
        val updateVelocityCtd: Boolean = true,
        val leafVersion: String = "latest",
        val paperVersion: String = "1.21.4",
        val velocityVersion: String = "3.4.0-SNAPSHOT",
        val velocityCtdVersion: String = "latest"
    )

    fun save(file: File) {
        file.parentFile.mkdirs()
        JsonLib.fromObject(this).saveAsFile(file)
    }

    companion object {
        fun fromFile(file: File): UpdaterConfig {
            val loadedConfig = JsonLib.fromJsonFile(file)?.getObject(UpdaterConfig::class.java)

            return if (loadedConfig != null) {
                val defaultPlugins = getDefaultPlugins()
                val mergedPlugins = loadedConfig.plugins.map { loadedPlugin ->
                    val defaultPlugin = defaultPlugins.find { it.name == loadedPlugin.name }

                    if (defaultPlugin != null) {
                        PluginConfig(
                            name = loadedPlugin.name,
                            enabled = loadedPlugin.enabled,
                            platforms = defaultPlugin.platforms,
                            downloadUrl = defaultPlugin.downloadUrl,
                            githubRepo = defaultPlugin.githubRepo,
                            spigotResourceId = defaultPlugin.spigotResourceId,
                            hangarSlug = defaultPlugin.hangarSlug,
                            modrinthId = defaultPlugin.modrinthId,
                            customUrl = defaultPlugin.customUrl,
                            fileName = defaultPlugin.fileName
                        )
                    } else {
                        loadedPlugin
                    }
                }

                loadedConfig.copy(plugins = mergedPlugins)
            } else {
                createDefault()
            }
        }

        fun createDefault(): UpdaterConfig {
            return UpdaterConfig()
        }

        fun getDefaultPlugins(): List<PluginConfig> = listOf(
            PluginConfig(
                name = "LuckPerms",
                enabled = true,
                platforms = listOf(PluginPlatform.BUKKIT),
                customUrl = "https://download.luckperms.net/1594/bukkit/loader/LuckPerms-Bukkit-5.5.9.jar",
                fileName = "LuckPerms-Bukkit.jar"
            ),
            PluginConfig(
                name = "LuckPerms-Velocity",
                enabled = true,
                platforms = listOf(PluginPlatform.VELOCITY),
                customUrl = "https://download.luckperms.net/1594/velocity/LuckPerms-Velocity-5.5.9.jar",
                fileName = "LuckPerms-Velocity.jar"
            ),
            PluginConfig(
                name = "Spark",
                enabled = true,
                platforms = listOf(PluginPlatform.BUKKIT),
                customUrl = "https://ci.lucko.me/job/spark/lastSuccessfulBuild/artifact/spark-bukkit/build/libs/spark-1.10.140-bukkit.jar",
                fileName = "spark-bukkit.jar"
            ),
            PluginConfig(
                name = "Spark-Velocity",
                enabled = true,
                platforms = listOf(PluginPlatform.VELOCITY),
                customUrl = "https://ci.lucko.me/job/spark/lastSuccessfulBuild/artifact/spark-velocity/build/libs/spark-1.10.140-velocity.jar",
                fileName = "spark-velocity.jar"
            ),
            PluginConfig(
                name = "Vault",
                enabled = true,
                platforms = listOf(PluginPlatform.BUKKIT),
                githubRepo = "MilkBowl/Vault",
                fileName = "Vault.jar"
            ),
            PluginConfig(
                name = "PlaceholderAPI",
                enabled = true,
                platforms = listOf(PluginPlatform.BUKKIT),
                customUrl = "https://ci.extendedclip.com/job/PlaceholderAPI/lastSuccessfulBuild/artifact/build/libs/PlaceholderAPI-2.11.6.jar",
                fileName = "PlaceholderAPI.jar"
            ),
            PluginConfig(
                name = "ProtocolLib",
                enabled = false,
                platforms = listOf(PluginPlatform.BUKKIT),
                customUrl = "https://ci.dmulloy2.net/job/ProtocolLib/lastSuccessfulBuild/artifact/build/libs/ProtocolLib.jar",
                fileName = "ProtocolLib.jar"
            ),
            PluginConfig(
                name = "ViaVersion",
                enabled = true,
                platforms = listOf(PluginPlatform.BUKKIT),
                hangarSlug = "ViaVersion/ViaVersion",
                fileName = "ViaVersion.jar"
            ),
            PluginConfig(
                name = "ViaBackwards",
                enabled = false,
                platforms = listOf(PluginPlatform.BUKKIT),
                hangarSlug = "ViaVersion/ViaBackwards",
                fileName = "ViaBackwards.jar"
            ),
            PluginConfig(
                name = "VelocityVanish",
                enabled = false,
                platforms = listOf(PluginPlatform.VELOCITY),
                githubRepo = "LooFifteen/VelocityVanish",
                fileName = "VelocityVanish.jar"
            ),
            PluginConfig(
                name = "SignedVelocity",
                enabled = false,
                platforms = listOf(PluginPlatform.VELOCITY),
                modrinthId = "signedvelocity",
                fileName = "SignedVelocity.jar"
            ),
            PluginConfig(
                name = "Geyser-Spigot",
                enabled = false,
                platforms = listOf(PluginPlatform.BUKKIT),
                customUrl = "https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot",
                fileName = "Geyser-Spigot.jar"
            ),
            PluginConfig(
                name = "Geyser-Velocity",
                enabled = true,
                platforms = listOf(PluginPlatform.VELOCITY),
                customUrl = "https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/velocity",
                fileName = "Geyser-Velocity.jar"
            ),
            PluginConfig(
                name = "Floodgate-Spigot",
                enabled = false,
                platforms = listOf(PluginPlatform.BUKKIT),
                customUrl = "https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/spigot",
                fileName = "Floodgate-Spigot.jar"
            ),
            PluginConfig(
                name = "Floodgate-Velocity",
                enabled = true,
                platforms = listOf(PluginPlatform.VELOCITY),
                customUrl = "https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/velocity",
                fileName = "Floodgate-Velocity.jar"
            )
        )
    }
}