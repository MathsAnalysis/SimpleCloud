package eu.thesimplecloud.module.updater.config

import eu.thesimplecloud.jsonlib.JsonLib

data class AutoManagerConfig(
    val enableAutomation: Boolean = true,
    val enableServerVersionUpdates: Boolean = true,
    val enablePluginUpdates: Boolean = true,
    val enableTemplateSync: Boolean = true,
    val enableNotifications: Boolean = false,
    val enableBackup: Boolean = true,
    val updateInterval: String = "6h",
    val serverSoftware: List<String> = listOf("paper", "leaf"),
    val plugins: List<PluginConfig> = emptyList(),
    val templates: TemplateConfig = TemplateConfig()
) {

    data class PluginConfig(
        val name: String,
        val enabled: Boolean = true,
        val platforms: List<String> = listOf("bukkit"),
        val customUrl: String? = null
    )

    data class TemplateConfig(
        val autoCreateBaseTemplates: Boolean = true,
        val syncOnStart: Boolean = true
    )

    fun toJson(): Map<String, Any> {
        return mapOf(
            "enableAutomation" to enableAutomation,
            "enableServerVersionUpdates" to enableServerVersionUpdates,
            "enablePluginUpdates" to enablePluginUpdates,
            "enableTemplateSync" to enableTemplateSync,
            "enableNotifications" to enableNotifications,
            "enableBackup" to enableBackup,
            "updateInterval" to updateInterval,
            "serverSoftware" to serverSoftware,
            "plugins" to plugins.map { plugin ->
                mapOf(
                    "name" to plugin.name,
                    "enabled" to plugin.enabled,
                    "platforms" to plugin.platforms,
                    "customUrl" to plugin.customUrl
                )
            },
            "templates" to mapOf(
                "autoCreateBaseTemplates" to templates.autoCreateBaseTemplates,
                "syncOnStart" to templates.syncOnStart
            )
        )
    }

    companion object {
        fun fromJson(jsonLib: JsonLib): AutoManagerConfig {
            val enableAutomation = jsonLib.getBoolean("enableAutomation") ?: true
            val enableServerVersionUpdates = jsonLib.getBoolean("enableServerVersionUpdates") ?: true
            val enablePluginUpdates = jsonLib.getBoolean("enablePluginUpdates") ?: true
            val enableTemplateSync = jsonLib.getBoolean("enableTemplateSync") ?: true
            val enableNotifications = jsonLib.getBoolean("enableNotifications") ?: false
            val enableBackup = jsonLib.getBoolean("enableBackup") ?: true
            val updateInterval = jsonLib.getString("updateInterval") ?: "6h"

            val serverSoftwareArray = jsonLib.getAsJsonArray("serverSoftware")
            val serverSoftware = serverSoftwareArray?.map { it.asString } ?: listOf("paper", "leaf")

            val pluginsArray = jsonLib.getAsJsonArray("plugins")
            val plugins = pluginsArray?.map { pluginElement ->
                val pluginObj = pluginElement.asJsonObject
                val name = pluginObj.get("name").asString
                val enabled = pluginObj.get("enabled")?.asBoolean ?: true
                val platformsArray = pluginObj.getAsJsonArray("platforms")
                val platforms = platformsArray?.map { it.asString } ?: listOf("bukkit")
                val customUrl = pluginObj.get("customUrl")?.asString

                PluginConfig(name, enabled, platforms, customUrl)
            } ?: emptyList()

            val templatesObj = jsonLib.getProperty("templates")
            val templates = if (templatesObj != null) {
                val autoCreateBaseTemplates = templatesObj.getBoolean("autoCreateBaseTemplates") ?: true
                val syncOnStart = templatesObj.getBoolean("syncOnStart") ?: true
                TemplateConfig(autoCreateBaseTemplates, syncOnStart)
            } else {
                TemplateConfig()
            }

            return AutoManagerConfig(
                enableAutomation = enableAutomation,
                enableServerVersionUpdates = enableServerVersionUpdates,
                enablePluginUpdates = enablePluginUpdates,
                enableTemplateSync = enableTemplateSync,
                enableNotifications = enableNotifications,
                enableBackup = enableBackup,
                updateInterval = updateInterval,
                serverSoftware = serverSoftware,
                plugins = plugins,
                templates = templates
            )
        }
    }
}