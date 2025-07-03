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

    private val templatesDirectory = File(DirectoryPaths.paths.templatesPath)

    suspend fun syncAllTemplates(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            println("[TemplateManager] Starting template synchronization...")
            var success = true

            if (config.templates.autoCreateBaseTemplates) {
                success = createBaseTemplates() && success
            }

            if (config.enableServerVersionUpdates) {
                println("[TemplateManager] Server updates enabled, starting updateBaseJars...")
                success = updateBaseJars() && success
            }

            val templateManager = CloudAPI.instance.getTemplateManager()
            val allTemplates = templateManager.getAllCachedObjects()

            allTemplates.forEach { template ->
                try {
                    syncTemplate(template)
                } catch (e: Exception) {
                    println("[TemplateManager] Error syncing template ${template.getName()}: ${e.message}")
                    success = false
                }
            }

            println("[TemplateManager] Template synchronization completed with success: $success")
            success
        } catch (e: Exception) {
            println("[TemplateManager] General syncAllTemplates error: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private suspend fun updateBaseJars(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            println("[TemplateManager] Starting base jars update...")
            var success = true

            val serverVersionManager = module.getServerVersionManager()

            // First update versions from GitHub API
            println("[TemplateManager] Calling updateAllVersions...")
            val updateResult = serverVersionManager.updateAllVersions()
            if (!updateResult) {
                println("[TemplateManager] ERROR: Failed to update server versions")
                return@withContext false
            }

            val currentVersions = serverVersionManager.getCurrentVersions()
            println("[TemplateManager] Available versions: ${currentVersions.map { "${it.name}:${it.latestVersion}" }}")

            if (currentVersions.isEmpty()) {
                println("[TemplateManager] ERROR: No server versions available")
                return@withContext false
            }

            // Update Leaf versions
            val leafVersions = currentVersions.find { it.name == "Leaf" }
            if (leafVersions != null) {
                println("[TemplateManager] Found Leaf configuration, starting update...")
                success = updateBaseLeafJar(leafVersions) && success
            } else {
                println("[TemplateManager] WARNING: Leaf configuration not found")
            }

            // Update VelocityCTD versions
            val velocityCTDVersions = currentVersions.find { it.name == "VelocityCTD" }
            if (velocityCTDVersions != null) {
                success = updateBaseVelocityCTDJar(velocityCTDVersions) && success
            }

            println("[TemplateManager] Base jars update completed: $success")
            success
        } catch (e: Exception) {
            println("[TemplateManager] Error updateBaseJars: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private suspend fun updateBaseLeafJar(leafVersionEntry: ServerVersionManager.ServerVersionEntry): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            println("[TemplateManager] === LEAF UPDATE STARTED ===")
            println("[TemplateManager] Leaf version: ${leafVersionEntry.latestVersion}")
            println("[TemplateManager] Available download links: ${leafVersionEntry.downloadLinks.size}")

            if (leafVersionEntry.downloadLinks.isEmpty()) {
                println("[TemplateManager] ERROR: No download links available for Leaf")
                return@withContext false
            }

            var downloadSuccess = false

            // Try each download link until one works
            for (download in leafVersionEntry.downloadLinks) {
                try {
                    println("[TemplateManager] Trying download: ${download.version} -> ${download.link}")

                    // Create directory for specific version
                    val leafVersionDir = File(DirectoryPaths.paths.minecraftJarsPath + "LEAF_${download.version.replace(".", "_")}/")
                    if (!leafVersionDir.exists()) {
                        leafVersionDir.mkdirs()
                        println("[TemplateManager] Created directory: ${leafVersionDir.absolutePath}")
                    }

                    val paperclipFile = File(leafVersionDir, "paperclip.jar")

                    // Check if already updated (within last 24 hours)
                    if (paperclipFile.exists()) {
                        val fileAge = System.currentTimeMillis() - paperclipFile.lastModified()
                        val oneDayMillis = 24 * 60 * 60 * 1000

                        if (fileAge < oneDayMillis) {
                            println("[TemplateManager] Leaf ${download.version} already up to date (recent file)")
                            return@withContext true
                        }
                    }

                    // Download the JAR with proper headers
                    val connection = URL(download.link).openConnection()
                    connection.setRequestProperty("User-Agent", "SimpleCloud-AutoUpdater")
                    connection.connectTimeout = 10000
                    connection.readTimeout = 30000

                    connection.getInputStream().use { input ->
                        paperclipFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    println("[TemplateManager] ✅ Leaf ${download.version} downloaded successfully!")
                    println("[TemplateManager] ✅ File saved to: ${paperclipFile.absolutePath}")
                    println("[TemplateManager] ✅ File size: ${paperclipFile.length()} bytes")

                    downloadSuccess = true
                    break

                } catch (e: Exception) {
                    println("[TemplateManager] Failed to download ${download.version}: ${e.message}")
                    continue
                }
            }

            if (!downloadSuccess) {
                println("[TemplateManager] ❌ ERROR: All Leaf download attempts failed")
                return@withContext false
            }

            println("[TemplateManager] === LEAF UPDATE COMPLETED ===")
            true

        } catch (e: Exception) {
            println("[TemplateManager] ❌ ERROR updateBaseLeafJar: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private suspend fun updateBaseVelocityCTDJar(velocityEntry: ServerVersionManager.ServerVersionEntry): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            if (velocityEntry.downloadLinks.isEmpty()) {
                return@withContext false
            }

            val latestDownload = velocityEntry.downloadLinks.first()
            val velocityJarFile = File(DirectoryPaths.paths.minecraftJarsPath + "VelocityCTD.jar")

            velocityJarFile.parentFile.mkdirs()

            URL(latestDownload.link).openStream().use { input ->
                velocityJarFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            println("[TemplateManager] VelocityCTD ${latestDownload.version} updated")
            true
        } catch (e: Exception) {
            println("[TemplateManager] Error updateBaseVelocityCTDJar: ${e.message}")
            false
        }
    }

    suspend fun syncStaticServersOnRestart(): Boolean = withContext(Dispatchers.IO) {
        try {
            println("[TemplateManager] Synchronizing static servers...")
            val serviceManager = CloudAPI.instance.getCloudServiceManager()
            val staticServices = serviceManager.getAllCachedObjects().filter { it.isStatic() }

            var allSuccess = true
            staticServices.forEach { service ->
                val success = syncStaticServersOnRestart(service)
                if (!success) allSuccess = false
            }

            return@withContext allSuccess
        } catch (e: Exception) {
            println("[TemplateManager] Error during global syncStaticServersOnRestart: ${e.message}")
            return@withContext false
        }
    }

    suspend fun syncStaticServersOnRestart(service: ICloudService): Boolean = withContext(Dispatchers.IO) {
        try {
            if (needsServerJarUpdate(service)) {
                println("[TemplateManager] Detected service ${service.getName()} needs jar update")
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

            // Update server JARs for static services
            if (needsServerJarUpdate(service)) {
                println("[TemplateManager] Starting jar update for service ${service.getName()}")
                updateServerJar(service, serviceDir)
            }

            return@withContext true

        } catch (e: Exception) {
            println("[TemplateManager] Error during syncStaticServersOnRestart for ${service.getName()}: ${e.message}")
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
            println("[TemplateManager] Error updateServerJar: ${e.message}")
        }
    }

    private suspend fun updateLeafJar(serviceDir: File) = withContext(Dispatchers.IO) {
        try {
            println("[TemplateManager] Updating Leaf jar for service...")

            val serverVersionManager = module.getServerVersionManager()

            // Always fetch latest versions
            println("[TemplateManager] Fetching latest Leaf versions...")
            serverVersionManager.updateAllVersions()

            val leafVersions = serverVersionManager.getCurrentVersions().find { it.name == "Leaf" }

            if (leafVersions != null && leafVersions.downloadLinks.isNotEmpty()) {
                var downloadSuccess = false

                // Try each download link until one works
                for (download in leafVersions.downloadLinks) {
                    try {
                        val jarFile = File(serviceDir, "server.jar")
                        println("[TemplateManager] Attempting to download Leaf ${download.version} from: ${download.link}")

                        val connection = URL(download.link).openConnection()
                        connection.setRequestProperty("User-Agent", "SimpleCloud-AutoUpdater")
                        connection.connectTimeout = 10000
                        connection.readTimeout = 30000

                        connection.getInputStream().use { input ->
                            jarFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }

                        println("[TemplateManager] Successfully downloaded Leaf ${download.version} (${jarFile.length()} bytes)")
                        downloadSuccess = true
                        break

                    } catch (e: Exception) {
                        println("[TemplateManager] Failed to download from ${download.link}: ${e.message}")
                        continue
                    }
                }

                if (!downloadSuccess) {
                    println("[TemplateManager] ERROR: All Leaf download attempts failed")
                }
            } else {
                println("[TemplateManager] No Leaf version available for service")
            }
        } catch (e: Exception) {
            println("[TemplateManager] Error updating Leaf jar service: ${e.message}")
            e.printStackTrace()
        }
    }

    private suspend fun updateVelocityCTDJar(serviceDir: File) = withContext(Dispatchers.IO) {
        try {
            println("[TemplateManager] Updating VelocityCTD jar...")

            val serverVersionManager = module.getServerVersionManager()
            val velocityCTDVersions = serverVersionManager.getCurrentVersions().find { it.name == "VelocityCTD" }

            if (velocityCTDVersions != null && velocityCTDVersions.downloadLinks.isNotEmpty()) {
                val latestDownload = velocityCTDVersions.downloadLinks.first()
                val jarFile = File(serviceDir, "velocity.jar")

                println("[TemplateManager] Downloading VelocityCTD ${latestDownload.version} from: ${latestDownload.link}")

                URL(latestDownload.link).openStream().use { input ->
                    jarFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                println("[TemplateManager] VelocityCTD jar updated successfully to version ${latestDownload.version}")
            } else {
                println("[TemplateManager] No VelocityCTD version available for download")
            }
        } catch (e: Exception) {
            println("[TemplateManager] Error updating VelocityCTD jar: ${e.message}")
            e.printStackTrace()
        }
    }

    private suspend fun createBaseTemplates(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Since the config doesn't define specific template names,
            // we'll create some standard templates if they don't exist
            val baseTemplateNames = listOf(
                "proxy",
                "lobby",
                "server",
                "leaf",
                "velocityctd"
            )

            val createdTemplates = mutableListOf<ITemplate>()
            val templateManager = CloudAPI.instance.getTemplateManager()

            baseTemplateNames.forEach { templateName ->
                // Check if template already exists
                if (templateManager.getTemplateByName(templateName) == null) {
                    val template = DefaultTemplate(templateName)
                    createdTemplates.add(template)
                    templateManager.update(template)
                    println("[TemplateManager] Created base template: $templateName")
                }
            }

            // Sync all created templates
            createdTemplates.forEach { template ->
                syncTemplate(template)
            }

            println("[TemplateManager] Base templates creation completed. Created ${createdTemplates.size} templates.")
            return@withContext true
        } catch (e: Exception) {
            println("[TemplateManager] Error creating base templates: ${e.message}")
            e.printStackTrace()
            return@withContext false
        }
    }

    private suspend fun syncTemplate(template: ITemplate) = withContext(Dispatchers.IO) {
        try {
            val templateDir = File(templatesDirectory, template.getName())
            if (!templateDir.exists()) {
                templateDir.mkdirs()
            }

            val pluginsDir = File(templateDir, "plugins")
            if (!pluginsDir.exists()) {
                pluginsDir.mkdirs()
            }

            // Copy modules to template
            template.getModuleNamesToCopy().forEach { moduleName ->
                val moduleFile = findModuleFile(moduleName)
                if (moduleFile != null && moduleFile.exists()) {
                    val targetFile = File(pluginsDir, moduleFile.name)
                    if (!targetFile.exists() || moduleFile.lastModified() > targetFile.lastModified()) {
                        moduleFile.copyTo(targetFile, overwrite = true)
                        println("[TemplateManager] Copied module ${moduleFile.name} to template ${template.getName()}")
                    }
                }
            }

            // Update Minecraft JARs in templates
            if (isLeafTemplate(template)) {
                updateTemplateLeafJar(templateDir)
            }

            if (isVelocityCTDTemplate(template)) {
                updateTemplateVelocityCTDJar(templateDir)
            }

        } catch (e: Exception) {
            println("[TemplateManager] Error syncing template ${template.getName()}: ${e.message}")
        }
    }

    private suspend fun updateTemplateLeafJar(templateDir: File) = withContext(Dispatchers.IO) {
        try {
            println("[TemplateManager] Updating Leaf jar in template...")

            val serverVersionManager = module.getServerVersionManager()

            // Always fetch latest versions
            serverVersionManager.updateAllVersions()

            val leafVersions = serverVersionManager.getCurrentVersions().find { it.name == "Leaf" }

            if (leafVersions != null && leafVersions.downloadLinks.isNotEmpty()) {
                var downloadSuccess = false

                // Try each download link until one works
                for (download in leafVersions.downloadLinks) {
                    try {
                        val jarFile = File(templateDir, "server.jar")
                        println("[TemplateManager] Attempting to download Leaf ${download.version} from: ${download.link}")

                        val connection = URL(download.link).openConnection()
                        connection.setRequestProperty("User-Agent", "SimpleCloud-AutoUpdater")
                        connection.connectTimeout = 10000
                        connection.readTimeout = 30000

                        connection.getInputStream().use { input ->
                            jarFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }

                        println("[TemplateManager] Successfully downloaded Leaf ${download.version} (${jarFile.length()} bytes)")
                        downloadSuccess = true
                        break

                    } catch (e: Exception) {
                        println("[TemplateManager] Failed to download from ${download.link}: ${e.message}")
                        continue
                    }
                }

                if (!downloadSuccess) {
                    println("[TemplateManager] ERROR: All Leaf download attempts failed")
                }
            } else {
                println("[TemplateManager] No Leaf versions available for download")
            }
        } catch (e: Exception) {
            println("[TemplateManager] Error updating Leaf jar in template: ${e.message}")
            e.printStackTrace()
        }
    }

    private suspend fun updateTemplateVelocityCTDJar(templateDir: File) = withContext(Dispatchers.IO) {
        try {
            println("[TemplateManager] Updating VelocityCTD jar in template...")

            val serverVersionManager = module.getServerVersionManager()
            val velocityCTDVersions = serverVersionManager.getCurrentVersions().find { it.name == "VelocityCTD" }

            if (velocityCTDVersions != null && velocityCTDVersions.downloadLinks.isNotEmpty()) {
                val latestDownload = velocityCTDVersions.downloadLinks.first()
                val jarFile = File(templateDir, "velocity.jar")

                println("[TemplateManager] Downloading VelocityCTD ${latestDownload.version} for template from: ${latestDownload.link}")

                URL(latestDownload.link).openStream().use { input ->
                    jarFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                println("[TemplateManager] VelocityCTD jar template updated successfully to version ${latestDownload.version}")
            }
        } catch (e: Exception) {
            println("[TemplateManager] Error updating VelocityCTD jar in template: ${e.message}")
            e.printStackTrace()
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

    private fun isVelocityCTDService(serviceName: String, groupName: String): Boolean {
        return serviceName.contains("velocityctd") || groupName.contains("velocityctd") ||
                serviceName.contains("velocity-ctd") || groupName.contains("velocity-ctd") ||
                serviceName.contains("ctd") || groupName.contains("ctd")
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

    private fun isLeafTemplate(template: ITemplate): Boolean {
        val templateName = template.getName().lowercase()
        return templateName.contains("leaf") || templateName.contains("server") || templateName.contains("survival")
    }

    private fun isVelocityCTDTemplate(template: ITemplate): Boolean {
        val templateName = template.getName().lowercase()
        return templateName.contains("velocityctd") || templateName.contains("velocity-ctd") ||
                templateName.contains("ctd") || templateName.contains("proxy")
    }
}