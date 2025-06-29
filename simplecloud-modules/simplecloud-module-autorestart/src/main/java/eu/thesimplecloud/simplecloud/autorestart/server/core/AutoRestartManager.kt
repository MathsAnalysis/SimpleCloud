package eu.thesimplecloud.simplecloud.autorestart.server.core

import eu.thesimplecloud.api.CloudAPI
import eu.thesimplecloud.api.service.ICloudService
import eu.thesimplecloud.api.servicegroup.ICloudServiceGroup
import eu.thesimplecloud.simplecloud.autorestart.config.AutoRestartConfig
import eu.thesimplecloud.simplecloud.autorestart.config.RestartGroupConfig
import eu.thesimplecloud.simplecloud.autorestart.config.RestartTargetConfig
import eu.thesimplecloud.simplecloud.autorestart.log.RestartLog
import eu.thesimplecloud.simplecloud.autorestart.server.group.RestartGroup
import eu.thesimplecloud.simplecloud.autorestart.server.structure.RestartTarget
import eu.thesimplecloud.simplecloud.autorestart.server.structure.RestartTargetType
import eu.thesimplecloud.simplecloud.autorestart.type.RestartStatus
import kotlinx.coroutines.*
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class AutoRestartManager(
    private var config: AutoRestartConfig
) {
    private val restartGroups = ConcurrentHashMap<String, RestartGroup>()
    private val restartLogs = mutableListOf<RestartLog>()
    private val schedulerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var scheduledJobs = mutableSetOf<Job>()
    private var running = false

    init {
        loadFromConfig()
    }

    fun updateConfig(newConfig: AutoRestartConfig) {
        this.config = newConfig
        loadFromConfig()
        if (running) {
            setupScheduler()
        }
    }

    private fun loadFromConfig() {
        restartGroups.clear()

        for (groupConfig in config.restartGroups) {
            val targets = groupConfig.targets.map { targetConfig ->
                RestartTarget(
                    targetConfig.name,
                    RestartTargetType.valueOf(targetConfig.type),
                    targetConfig.priority,
                    targetConfig.restartTimeout,
                    targetConfig.healthCheckTimeout
                )
            }.toMutableList()

            restartGroups[groupConfig.name] = RestartGroup(
                groupConfig.name,
                targets,
                groupConfig.restartTime,
                groupConfig.enabled
            )
        }
    }

    private fun syncConfigGroup(restartGroup: RestartGroup) {
        val groupConfig = config.restartGroups.find { it.name == restartGroup.name }
        if (groupConfig != null) {
            groupConfig.restartTime = restartGroup.restartTime
            groupConfig.enabled = restartGroup.enabled

            groupConfig.targets.clear()
            groupConfig.targets.addAll(
                restartGroup.targets.map { target ->
                    RestartTargetConfig(
                        target.name,
                        target.type.name,
                        target.priority,
                        target.restartTimeout,
                        target.healthCheckTimeout
                    )
                }
            )
        }
    }

    fun addGroup(name: String, restartTime: String, enabled: Boolean = true): Boolean {
        if (restartGroups.containsKey(name)) {
            return false
        }

        if (!isValidTimeFormat(restartTime)) {
            return false
        }

        restartGroups[name] = RestartGroup(name, mutableListOf(), restartTime, enabled)
        config.restartGroups.add(RestartGroupConfig(name, restartTime, enabled, mutableListOf()))
        return true
    }

    fun removeGroup(name: String): Boolean {
        if (!restartGroups.containsKey(name)) {
            return false
        }

        restartGroups.remove(name)
        config.restartGroups.removeIf { it.name == name }
        return true
    }

    fun addTargetToGroup(groupName: String, target: RestartTarget): Boolean {
        val group = restartGroups[groupName] ?: return false

        val targetExists = group.targets.any { it.name == target.name }
        if (targetExists) {
            return false
        }

        group.addTarget(target)
        syncConfigGroup(group)
        return true
    }

    fun removeTargetFromGroup(groupName: String, targetName: String): Boolean {
        val group = restartGroups[groupName] ?: return false

        val removed = group.removeTarget(targetName)
        if (removed) {
            syncConfigGroup(group)
        }
        return removed
    }

    fun setGroupTime(groupName: String, restartTime: String): Boolean {
        val group = restartGroups[groupName] ?: return false

        if (!isValidTimeFormat(restartTime)) {
            return false
        }

        group.restartTime = restartTime
        syncConfigGroup(group)
        return true
    }

    fun enableGroup(groupName: String, enabled: Boolean = true): Boolean {
        val group = restartGroups[groupName] ?: return false

        group.enabled = enabled
        syncConfigGroup(group)
        return true
    }

    private fun isValidTimeFormat(time: String): Boolean {
        return try {
            LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm"))
            true
        } catch (e: DateTimeParseException) {
            false
        }
    }

    suspend fun executeServiceRestart(service: ICloudService): Boolean = withContext(Dispatchers.IO) {
        try {
            service.shutdown()

            delay(5000)

            val serviceGroup = service.getServiceGroup()
            serviceGroup.startNewService()

            return@withContext true
        } catch (e: Exception) {
            return@withContext false
        }
    }

    suspend fun executeGroupRestart(serviceGroup: ICloudServiceGroup): Boolean = withContext(Dispatchers.IO) {
        try {
            if (serviceGroup.isStatic()) {
                val services = serviceGroup.getAllServices()
                    .filter { it.isStartingOrVisible() }
                    .sortedByDescending { it.getServiceNumber() }

                services.forEach { service ->
                    service.shutdown()
                    delay(3000)
                }

                delay(15000)

                val servicesToRestart = services.sortedBy { it.getServiceNumber() }
                servicesToRestart.forEach { _ ->
                    serviceGroup.startNewService()
                    delay(5000)
                }
            } else {
                val services = serviceGroup.getAllServices()
                    .filter { it.isStartingOrVisible() }
                    .sortedByDescending { it.getServiceNumber() }

                services.forEach { service ->
                    service.shutdown()
                    delay(2000)
                }

                delay(10000)
            }

            return@withContext true
        } catch (e: Exception) {
            return@withContext false
        }
    }

    fun restartGroup(groupName: String) {
        val group = restartGroups[groupName]
        if (group == null || !group.enabled) {
            return
        }

        schedulerScope.launch {
            val targetsByPriority = group.targets
                .sortedWith(compareByDescending<RestartTarget> { it.priority }.thenBy { it.name })
                .groupBy { it.priority }

            val maxPriority = targetsByPriority.keys.maxOrNull() ?: return@launch

            for (priority in maxPriority downTo (targetsByPriority.keys.minOrNull() ?: 0)) {
                val targetsAtPriority = targetsByPriority[priority] ?: continue

                val groupTargets = targetsAtPriority.filter { it.type == RestartTargetType.GROUP }
                val serviceTargets = targetsAtPriority.filter { it.type == RestartTargetType.SERVICE }

                for (target in groupTargets) {
                    val logEntry = RestartLog(
                        LocalDateTime.now(),
                        target.name,
                        groupName,
                        RestartStatus.RUNNING
                    )
                    restartLogs.add(logEntry)

                    val serviceGroup = CloudAPI.instance.getCloudServiceGroupManager()
                        .getServiceGroupByName(target.name)

                    val success = if (serviceGroup != null) {
                        val groupStartPriority = serviceGroup.getStartPriority()
                        executeGroupRestart(serviceGroup)
                    } else {
                        false
                    }

                    logEntry.status = if (success) RestartStatus.SUCCESS else RestartStatus.FAILED
                    if (!success) {
                        logEntry.errorMessage = "Group restart failed or group not found"
                    }

                    if (success && targetsAtPriority.size > 1) {
                        delay(10000)
                    }
                }

                for (target in serviceTargets) {
                    val logEntry = RestartLog(
                        LocalDateTime.now(),
                        target.name,
                        groupName,
                        RestartStatus.RUNNING
                    )
                    restartLogs.add(logEntry)

                    val service = CloudAPI.instance.getCloudServiceManager()
                        .getCloudServiceByName(target.name)

                    val success = if (service != null) {
                        executeServiceRestart(service)
                    } else {
                        false
                    }

                    logEntry.status = if (success) RestartStatus.SUCCESS else RestartStatus.FAILED
                    if (!success) {
                        logEntry.errorMessage = "Service restart failed or service not found"
                    }

                    if (success && serviceTargets.indexOf(target) < serviceTargets.size - 1) {
                        delay(5000)
                    }
                }

                if (priority > (targetsByPriority.keys.minOrNull() ?: 0)) {
                    delay(20000)
                }
            }
        }
    }

    fun setupScheduler() {
        clearScheduledTasks()

        for (group in restartGroups.values) {
            if (group.enabled) {
                val restartTime = LocalTime.parse(group.restartTime, DateTimeFormatter.ofPattern("HH:mm"))

                val job = schedulerScope.launch {
                    while (isActive) {
                        val initialDelay = calculateInitialDelay(restartTime)
                        delay(initialDelay.seconds)

                        if (isActive) {
                            restartGroup(group.name)
                        }

                        delay(1.days)
                    }
                }

                scheduledJobs.add(job)
            }
        }
    }

    private fun calculateInitialDelay(targetTime: LocalTime): Long {
        val now = LocalDateTime.now()
        var nextRun = now.toLocalDate().atTime(targetTime)

        if (nextRun.isBefore(now) || nextRun.isEqual(now)) {
            nextRun = nextRun.plusDays(1)
        }

        return java.time.Duration.between(now, nextRun).seconds
    }

    private fun clearScheduledTasks() {
        scheduledJobs.forEach { it.cancel() }
        scheduledJobs.clear()
    }

    fun startScheduler() {
        if (running) {
            return
        }

        setupScheduler()
        running = true
    }

    fun stopScheduler() {
        running = false
        clearScheduledTasks()
    }

    fun getStatus(): Map<String, Any?> {
        return mapOf(
            "running" to running,
            "groups_count" to restartGroups.size,
            "enabled_groups" to restartGroups.values.count { it.enabled },
            "total_targets" to restartGroups.values.sumOf { it.targets.size },
            "next_restart" to getNextRestartTime()
        )
    }

    fun getNextRestartTime(): String? {
        if (restartGroups.isEmpty()) {
            return null
        }

        val now = LocalDateTime.now()
        var nextRestart: LocalDateTime? = null
        var nextGroupName: String? = null

        for (group in restartGroups.values) {
            if (!group.enabled) {
                continue
            }

            val restartTime = LocalTime.parse(group.restartTime, DateTimeFormatter.ofPattern("HH:mm"))
            var nextRun = now.toLocalDate().atTime(restartTime)

            if (nextRun.isBefore(now) || nextRun.isEqual(now)) {
                nextRun = nextRun.plusDays(1)
            }

            if (nextRestart == null || nextRun.isBefore(nextRestart)) {
                nextRestart = nextRun
                nextGroupName = group.name
            }
        }

        return nextRestart?.let {
            "${it.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))} (group: $nextGroupName)"
        }
    }

    fun getRecentLogs(limit: Int = 50): List<Map<String, Any?>> {
        return restartLogs
            .sortedByDescending { it.timestamp }
            .take(limit)
            .map { log ->
                mapOf(
                    "timestamp" to log.timestamp.toString(),
                    "target_name" to log.serverName,
                    "group_name" to log.groupName,
                    "status" to log.status.name.lowercase(),
                    "error_message" to log.errorMessage
                )
            }
    }

    fun listGroups(): Map<String, Map<String, Any>> {
        return restartGroups.mapValues { (_, group) ->
            mapOf(
                "restart_time" to group.restartTime,
                "enabled" to group.enabled,
                "targets" to group.targets.map { target ->
                    mapOf(
                        "name" to target.name,
                        "type" to target.type.name.lowercase(),
                        "priority" to target.priority
                    )
                }
            )
        }
    }

    fun shutdown() {
        stopScheduler()
        schedulerScope.cancel()
    }
}