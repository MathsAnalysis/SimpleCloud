package eu.thesimplecloud.module.updater.api

import eu.thesimplecloud.module.updater.config.AutoManagerConfig
import eu.thesimplecloud.module.updater.manager.AutoServerVersionManager
import eu.thesimplecloud.module.updater.manager.PluginManager
import eu.thesimplecloud.module.updater.manager.PluginUpdaterModule
import eu.thesimplecloud.module.updater.manager.TemplateManager
import eu.thesimplecloud.module.updater.thread.UpdateScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture

typealias ServerVersionEntry = AutoServerVersionManager.ServerVersionEntry

class AutoManagerAPI(private val module: PluginUpdaterModule) {

    private val apiScope = CoroutineScope(Dispatchers.IO)

    fun getConfig(): AutoManagerConfig = module.getConfig()

    fun getServerVersionManager() = module.getServerVersionManager()

    fun getPluginManager(): PluginManager = module.getPluginManager()

    fun getTemplateManager(): TemplateManager = module.getTemplateManager()

    fun getUpdateScheduler(): UpdateScheduler = module.getUpdateScheduler()

    fun forceUpdate(): java.util.concurrent.CompletableFuture<Boolean> {
        val future = java.util.concurrent.CompletableFuture<Boolean>()

        apiScope.launch {
            try {
                val result = module.runManualUpdate()
                future.complete(result)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }

        return future
    }

    fun updateServerVersions(): java.util.concurrent.CompletableFuture<Boolean> {
        val future = java.util.concurrent.CompletableFuture<Boolean>()

        apiScope.launch {
            try {
                val result = getServerVersionManager().updateAllVersions()
                future.complete(result)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }

        return future
    }

    fun updatePlugins(): java.util.concurrent.CompletableFuture<Boolean> {
        val future = java.util.concurrent.CompletableFuture<Boolean>()

        apiScope.launch {
            try {
                val result = getPluginManager().updateAllPlugins()
                future.complete(result)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }

        return future
    }

    fun syncTemplates(): java.util.concurrent.CompletableFuture<Boolean> {
        val future = java.util.concurrent.CompletableFuture<Boolean>()

        apiScope.launch {
            try {
                val result = getTemplateManager().syncAllTemplates()
                future.complete(result)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }

        return future
    }

    fun ensurePluginsDownloaded(): java.util.concurrent.CompletableFuture<Boolean> {
        val future = java.util.concurrent.CompletableFuture<Boolean>()

        apiScope.launch {
            try {
                val result = getPluginManager().ensureAllPluginsDownloaded()
                future.complete(result)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }

        return future
    }

    fun updateStaticServers(): java.util.concurrent.CompletableFuture<Boolean> {
        val future = java.util.concurrent.CompletableFuture<Boolean>()

        apiScope.launch {
            try {
                val result = getTemplateManager().syncStaticServersOnRestart()
                future.complete(result)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }

        return future
    }

    fun reloadConfig() {
        module.reloadConfig()
    }

    fun isAutomationEnabled(): Boolean = getConfig().enableAutomation

    fun isSchedulerRunning(): Boolean = getUpdateScheduler().isRunning()

    fun getNextUpdateTime(): Long = getUpdateScheduler().getNextUpdateTime()

    fun getCurrentServerVersions() = getServerVersionManager().getCurrentVersions()

    fun isPluginEnabled(pluginName: String): Boolean {
        return getConfig().plugins.find { it.name.equals(pluginName, true) }?.enabled ?: false
    }

    fun isServerSoftwareEnabled(software: String): Boolean {
        return getConfig().serverSoftware.contains(software.lowercase())
    }

    fun getStats(): AutoManagerStats {
        return AutoManagerStats(
            isAutomationEnabled = isAutomationEnabled(),
            isSchedulerRunning = isSchedulerRunning(),
            nextUpdateTime = getNextUpdateTime(),
            configuredPlugins = getConfig().plugins.size,
            enabledPlugins = getConfig().plugins.count { it.enabled },
            configuredServerSoftware = getConfig().serverSoftware.size,
            updateInterval = getConfig().updateInterval,
            enableServerVersionUpdates = getConfig().enableServerVersionUpdates,
            enablePluginUpdates = getConfig().enablePluginUpdates,
            enableTemplateSync = getConfig().enableTemplateSync,
            enableNotifications = getConfig().enableNotifications,
            enableBackup = getConfig().enableBackup
        )
    }

    data class AutoManagerStats(
        val isAutomationEnabled: Boolean,
        val isSchedulerRunning: Boolean,
        val nextUpdateTime: Long,
        val configuredPlugins: Int,
        val enabledPlugins: Int,
        val configuredServerSoftware: Int,
        val updateInterval: String,
        val enableServerVersionUpdates: Boolean,
        val enablePluginUpdates: Boolean,
        val enableTemplateSync: Boolean,
        val enableNotifications: Boolean,
        val enableBackup: Boolean
    ) {

        fun toMap(): Map<String, Any> = mapOf(
            "automation_enabled" to isAutomationEnabled,
            "scheduler_running" to isSchedulerRunning,
            "next_update_time" to nextUpdateTime,
            "configured_plugins" to configuredPlugins,
            "enabled_plugins" to enabledPlugins,
            "configured_server_software" to configuredServerSoftware,
            "update_interval" to updateInterval,
            "server_version_updates" to enableServerVersionUpdates,
            "plugin_updates" to enablePluginUpdates,
            "template_sync" to enableTemplateSync,
            "notifications" to enableNotifications,
            "backup" to enableBackup
        )

        override fun toString(): String {
            return """
                AutoManager Statistics:
                - Automation: ${if (isAutomationEnabled) "Enabled" else "Disabled"}
                - Scheduler: ${if (isSchedulerRunning) "Running" else "Stopped"}
                - Update Interval: $updateInterval
                - Configured Plugins: $configuredPlugins ($enabledPlugins enabled)
                - Server Software: $configuredServerSoftware types
                - Features: Server Updates:${if (enableServerVersionUpdates) "Yes" else "No"} | Plugin Updates:${if (enablePluginUpdates) "Yes" else "No"} | Template Sync:${if (enableTemplateSync) "Yes" else "No"}
                - Other: Notifications:${if (enableNotifications) "Yes" else "No"} | Backup:${if (enableBackup) "Yes" else "No"}
            """.trimIndent()
        }
    }
}