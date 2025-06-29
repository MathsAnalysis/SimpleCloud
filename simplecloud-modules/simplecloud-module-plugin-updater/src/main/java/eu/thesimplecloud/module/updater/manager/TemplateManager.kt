package eu.thesimplecloud.module.updater.manager

import eu.thesimplecloud.api.CloudAPI
import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.api.template.ITemplate
import eu.thesimplecloud.api.template.impl.DefaultTemplate
import eu.thesimplecloud.module.updater.config.AutoManagerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

class TemplateManager(
    private val module: PluginUpdaterModule,
    private val config: AutoManagerConfig
) {

    private val templatesDirectory = File(DirectoryPaths.paths.templatesPath)

    suspend fun syncAllTemplates(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            var success = true

            if (config.templates.autoCreateBaseTemplates) {
                success = createBaseTemplates() && success
            }

            val templateManager = CloudAPI.instance.getTemplateManager()
            val allTemplates = templateManager.getAllCachedObjects()

            allTemplates.forEach { template ->
                try {
                    syncTemplate(template)
                } catch (e: Exception) {
                    success = false
                }
            }

            success
        } catch (e: Exception) {
            false
        }
    }

    suspend fun syncStaticServersOnRestart(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val serviceManager = CloudAPI.instance.getCloudServiceManager()
            val staticServices = serviceManager.getAllCachedObjects().filter { it.isStatic() }

            staticServices.forEach { service ->
                if (shouldUpdateStaticService(service)) {
                    syncStaticServiceOnRestart(service)
                }
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun syncStaticServiceOnRestart(service: eu.thesimplecloud.api.service.ICloudService) = withContext(Dispatchers.IO) {
        try {
            val serviceDir = File(DirectoryPaths.paths.staticPath + service.getName())
            val pluginsDir = File(serviceDir, "plugins")

            if (!pluginsDir.exists()) {
                pluginsDir.mkdirs()
            }

            val template = service.getTemplate()
            template.getModuleNamesToCopy().forEach { moduleName ->
                val moduleFile = findModuleFile(moduleName)
                if (moduleFile != null && moduleFile.exists()) {
                    val targetFile = File(pluginsDir, moduleFile.name)

                    if (!targetFile.exists() || moduleFile.lastModified() > targetFile.lastModified()) {
                        moduleFile.copyTo(targetFile, overwrite = true)
                    }
                }
            }

            if (needsServerJarUpdate(service)) {
                updateServerJar(service, serviceDir)
            }

        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun shouldUpdateStaticService(service: eu.thesimplecloud.api.service.ICloudService): Boolean {
        val serviceName = service.getName().lowercase()
        val groupName = service.getServiceGroup().getName().lowercase()

        return isLeafService(serviceName, groupName) || isVelocityCTDService(serviceName, groupName)
    }

    private fun needsServerJarUpdate(service: eu.thesimplecloud.api.service.ICloudService): Boolean {
        val serviceName = service.getName().lowercase()
        val groupName = service.getServiceGroup().getName().lowercase()

        return isLeafService(serviceName, groupName) || isVelocityCTDService(serviceName, groupName)
    }

    private suspend fun updateServerJar(service: eu.thesimplecloud.api.service.ICloudService, serviceDir: File) = withContext(Dispatchers.IO) {
        try {
            val serviceName = service.getName().lowercase()
            val groupName = service.getServiceGroup().getName().lowercase()

            when {
                isLeafService(serviceName, groupName) -> {
                    updateLeafJar(serviceDir)
                }
                isVelocityCTDService(serviceName, groupName) -> {
                    updateVelocityCTDJar(serviceDir)
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private suspend fun updateLeafJar(serviceDir: File) = withContext(Dispatchers.IO) {
        try {
            val serverVersionManager = module.getServerVersionManager()
            val leafVersions = serverVersionManager.getCurrentVersions().find { it.name == "Leaf" }

            if (leafVersions != null && leafVersions.downloadLinks.isNotEmpty()) {
                val latestDownload = leafVersions.downloadLinks.first()
                val jarFile = File(serviceDir, "server.jar")

                URL(latestDownload.link).openStream().use { input ->
                    jarFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private suspend fun updateVelocityCTDJar(serviceDir: File) = withContext(Dispatchers.IO) {
        try {
            val serverVersionManager = module.getServerVersionManager()
            val velocityCTDVersions = serverVersionManager.getCurrentVersions().find { it.name == "VelocityCTD" }

            if (velocityCTDVersions != null && velocityCTDVersions.downloadLinks.isNotEmpty()) {
                val latestDownload = velocityCTDVersions.downloadLinks.first()
                val jarFile = File(serviceDir, "velocity.jar")

                URL(latestDownload.link).openStream().use { input ->
                    jarFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private suspend fun createBaseTemplates(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val templateManager = CloudAPI.instance.getTemplateManager()
            var created = 0

            val baseTemplates = listOf("auto-plugins", "base-server", "base-proxy")

            baseTemplates.forEach { name ->
                if (templateManager.getTemplateByName(name) == null) {
                    val template = DefaultTemplate(name)
                    templateManager.update(template)
                    template.getDirectory().mkdirs()
                    created++
                }
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun syncTemplate(template: ITemplate) = withContext(Dispatchers.IO) {
        try {
            if (!template.getDirectory().exists()) {
                template.getDirectory().mkdirs()
            }

            syncTemplatePlugins(template)
        } catch (e: Exception) {
            throw e
        }
    }

    private fun syncTemplatePlugins(template: ITemplate) {
        val pluginsDir = File(template.getDirectory(), "plugins")
        pluginsDir.mkdirs()

        template.getModuleNamesToCopy().forEach { moduleName ->
            try {
                val moduleFile = findModuleFile(moduleName)
                if (moduleFile != null && moduleFile.exists()) {
                    val targetFile = File(pluginsDir, moduleFile.name)

                    if (!targetFile.exists() || moduleFile.lastModified() > targetFile.lastModified()) {
                        moduleFile.copyTo(targetFile, overwrite = true)
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun findModuleFile(moduleName: String): File? {
        val locations = listOf(
            File(DirectoryPaths.paths.modulesPath + "auto-plugins/"),
            File(DirectoryPaths.paths.modulesPath)
        )

        locations.forEach { location ->
            if (location.exists()) {
                location.walkTopDown().forEach { file ->
                    if (file.isFile &&
                        file.extension == "jar" &&
                        file.nameWithoutExtension.contains(moduleName, true)) {
                        return file
                    }
                }
            }
        }

        return null
    }

    private fun getOptionalBukkitPlugins(): List<String> = emptyList()
    private fun getOptionalVelocityPlugins(): List<String> = emptyList()

    private fun isLeafService(serviceName: String, groupName: String): Boolean {
        return serviceName.contains("leaf") || groupName.contains("leaf")
    }

    private fun isPaperService(serviceName: String, groupName: String): Boolean {
        return serviceName.contains("paper") || groupName.contains("paper") ||
                serviceName.contains("purpur") || groupName.contains("purpur")
    }

    private fun isVelocityCTDService(serviceName: String, groupName: String): Boolean {
        return serviceName.contains("velocityctd") || groupName.contains("velocityctd") ||
                serviceName.contains("velocity-ctd") || groupName.contains("velocity-ctd") ||
                serviceName.contains("ctd") || groupName.contains("ctd")
    }

    private fun isVelocityService(serviceName: String, groupName: String): Boolean {
        return (serviceName.contains("velocity") || groupName.contains("velocity")) && !isVelocityCTDService(serviceName, groupName)
    }

    private fun isBungeeService(serviceName: String, groupName: String): Boolean {
        return serviceName.contains("bungee") || groupName.contains("bungee") ||
                serviceName.contains("waterfall") || groupName.contains("waterfall")
    }
}
