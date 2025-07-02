package eu.thesimplecloud.module.updater.manager

import eu.thesimplecloud.api.CloudAPI
import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.api.service.ICloudService
import eu.thesimplecloud.api.template.ITemplate
import eu.thesimplecloud.api.template.impl.DefaultTemplate
import eu.thesimplecloud.module.updater.bootstrap.PluginUpdaterModule
import eu.thesimplecloud.module.updater.config.AutoManagerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.io.FileUtils
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

            if (config.enableServerVersionUpdates) {
                success = updateBaseJars() && success
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

    private suspend fun updateBaseJars(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            var success = true

            val serverVersionManager = module.getServerVersionManager()
            val currentVersions = serverVersionManager.getCurrentVersions()

            val leafVersions = currentVersions.find { it.name == "Leaf" }
            if (leafVersions != null) {
                success = updateBaseLeafJar(leafVersions) && success
            }

            val velocityCTDVersions = currentVersions.find { it.name == "VelocityCTD" }
            if (velocityCTDVersions != null) {
                success = updateBaseVelocityCTDJar(velocityCTDVersions) && success
            }

            success
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun updateBaseLeafJar(serverVersion: ServerVersionManager.ServerVersionEntry): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            if (serverVersion.downloadLinks.isEmpty()) return@withContext true

            val latestDownload = serverVersion.downloadLinks.first()

            val leafBaseDir = File(DirectoryPaths.paths.minecraftJarsPath + "Leaf/")
            val paperclipFile = File(leafBaseDir, "paperclip.jar")
            val serverJarFile = File(leafBaseDir, "server.jar")

            if (isJarUpToDate(paperclipFile, latestDownload.link)) {
                return@withContext true
            }

            leafBaseDir.mkdirs()

            URL(latestDownload.link).openStream().use { input ->
                paperclipFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (serverJarFile.exists()) {
                serverJarFile.delete()
            }

            executePaperClip(paperclipFile, leafBaseDir)
            deleteUnnecessaryFiles(leafBaseDir)

            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun updateBaseVelocityCTDJar(serverVersion: ServerVersionManager.ServerVersionEntry): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            if (serverVersion.downloadLinks.isEmpty()) return@withContext true

            val latestDownload = serverVersion.downloadLinks.first()

            val velocityJarFile = File(DirectoryPaths.paths.minecraftJarsPath + "VelocityCTD.jar")

            if (isJarUpToDate(velocityJarFile, latestDownload.link)) {
                return@withContext true
            }

            velocityJarFile.parentFile.mkdirs()

            URL(latestDownload.link).openStream().use { input ->
                velocityJarFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun isJarUpToDate(jarFile: File, downloadUrl: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            if (!jarFile.exists()) return@withContext false

            val fileModifiedTime = jarFile.lastModified()
            val currentTime = System.currentTimeMillis()
            val oneDayMillis = 24 * 60 * 60 * 1000

            (currentTime - fileModifiedTime) < oneDayMillis
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun executePaperClip(paperclipFile: File, workingDir: File) = withContext(Dispatchers.IO) {
        try {
            val processBuilder = ProcessBuilder("java", "-jar", paperclipFile.absolutePath)
            processBuilder.directory(workingDir)
            val process = processBuilder.start()
            process.waitFor()
        } catch (e: Exception) {

        }
    }

    private fun deleteUnnecessaryFiles(dir: File) {
        try {
            val unnecessaryFileNames = listOf("eula.txt", "server.properties", "logs", "cache")
            for (fileName in unnecessaryFileNames) {
                val file = File(dir, fileName)
                if (file.exists()) {
                    if (file.isDirectory) {
                        FileUtils.deleteDirectory(file)
                    } else {
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {

        }
    }

    suspend fun syncStaticServersOnRestart(service: eu.thesimplecloud.api.service.ICloudService): Boolean = withContext(Dispatchers.IO) {
        try {
            if (needsServerJarUpdate(service)) {
                println("[AutoManager] Rilevato servizio ${service.getName()} che necessita aggiornamento jar")
            }

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
                println("[AutoManager] Avvio aggiornamento jar per servizio ${service.getName()}")
                updateServerJar(service, serviceDir)
            }

            return@withContext true

        } catch (e: Exception) {
            println("[AutoManager] Errore durante syncStaticServersOnRestart per ${service.getName()}: ${e.message}")
             return@withContext false
        }
    }

    suspend fun syncStaticServersOnRestart(): Boolean = withContext(Dispatchers.IO) {
        try {
            val serviceManager = CloudAPI.instance.getCloudServiceManager()
            val staticServices = serviceManager.getAllCachedObjects().filter { it.isStatic() }

            var allSuccess = true
            staticServices.forEach { service ->
                val success = syncStaticServersOnRestart(service)
                if (!success) allSuccess = false
            }

            return@withContext allSuccess
        } catch (e: Exception) {
            println("[AutoManager] Errore durante syncStaticServersOnRestart globale: ${e.message}")
            return@withContext false
        }
    }

    private fun shouldUpdateStaticService(service: ICloudService): Boolean {
        val serviceName = service.getName().lowercase()
        val groupName = service.getServiceGroup().getName().lowercase()

        return isLeafService(serviceName, groupName) || isVelocityCTDService(serviceName, groupName)
    }

    private fun needsServerJarUpdate(service: ICloudService): Boolean {
        return isLeafService(service) || isVelocityCTDService(service)
    }

    private suspend fun updateServerJar(service: ICloudService, serviceDir: File) = withContext(Dispatchers.IO) {
        try {
            when {
                isLeafService(service) -> {
                    updateLeafJar(serviceDir)
                }
                isVelocityCTDService(service) -> {
                    updateVelocityCTDJar(serviceDir)
                }
            }
        } catch (e: Exception) {
            println("[AutoManager] Errore generale durante l'aggiornamento server jar: ${e.message}")
        }
    }

    private suspend fun updateLeafJar(serviceDir: File) = withContext(Dispatchers.IO) {
        try {
            println("[AutoManager] Aggiornando Leaf jar...")

            val serverVersionManager = module.getServerVersionManager()
            val leafVersions = serverVersionManager.getCurrentVersions().find { it.name == "Leaf" }

            if (leafVersions != null && leafVersions.downloadLinks.isNotEmpty()) {
                val latestDownload = leafVersions.downloadLinks.first()
                val jarFile = File(serviceDir, "server.jar")

                println("[AutoManager] Scaricando Leaf ${latestDownload.version} da: ${latestDownload.link}")

                URL(latestDownload.link).openStream().use { input ->
                    jarFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                println("[AutoManager] Leaf jar aggiornato con successo a versione ${latestDownload.version}")
            } else {
                println("[AutoManager] Nessuna versione Leaf disponibile per il download")
            }
        } catch (e: Exception) {
            println("[AutoManager] Errore durante l'aggiornamento Leaf jar: ${e.message}")
            e.printStackTrace()
        }
    }

    private suspend fun updateVelocityCTDJar(serviceDir: File) = withContext(Dispatchers.IO) {
        try {
            println("[AutoManager] Aggiornando VelocityCTD jar...")

            val serverVersionManager = module.getServerVersionManager()
            val velocityCTDVersions = serverVersionManager.getCurrentVersions().find { it.name == "VelocityCTD" }

            if (velocityCTDVersions != null && velocityCTDVersions.downloadLinks.isNotEmpty()) {
                val latestDownload = velocityCTDVersions.downloadLinks.first()
                val jarFile = File(serviceDir, "velocity.jar")

                println("[AutoManager] Scaricando VelocityCTD ${latestDownload.version} da: ${latestDownload.link}")

                URL(latestDownload.link).openStream().use { input ->
                    jarFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                println("[AutoManager] VelocityCTD jar aggiornato con successo a versione ${latestDownload.version}")
            } else {
                println("[AutoManager] Nessuna versione VelocityCTD disponibile per il download")
            }
        } catch (e: Exception) {
            println("[AutoManager] Errore durante l'aggiornamento VelocityCTD jar: ${e.message}")
            e.printStackTrace()
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
        return serviceName.contains("leaf", true) || groupName.contains("leaf", true)
    }

    private fun isLeafService(service: ICloudService): Boolean {
        val serviceName = service.getName().lowercase()
        val groupName = service.getServiceGroup().getName().lowercase()

        if (serviceName.contains("leaf") || groupName.contains("leaf")) {
            return true
        }

        try {
            val serviceVersionName = service.getServiceVersion().name.uppercase()
            return serviceVersionName.startsWith("LEAF")
        } catch (e: Exception) {
            return false
        }
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

    private fun isVelocityCTDService(service: ICloudService): Boolean {
        val serviceName = service.getName().lowercase()
        val groupName = service.getServiceGroup().getName().lowercase()

        if (isVelocityCTDService(serviceName, groupName)) {
            return true
        }

        try {
            val serviceVersionName = service.getServiceVersion().name.uppercase()
            return serviceVersionName.startsWith("VELOCITYCTD")
        } catch (e: Exception) {
            return false
        }
    }

    private fun isVelocityService(serviceName: String, groupName: String): Boolean {
        return (serviceName.contains("velocity") || groupName.contains("velocity")) && !isVelocityCTDService(serviceName, groupName)
    }


    private fun isBungeeService(serviceName: String, groupName: String): Boolean {
        return serviceName.contains("bungee") || groupName.contains("bungee") ||
                serviceName.contains("waterfall") || groupName.contains("waterfall")
    }
}
