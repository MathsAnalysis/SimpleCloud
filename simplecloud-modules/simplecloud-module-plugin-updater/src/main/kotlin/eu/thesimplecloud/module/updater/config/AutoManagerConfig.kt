/*
 * MIT License
 *
 * Copyright (C) 2020-2022 The SimpleCloud authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software,
 * and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package eu.thesimplecloud.module.updater.config

import com.fasterxml.jackson.annotation.JsonProperty
import eu.thesimplecloud.jsonlib.JsonLib

data class AutoManagerConfig(
    @JsonProperty("externalSources")
    val externalSources: List<String> = emptyList(),

    @JsonProperty("enableAutomation")
    var enableAutomation: Boolean = true,

    @JsonProperty("enableServerVersionUpdates")
    val enableServerVersionUpdates: Boolean = true,

    @JsonProperty("enablePluginUpdates")
    val enablePluginUpdates: Boolean = true,

    @JsonProperty("enableTemplateSync")
    val enableTemplateSync: Boolean = true,

    @JsonProperty("enableNotifications")
    val enableNotifications: Boolean = false,

    @JsonProperty("enableBackup")
    val enableBackup: Boolean = true,

    // AGGIUNGI QUESTO CAMPO
    @JsonProperty("enableDebug")
    val enableDebug: Boolean = false,

    @JsonProperty("updateInterval")
    val updateInterval: String = "24h",

    @JsonProperty("updateTime")
    val updateTime: String = "04:00",

    @JsonProperty("serverSoftware")
    val serverSoftware: List<String> = listOf("paper", "leaf"),

    @JsonProperty("plugins")
    val plugins: List<PluginConfig> = emptyList(),

    @JsonProperty("templates")
    val templates: TemplateConfig = TemplateConfig()
) {
    data class PluginConfig(
        @JsonProperty("name")
        val name: String,

        @JsonProperty("enabled")
        val enabled: Boolean = true,

        @JsonProperty("platforms")
        val platforms: List<String> = listOf("bukkit"),

        @JsonProperty("customUrl")
        val customUrl: String? = null
    )

    data class TemplateConfig(
        @JsonProperty("autoCreateBaseTemplates")
        val autoCreateBaseTemplates: Boolean = true,

        @JsonProperty("syncOnStart")
        val syncOnStart: Boolean = true
    )

    companion object {
        fun getDefault(): AutoManagerConfig {
            return AutoManagerConfig(
                plugins = listOf(
                    PluginConfig(
                        name = "LuckPerms",
                        enabled = true,
                        platforms = listOf("bukkit", "bungeecord", "velocity")
                    ),
                    PluginConfig(
                        name = "Spark",
                        enabled = true,
                        platforms = listOf("bukkit", "bungeecord", "velocity")
                    ),
                    PluginConfig(
                        name = "ProtocolLib",
                        enabled = true,
                        platforms = listOf("bukkit")
                    ),
                    PluginConfig(
                        name = "PlaceholderAPI",
                        enabled = true,
                        platforms = listOf("bukkit")
                    ),
                    PluginConfig(
                        name = "Floodgate",
                        enabled = false,
                        platforms = listOf("bukkit", "bungeecord", "velocity")
                    ),
                    PluginConfig(
                        name = "Geyser",
                        enabled = false,
                        platforms = listOf("bukkit", "bungeecord", "velocity")
                    )
                ),
                updateInterval = "24h",
            )
        }

        fun fromJson(jsonLib: JsonLib): AutoManagerConfig {
            return jsonLib.getObject(AutoManagerConfig::class.java) ?: getDefault()
        }

        fun toJson(autoManagerConfig: AutoManagerConfig): JsonLib {
            return JsonLib.fromObject(autoManagerConfig)
        }
    }
}