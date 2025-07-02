package eu.thesimplecloud.module.updater.bootstrap

import eu.thesimplecloud.api.CloudAPI
import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.api.event.service.CloudServiceStartingEvent
import eu.thesimplecloud.api.eventapi.CloudEventHandler
import eu.thesimplecloud.api.eventapi.IListener
import eu.thesimplecloud.api.external.ICloudModule
import eu.thesimplecloud.api.service.ICloudService
import kotlinx.coroutines.*
import java.io.File
import java.net.URL

class PluginUpdaterModule : ICloudModule, IListener {

    companion object {
        lateinit var instance: PluginUpdaterModule
            private set
    }

    private val moduleScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onEnable() {
        instance = this
        try {
            CloudAPI.instance.getEventManager().registerListener(this, this)
            println("[AutoManager] Module loaded successfully")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDisable() {
        try {
            moduleScope.cancel()
            CloudAPI.instance.getEventManager().unregisterListener(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @CloudEventHandler
    fun onServiceStarting(event: CloudServiceStartingEvent) {
        moduleScope.launch {
            val service = event.cloudService
            if (isLeafService(service) || isVelocityCTDService(service)) {
                updateServiceJar(service)
            }
        }
    }

    private suspend fun updateServiceJar(service: ICloudService) = withContext(Dispatchers.IO) {
        try {
            val serviceDir = if (service.isStatic()) {
                File(DirectoryPaths.paths.staticPath + service.getName())
            } else {
                File(DirectoryPaths.paths.tempPath + service.getName())
            }

            when {
                isLeafService(service) -> {
                    val jarFile = File(serviceDir, "server.jar")
                    val downloadUrl = "https://github.com/Winds-Studio/Leaf/releases/download/ver-1.21.6/leaf-1.21.6-20.jar"

                    println("[AutoManager] Updating ${service.getName()} to latest Leaf version")

                    serviceDir.mkdirs()
                    URL(downloadUrl).openStream().use { input ->
                        jarFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    println("[AutoManager] ${service.getName()} updated successfully")
                }
                isVelocityCTDService(service) -> {
                    val velocityJar = File(serviceDir, "velocity.jar")
                    val downloadUrl = "https://github.com/GemstoneGG/Velocity-CTD/releases/download/Releases/velocity-proxy-3.4.0-SNAPSHOT-all.jar"

                    println("[AutoManager] Updating ${service.getName()} to latest VelocityCTD version")

                    serviceDir.mkdirs()
                    URL(downloadUrl).openStream().use { input ->
                        velocityJar.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    println("[AutoManager] ${service.getName()} updated successfully")
                }
            }
        } catch (e: Exception) {
            println("[AutoManager] Error updating ${service.getName()}: ${e.message}")
        }
    }

    private fun isLeafService(service: ICloudService): Boolean {
        val serviceName = service.getName().lowercase()
        val groupName = service.getServiceGroup().getName().lowercase()

        if (serviceName.contains("leaf") || groupName.contains("leaf")) {
            return true
        }

        return try {
            val serviceVersionName = service.getServiceVersion().name.uppercase()
            serviceVersionName.startsWith("LEAF")
        } catch (e: Exception) {
            false
        }
    }

    private fun isVelocityCTDService(service: ICloudService): Boolean {
        val serviceName = service.getName().lowercase()
        val groupName = service.getServiceGroup().getName().lowercase()

        if (serviceName.contains("velocityctd") || groupName.contains("velocityctd") ||
            serviceName.contains("velocity-ctd") || groupName.contains("velocity-ctd") ||
            serviceName.contains("ctd") || groupName.contains("ctd")) {
            return true
        }

        return try {
            val serviceVersionName = service.getServiceVersion().name.uppercase()
            serviceVersionName.startsWith("VELOCITYCTD")
        } catch (e: Exception) {
            false
        }
    }
}