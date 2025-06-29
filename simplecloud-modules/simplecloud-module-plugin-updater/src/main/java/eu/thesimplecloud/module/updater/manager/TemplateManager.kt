package eu.thesimplecloud.module.updater.manager

import eu.thesimplecloud.api.CloudAPI
import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.api.template.ITemplate
import eu.thesimplecloud.api.template.impl.DefaultTemplate
import eu.thesimplecloud.module.automanager.config.AutoManagerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TemplateManager(
    private val module: PluginUpdaterModule,
    private val config: AutoManagerConfig
) {

    private val templatesDirectory = File(DirectoryPaths.paths.templatesPath)

    suspend fun syncAllTemplates(): Boolean = withContext(Dispatchers.Main) {
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

    private suspend fun createBaseTemplates(): Boolean = withContext(Dispatchers.Main) {
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
}
