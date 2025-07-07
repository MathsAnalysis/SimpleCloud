package eu.thesimplecloud.module.updater.api

import eu.thesimplecloud.module.updater.plugin.PluginManager
import eu.thesimplecloud.module.updater.manager.ServerVersionManager
import eu.thesimplecloud.module.updater.manager.TemplateManager
import eu.thesimplecloud.module.updater.utils.LoggingUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture

class UpdaterAPI(
    private val serverVersionManager: ServerVersionManager,
    private val pluginManager: PluginManager,
    private val templateManager: TemplateManager
) {

    companion object {
        private const val TAG = "UpdaterAPI"
    }

    private val apiScope = CoroutineScope(Dispatchers.IO)

    init {
        LoggingUtils.init(TAG, "UpdaterAPI initialized")
        LoggingUtils.debug(TAG, "API scope created with IO dispatcher")
    }

    fun getServerVersionManager(): ServerVersionManager {
        LoggingUtils.debug(TAG, "ServerVersionManager requested")
        return serverVersionManager
    }

    fun getPluginManager(): PluginManager {
        LoggingUtils.debug(TAG, "PluginManager requested")
        return pluginManager
    }

    fun getTemplateManager(): TemplateManager {
        LoggingUtils.debug(TAG, "TemplateManager requested")
        return templateManager
    }

    fun updateServerVersions(): CompletableFuture<Boolean> {
        LoggingUtils.debugStart(TAG, "server versions update (async)")

        val future = CompletableFuture<Boolean>()

        apiScope.launch {
            val startTime = System.currentTimeMillis()

            try {
                LoggingUtils.debug(TAG, "Executing server versions update...")
                val result = serverVersionManager.updateAllVersions()
                val duration = System.currentTimeMillis() - startTime

                if (result) {
                    LoggingUtils.debugSuccess(TAG, "server versions update (async)")
                    LoggingUtils.debug(TAG, "Server versions update completed in ${duration}ms")
                } else {
                    LoggingUtils.debugFailure(TAG, "server versions update (async)", "update returned false")
                }

                future.complete(result)
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                LoggingUtils.error(TAG, "Error updating server versions (async): ${e.message}", e)
                LoggingUtils.debug(TAG, "Server versions update failed after ${duration}ms")
                future.completeExceptionally(e)
            }
        }

        return future
    }

    fun updatePlugins(): CompletableFuture<Boolean> {
        LoggingUtils.debugStart(TAG, "plugins update (async)")

        val future = CompletableFuture<Boolean>()

        apiScope.launch {
            val startTime = System.currentTimeMillis()

            try {
                LoggingUtils.debug(TAG, "Executing plugins update...")
                val result = pluginManager.ensureAllPluginsDownloaded()
                val duration = System.currentTimeMillis() - startTime

                if (result) {
                    LoggingUtils.debugSuccess(TAG, "plugins update (async)")
                    LoggingUtils.debug(TAG, "Plugins update completed in ${duration}ms")
                } else {
                    LoggingUtils.debugFailure(TAG, "plugins update (async)", "update returned false")
                }

                future.complete(result)
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                LoggingUtils.error(TAG, "Error updating plugins (async): ${e.message}", e)
                LoggingUtils.debug(TAG, "Plugins update failed after ${duration}ms")
                future.completeExceptionally(e)
            }
        }

        return future
    }

    fun syncTemplates(): CompletableFuture<Boolean> {
        LoggingUtils.debugStart(TAG, "templates sync (async)")

        val future = CompletableFuture<Boolean>()

        apiScope.launch {
            val startTime = System.currentTimeMillis()

            try {
                LoggingUtils.debug(TAG, "Executing templates synchronization...")
                val result = templateManager.syncAllTemplates()
                val duration = System.currentTimeMillis() - startTime

                if (result) {
                    LoggingUtils.debugSuccess(TAG, "templates sync (async)")
                    LoggingUtils.debug(TAG, "Templates sync completed in ${duration}ms")
                } else {
                    LoggingUtils.debugFailure(TAG, "templates sync (async)", "sync returned false")
                }

                future.complete(result)
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                LoggingUtils.error(TAG, "Error syncing templates (async): ${e.message}", e)
                LoggingUtils.debug(TAG, "Templates sync failed after ${duration}ms")
                future.completeExceptionally(e)
            }
        }

        return future
    }

    fun ensurePluginsDownloaded(): CompletableFuture<Boolean> {
        LoggingUtils.debugStart(TAG, "plugins download check (async)")

        val future = CompletableFuture<Boolean>()

        apiScope.launch {
            val startTime = System.currentTimeMillis()

            try {
                LoggingUtils.debug(TAG, "Ensuring all plugins are downloaded...")
                val result = pluginManager.ensureAllPluginsDownloaded()
                val duration = System.currentTimeMillis() - startTime

                if (result) {
                    LoggingUtils.debugSuccess(TAG, "plugins download check (async)")
                    LoggingUtils.debug(TAG, "Plugins download check completed in ${duration}ms")
                } else {
                    LoggingUtils.debugFailure(TAG, "plugins download check (async)", "download check returned false")
                }

                future.complete(result)
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                LoggingUtils.error(TAG, "Error ensuring plugins downloaded (async): ${e.message}", e)
                LoggingUtils.debug(TAG, "Plugins download check failed after ${duration}ms")
                future.completeExceptionally(e)
            }
        }

        return future
    }

    fun performFullUpdate(): CompletableFuture<UpdateResult> {
        LoggingUtils.debugStart(TAG, "full update (async)")

        val future = CompletableFuture<UpdateResult>()

        apiScope.launch {
            val startTime = System.currentTimeMillis()
            val operations = mutableMapOf<String, Boolean>()
            var overallSuccess = true

            try {
                LoggingUtils.debug(TAG, "Starting comprehensive update operation...")

                LoggingUtils.debug(TAG, "Phase 1: Updating server versions...")
                try {
                    val serverResult = serverVersionManager.updateAllVersions()
                    operations["server_versions"] = serverResult
                    if (!serverResult) overallSuccess = false
                    LoggingUtils.debug(TAG, "Server versions update: ${if (serverResult) "SUCCESS" else "FAILED"}")
                } catch (e: Exception) {
                    LoggingUtils.error(TAG, "Server versions update failed: ${e.message}", e)
                    operations["server_versions"] = false
                    overallSuccess = false
                }

                LoggingUtils.debug(TAG, "Phase 2: Updating plugins...")
                try {
                    val pluginResult = pluginManager.ensureAllPluginsDownloaded()
                    operations["plugins"] = pluginResult
                    if (!pluginResult) overallSuccess = false
                    LoggingUtils.debug(TAG, "Plugins update: ${if (pluginResult) "SUCCESS" else "FAILED"}")
                } catch (e: Exception) {
                    LoggingUtils.error(TAG, "Plugins update failed: ${e.message}", e)
                    operations["plugins"] = false
                    overallSuccess = false
                }

                LoggingUtils.debug(TAG, "Phase 3: Syncing templates...")
                try {
                    val templateResult = templateManager.syncAllTemplates()
                    operations["templates"] = templateResult
                    if (!templateResult) overallSuccess = false
                    LoggingUtils.debug(TAG, "Templates sync: ${if (templateResult) "SUCCESS" else "FAILED"}")
                } catch (e: Exception) {
                    LoggingUtils.error(TAG, "Templates sync failed: ${e.message}", e)
                    operations["templates"] = false
                    overallSuccess = false
                }

                val duration = System.currentTimeMillis() - startTime
                val result = UpdateResult(
                    success = overallSuccess,
                    duration = duration,
                    operations = operations.toMap(),
                    message = if (overallSuccess) "Full update completed successfully" else "Full update completed with errors"
                )

                LoggingUtils.debugStats(TAG, mapOf(
                    "overall_success" to overallSuccess,
                    "duration_ms" to duration,
                    "operations" to operations
                ))

                if (overallSuccess) {
                    LoggingUtils.debugSuccess(TAG, "full update (async)")
                } else {
                    LoggingUtils.debugFailure(TAG, "full update (async)", "one or more operations failed")
                }

                future.complete(result)

            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                LoggingUtils.error(TAG, "Critical error during full update: ${e.message}", e)

                val result = UpdateResult(
                    success = false,
                    duration = duration,
                    operations = operations.toMap(),
                    message = "Full update failed: ${e.message}"
                )

                future.complete(result)
            }
        }

        return future
    }

    suspend fun updateServerVersionsSync(): Boolean {
        LoggingUtils.debugStart(TAG, "server versions update (sync)")

        return try {
            val result = serverVersionManager.updateAllVersions()
            if (result) {
                LoggingUtils.debugSuccess(TAG, "server versions update (sync)")
            } else {
                LoggingUtils.debugFailure(TAG, "server versions update (sync)", "update returned false")
            }
            result
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error updating server versions (sync): ${e.message}", e)
            false
        }
    }

    suspend fun updatePluginsSync(): Boolean {
        LoggingUtils.debugStart(TAG, "plugins update (sync)")

        return try {
            val result = pluginManager.ensureAllPluginsDownloaded()
            if (result) {
                LoggingUtils.debugSuccess(TAG, "plugins update (sync)")
            } else {
                LoggingUtils.debugFailure(TAG, "plugins update (sync)", "update returned false")
            }
            result
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error updating plugins (sync): ${e.message}", e)
            false
        }
    }

    suspend fun syncTemplatesSync(): Boolean {
        LoggingUtils.debugStart(TAG, "templates sync (sync)")

        return try {
            val result = templateManager.syncAllTemplates()
            if (result) {
                LoggingUtils.debugSuccess(TAG, "templates sync (sync)")
            } else {
                LoggingUtils.debugFailure(TAG, "templates sync (sync)", "sync returned false")
            }
            result
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error syncing templates (sync): ${e.message}", e)
            false
        }
    }

    fun getAPIStats(): Map<String, Any> {
        LoggingUtils.debug(TAG, "API stats requested")

        return mapOf(
            "api_scope_active" to apiScope.isActive,
            "managers_initialized" to true,
            "available_operations" to listOf(
                "updateServerVersions",
                "updatePlugins",
                "syncTemplates",
                "ensurePluginsDownloaded",
                "performFullUpdate"
            )
        )
    }

    data class UpdateResult(
        val success: Boolean,
        val duration: Long,
        val operations: Map<String, Boolean>,
        val message: String
    ) {
        fun getSuccessfulOperations(): List<String> = operations.filter { it.value }.keys.toList()
        fun getFailedOperations(): List<String> = operations.filter { !it.value }.keys.toList()
        fun getSuccessRate(): Double = if (operations.isEmpty()) 0.0 else operations.values.count { it }.toDouble() / operations.size
    }
}