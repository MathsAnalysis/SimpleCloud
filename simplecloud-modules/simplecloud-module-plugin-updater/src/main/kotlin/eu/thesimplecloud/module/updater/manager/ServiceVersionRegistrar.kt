package eu.thesimplecloud.module.updater.manager

import eu.thesimplecloud.api.CloudAPI
import eu.thesimplecloud.api.service.version.ServiceVersion
import eu.thesimplecloud.api.service.version.ServiceVersionType
import eu.thesimplecloud.api.service.version.type.ServiceVersionType
import eu.thesimplecloud.launcher.startup.Launcher
import java.io.File

class ServiceVersionRegistrar {

    fun registerLeafVersions(versions: List<ServerVersionManager.VersionDownload>) {
        println("[ServiceVersionRegistrar] Registering ${versions.size} Leaf versions...")

        val serviceVersionHandler = CloudAPI.instance.getServiceVersionHandler()

        versions.forEach { version ->
            val versionName = "LEAF_${version.version.replace(".", "_")}"

            // Check if version already exists
            if (serviceVersionHandler.getServiceVersionByName(versionName) == null) {
                val serviceVersion = ServiceVersion(
                    name = versionName,
                    type = ServiceVersionType.SERVER,
                    paperClip = true,
                    url = version.link
                )

                serviceVersionHandler.addServiceVersion(serviceVersion)
                println("[ServiceVersionRegistrar] Registered Leaf version: $versionName")

                // Also register with Launcher if needed
                try {
                    Launcher.instance.availableServiceVersions.add(serviceVersion)
                } catch (e: Exception) {
                    // Launcher might not be available in all contexts
                }
            } else {
                println("[ServiceVersionRegistrar] Leaf version already registered: $versionName")
            }
        }

        println("[ServiceVersionRegistrar] Leaf version registration complete")
    }

    fun registerVelocityCTDVersion(version: ServerVersionManager.VersionDownload) {
        val versionName = "VELOCITYCTD_${version.version.replace(".", "_").replace("-", "_")}"
        val serviceVersionHandler = CloudAPI.instance.getServiceVersionHandler()

        if (serviceVersionHandler.getServiceVersionByName(versionName) == null) {
            val serviceVersion = ServiceVersion(
                name = versionName,
                type = ServiceVersionType.PROXY,
                paperClip = false,
                url = version.link
            )

            serviceVersionHandler.addServiceVersion(serviceVersion)
            println("[ServiceVersionRegistrar] Registered VelocityCTD version: $versionName")
        }
    }

    fun unregisterOldVersions(keepVersions: List<String>) {
        val serviceVersionHandler = CloudAPI.instance.getServiceVersionHandler()
        val allVersions = serviceVersionHandler.getAllVersionNames()

        allVersions.filter { it.startsWith("LEAF_") || it.startsWith("VELOCITYCTD_") }
            .filter { !keepVersions.contains(it) }
            .forEach { versionName ->
                try {
                    serviceVersionHandler.removeServiceVersion(versionName)
                    println("[ServiceVersionRegistrar] Removed old version: $versionName")
                } catch (e: Exception) {
                    println("[ServiceVersionRegistrar] Failed to remove version $versionName: ${e.message}")
                }
            }
    }
}