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
    private var serverVersionManager: ServerVersionManager? = null
    private var pluginManager: PluginManager? = null

    fun getServerVersionManager(): ServerVersionManager {
        if (serverVersionManager == null) {
            serverVersionManager = ServerVersionManager(module, config)
        }
        return serverVersionManager!!
    }

    fun getPluginManager(): PluginManager {
        if (pluginManager == null) {
            pluginManager = PluginManager(module, config)
        }
        return pluginManager!!
    }

    suspend fun syncAllTemplates(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            var success = true

            if (config.templates.autoCreateBaseTemplates) {
                success = createBaseTemplates() && success
            }

            if (config.enableServerVersionUpdates) {
                success = updateBaseJars() && success
            }

            if (config.enablePluginUpdates) {
                success = getPluginManager().ensureAllPluginsDownloaded() && success
            }

            val templateManager = CloudAPI.instance.getTemplateManager()
            val allTemplates = templateManager.getAllCachedObjects()

            allTemplates.forEach { template ->
                try {
                    syncTemplate(template)
                    syncTemplatePlugins(template)
                } catch (e: Exception) {
                    println("[TemplateManager] Errore sync template ${template.getName()}: ${e.message}")
                    success = false
                }
            }

            if (config.enablePluginUpdates) {
                success = syncPluginsToTemplates() && success
            }

            success
        } catch (e: Exception) {
            println("[TemplateManager] Errore generale syncAllTemplates: ${e.message}")
            false
        }
    }

    private suspend fun updateBaseJars(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            var success = true

            val serverVersionManager = getServerVersionManager()
            val updateResult = serverVersionManager.updateAllVersions()
            if (!updateResult) {
                println("[TemplateManager] Fallito aggiornamento versioni server")
                return@withContext false
            }

            val currentVersions = serverVersionManager.getCurrentVersions()

            if (currentVersions.isEmpty()) {
                println("[TemplateManager] Nessuna versione server disponibile")
                return@withContext false
            }

            val leafVersions = currentVersions.find { it.name == "Leaf" }
            if (leafVersions != null) {
                success = updateBaseLeafJar() && success
            }

            val velocityCTDVersions = currentVersions.find { it.name == "VelocityCTD" }
            if (velocityCTDVersions != null) {
                success = updateBaseVelocityCTDJar() && success
            }

            success
        } catch (e: Exception) {
            println("[TemplateManager] Errore updateBaseJars: ${e.message}")
            false
        }
    }

    private suspend fun updateBaseLeafJar(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val baseServerTemplate = File(templatesDirectory, "base-server")
            if (!baseServerTemplate.exists()) {
                baseServerTemplate.mkdirs()
            }

            val jarFile = File(baseServerTemplate, "server.jar")
            val leafDownloadUrl = "https://github.com/Winds-Studio/Leaf/releases/download/ver-1.21.6-dev-18/leaf-1.21.6-dev-18.jar"

            println("[TemplateManager] Download Leaf jar...")

            URL(leafDownloadUrl).openStream().use { input ->
                jarFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            println("[TemplateManager] Leaf aggiornato")
            true
        } catch (e: Exception) {
            println("[TemplateManager] Errore updateBaseLeafJar: ${e.message}")
            false
        }
    }

    private suspend fun updateBaseVelocityCTDJar(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val baseProxyTemplate = File(templatesDirectory, "base-proxy")
            if (!baseProxyTemplate.exists()) {
                baseProxyTemplate.mkdirs()
            }

            val jarFile = File(baseProxyTemplate, "velocity.jar")
            val velocityDownloadUrl = "https://github.com/GemstoneGG/Velocity-CTD/releases/download/Releases/velocity-proxy-3.4.0-SNAPSHOT-all.jar"

            println("[TemplateManager] Download VelocityCTD...")

            URL(velocityDownloadUrl).openStream().use { input ->
                jarFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            println("[TemplateManager] VelocityCTD aggiornato")
            true
        } catch (e: Exception) {
            println("[TemplateManager] Errore updateBaseVelocityCTDJar: ${e.message}")
            false
        }
    }

    suspend fun syncStaticServersOnRestart(service: ICloudService): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            if (!service.isStatic()) {
                return@withContext true
            }

            val serviceDir = File(DirectoryPaths.paths.staticPath, service.getName())
            if (!serviceDir.exists()) {
                serviceDir.mkdirs()
            }

            val pluginsDir = File(serviceDir, "plugins")
            pluginsDir.mkdirs()

            service.getTemplate().getModuleNamesToCopy().forEach { moduleName ->
                try {
                    val moduleFile = findModuleFile(moduleName)
                    if (moduleFile != null && moduleFile.exists()) {
                        val targetFile = File(pluginsDir, moduleFile.name)

                        if (!targetFile.exists() || moduleFile.lastModified() > targetFile.lastModified()) {
                            moduleFile.copyTo(targetFile, overwrite = true)
                        }
                    }
                } catch (e: Exception) {
                    println("[TemplateManager] Errore copia modulo $moduleName: ${e.message}")
                }
            }

            if (needsServerJarUpdate(service)) {
                println("[TemplateManager] Aggiornamento jar per ${service.getName()}")
                updateServerJar(service, serviceDir)
            }

            true

        } catch (e: Exception) {
            println("[TemplateManager] Errore syncStaticServersOnRestart ${service.getName()}: ${e.message}")
            false
        }
    }

    suspend fun syncStaticServersOnRestart(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val serviceManager = CloudAPI.instance.getCloudServiceManager()
            val staticServices = serviceManager.getAllCachedObjects().filter { it.isStatic() }

            var allSuccess = true
            staticServices.forEach { service ->
                val success = syncStaticServersOnRestart(service)
                if (!success) allSuccess = false
            }

            allSuccess
        } catch (e: Exception) {
            println("[TemplateManager] Errore syncStaticServersOnRestart globale: ${e.message}")
            false
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
            println("[TemplateManager] Errore updateServerJar: ${e.message}")
        }
    }

    private suspend fun updateLeafJar(serviceDir: File) = withContext(Dispatchers.IO) {
        try {
            println("[TemplateManager] Aggiornando Leaf jar...")

            val jarFile = File(serviceDir, "server.jar")
            val leafDownloadUrl = "https://github.com/Winds-Studio/Leaf/releases/download/ver-1.21.6-dev-18/leaf-1.21.6-dev-18.jar"

            println("[TemplateManager] Scaricando Leaf...")

            URL(leafDownloadUrl).openStream().use { input ->
                jarFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            println("[TemplateManager] Leaf aggiornato")
        } catch (e: Exception) {
            println("[TemplateManager] Errore updateLeafJar: ${e.message}")
            e.printStackTrace()
        }
    }

    private suspend fun updateVelocityCTDJar(serviceDir: File) = withContext(Dispatchers.IO) {
        try {
            println("[TemplateManager] Aggiornando VelocityCTD jar...")

            val jarFile = File(serviceDir, "velocity.jar")
            val velocityDownloadUrl = "https://github.com/GemstoneGG/Velocity-CTD/releases/download/Releases/velocity-proxy-3.4.0-SNAPSHOT-all.jar"

            println("[TemplateManager] Scaricando VelocityCTD...")

            URL(velocityDownloadUrl).openStream().use { input ->
                jarFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            println("[TemplateManager] VelocityCTD aggiornato")
        } catch (e: Exception) {
            println("[TemplateManager] Errore updateVelocityCTDJar: ${e.message}")
            e.printStackTrace()
        }
    }

    private suspend fun createBaseTemplates(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val templateManager = CloudAPI.instance.getTemplateManager()

            val baseTemplates = listOf("auto-plugins", "base-server", "base-proxy")

            baseTemplates.forEach { name ->
                if (templateManager.getTemplateByName(name) == null) {
                    val template = DefaultTemplate(name)
                    templateManager.update(template)
                    template.getDirectory().mkdirs()
                }
            }

            true
        } catch (e: Exception) {
            println("[TemplateManager] Errore createBaseTemplates: ${e.message}")
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
                println("[TemplateManager] Errore copia plugin $moduleName: ${e.message}")
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

    private fun isLeafService(serviceName: String, groupName: String): Boolean {
        return serviceName.contains("leaf", true) || groupName.contains("leaf", true)
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

        return try {
            val serviceVersionName = service.getServiceVersion().name.uppercase()
            serviceVersionName.startsWith("VELOCITYCTD")
        } catch (e: Exception) {
            false
        }
    }

    private fun isVelocityService(serviceName: String, groupName: String): Boolean {
        return (serviceName.contains("velocity") || groupName.contains("velocity")) &&
                !isVelocityCTDService(serviceName, groupName)
    }

    private fun isPaperService(serviceName: String, groupName: String): Boolean {
        return serviceName.contains("paper") || groupName.contains("paper") ||
                serviceName.contains("purpur") || groupName.contains("purpur")
    }

    private fun isBungeeService(serviceName: String, groupName: String): Boolean {
        return serviceName.contains("bungee") || groupName.contains("bungee") ||
                serviceName.contains("waterfall") || groupName.contains("waterfall")
    }

    private suspend fun syncPluginsToTemplates(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val templateManager = CloudAPI.instance.getTemplateManager()
            val allTemplates = templateManager.getAllCachedObjects()

            allTemplates.forEach { template ->
                val updater = template.getUpdater()
                var shouldUpdate = false

                when {
                    isServerTemplate(template.getName()) -> {
                        val serverPlugins = getServerPluginsForTemplate(template.getName())
                        serverPlugins.forEach { pluginName ->
                            if (!updater.getModuleNamesToCopy().contains(pluginName)) {
                                updater.addModuleNameToCopy(pluginName)
                                shouldUpdate = true
                                println("[TemplateManager] Aggiunto plugin $pluginName al template ${template.getName()}")
                            }
                        }
                    }

                    isProxyTemplate(template.getName()) -> {
                        val proxyPlugins = getProxyPluginsForTemplate(template.getName())
                        proxyPlugins.forEach { pluginName ->
                            if (!updater.getModuleNamesToCopy().contains(pluginName)) {
                                updater.addModuleNameToCopy(pluginName)
                                shouldUpdate = true
                                println("[TemplateManager] Aggiunto plugin $pluginName al template ${template.getName()}")
                            }
                        }
                    }
                }

                if (shouldUpdate) {
                    updater.update()
                }
            }

            true
        } catch (e: Exception) {
            println("[TemplateManager] Errore syncPluginsToTemplates: ${e.message}")
            false
        }
    }

    private fun isServerTemplate(templateName: String): Boolean {
        return templateName.contains("server", true) ||
                templateName.contains("lobby", true) ||
                templateName.contains("survival", true) ||
                templateName.contains("creative", true) ||
                templateName.contains("base-server", true)
    }

    private fun isProxyTemplate(templateName: String): Boolean {
        return templateName.contains("proxy", true) ||
                templateName.contains("bungee", true) ||
                templateName.contains("velocity", true) ||
                templateName.contains("base-proxy", true)
    }

    private fun getServerPluginsForTemplate(templateName: String): List<String> {
        return config.plugins.filter { plugin ->
            plugin.enabled && plugin.platforms.contains("bukkit")
        }.map { it.name }
    }

    private fun getProxyPluginsForTemplate(templateName: String): List<String> {
        return config.plugins.filter { plugin ->
            plugin.enabled && (plugin.platforms.contains("velocity") || plugin.platforms.contains("bungeecord"))
        }.map { it.name }
    }
}