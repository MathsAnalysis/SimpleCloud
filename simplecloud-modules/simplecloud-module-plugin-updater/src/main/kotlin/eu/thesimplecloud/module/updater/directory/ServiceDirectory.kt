/*
 * MIT License
 *
 * Copyright (C) 2020-2022 The SimpleCloud authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software,
 * and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package eu.thesimplecloud.base.wrapper.process.filehandler

import eu.thesimplecloud.api.CloudAPI
import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.api.service.ICloudService
import eu.thesimplecloud.api.service.ServiceType
import eu.thesimplecloud.api.template.ITemplate
import eu.thesimplecloud.base.wrapper.startup.Wrapper
import eu.thesimplecloud.clientserverapi.client.NettyClient
import eu.thesimplecloud.jsonlib.JsonLib
import eu.thesimplecloud.launcher.external.module.LoadedModuleFileContent
import eu.thesimplecloud.launcher.external.module.ModuleCopyType
import eu.thesimplecloud.launcher.startup.Launcher
import eu.thesimplecloud.launcher.utils.FileCopier
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException

class ServiceDirectory(private val cloudService: ICloudService) {

    private var copiedModulesAsPlugins: List<File> = emptyList()
    private var copiedPlugins: List<File> = emptyList()
    val serviceTmpDirectory = getServiceTmpDirectory(cloudService)

    fun copyTemplateFilesAndModules() {
        copyTemplateFiles()
        copyServiceVersionIfNotExist()
        copyModules()
        copyAutoDownloadedPlugins()
    }

    fun deleteTemporaryModuleFiles() {
        this.copiedModulesAsPlugins.forEach { it.delete() }
        this.copiedPlugins.forEach { it.delete() }
    }

    @Throws(IOException::class)
    fun deleteServiceDirectoryUnsafe() {
        FileUtils.deleteDirectory(this.serviceTmpDirectory)
    }

    private fun copyAutoDownloadedPlugins() {
        val copiedPluginsList = mutableListOf<File>()
        val pluginsDirectory = File(DirectoryPaths.paths.storagePath + "plugins")
        val logsDirectory = File(DirectoryPaths.paths.modulesPath + "automanager/logs")
        val logFile = File(logsDirectory, "service-directory-${System.currentTimeMillis()}.log")

        if (!logsDirectory.exists()) {
            logsDirectory.mkdirs()
        }

        fun log(message: String) {
            val timestamp = java.time.LocalDateTime.now()
            val logMessage = "[$timestamp] $message"
            println("[ServiceDirectory] $message")
            try {
                logFile.appendText("$logMessage\n")
            } catch (e: Exception) {
                println("[ServiceDirectory] Failed to write to log: ${e.message}")
            }
        }

        log("Starting plugin copy for service: ${cloudService.getName()}")
        log("Service type: ${cloudService.getServiceType()}")
        log("Service version: ${cloudService.getServiceVersion().name}")

        if (!pluginsDirectory.exists()) {
            log("Plugins directory not found: ${pluginsDirectory.absolutePath}")
            return
        }

        val platform = when {
            cloudService.isProxy() -> when (cloudService.getServiceVersion().name.lowercase()) {
                in listOf("bungeecord", "waterfall", "hexacord", "flamecord") -> "bungeecord"
                in listOf("velocity") -> "velocity"
                else -> null
            }
            else -> "bukkit"
        }

        log("Determined platform: $platform")
        log("Scanning plugins directory: ${pluginsDirectory.absolutePath}")

        pluginsDirectory.listFiles()?.forEach { pluginDir ->
            if (pluginDir.isDirectory) {
                log("Found plugin directory: ${pluginDir.name}")
                val platformDir = File(pluginDir, platform ?: "bukkit")

                if (platformDir.exists() && platformDir.isDirectory) {
                    log("Found platform directory: ${platformDir.absolutePath}")

                    platformDir.listFiles()?.filter { it.extension == "jar" }?.forEach { jarFile ->
                        try {
                            val targetPluginsDir = File(this.serviceTmpDirectory, "plugins")
                            targetPluginsDir.mkdirs()

                            val targetFile = File(targetPluginsDir, jarFile.name)

                            log("Copying plugin: ${jarFile.name} (${jarFile.length() / 1024} KB)")
                            log("Source: ${jarFile.absolutePath}")
                            log("Target: ${targetFile.absolutePath}")

                            jarFile.copyTo(targetFile, overwrite = true)
                            copiedPluginsList.add(targetFile)

                            log("Successfully copied ${jarFile.name}")
                        } catch (e: Exception) {
                            log("ERROR copying plugin ${jarFile.name}: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                } else {
                    log("Platform directory not found: ${platformDir.absolutePath}")
                }
            }
        }

        this.copiedPlugins = copiedPluginsList
        log("Plugin copy completed. Total plugins copied: ${copiedPluginsList.size}")
        log("Copied plugins: ${copiedPluginsList.map { it.name }}")
    }

    private fun copyServiceVersionIfNotExist() {
        val expectedExecutableJar = File(this.serviceTmpDirectory, "server.jar")
        if (expectedExecutableJar.exists()) {
            return
        }

        val loadedServiceVersion =
            Wrapper.instance.serviceVersionLoader.loadVersionFile(this.cloudService.getServiceVersion())
        loadedServiceVersion.copyToDirectory(this.serviceTmpDirectory)
        renameExecutableJar(loadedServiceVersion)
    }

    private fun renameExecutableJar(loadedServiceVersion: LoadedServiceVersion) {
        val executableJar = File(this.serviceTmpDirectory, loadedServiceVersion.fileNameToExecute)
        val renamedExecutableJar = File(this.serviceTmpDirectory, "server.jar")
        executableJar.renameTo(renamedExecutableJar)
    }

    private fun copyTemplateFiles() {
        val template = cloudService.getTemplate()
        val everyDir = File(DirectoryPaths.paths.templatesPath + "EVERY")
        val everyTypeDir = if (cloudService.getServiceType() == ServiceType.PROXY)
            File(DirectoryPaths.paths.templatesPath + "EVERY_PROXY")
        else
            File(DirectoryPaths.paths.templatesPath + "EVERY_SERVER")
        val templateDirectories = getDirectoriesOfTemplateAndSubTemplates(template)

        val dontCopyTemplates = cloudService.isStatic() && !cloudService.isForceCopyTemplates() && this.serviceTmpDirectory.exists()
        if (!dontCopyTemplates) {
            if (everyDir.exists())
                FileUtils.copyDirectory(everyDir, this.serviceTmpDirectory)
            if (everyTypeDir.exists())
                FileUtils.copyDirectory(everyTypeDir, this.serviceTmpDirectory)
            templateDirectories.filter { it.exists() }.forEach { FileUtils.copyDirectory(it, this.serviceTmpDirectory) }
        }

        if (cloudService.getServiceType() == ServiceType.PROXY) {
            val destServerIconFile = File(this.serviceTmpDirectory, "/server-icon.png")
            if (!destServerIconFile.exists())
                FileCopier.copyFileOutOfJar(destServerIconFile, "/files/server-icon.png")
        }

        val cloudPluginFile = File(this.serviceTmpDirectory, "/plugins/SimpleCloud-Plugin.jar")
        val version = Launcher.instance.getCurrentVersion().replace("-SNAPSHOT", "")
        File(DirectoryPaths.paths.storagePath + "pluginJars/SimpleCloud-Plugin-$version.jar").copyTo(cloudPluginFile, true)

        generateServiceFile()
    }

    private fun copyModules() {
        val modulesForService = getModulesForService()
        modulesForService.forEach {
            FileUtils.copyFile(
                it.file,
                File(this.serviceTmpDirectory, "/plugins/" + it.file.name)
            )
        }
        this.copiedModulesAsPlugins = getModuleFilesInService()
    }

    private fun getServiceTmpDirectory(cloudService: ICloudService): File {
        return if (cloudService.isStatic())
            File(DirectoryPaths.paths.staticPath + cloudService.getName())
        else
            File(DirectoryPaths.paths.tempPath + cloudService.getName())
    }

    private fun getModulesForService(): List<LoadedModuleFileContent> {
        val modulesByCopyType = Wrapper.instance.existingModules
            .filter { it.content.moduleCopyType != ModuleCopyType.NONE }.toMutableList()
        if (!cloudService.isLobby())
            modulesByCopyType.removeIf { it.content.moduleCopyType == ModuleCopyType.LOBBY }
        if (!cloudService.isProxy())
            modulesByCopyType.removeIf { it.content.moduleCopyType == ModuleCopyType.PROXY }
        if (cloudService.isProxy())
            modulesByCopyType.removeIf { it.content.moduleCopyType == ModuleCopyType.SERVER }

        val moduleNamesToCopy = getModulesToCopyOfTemplateAndSubTemplates(this.cloudService.getTemplate())
        val modulesByName = Wrapper.instance.existingModules.filter { moduleNamesToCopy.contains(it.content.name) }
        return modulesByCopyType.union(modulesByName).distinctBy { it.content.name }
    }

    fun getModuleFilesInService(): List<File> {
        val modulesForService = getModulesForService()
        return modulesForService.map { File(this.serviceTmpDirectory, "/plugins/" + it.file.name) }
    }

    private fun generateServiceFile() {
        val communicationClient = Wrapper.instance.communicationClient
        communicationClient as NettyClient
        JsonLib.empty().append("managerHost", communicationClient.getHost())
            .append("managerPort", communicationClient.getPort())
            .append("serviceName", cloudService.getName())
            .saveAsFile(File(this.serviceTmpDirectory, "SIMPLE-CLOUD.json"))
    }

    private fun getDirectoriesOfTemplateAndSubTemplates(template: ITemplate): Set<File> {
        val set = HashSet<File>()
        for (templateName in template.getInheritedTemplateNames()) {
            val subTemplate = CloudAPI.instance.getTemplateManager().getTemplateByName(templateName)
            subTemplate?.let { set.addAll(getDirectoriesOfTemplateAndSubTemplates(it)) }
        }
        set.add(template.getDirectory())
        return set
    }

    private fun getModulesToCopyOfTemplateAndSubTemplates(template: ITemplate): Set<String> {
        val set = HashSet<String>()
        for (templateName in template.getInheritedTemplateNames()) {
            val subTemplate = CloudAPI.instance.getTemplateManager().getTemplateByName(templateName)
            subTemplate?.let { set.addAll(getModulesToCopyOfTemplateAndSubTemplates(it)) }
        }
        set.addAll(template.getModuleNamesToCopy())
        return set
    }
}