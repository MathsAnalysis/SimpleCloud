package eu.thesimplecloud.simplecloud.autorestart.config.loader

import eu.thesimplecloud.api.config.AbstractJsonLibConfigLoader
import eu.thesimplecloud.simplecloud.autorestart.config.AutoRestartConfig
import eu.thesimplecloud.simplecloud.autorestart.config.RestartGroupConfig
import eu.thesimplecloud.simplecloud.autorestart.config.RestartTargetConfig
import java.io.File

class AutoRestartConfigLoader : AbstractJsonLibConfigLoader<AutoRestartConfig>(
    AutoRestartConfig::class.java,
    File("modules/autorestart/config.json"),
    { createDefaultConfig() },
    true
) {
    companion object {
        private fun createDefaultConfig(): AutoRestartConfig {
            val serverTargets = mutableListOf(
                RestartTargetConfig(
                    "BedWars",
                    "GROUP",
                    100
                ),
                RestartTargetConfig(
                    "SkyWars",
                    "GROUP",
                    50
                )
            )

            val lobbyTargets = mutableListOf(
                RestartTargetConfig(
                    "Lobby",
                    "GROUP",
                    200
                )
            )

            val proxyTargets = mutableListOf(
                RestartTargetConfig(
                    "Proxy",
                    "GROUP",
                    300
                )
            )

            val restartGroups = mutableListOf(
                RestartGroupConfig("servers", "03:00", true, serverTargets),
                RestartGroupConfig("lobbies", "03:30", true, lobbyTargets),
                RestartGroupConfig("proxies", "04:00", true, proxyTargets)
            )

            return AutoRestartConfig(
                enabled = true,
                globalCheckInterval = 60,
                maxConcurrentRestarts = 3,
                defaultHealthCheckTimeout = 60,
                defaultRestartTimeout = 300,
                restartGroups = restartGroups
            )
        }
    }
}