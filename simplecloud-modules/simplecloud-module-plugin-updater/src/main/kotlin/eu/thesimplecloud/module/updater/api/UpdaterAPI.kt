package eu.thesimplecloud.module.updater.api

import eu.thesimplecloud.module.updater.manager.PluginManager
import eu.thesimplecloud.module.updater.manager.ServerVersionManager
import eu.thesimplecloud.module.updater.manager.TemplateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture

class UpdaterAPI(
    private val serverVersionManager: ServerVersionManager,
    private val pluginManager: PluginManager,
    private val templateManager: TemplateManager
) {

    private val apiScope = CoroutineScope(Dispatchers.IO)

    fun getServerVersionManager(): ServerVersionManager = serverVersionManager

    fun getPluginManager(): PluginManager = pluginManager

    fun getTemplateManager(): TemplateManager = templateManager

    fun updateServerVersions(): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()

        apiScope.launch {
            try {
                val result = serverVersionManager.updateAllVersions()
                future.complete(result)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }

        return future
    }

    fun updatePlugins(): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()

        apiScope.launch {
            try {
                val result = pluginManager.updateAllPlugins()
                future.complete(result)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }

        return future
    }

    fun syncTemplates(): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()

        apiScope.launch {
            try {
                val result = templateManager.syncAllTemplates()
                future.complete(result)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }

        return future
    }

    fun ensurePluginsDownloaded(): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()

        apiScope.launch {
            try {
                val result = pluginManager.ensureAllPluginsDownloaded()
                future.complete(result)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }

        return future
    }
}