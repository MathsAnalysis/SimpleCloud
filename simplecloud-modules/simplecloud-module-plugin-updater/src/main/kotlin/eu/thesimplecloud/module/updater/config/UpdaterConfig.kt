package eu.thesimplecloud.module.updater.config

import eu.thesimplecloud.jsonlib.JsonLib
import eu.thesimplecloud.module.updater.updater.PluginConfig
import java.io.File

data class UpdaterConfig(
    val enabled: Boolean = true,
    val updateIntervalHours: Int = 24,
    val updateServerJars: Boolean = true,
    val updatePlugins: Boolean = true,
    val syncPluginsToTemplates: Boolean = true,
    val plugins: List<PluginConfig> = getDefaultPlugins()
) {
    fun save(file: File) {
        file.parentFile.mkdirs()
        JsonLib.fromObject(this).saveAsFile(file)
    }
    
    companion object {
        fun fromFile(file: File): UpdaterConfig {
            return try {
                JsonLib.fromJsonFile(file)?.getObject(UpdaterConfig::class.java) ?: createDefault()
            } catch (e: Exception) {
                createDefault()
            }
        }
        
        fun createDefault(): UpdaterConfig {
            return UpdaterConfig()
        }
        
        private fun getDefaultPlugins(): List<PluginConfig> {
            return listOf(
                PluginConfig(
                    name = "LuckPerms",
                    enabled = true,
                    platforms = listOf("bukkit", "velocity")
                ),
                PluginConfig(
                    name = "Spark",
                    enabled = true,
                    platforms = listOf("bukkit", "velocity")
                ),
                PluginConfig(
                    name = "ViaVersion",
                    enabled = true,
                    platforms = listOf("bukkit"),
                    customUrls = mapOf(
                        "bukkit" to "https://github.com/ViaVersion/ViaVersion/releases/latest/download/ViaVersion.jar"
                    )
                ),
                PluginConfig(
                    name = "ViaBackwards",
                    enabled = true,
                    platforms = listOf("bukkit"),
                    customUrls = mapOf(
                        "bukkit" to "https://github.com/ViaVersion/ViaBackwards/releases/latest/download/ViaBackwards.jar"
                    )
                )
            )
        }
    }
}