package eu.thesimplecloud.module.updater.manager

import eu.thesimplecloud.api.service.version.ServiceVersion
import eu.thesimplecloud.api.service.version.type.ServiceAPIType
import eu.thesimplecloud.module.updater.utils.LoggingUtils

class ServiceVersionRegistrar {

    companion object {
        private const val TAG = "ServiceVersionRegistrar"
    }

    private val registeredVersions = mutableListOf<ServiceVersion>()

    init {
        LoggingUtils.init(TAG, "ServiceVersionRegistrar initialized")
    }

    fun registerLeafVersions(versions: List<ServerVersionManager.VersionDownload>) {
        LoggingUtils.debugStart(TAG, "registering Leaf versions")
        LoggingUtils.debug(TAG, "Registering ${versions.size} Leaf versions...")

        var successCount = 0
        var failureCount = 0

        versions.forEach { version ->
            try {
                val versionName = "LEAF_${version.version.replace(".", "_")}"
                LoggingUtils.debug(TAG, "Registering Leaf version: $versionName from ${version.link}")

                val serviceVersion = ServiceVersion(
                    name = versionName,
                    serviceAPIType = ServiceAPIType.SPIGOT,
                    downloadURL = version.link,
                    isPaperClip = true
                )

                registeredVersions.add(serviceVersion)
                successCount++

                LoggingUtils.debug(TAG, "Successfully registered Leaf version: $versionName")
            } catch (e: Exception) {
                LoggingUtils.error(TAG, "Failed to register Leaf version ${version.version}: ${e.message}", e)
                failureCount++
            }
        }

        val stats = mapOf(
            "total_versions" to versions.size,
            "successful" to successCount,
            "failed" to failureCount
        )
        LoggingUtils.debugStats(TAG, stats)

        if (failureCount == 0) {
            LoggingUtils.debugSuccess(TAG, "registering Leaf versions")
        } else {
            LoggingUtils.debugFailure(TAG, "registering Leaf versions", "$failureCount versions failed")
        }

        LoggingUtils.info(TAG, "Registered $successCount/$${versions.size} Leaf versions")
    }

    fun registerVelocityCTDVersion(version: ServerVersionManager.VersionDownload) {
        LoggingUtils.debugStart(TAG, "registering VelocityCTD version")

        try {
            val versionName = "VELOCITYCTD_${version.version.replace(".", "_").replace("-", "_")}"
            LoggingUtils.debug(TAG, "Registering VelocityCTD version: $versionName from ${version.link}")

            val serviceVersion = ServiceVersion(
                name = versionName,
                serviceAPIType = ServiceAPIType.VELOCITY,
                downloadURL = version.link,
                isPaperClip = false
            )

            registeredVersions.add(serviceVersion)

            LoggingUtils.debugSuccess(TAG, "registering VelocityCTD version")
            LoggingUtils.debug(TAG, "Successfully registered VelocityCTD version: $versionName")

        } catch (e: Exception) {
            LoggingUtils.error(TAG, "Failed to register VelocityCTD version ${version.version}: ${e.message}", e)
        }
    }

    fun registerPaperVersions(versions: List<ServerVersionManager.VersionDownload>) {
        LoggingUtils.debugStart(TAG, "registering Paper versions")
        LoggingUtils.debug(TAG, "Registering ${versions.size} Paper versions...")

        var successCount = 0
        var failureCount = 0

        versions.forEach { version ->
            try {
                val versionName = "PAPER_${version.version.replace(".", "_")}"
                LoggingUtils.debug(TAG, "Registering Paper version: $versionName from ${version.link}")

                val serviceVersion = ServiceVersion(
                    name = versionName,
                    serviceAPIType = ServiceAPIType.SPIGOT,
                    downloadURL = version.link,
                    isPaperClip = true
                )

                registeredVersions.add(serviceVersion)
                successCount++

                LoggingUtils.debug(TAG, "Successfully registered Paper version: $versionName")
            } catch (e: Exception) {
                LoggingUtils.error(TAG, "Failed to register Paper version ${version.version}: ${e.message}", e)
                failureCount++
            }
        }

        val stats = mapOf(
            "total_versions" to versions.size,
            "successful" to successCount,
            "failed" to failureCount
        )
        LoggingUtils.debugStats(TAG, stats)

        if (failureCount == 0) {
            LoggingUtils.debugSuccess(TAG, "registering Paper versions")
        } else {
            LoggingUtils.debugFailure(TAG, "registering Paper versions", "$failureCount versions failed")
        }

        LoggingUtils.info(TAG, "Registered $successCount/${versions.size} Paper versions")
    }

