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
import java.io.File
import java.net.URL

class TemplateManager(
    private val module: PluginUpdaterModule,
    private val config: AutoManagerConfig
) {
    companion object {
        private const val USER_AGENT = "SimpleCloud-AutoUpdater"
        private const val CONNECT_TIMEOUT = 10000
        private const val READ_TIMEOUT = 30000
        private const val ONE_DAY_MILLIS = 24 * 60 * 60 * 1000
        private const val MIN_JAR_SIZE = 1000000
    }

    private val templatesDirectory = File(DirectoryPaths.paths.templatesPath)

    suspend fun syncAllTemplates(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            logger("Starting unified template synchronization...")
            var success = true

            if (config.templates.autoCreateBaseTemplates) {
                success = createBaseTemplates() && success
            }

            if (config.enableServerVersionUpdates) {
                success = updateAllJars() && success
            }

            success = syncExistingTemplates() && success

            logger("Template synchronization completed with success: $success")
            success
        } catch (e: Exception) {
            logger("Error in syncAllTemplates: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private suspend fun updateAllJars(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            logger("Starting unified jar updates...")

            val serverVersionManager = module.getServerVersionManager()
            val updateResult = serverVersionManager.updateAllVersions()

            if (!updateResult) {
                logger("ERROR: Failed to update server versions")
                return@withContext false
            }

            val currentVersions = serverVersionManager.getCurrentVersions()
            logger("Available versions: ${currentVersions.map { "${it.name}:${it.latestVersion}" }}")

            if (currentVersions.isEmpty()) {
                logger("ERROR: No server versions available")
                return@withContext false
            }

            var success = true

            currentVersions.forEach { versionEntry ->
                when (versionEntry.name) {
                    "Leaf" -> success = updateBaseJars(versionEntry, "paperclip.jar") && success
                    "Velocity" -> success = updateBaseJars(versionEntry, "velocity.jar") && success
                    "VelocityCTD" -> success = updateBaseJars(versionEntry, "velocity.jar") && success
                }
            }

            logger("Unified jar updates completed: $success")
            success
        } catch (e: Exception) {
            logger("Error in updateAllJars: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private suspend fun updateBaseJars(
        versionEntry: ServerVersionManager.ServerVersionEntry,
        jarFileName: String
    ): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            logger("=== ${versionEntry.name.uppercase()} UPDATE STARTED ===")
            logger("${versionEntry.name} version: ${versionEntry.latestVersion}")
            logger("Available download links: ${versionEntry.downloadLinks.size}")

            if (versionEntry.downloadLinks.isEmpty()) {
                logger("ERROR: No download links available for ${versionEntry.name}")
                return@withContext false
            }

            var downloadSuccess = false

            for (download in versionEntry.downloadLinks) {
                try {
                    logger("Trying download: ${download.version} -> ${download.link}")

                    val targetFile = if (versionEntry.name == "VelocityCTD" || versionEntry.name == "Velocity") {
                        val versionName = sanitizeVersionName("${versionEntry.name.uppercase()}_${download.version}")
                        File(DirectoryPaths.paths.minecraftJarsPath + "${versionName}.jar")
                    } else {
                        val leafVersionDir = File(DirectoryPaths.paths.minecraftJarsPath + "LEAF_${download.version.replace(".", "_")}/")
                        leafVersionDir.mkdirs()
                        File(leafVersionDir, jarFileName)
                    }

                    if (targetFile.exists() && isRecentFile(targetFile)) {
                        logger("${versionEntry.name} ${download.version} already up to date (recent file)")
                        downloadSuccess = true
                        break
                    }

                    val success = downloadFileWithRetry(download.link, targetFile, "${versionEntry.name} ${download.version}")

                    if (success) {
                        logger("✅ ${versionEntry.name} ${download.version} downloaded successfully!")
                        logger("✅ File saved to: ${targetFile.absolutePath}")
                        logger("✅ File size: ${targetFile.length()} bytes")
                        downloadSuccess = true
                        break
                    }

                } catch (e: Exception) {
                    logger("Failed to download ${download.version}: ${e.message}")
                    continue
                }
            }

            if (!downloadSuccess) {
                logger("❌ ERROR: All ${versionEntry.name} download attempts failed")
                return@withContext false
            }

            logger("=== ${versionEntry.name.uppercase()} UPDATE COMPLETED ===")
            true

        } catch (e: Exception) {
            logger("❌ ERROR updating ${versionEntry.name}: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private suspend fun downloadFileWithRetry(
        url: String,
        targetFile: File,
        description: String,
        maxRetries: Int = 3
    ): Boolean = withContext(Dispatchers.IO) {
        repeat(maxRetries) { attempt ->
            try {
                logger("Download attempt ${attempt + 1}/$maxRetries for $description")

                targetFile.parentFile.mkdirs()

                val connection = createConnection(url)

                connection.getInputStream().use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                if (targetFile.exists() && targetFile.length() > MIN_JAR_SIZE) {
                    logger("Successfully downloaded $description (${targetFile.length()} bytes)")
                    return@withContext true
                } else {
                    logger("Download validation failed for $description: file size ${targetFile.length()}")
                    targetFile.delete()
                }

            } catch (e: Exception) {
                logger("Download attempt ${attempt + 1} failed for $description: ${e.message}")
                if (targetFile.exists()) targetFile.delete()

                if (attempt < maxRetries - 1) {
                    Thread.sleep(2000L * (attempt + 1))
                }
            }
        }

        logger("All download attempts failed for $description")
        return@withContext false
    }

    private fun createConnection(url: String): java.net.URLConnection {
        val connection = URL(url).openConnection()
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        return connection
    }

    private suspend fun syncExistingTemplates(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val templateManager = CloudAPI.instance.getTemplateManager()
            val allTemplates = templateManager.getAllCachedObjects()
            var success = true

            allTemplates.forEach { template ->
                try {
                    syncSingleTemplate(template)
                } catch (e: Exception) {
                    logger("Error syncing template ${template.getName()}: ${e.message}")
                    success = false
                }
            }

            success
        } catch (e: Exception) {
            logger("Error syncing existing templates: ${e.message}")
            false
        }
    }

    private suspend fun syncSingleTemplate(template: ITemplate) = withContext(Dispatchers.IO) {
        try {
            val templateDir = File(templatesDirectory, template.getName())
            if (!templateDir.exists()) {
                templateDir.mkdirs()
            }

            val pluginsDir = File(templateDir, "plugins")
            if (!pluginsDir.exists()) {
                pluginsDir.mkdirs()
            }

            syncTemplateModules(template, pluginsDir)

            updateTemplateJars(template, templateDir)

        } catch (e: Exception) {
            logger("Error syncing template ${template.getName()}: ${e.message}")
        }
    }

    private fun syncTemplateModules(template: ITemplate, pluginsDir: File) {
        template.getModuleNamesToCopy().forEach { moduleName ->
            val moduleFile = findModuleFile(moduleName)
            if (moduleFile != null && moduleFile.exists()) {
                val targetFile = File(pluginsDir, moduleFile.name)

                if (!targetFile.exists() || moduleFile.lastModified() > targetFile.lastModified()) {
                    moduleFile.copyTo(targetFile, overwrite = true)
                    logger("Copied module ${moduleFile.name} to template ${template.getName()}")
                }
            }
        }
    }

    private suspend fun updateTemplateJars(template: ITemplate, templateDir: File) = withContext(Dispatchers.IO) {
        val templateName = template.getName().lowercase()
        val serverVersionManager = module.getServerVersionManager()

        when {
            isLeafTemplate(templateName) -> {
                val leafVersions = serverVersionManager.getCurrentVersions().find { it.name == "Leaf" }
                if (leafVersions != null) {
                    updateTemplateWithVersions(templateDir, leafVersions, "server.jar")
                }
            }
            isVelocityTemplate(templateName) -> {
                val velocityVersions = serverVersionManager.getCurrentVersions().find { it.name == "Velocity" }
                    ?: serverVersionManager.getCurrentVersions().find { it.name == "VelocityCTD" }
                if (velocityVersions != null) {
                    updateTemplateWithVersions(templateDir, velocityVersions, "velocity.jar")
                }
            }
        }
    }

    private suspend fun updateTemplateWithVersions(
        templateDir: File,
        versionEntry: ServerVersionManager.ServerVersionEntry,
        jarFileName: String
    ) = withContext(Dispatchers.IO) {
        try {
            logger("Updating ${versionEntry.name} jar in template...")

            if (versionEntry.downloadLinks.isNotEmpty()) {
                val latestDownload = versionEntry.downloadLinks.first()
                val jarFile = File(templateDir, jarFileName)

                val success = downloadFileWithRetry(
                    latestDownload.link,
                    jarFile,
                    "${versionEntry.name} ${latestDownload.version} for template"
                )

                if (success) {
                    logger("${versionEntry.name} jar template updated successfully to version ${latestDownload.version}")
                } else {
                    logger("Failed to update ${versionEntry.name} jar in template")
                }
            }
        } catch (e: Exception) {
            logger("Error updating ${versionEntry.name} jar in template: ${e.message}")
            e.printStackTrace()
        }
    }

    suspend fun syncStaticServersOnRestart(): Boolean = withContext(Dispatchers.IO) {
        try {
            logger("Synchronizing static servers...")
            val serviceManager = CloudAPI.instance.getCloudServiceManager()
            val staticServices = serviceManager.getAllCachedObjects().filter { it.isStatic() }

            var allSuccess = true
            staticServices.forEach { service ->
                val success = syncSingleStaticService(service)
                if (!success) allSuccess = false
            }

            return@withContext allSuccess
        } catch (e: Exception) {
            logger("Error during static server sync: ${e.message}")
            return@withContext false
        }
    }

    private suspend fun syncSingleStaticService(service: ICloudService): Boolean = withContext(Dispatchers.IO) {
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

            if (needsJarUpdate(service)) {
                logger("Starting jar update for service ${service.getName()}")
                updateServiceJar(service, serviceDir)
            }

            return@withContext true

        } catch (e: Exception) {
            logger("Error syncing static service ${service.getName()}: ${e.message}")
            return@withContext false
        }
    }

    private suspend fun updateServiceJar(service: ICloudService, serviceDir: File) = withContext(Dispatchers.IO) {
        try {
            val serverVersionManager = module.getServerVersionManager()

            when {
                isLeafService(service) -> {
                    val leafVersions = serverVersionManager.getCurrentVersions().find { it.name == "Leaf" }
                    if (leafVersions != null) {
                        updateServiceWithVersions(serviceDir, leafVersions, "server.jar")
                    }
                }
                isVelocityService(service) -> {
                    val velocityVersions = serverVersionManager.getCurrentVersions().find { it.name == "Velocity" }
                        ?: serverVersionManager.getCurrentVersions().find { it.name == "VelocityCTD" }
                    if (velocityVersions != null) {
                        updateServiceWithVersions(serviceDir, velocityVersions, "velocity.jar")
                    }
                }
            }
        } catch (e: Exception) {
            logger("Error updateServiceJar: ${e.message}")
        }
    }

    private suspend fun updateServiceWithVersions(
        serviceDir: File,
        versionEntry: ServerVersionManager.ServerVersionEntry,
        jarFileName: String
    ) = withContext(Dispatchers.IO) {
        try {
            logger("Updating ${versionEntry.name} jar for service...")

            if (versionEntry.downloadLinks.isNotEmpty()) {
                val latestDownload = versionEntry.downloadLinks.first()
                val jarFile = File(serviceDir, jarFileName)

                val success = downloadFileWithRetry(
                    latestDownload.link,
                    jarFile,
                    "${versionEntry.name} ${latestDownload.version} for service"
                )

                if (success) {
                    logger("Successfully updated ${versionEntry.name} jar for service to version ${latestDownload.version}")
                } else {
                    logger("Failed to update ${versionEntry.name} jar for service")
                }
            }
        } catch (e: Exception) {
            logger("Error updating ${versionEntry.name} jar for service: ${e.message}")
            e.printStackTrace()
        }
    }

    private suspend fun createBaseTemplates(): Boolean = withContext(Dispatchers.IO) {
        try {
            val baseTemplateNames = listOf("proxy", "lobby", "server", "leaf", "velocityctd")
            val createdTemplates = mutableListOf<ITemplate>()
            val templateManager = CloudAPI.instance.getTemplateManager()

            baseTemplateNames.forEach { templateName ->
                if (templateManager.getTemplateByName(templateName) == null) {
                    val template = DefaultTemplate(templateName)
                    createdTemplates.add(template)
                    templateManager.update(template)
                    logger("Created base template: $templateName")
                }
            }

            createdTemplates.forEach { template ->
                syncSingleTemplate(template)
            }

            logger("Base templates creation completed. Created ${createdTemplates.size} templates.")
            return@withContext true
        } catch (e: Exception) {
            logger("Error creating base templates: ${e.message}")
            e.printStackTrace()
            return@withContext false
        }
    }

    private fun isRecentFile(file: File): Boolean {
        val fileAge = System.currentTimeMillis() - file.lastModified()
        return fileAge < ONE_DAY_MILLIS
    }

    private fun sanitizeVersionName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9_]"), "_")
            .replace(Regex("_{2,}"), "_")
            .trimEnd('_')
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

    private fun isLeafService(service: ICloudService): Boolean {
        val serviceName = service.getName().lowercase()
        val groupName = service.getServiceGroup().getName().lowercase()

        return serviceName.contains("leaf") || groupName.contains("leaf") ||
                try {
                    service.getServiceVersion().name.uppercase().startsWith("LEAF")
                } catch (e: Exception) { false }
    }

    private fun isVelocityService(service: ICloudService): Boolean {
        val serviceName = service.getName().lowercase()
        val groupName = service.getServiceGroup().getName().lowercase()

        return serviceName.contains("velocity") || groupName.contains("velocity") ||
                serviceName.contains("proxy") || groupName.contains("proxy") ||
                serviceName.contains("ctd") || groupName.contains("ctd") ||
                try {
                    val versionName = service.getServiceVersion().name.uppercase()
                    versionName.startsWith("VELOCITY") || versionName.startsWith("VELOCITYCTD")
                } catch (e: Exception) { false }
    }

    private fun isLeafTemplate(templateName: String): Boolean {
        return templateName.contains("leaf") || templateName.contains("server") || templateName.contains("survival")
    }

    private fun isVelocityTemplate(templateName: String): Boolean {
        return templateName.contains("velocity") || templateName.contains("ctd") ||
                templateName.contains("proxy") || templateName.contains("bungee")
    }

    private fun needsJarUpdate(service: ICloudService): Boolean {
        return isLeafService(service) || isVelocityService(service)
    }

    private fun logger(message: String) {
        println("[TemplateManager] $message")
    }
}