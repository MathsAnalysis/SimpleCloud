package eu.thesimplecloud.module.updater.thread

import eu.thesimplecloud.module.automanager.config.AutoManagerConfig
import eu.thesimplecloud.module.updater.manager.PluginUpdaterModule
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class UpdateScheduler(
    private val module: PluginUpdaterModule,
    private var config: AutoManagerConfig
) {

    private val schedulerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentJob: Job? = null

    fun start() {
        if (currentJob?.isActive == true) {
            return
        }

        currentJob = schedulerScope.launch {
            delay(30_000)

            while (isActive) {
                try {
                    runScheduledUpdate()
                    delay(parseInterval(config.updateInterval))
                } catch (e: Exception) {
                    delay(TimeUnit.MINUTES.toMillis(30))
                }
            }
        }
    }

    fun shutdown() {
        currentJob?.cancel()
        schedulerScope.cancel()
    }

    fun updateConfig(newConfig: AutoManagerConfig) {
        this.config = newConfig

        if (currentJob?.isActive == true) {
            currentJob?.cancel()
            start()
        }
    }

    private suspend fun runScheduledUpdate() {
        try {
            if (config.enableServerVersionUpdates) {
                module.getServerVersionManager().updateAllVersions()
            }

            if (config.enablePluginUpdates) {
                module.getPluginManager().updateAllPlugins()
            }

            if (config.enableTemplateSync) {
                module.getTemplateManager().syncAllTemplates()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseInterval(interval: String): Long {
        val regex = Regex("(\\d+)([smhd])")
        val match = regex.find(interval.lowercase())
            ?: return TimeUnit.HOURS.toMillis(6)

        val value = match.groupValues[1].toLongOrNull() ?: 6
        return when (match.groupValues[2]) {
            "s" -> TimeUnit.SECONDS.toMillis(value)
            "m" -> TimeUnit.MINUTES.toMillis(value)
            "h" -> TimeUnit.HOURS.toMillis(value)
            "d" -> TimeUnit.DAYS.toMillis(value)
            else -> TimeUnit.HOURS.toMillis(value)
        }
    }

    suspend fun forceUpdate(): Boolean {
        return try {
            runScheduledUpdate()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getNextUpdateTime(): Long {
        return System.currentTimeMillis() + parseInterval(config.updateInterval)
    }

    fun isRunning(): Boolean {
        return currentJob?.isActive == true
    }
}