    fun registerVelocityVersions(versions: List<ServerVersionManager.VersionDownload>) {
        LoggingUtils.debugStart(TAG, "registering Velocity versions")
        LoggingUtils.debug(TAG, "Registering ${versions.size} Velocity versions...")

        var successCount = 0
        var failureCount = 0

        versions.forEach { version ->
            try {
                val versionName = "VELOCITY_${version.version.replace(".", "_").replace("-", "_")}"
                LoggingUtils.debug(TAG, "Registering Velocity version: $versionName from ${version.link}")

                val serviceVersion = ServiceVersion(
                    name = versionName,
                    serviceAPIType = ServiceAPIType.VELOCITY,
                    downloadURL = version.link,
                    isPaperClip = false
                )

                registeredVersions.add(serviceVersion)
                successCount++

                LoggingUtils.debug(TAG, "Successfully registered Velocity version: $versionName")
            } catch (e: Exception) {
                LoggingUtils.error(TAG, "Failed to register Velocity version ${version.version}: ${e.message}", e)
                failureCount++
            }
        }

        val stats = mapOf(
            "total_versions" to versions.size,
            "successful" to successCount,
            "failed" to failureCount
        )
        LoggingUtils.debugStats(TAG, stats)

        if (failureCount == 0) {
            LoggingUtils.debugSuccess(TAG, "registering Velocity versions")
        } else {
            LoggingUtils.debugFailure(TAG, "registering Velocity versions", "$failureCount versions failed")
        }

        LoggingUtils.info(TAG, "Registered $successCount/${versions.size} Velocity versions")
    }

    fun registerCustomVersions(
        prefix: String,
        apiType: ServiceAPIType,
        isPaperclip: Boolean,
        versions: List<ServerVersionManager.VersionDownload>
    ) {
        LoggingUtils.debugStart(TAG, "registering custom $prefix versions")
        LoggingUtils.debug(TAG, "Registering ${versions.size} $prefix versions...")

        var successCount = 0
        var failureCount = 0

        versions.forEach { version ->
            try {
                val versionName = "${prefix.uppercase()}_${version.version.replace(".", "_").replace("-", "_")}"
                LoggingUtils.debug(TAG, "Registering $prefix version: $versionName from ${version.link}")

                val serviceVersion = ServiceVersion(
                    name = versionName,
                    serviceAPIType = apiType,
                    downloadURL = version.link,
                    isPaperClip = isPaperclip
                )

                registeredVersions.add(serviceVersion)
                successCount++

                LoggingUtils.debug(TAG, "Successfully registered $prefix version: $versionName")
            } catch (e: Exception) {
                LoggingUtils.error(TAG, "Failed to register $prefix version ${version.version}: ${e.message}", e)
                failureCount++
            }
        }

        val stats = mapOf(
            "prefix" to prefix,
            "api_type" to apiType.name,
            "is_paperclip" to isPaperclip,
            "total_versions" to versions.size,
            "successful" to successCount,
            "failed" to failureCount
        )
        LoggingUtils.debugStats(TAG, stats)

        if (failureCount == 0) {
            LoggingUtils.debugSuccess(TAG, "registering custom $prefix versions")
        } else {
            LoggingUtils.debugFailure(TAG, "registering custom $prefix versions", "$failureCount versions failed")
        }

        LoggingUtils.info(TAG, "Registered $successCount/${versions.size} $prefix versions")
    }

