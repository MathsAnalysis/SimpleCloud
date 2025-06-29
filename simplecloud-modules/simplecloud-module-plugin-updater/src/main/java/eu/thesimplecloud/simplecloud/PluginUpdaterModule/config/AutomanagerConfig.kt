package eu.thesimplecloud.simplecloud.PluginUpdaterModule.config


import eu.thesimplecloud.jsonlib.JsonLib
import java.io.File

data class AutoManagerConfig(
    val enableAutomation: Boolean = true,
    val updateInterval: String = "6h",
    val enableServerVersionUpdates: Boolean = true,
    val enablePluginUpdates: Boolean = true,
    val enableTemplateSync: Boolean = true,
    val enableNotifications: Boolean = true,
    val enableBackup: Boolean = true,
    val serverSoftware: List<String> = defaultServerSoftware(),
    val plugins: List<PluginConfig> = defaultPlugins(),
    val repositories: List<RepositoryConfig> = defaultRepositories(),
    val templates: TemplateConfig = TemplateConfig(),
    val notifications: NotificationConfig = NotificationConfig(),
    val security: SecurityConfig = SecurityConfig()
) {

    data class PluginConfig(
        val name: String,
        val enabled: Boolean = true,
        val platforms: List<String> = listOf("bukkit", "velocity", "bungeecord"),
        val customUrl: String? = null,
        val updateInterval: String? = null
    )

    data class RepositoryConfig(
        val name: String,
        val url: String,
        val type: String,
        val authentication: String? = null,
        val enabled: Boolean = true
    )

    data class TemplateConfig(
        val autoCreateBaseTemplates: Boolean = true,
        val autoUpdateConfigurations: Boolean = true,
        val backupBeforeUpdate: Boolean = true,
        val customTemplates: List<String> = emptyList()
    )

    data class NotificationConfig(
        val discord: DiscordConfig = DiscordConfig(),
        val console: Boolean = true,
        val players: Boolean = true,
        val adminPermission: String = "simplecloud.admin"
    )

    data class DiscordConfig(
        val enabled: Boolean = false,
        val webhook: String = "",
        val updateNotifications: Boolean = true,
        val errorNotifications: Boolean = true
    )

    data class SecurityConfig(
        val verifyDownloads: Boolean = true,
        val allowBetaVersions: Boolean = false,
        val maxDownloadSize: Long = 100 * 1024 * 1024,
        val trustedDomains: List<String> = defaultTrustedDomains()
    )

    companion object {
        fun load(file: File): AutoManagerConfig {
            return try {
                if (file.exists()) {
                    JsonLib.fromJsonFile(file)?.getObject(AutoManagerConfig::class.java) ?: createDefault(file)
                } else {
                    createDefault(file)
                }
            } catch (e: Exception) {
                createDefault(file)
            }
        }

        private fun createDefault(file: File): AutoManagerConfig {
            val config = AutoManagerConfig()
            save(file, config)
            return config
        }

        fun save(file: File, config: AutoManagerConfig) {
            file.parentFile.mkdirs()
            JsonLib.empty()
                .append("config", config)
                .saveAsFile(file)
        }

        fun defaultServerSoftware() = listOf(
            "paper", "purpur", "leaf", "folia",
            "velocity", "bungeecord", "waterfall",
            "fabric", "vanilla"
        )

        fun defaultPlugins() = listOf(
            PluginConfig("luckperms", true, listOf("bukkit", "velocity", "bungeecord")),
            PluginConfig("spark", true, listOf("bukkit", "velocity", "bungeecord")),
            PluginConfig("floodgate", true, listOf("bukkit", "velocity")),
            PluginConfig("geyser", true, listOf("bukkit", "velocity")),
            PluginConfig("protocollib", true, listOf("bukkit")),
            PluginConfig("placeholderapi", true, listOf("bukkit"))
        )

        fun defaultRepositories() = listOf(
            RepositoryConfig("paper", "https://api.papermc.io/v2", "api"),
            RepositoryConfig("purpur", "https://api.purpurmc.org/v2", "api"),
            RepositoryConfig("leaf", "https://api.github.com/repos/Winds-Studio/Leaf", "github"),
            RepositoryConfig("luckperms", "https://metadata.luckperms.net", "api"),
            RepositoryConfig("spark", "https://api.github.com/repos/lucko/spark", "github"),
            RepositoryConfig("velocityctd", "https://github.com/GemstoneGG/Velocity-CTD", "direct")
        )

        fun defaultTrustedDomains() = listOf(
            "api.papermc.io",
            "api.purpurmc.org",
            "github.com",
            "download.luckperms.net",
            "download.lucko.me",
            "ci.md-5.net",
            "piston-data.mojang.com",
            "s3.mcjars.app"
        )
    }
}