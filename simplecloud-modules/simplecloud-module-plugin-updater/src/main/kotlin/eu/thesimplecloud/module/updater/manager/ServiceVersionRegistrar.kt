package eu.thesimplecloud.module.updater.manager

import eu.thesimplecloud.api.service.version.ServiceVersion
import eu.thesimplecloud.api.service.version.type.ServiceAPIType

class ServiceVersionRegistrar {

    // Store versions locally if the API doesn't support direct manipulation
    private val registeredVersions = mutableListOf<ServiceVersion>()

    fun registerLeafVersions(versions: List<ServerVersionManager.VersionDownload>) {
        println("[ServiceVersionRegistrar] Registering ${versions.size} Leaf versions...")

        versions.forEach { version ->
            val versionName = "LEAF_${version.version.replace(".", "_")}"

            val serviceVersion = ServiceVersion(
                name = versionName,
                serviceAPIType = ServiceAPIType.SPIGOT,
                downloadURL = version.link,
                isPaperClip = true
            )

            registeredVersions.add(serviceVersion)
            println("[ServiceVersionRegistrar] Registered Leaf version: $versionName")
        }
    }

    fun registerVelocityCTDVersion(version: ServerVersionManager.VersionDownload) {
        val versionName = "VELOCITYCTD_${version.version.replace(".", "_").replace("-", "_")}"

        val serviceVersion = ServiceVersion(
            name = versionName,
            serviceAPIType = ServiceAPIType.VELOCITY,
            downloadURL = version.link,
            isPaperClip = false
        )

        registeredVersions.add(serviceVersion)
        println("[ServiceVersionRegistrar] Registered VelocityCTD version: $versionName")
    }

    fun unregisterOldVersions(keepVersions: List<String>) {
        val toRemove = registeredVersions.filter { version ->
            (version.name.startsWith("LEAF_") || version.name.startsWith("VELOCITYCTD_")) &&
                    !keepVersions.contains(version.name)
        }

        registeredVersions.removeAll(toRemove)
        toRemove.forEach { version ->
            println("[ServiceVersionRegistrar] Removed old version: ${version.name}")
        }
    }

    // Getter for registered versions if needed elsewhere
    fun getRegisteredVersions(): List<ServiceVersion> = registeredVersions.toList()
}