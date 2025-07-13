package eu.thesimplecloud.module.updater.updater.service

import eu.thesimplecloud.module.updater.config.UpdaterConfig
import eu.thesimplecloud.module.updater.manager.*
import kotlinx.coroutines.*

class UpdateService(
    private var config: UpdaterConfig
) {
    private val serverJarManager = ServerJarManager()
    private val pluginManager = PluginManager()
    private val templateSyncManager = TemplateSyncManager()
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    suspend fun runFullUpdate() = serviceScope.async {
        println("[UpdateService] === INIZIO AGGIORNAMENTO COMPLETO ===")
        
        val results = mutableMapOf<String, Boolean>()
        
        // Aggiorna server JARs
        if (config.serverJars.enabled) {
            println("[UpdateService] Aggiornamento server JARs...")
            val serverResults = serverJarManager.updateAll(config.serverJars)
            results.putAll(serverResults)
            
            // Sincronizza con template
            templateSyncManager.syncServerJars()
        }
        
        // Aggiorna plugins
        if (config.plugins.enabled) {
            println("[UpdateService] Aggiornamento plugins...")
            val pluginResults = pluginManager.updateAll(config.plugins)
            results.putAll(pluginResults)
            
            // Sincronizza con template
            if (config.plugins.syncToTemplates) {
                templateSyncManager.syncPlugins()
            }
        }
        
        // Log risultati
        println("[UpdateService] === RISULTATI AGGIORNAMENTO ===")
        results.forEach { (name, success) ->
            println("[UpdateService] - $name: ${if (success) "SUCCESS ✓" else "FAILED ✗"}")
        }
        
        println("[UpdateService] === AGGIORNAMENTO COMPLETATO ===")
    }.await()
    
    fun updateConfig(newConfig: UpdaterConfig) {
        config = newConfig
    }
}