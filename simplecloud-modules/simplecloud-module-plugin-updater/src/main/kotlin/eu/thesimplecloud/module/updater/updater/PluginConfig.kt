package eu.thesimplecloud.module.updater.updater

data class PluginConfig(
    val name: String,
    val enabled: Boolean,
    val platforms: List<String>,
    val customUrls: Map<String, String> = emptyMap()
)