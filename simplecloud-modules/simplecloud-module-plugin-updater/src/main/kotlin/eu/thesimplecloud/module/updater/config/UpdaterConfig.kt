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
        fun createDefault(): UpdaterConfig {
            return UpdaterConfig(
                enabled = true,
                updateServerJars = true,
                updatePlugins = true,
                updateIntervalHours = 24,
                syncPluginsToTemplates = true,
                serverVersions = ServerVersionsConfig(),
                plugins = getDefaultPlugins()
            )
        }

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
                            downloadUrl = defaultPlugin.downloadUrl
                        )
                    } else {
                        loadedPlugin
                    }
                }.plus(
                    defaultPlugins.filter { defaultPlugin ->
                        !loadedConfig.plugins.any { it.name == defaultPlugin.name }
                    }
                )

                loadedConfig.copy(plugins = mergedPlugins)
            } else {
                createDefault()
            }
        }

        private fun getDefaultPlugins(): List<PluginConfig> {
            return listOf(
                PluginConfig(
                    name = "LuckPerms",
                    enabled = true,
                    platforms = listOf(PluginPlatform.BUKKIT, PluginPlatform.VELOCITY),
                    downloadUrl = null
                ),
                PluginConfig(
                    name = "PlaceholderAPI",
                    enabled = true,
                    platforms = listOf(PluginPlatform.BUKKIT),
                    downloadUrl = null
                ),
                PluginConfig(
                    name = "Spark",
                    enabled = true,
                    platforms = listOf(PluginPlatform.BUKKIT, PluginPlatform.VELOCITY),
                    downloadUrl = null
                ),
                PluginConfig(
                    name = "ViaVersion",
                    enabled = true,
                    platforms = listOf(PluginPlatform.BUKKIT, PluginPlatform.VELOCITY),
                    downloadUrl = null
                ),
                PluginConfig(
                    name = "ViaBackwards",
                    enabled = true,
                    platforms = listOf(PluginPlatform.BUKKIT, PluginPlatform.VELOCITY),
                    downloadUrl = null
                )
            )
        }
    }
}