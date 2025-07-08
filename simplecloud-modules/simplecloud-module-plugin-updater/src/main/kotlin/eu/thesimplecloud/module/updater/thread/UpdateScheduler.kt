package eu.thesimplecloud.module.updater.thread

import eu.thesimplecloud.module.updater.bootstrap.PluginUpdaterModule
import eu.thesimplecloud.module.updater.config.AutoManagerConfig
import eu.thesimplecloud.module.updater.utils.LoggingUtils
import kotlinx.coroutines.*
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

class UpdateScheduler(
    private val module: PluginUpdaterModule,
    private var config: AutoManagerConfig
) {
    companion object {
        private const val TAG = "UpdateScheduler"
        private const val INITIAL_DELAY_SECONDS = 30L
        private const val ERROR_RETRY_MINUTES = 30L
        private const val DEFAULT_INTERVAL_HOURS = 6L
    }

    private val schedulerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentJob: Job? = null
    private var isRunning = false
    private var nextUpdateTime: Long = 0L
    private var useTimeBasedScheduling = false

    init {
        LoggingUtils.init(TAG, "Initializing UpdateScheduler...")
        logSchedulerConfiguration()
    }

    private fun logSchedulerConfiguration() {
        LoggingUtils.debugConfig(TAG, "update_interval", config.updateInterval)
        LoggingUtils.debugConfig(TAG, "update_time", config.updateTime)
        LoggingUtils.debugConfig(TAG, "enable_automation", config.enableAutomation)
        LoggingUtils.debugConfig(TAG, "enable_server_version_updates", config.enableServerVersionUpdates)
        LoggingUtils.debugConfig(TAG, "enable_plugin_updates", config.enablePluginUpdates)
        LoggingUtils.debugConfig(TAG, "enable_template_sync", config.enableTemplateSync)
    }

    fun start() {
        LoggingUtils.debugStart(TAG, "scheduler startup")

        if (currentJob?.isActive == true) {
            LoggingUtils.debug(TAG, "Scheduler is already running, skipping start")
            return
        }

        if (!config.enableAutomation) {
            LoggingUtils.info(TAG, "Automation is disabled, scheduler will not start")
            return
        }

        // Determina quale tipo di scheduling usare
        useTimeBasedScheduling = config.updateTime.isNotEmpty()

        if (useTimeBasedScheduling) {
            LoggingUtils.info(TAG, "Using time-based scheduling: ${config.updateTime}")
            startTimeBasedScheduling()
        } else {
            LoggingUtils.info(TAG, "Using interval-based scheduling: ${config.updateInterval}")
            startIntervalBasedScheduling()
        }

        LoggingUtils.debugSuccess(TAG, "scheduler startup")
    }

    private fun startTimeBasedScheduling() {
        try {
            val updateTime = LocalTime.parse(config.updateTime)
            LoggingUtils.debug(TAG, "Parsed update time: $updateTime")

            currentJob = schedulerScope.launch {
                try {
                    isRunning = true
                    LoggingUtils.info(TAG, "Time-based scheduler started with ${INITIAL_DELAY_SECONDS}s initial delay")

                    delay(INITIAL_DELAY_SECONDS * 1000)
                    LoggingUtils.debug(TAG, "Initial delay completed, starting time-based update cycle")

                    while (isActive && isRunning) {
                        try {
                            // Calcola il prossimo aggiornamento
                            val now = LocalDateTime.now()
                            var nextUpdate = now.with(updateTime)

                            // Se l'orario è già passato oggi, pianifica per domani
                            if (nextUpdate.isBefore(now) || nextUpdate.isEqual(now)) {
                                nextUpdate = nextUpdate.plusDays(1)
                            }

                            val delayMillis = now.until(nextUpdate, ChronoUnit.MILLIS)
                            nextUpdateTime = System.currentTimeMillis() + delayMillis

                            LoggingUtils.info(TAG, "Next update scheduled at: $nextUpdate (in ${delayMillis / 1000 / 60} minutes)")

                            // Aspetta fino all'orario programmato
                            delay(delayMillis)

                            // Esegui l'aggiornamento
                            if (isActive && isRunning) {
                                LoggingUtils.info(TAG, "Executing scheduled update at ${LocalDateTime.now()}")
                                runScheduledUpdate()
                            }

                        } catch (e: CancellationException) {
                            LoggingUtils.debug(TAG, "Time-based update cycle cancelled")
                            break
                        } catch (e: Exception) {
                            LoggingUtils.error(TAG, "Error in time-based update cycle: ${e.message}", e)
                            LoggingUtils.warn(TAG, "Retrying in $ERROR_RETRY_MINUTES minutes...")
                            delay(TimeUnit.MINUTES.toMillis(ERROR_RETRY_MINUTES))
                        }
                    }
                } finally {
                    isRunning = false
                    LoggingUtils.info(TAG, "Time-based scheduler stopped")
                }
            }
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error starting time-based scheduling: ${e.message}", e)
            LoggingUtils.warn(TAG, "Falling back to interval-based scheduling")
            startIntervalBasedScheduling()
        }
    }

    private fun startIntervalBasedScheduling() {
        val intervalMillis = parseInterval(config.updateInterval)
        LoggingUtils.debug(TAG, "Parsed update interval: ${intervalMillis}ms (${intervalMillis / 1000 / 60} minutes)")

        currentJob = schedulerScope.launch {
            try {
                isRunning = true
                LoggingUtils.info(TAG, "Interval-based scheduler started with ${INITIAL_DELAY_SECONDS}s initial delay")

                delay(INITIAL_DELAY_SECONDS * 1000)
                LoggingUtils.debug(TAG, "Initial delay completed, starting interval-based update cycle")

                while (isActive && isRunning) {
                    try {
                        nextUpdateTime = System.currentTimeMillis() + intervalMillis
                        LoggingUtils.debug(TAG, "Next update scheduled for: ${java.time.Instant.ofEpochMilli(nextUpdateTime)}")

                        runScheduledUpdate()

                        LoggingUtils.debug(TAG, "Waiting ${intervalMillis / 1000 / 60} minutes until next update")
                        delay(intervalMillis)

                    } catch (e: CancellationException) {
                        LoggingUtils.debug(TAG, "Interval-based update cycle cancelled")
                        break
                    } catch (e: Exception) {
                        LoggingUtils.error(TAG, "Error in interval-based update cycle: ${e.message}", e)
                        LoggingUtils.warn(TAG, "Retrying in $ERROR_RETRY_MINUTES minutes...")
                        delay(TimeUnit.MINUTES.toMillis(ERROR_RETRY_MINUTES))
                    }
                }
            } finally {
                isRunning = false
                LoggingUtils.info(TAG, "Interval-based scheduler stopped")
            }
        }
    }

    fun shutdown() {
        LoggingUtils.debugStart(TAG, "scheduler shutdown")

        try {
            isRunning = false

            currentJob?.let { job ->
                LoggingUtils.debug(TAG, "Cancelling current update job...")
                job.cancel()

                runBlocking {
                    try {
                        withTimeout(5000) {
                            job.join()
                        }
                        LoggingUtils.debug(TAG, "Update job cancelled gracefully")
                    } catch (e: TimeoutCancellationException) {
                        LoggingUtils.warn(TAG, "Update job cancellation timed out, forcing shutdown")
                    }
                }
            }

            LoggingUtils.debug(TAG, "Cancelling scheduler scope...")
            schedulerScope.cancel()

            LoggingUtils.debugSuccess(TAG, "scheduler shutdown")
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Error during scheduler shutdown: ${e.message}", e)
        }
    }

    fun updateConfig(newConfig: AutoManagerConfig) {
        LoggingUtils.debug(TAG, "Updating scheduler configuration...")

        val oldInterval = config.updateInterval
        val oldTime = config.updateTime
        val oldAutomation = config.enableAutomation

        this.config = newConfig

        LoggingUtils.debugConfig(TAG, "old_interval", oldInterval)
        LoggingUtils.debugConfig(TAG, "new_interval", newConfig.updateInterval)
        LoggingUtils.debugConfig(TAG, "old_time", oldTime)
        LoggingUtils.debugConfig(TAG, "new_time", newConfig.updateTime)
        LoggingUtils.debugConfig(TAG, "old_automation", oldAutomation)
        LoggingUtils.debugConfig(TAG, "new_automation", newConfig.enableAutomation)

        // Riavvia il scheduler se la configurazione è cambiata
        val scheduleChanged = oldInterval != newConfig.updateInterval ||
                oldTime != newConfig.updateTime ||
                oldAutomation != newConfig.enableAutomation

        if (currentJob?.isActive == true && scheduleChanged) {
            LoggingUtils.debug(TAG, "Configuration changed, restarting scheduler...")

            currentJob?.cancel()

            if (newConfig.enableAutomation) {
                start()
            } else {
                LoggingUtils.info(TAG, "Automation disabled, scheduler stopped")
            }
        } else if (!oldAutomation && newConfig.enableAutomation) {
            LoggingUtils.debug(TAG, "Automation enabled, starting scheduler...")
            start()
        }

        LoggingUtils.debug(TAG, "Scheduler configuration updated")
    }

    private suspend fun runScheduledUpdate() {
        LoggingUtils.debugStart(TAG, "scheduled update execution")

        val startTime = System.currentTimeMillis()
        var success = true
        val operationsPerformed = mutableListOf<String>()

        try {
            LoggingUtils.info(TAG, "=== SCHEDULED UPDATE STARTED ===")

            if (config.enableServerVersionUpdates) {
                LoggingUtils.debug(TAG, "Executing server version updates...")
                try {
                    val result = module.getServerVersionManager().updateAllVersions()
                    if (result) {
                        LoggingUtils.debug(TAG, "Server version updates completed successfully")
                        operationsPerformed.add("server_versions")
                    } else {
                        LoggingUtils.warn(TAG, "Server version updates failed")
                        success = false
                    }
                } catch (e: Exception) {
                    LoggingUtils.error(TAG, "Error during server version updates: ${e.message}", e)
                    success = false
                }
            } else {
                LoggingUtils.debug(TAG, "Server version updates disabled")
            }

            if (config.enablePluginUpdates) {
                LoggingUtils.debug(TAG, "Executing plugin updates...")
                try {
                    val result = module.getPluginManager().ensureAllPluginsDownloaded()
                    if (result) {
                        LoggingUtils.debug(TAG, "Plugin updates completed successfully")
                        operationsPerformed.add("plugins")
                    } else {
                        LoggingUtils.warn(TAG, "Plugin updates failed")
                        success = false
                    }
                } catch (e: Exception) {
                    LoggingUtils.error(TAG, "Error during plugin updates: ${e.message}", e)
                    success = false
                }
            } else {
                LoggingUtils.debug(TAG, "Plugin updates disabled")
            }

            if (config.enableTemplateSync) {
                LoggingUtils.debug(TAG, "Executing template synchronization...")
                try {
                    val syncResult = module.getTemplateManager().syncAllTemplates()
                    val staticResult = module.getTemplateManager().syncStaticServersOnRestart()

                    if (syncResult && staticResult) {
                        LoggingUtils.debug(TAG, "Template synchronization completed successfully")
                        operationsPerformed.add("templates")
                    } else {
                        LoggingUtils.warn(TAG, "Template synchronization failed (sync: $syncResult, static: $staticResult)")
                        success = false
                    }
                } catch (e: Exception) {
                    LoggingUtils.error(TAG, "Error during template synchronization: ${e.message}", e)
                    success = false
                }
            } else {
                LoggingUtils.debug(TAG, "Template synchronization disabled")
            }

            val duration = System.currentTimeMillis() - startTime
            val stats = mapOf(
                "duration_ms" to duration,
                "duration_seconds" to duration / 1000,
                "operations_performed" to operationsPerformed,
                "success" to success
            )

            LoggingUtils.debugStats(TAG, stats)

            val resultMessage = if (success) "SUCCESS" else "PARTIAL FAILURE"
            LoggingUtils.info(TAG, "=== SCHEDULED UPDATE COMPLETED: $resultMessage (${duration}ms) ===")

            if (success) {
                LoggingUtils.debugSuccess(TAG, "scheduled update execution")
            } else {
                LoggingUtils.debugFailure(TAG, "scheduled update execution", "one or more operations failed")
            }

        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Critical error during scheduled update: ${e.message}", e)
        }
    }

    private fun parseInterval(interval: String): Long {
        LoggingUtils.debug(TAG, "Parsing interval: $interval")

        val regex = Regex("(\\d+)([smhd])")
        val match = regex.find(interval.lowercase())

        if (match == null) {
            LoggingUtils.warn(TAG, "Invalid interval format: $interval, using default ${DEFAULT_INTERVAL_HOURS}h")
            return TimeUnit.HOURS.toMillis(DEFAULT_INTERVAL_HOURS)
        }

        val value = match.groupValues[1].toLongOrNull()
        if (value == null) {
            LoggingUtils.warn(TAG, "Invalid interval value in: $interval, using default ${DEFAULT_INTERVAL_HOURS}h")
            return TimeUnit.HOURS.toMillis(DEFAULT_INTERVAL_HOURS)
        }

        val unit = match.groupValues[2]
        val millis = when (unit) {
            "s" -> TimeUnit.SECONDS.toMillis(value)
            "m" -> TimeUnit.MINUTES.toMillis(value)
            "h" -> TimeUnit.HOURS.toMillis(value)
            "d" -> TimeUnit.DAYS.toMillis(value)
            else -> {
                LoggingUtils.warn(TAG, "Invalid interval unit in: $interval, using default ${DEFAULT_INTERVAL_HOURS}h")
                TimeUnit.HOURS.toMillis(DEFAULT_INTERVAL_HOURS)
            }
        }

        LoggingUtils.debug(TAG, "Parsed interval: ${value}${unit} = ${millis}ms")
        return millis
    }

    suspend fun forceUpdate(): Boolean {
        LoggingUtils.info(TAG, "Force update requested")

        return try {
            LoggingUtils.debug(TAG, "Executing force update...")
            runScheduledUpdate()
            LoggingUtils.info(TAG, "Force update completed")
            true
        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Force update failed: ${e.message}", e)
            false
        }
    }

    fun getNextUpdateTime(): Long {
        return if (isRunning && nextUpdateTime > 0) {
            nextUpdateTime
        } else if (useTimeBasedScheduling && config.updateTime.isNotEmpty()) {
            // Calcola il prossimo aggiornamento basato sull'orario
            try {
                val updateTime = LocalTime.parse(config.updateTime)
                val now = LocalDateTime.now()
                var nextUpdate = now.with(updateTime)

                if (nextUpdate.isBefore(now) || nextUpdate.isEqual(now)) {
                    nextUpdate = nextUpdate.plusDays(1)
                }

                val delayMillis = now.until(nextUpdate, ChronoUnit.MILLIS)
                System.currentTimeMillis() + delayMillis
            } catch (e: Exception) {
                LoggingUtils.error(TAG, "Error calculating next update time: ${e.message}", e)
                System.currentTimeMillis() + parseInterval(config.updateInterval)
            }
        } else {
            System.currentTimeMillis() + parseInterval(config.updateInterval)
        }
    }

    fun isRunning(): Boolean {
        val jobActive = currentJob?.isActive == true
        LoggingUtils.debug(TAG, "Scheduler status - isRunning: $isRunning, jobActive: $jobActive, useTimeBasedScheduling: $useTimeBasedScheduling")
        return isRunning && jobActive
    }

    fun getStats(): Map<String, Any> {
        val nextUpdate = getNextUpdateTime()
        val timeUntilNext = if (nextUpdate > System.currentTimeMillis()) {
            nextUpdate - System.currentTimeMillis()
        } else 0L

        return mapOf(
            "is_running" to isRunning(),
            "next_update_time" to nextUpdate,
            "next_update_formatted" to java.time.Instant.ofEpochMilli(nextUpdate).toString(),
            "time_until_next_ms" to timeUntilNext,
            "time_until_next_minutes" to timeUntilNext / 1000 / 60,
            "update_interval" to config.updateInterval,
            "update_time" to config.updateTime,
            "automation_enabled" to config.enableAutomation,
            "job_active" to (currentJob?.isActive == true),
            "scheduling_type" to if (useTimeBasedScheduling) "time-based" else "interval-based"
        )
    }
}