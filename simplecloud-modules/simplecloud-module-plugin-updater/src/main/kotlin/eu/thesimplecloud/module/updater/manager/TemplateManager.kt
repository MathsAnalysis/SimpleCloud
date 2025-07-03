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
            println("[TemplateManager] Avvio sincronizzazione template...")
            var success = true

            if (config.templates.autoCreateBaseTemplates) {
                success = createBaseTemplates() && success
            }

            if (config.enableServerVersionUpdates) {
                println("[TemplateManager] Aggiornamento server abilitato, avvio updateBaseJars...")
                success = updateBaseJars() && success
            }

            val templateManager = CloudAPI.instance.getTemplateManager()
            val allTemplates = templateManager.getAllCachedObjects()

            allTemplates.forEach { template ->
                try {
                    syncTemplate(template)
                } catch (e: Exception) {
                    println("[TemplateManager] Errore sync template ${template.getName()}: ${e.message}")
                    success = false
                }
            }

            println("[TemplateManager] Sincronizzazione template completata con successo: $success")
            success
        } catch (e: Exception) {
            println("[TemplateManager] Errore generale syncAllTemplates: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private suspend fun updateBaseJars(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            println("[TemplateManager] Avvio aggiornamento base jars...")
            var success = true

            val serverVersionManager = module.getServerVersionManager()

            // Prima aggiorna le versioni dall'API GitHub
            println("[TemplateManager] Chiamando updateAllVersions...")
            val updateResult = serverVersionManager.updateAllVersions()
            if (!updateResult) {
                println("[TemplateManager] ERRORE: Fallito aggiornamento versioni server")
                return@withContext false
            }

            val currentVersions = serverVersionManager.getCurrentVersions()
            println("[TemplateManager] Versioni disponibili: ${currentVersions.map { "${it.name}:${it.latestVersion}" }}")

            if (currentVersions.isEmpty()) {
                println("[TemplateManager] ERRORE: Nessuna versione server disponibile")
                return@withContext false
            }

            val leafVersions = currentVersions.find { it.name == "Leaf" }
            if (leafVersions != null) {
                println("[TemplateManager] Trovata configurazione Leaf, avvio aggiornamento...")
                success = updateBaseLeafJar(leafVersions) && success
            } else {
                println("[TemplateManager] ATTENZIONE: Configurazione Leaf non trovata")
            }

            val velocityCTDVersions = currentVersions.find { it.name == "VelocityCTD" }
            if (velocityCTDVersions != null) {
                success = updateBaseVelocityCTDJar(velocityCTDVersions) && success
            }

            println("[TemplateManager] Aggiornamento base jars completato: $success")
            success
        } catch (e: Exception) {
            println("[TemplateManager] Errore updateBaseJars: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private suspend fun updateBaseLeafJar(leafVersionEntry: ServerVersionManager.ServerVersionEntry): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            println("[TemplateManager] === AGGIORNAMENTO LEAF INIZIATO ===")
            println("[TemplateManager] Versione Leaf: ${leafVersionEntry.latestVersion}")
            println("[TemplateManager] Download links disponibili: ${leafVersionEntry.downloadLinks.size}")

            if (leafVersionEntry.downloadLinks.isEmpty()) {
                println("[TemplateManager] ERRORE: Nessun link download disponibile per Leaf")
                return@withContext false
            }

            val latestDownload = leafVersionEntry.downloadLinks.first()
            println("[TemplateManager] Usando download: ${latestDownload.version} -> ${latestDownload.link}")

            // Crea directory per la versione specifica
            val leafVersionDir = File(DirectoryPaths.paths.minecraftJarsPath + "LEAF_${latestDownload.version.replace(".", "_")}/")
            if (!leafVersionDir.exists()) {
                leafVersionDir.mkdirs()
                println("[TemplateManager] Creata directory: ${leafVersionDir.absolutePath}")
            }

            val paperclipFile = File(leafVersionDir, "paperclip.jar")

            // Controlla se è già aggiornato
            if (paperclipFile.exists()) {
                val fileAge = System.currentTimeMillis() - paperclipFile.lastModified()
                val oneDayMillis = 24 * 60 * 60 * 1000

                if (fileAge < oneDayMillis) {
                    println("[TemplateManager] Leaf ${latestDownload.version} già aggiornato (file recente)")
                    return@withContext true
                }
            }

            println("[TemplateManager] Avvio download Leaf ${latestDownload.version} da: ${latestDownload.link}")

            // Download del JAR
            URL(latestDownload.link).openStream().use { input ->
                paperclipFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            println("[TemplateManager] ✅ Leaf ${latestDownload.version} scaricato con successo!")
            println("[TemplateManager] ✅ File salvato in: ${paperclipFile.absolutePath}")
            println("[TemplateManager] ✅ Dimensione file: ${paperclipFile.length()} bytes")

            // NON eseguire il paperclip, solo scaricarlo!
            println("[TemplateManager] === AGGIORNAMENTO LEAF COMPLETATO ===")

            true

        } catch (e: Exception) {
            println("[TemplateManager] ❌ ERRORE updateBaseLeafJar: ${e.message}")
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

            println("[TemplateManager] VelocityCTD ${latestDownload.version} aggiornato")
            true
        } catch (e: Exception) {
            println("[TemplateManager] Errore updateBaseVelocityCTDJar: ${e.message}")
            false
        }
    }

    suspend fun syncStaticServersOnRestart(): Boolean = withContext(Dispatchers.IO) {
        try {
            println("[TemplateManager] Sincronizzazione server statici...")
            val serviceManager = CloudAPI.instance.getCloudServiceManager()
            val staticServices = serviceManager.getAllCachedObjects().filter { it.isStatic() }

            var allSuccess = true
            staticServices.forEach { service ->
                val success = syncStaticServersOnRestart(service)
                if (!success) allSuccess = false
            }

            return@withContext allSuccess
        } catch (e: Exception) {
            println("[TemplateManager] Errore durante syncStaticServersOnRestart globale: ${e.message}")
            return@withContext false
        }
    }

    suspend fun syncStaticServersOnRestart(service: ICloudService): Boolean = withContext(Dispatchers.IO) {
        try {
            if (needsServerJarUpdate(service)) {
                println("[TemplateManager] Rilevato servizio ${service.getName()} che necessita aggiornamento jar")
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
                println("[TemplateManager] Avvio aggiornamento jar per servizio ${service.getName()}")
                updateServerJar(service, serviceDir)
            }

            return@withContext true

        } catch (e: Exception) {
            println("[TemplateManager] Errore durante syncStaticServersOnRestart per ${service.getName()}: ${e.message}")
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
            println("[TemplateManager] Errore updateServerJar: ${e.message}")
        }
    }

    private suspend fun updateLeafJar(serviceDir: File) = withContext(Dispatchers.IO) {
        try {
            println("[TemplateManager] Aggiornando Leaf jar per servizio...")

            val serverVersionManager = module.getServerVersionManager()
            val leafVersions = serverVersionManager.getCurrentVersions().find { it.name == "Leaf" }

            if (leafVersions != null && leafVersions.downloadLinks.isNotEmpty()) {
                val latestDownload = leafVersions.downloadLinks.first()
                val jarFile = File(serviceDir, "server.jar")

                println("[TemplateManager] Scaricando Leaf ${latestDownload.version} per servizio da: ${latestDownload.link}")

                URL(latestDownload.link).openStream().use { input ->
                    jarFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                println("[TemplateManager] Leaf jar servizio aggiornato con successo a versione ${latestDownload.version}")
            } else {
                println("[TemplateManager] Nessuna versione Leaf disponibile per il servizio")
            }
        } catch (e: Exception) {
            println("[TemplateManager] Errore durante l'aggiornamento Leaf jar servizio: ${e.message}")
            e.printStackTrace()
        }
    }

    private suspend fun updateVelocityCTDJar(serviceDir: File) = withContext(Dispatchers.IO) {
        try {
            println("[TemplateManager] Aggiornando VelocityCTD jar...")

            val serverVersionManager = module.getServerVersionManager()
            val velocityCTDVersions = serverVersionManager.getCurrentVersions().find { it.name == "VelocityCTD" }

            if (velocityCTDVersions != null && velocityCTDVersions.downloadLinks.isNotEmpty()) {
                val latestDownload = velocityCTDVersions.downloadLinks.first()
                val jarFile = File(serviceDir, "velocity.jar")

                println("[TemplateManager] Scaricando VelocityCTD ${latestDownload.version} da: ${latestDownload.link}")

                URL(latestDownload.link).openStream().use { input ->
                    jarFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                println("[TemplateManager] VelocityCTD jar aggiornato con successo a versione ${latestDownload.version}")
            } else {
                println("[TemplateManager] Nessuna versione VelocityCTD disponibile per il download")
            }
        } catch (e: Exception) {
            println("[TemplateManager] Errore durante l'aggiornamento VelocityCTD jar: ${e.message}")
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
}