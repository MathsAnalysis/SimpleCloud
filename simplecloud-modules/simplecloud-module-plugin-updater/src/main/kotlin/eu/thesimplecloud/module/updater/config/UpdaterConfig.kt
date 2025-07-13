package eu.thesimplecloud.module.updater.config

data class UpdaterConfig(
    val enabled: Boolean = true,
    val updateIntervalHours: Int = 24,
    val updateOnStart: Boolean = true,
    val serverJars: ServerJarsConfig = ServerJarsConfig(),
    val plugins: PluginsConfig = PluginsConfig()
) {
    data class ServerJarsConfig(
        val enabled: Boolean = true,
        val updateLeaf: Boolean = true,
        val updatePaper: Boolean = true,
        val updateVelocity: Boolean = true,
        val updateVelocityCtd: Boolean = true,
        val paperVersion: String = "1.21.4",
        val velocityVersion: String = "3.4.0-SNAPSHOT"
    )

    data class PluginsConfig(
        val enabled: Boolean = true,
        val syncToTemplates: Boolean = true,
        val items: List<PluginItem> = getDefaultPlugins()
    )

    data class PluginItem(
        val name: String,
        val enabled: Boolean = true,
        val platform: PluginPlatform,
        val source: PluginSource,
        val fileName: String? = null
    )

    enum class PluginPlatform {
        BUKKIT, VELOCITY, UNIVERSAL
    }

    data class PluginSource(
        val type: SourceType,
        val value: String
    )

    enum class SourceType {
        GITHUB, HANGAR, MODRINTH, JENKINS, DIRECT_URL, OFFICIAL_API
    }

    companion object {
        fun getDefaultPlugins(): List<PluginItem> = listOf(
            PluginItem(
                name = "LuckPerms-Bukkit",
                platform = PluginPlatform.BUKKIT,
                source = PluginSource(SourceType.OFFICIAL_API, "luckperms"),
                fileName = "LuckPerms-Bukkit.jar"
            ),
            PluginItem(
                name = "LuckPerms-Velocity",
                platform = PluginPlatform.VELOCITY,
                source = PluginSource(SourceType.OFFICIAL_API, "luckperms"),
                fileName = "LuckPerms-Velocity.jar"
            ),
            PluginItem(
                name = "spark-bukkit",
                platform = PluginPlatform.BUKKIT,
                source = PluginSource(SourceType.OFFICIAL_API, "spark"),
                fileName = "spark-bukkit.jar"
            ),
            PluginItem(
                name = "spark-velocity",
                platform = PluginPlatform.VELOCITY,
                source = PluginSource(SourceType.OFFICIAL_API, "spark"),
                fileName = "spark-velocity.jar"
            ),
            PluginItem(
                name = "ViaVersion",
                platform = PluginPlatform.BUKKIT,
                source = PluginSource(SourceType.HANGAR, "ViaVersion/ViaVersion"),
                fileName = "ViaVersion.jar"
            ),
            PluginItem(
                name = "Vault",
                platform = PluginPlatform.BUKKIT,
                source = PluginSource(SourceType.GITHUB, "MilkBowl/Vault"),
                fileName = "Vault.jar"
            ),
            PluginItem(
                name = "PlaceholderAPI",
                platform = PluginPlatform.BUKKIT,
                source = PluginSource(SourceType.GITHUB, "PlaceholderAPI/PlaceholderAPI"),
                fileName = "PlaceholderAPI.jar"
            ),
            PluginItem(
                name = "Geyser-Spigot",
                platform = PluginPlatform.BUKKIT,
                source = PluginSource(SourceType.DIRECT_URL, "https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot"),
                fileName = "Geyser-Spigot.jar"
            ),
            PluginItem(
                name = "Geyser-Velocity",
                platform = PluginPlatform.VELOCITY,
                source = PluginSource(SourceType.DIRECT_URL, "https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/velocity"),
                fileName = "Geyser-Velocity.jar"
            )
        )
    }
}