    fun unregisterOldVersions(keepVersions: List<String>) {
        LoggingUtils.debugStart(TAG, "unregistering old versions")
        LoggingUtils.debug(TAG, "Keeping ${keepVersions.size} versions, checking for old versions to remove...")

        val initialCount = registeredVersions.size

        val toRemove = registeredVersions.filter { version ->
            val isOldVersion = (version.name.startsWith("LEAF_") ||
                    version.name.startsWith("VELOCITYCTD_") ||
                    version.name.startsWith("PAPER_") ||
                    version.name.startsWith("VELOCITY_")) &&
                    !keepVersions.contains(version.name)

            if (isOldVersion) {
                LoggingUtils.debug(TAG, "Marking for removal: ${version.name}")
            }

            isOldVersion
        }

        registeredVersions.removeAll(toRemove)

        val stats = mapOf(
            "initial_count" to initialCount,
            "removed_count" to toRemove.size,
            "remaining_count" to registeredVersions.size,
            "keep_versions_count" to keepVersions.size
        )
        LoggingUtils.debugStats(TAG, stats)

        if (toRemove.isNotEmpty()) {
            LoggingUtils.debug(TAG, "Removed versions:")
            toRemove.forEach { version ->
                LoggingUtils.debug(TAG, "  - ${version.name}")
            }
        }

        LoggingUtils.debugSuccess(TAG, "unregistering old versions")
        LoggingUtils.info(TAG, "Unregistered ${toRemove.size} old versions, keeping ${registeredVersions.size} versions")
    }

    fun clearAllVersions() {
        LoggingUtils.debugStart(TAG, "clearing all versions")

        val removedCount = registeredVersions.size
        registeredVersions.clear()

        LoggingUtils.debug(TAG, "Removed $removedCount versions")
        LoggingUtils.debugSuccess(TAG, "clearing all versions")
        LoggingUtils.info(TAG, "Cleared all $removedCount registered versions")
    }

    fun getRegisteredVersions(): List<ServiceVersion> {
        LoggingUtils.debug(TAG, "Registered versions requested (${registeredVersions.size} versions)")
        return registeredVersions.toList()
    }

    fun getVersionsByType(apiType: ServiceAPIType): List<ServiceVersion> {
        val filteredVersions = registeredVersions.filter { it.serviceAPIType == apiType }
        LoggingUtils.debug(TAG, "Versions by type $apiType requested (${filteredVersions.size}/${registeredVersions.size} versions)")
        return filteredVersions
    }

    fun getVersionsByPrefix(prefix: String): List<ServiceVersion> {
        val filteredVersions = registeredVersions.filter { it.name.startsWith(prefix.uppercase()) }
        LoggingUtils.debug(TAG, "Versions by prefix $prefix requested (${filteredVersions.size}/${registeredVersions.size} versions)")
        return filteredVersions
    }

    fun hasVersion(versionName: String): Boolean {
        val exists = registeredVersions.any { it.name == versionName }
        LoggingUtils.debug(TAG, "Version existence check for '$versionName': $exists")
        return exists
    }

    fun removeVersion(versionName: String): Boolean {
        val removed = registeredVersions.removeIf { it.name == versionName }
        if (removed) {
            LoggingUtils.debug(TAG, "Removed version: $versionName")
        } else {
            LoggingUtils.debug(TAG, "Version not found for removal: $versionName")
        }
        return removed
    }

    fun getStats(): Map<String, Any> {
        val versionsByType = mutableMapOf<String, Int>()
        val versionsByPrefix = mutableMapOf<String, Int>()

        registeredVersions.forEach { version ->
            val apiType = version.serviceAPIType.name
            versionsByType[apiType] = versionsByType.getOrDefault(apiType, 0) + 1

            val prefix = version.name.substringBefore("_")
            versionsByPrefix[prefix] = versionsByPrefix.getOrDefault(prefix, 0) + 1
        }

        return mapOf(
            "total_registered" to registeredVersions.size,
            "versions_by_type" to versionsByType,
            "versions_by_prefix" to versionsByPrefix,
            "paperclip_versions" to registeredVersions.count { it.isPaperClip },
            "non_paperclip_versions" to registeredVersions.count { !it.isPaperClip }
        )
    }
}