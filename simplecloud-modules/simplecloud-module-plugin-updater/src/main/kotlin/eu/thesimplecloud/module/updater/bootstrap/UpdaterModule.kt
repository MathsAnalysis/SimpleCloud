package eu.thesimplecloud.module.updater.bootstrap

import eu.thesimplecloud.api.CloudAPI
import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.api.external.ICloudModule
import eu.thesimplecloud.api.service.version.ServiceVersion
import eu.thesimplecloud.api.service.version.loader.LocalServiceVersionHandler
import eu.thesimplecloud.api.service.version.type.ServiceAPIType
import eu.thesimplecloud.base.manager.serviceversion.ManagerServiceVersionHandler
import eu.thesimplecloud.launcher.startup.Launcher
import eu.thesimplecloud.module.updater.command.UpdaterCommand
import eu.thesimplecloud.module.updater.config.UpdaterConfig
import eu.thesimplecloud.module.updater.manager.JarManager
import eu.thesimplecloud.module.updater.updater.PluginUpdater
import eu.thesimplecloud.module.updater.updater.ServerVersionUpdater
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class UpdaterModule : ICloudModule {

    private lateinit var config: UpdaterConfig
    private lateinit var jarManager: JarManager
    private lateinit var serverVersionUpdater: ServerVersionUpdater
    private lateinit var pluginUpdater: PluginUpdater
    private lateinit var localServiceVersionHandler: LocalServiceVersionHandler

    private val updateScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var updateJob: Job? = null

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun onEnable() {
        println("[UpdaterModule] Starting...")

        loadConfig()
        initializeComponents()
        registerCommands()

        if (config.enabled) {
            updateScope.launch {
                delay(5000)
                println("[UpdaterModule] Running initial update...")
                runUpdate()

                delay(2000)
                registerServiceVersions()
            }

            startUpdateScheduler()
        } else {
            registerServiceVersions()
        }
    }

    override fun onDisable() {
        updateJob?.cancel()
        updateScope.cancel()
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
        okHttpClient.cache?.close()
        serverVersionUpdater.shutdown()
    }

    private fun loadConfig() {
        val configFile = File(DirectoryPaths.paths.modulesPath + "updater/config.json")
        config = if (configFile.exists()) {
            UpdaterConfig.fromFile(configFile)
        } else {
            UpdaterConfig.createDefault().also { it.save(configFile) }
        }
    }

    private fun initializeComponents() {
        jarManager = JarManager(okHttpClient)
        serverVersionUpdater = ServerVersionUpdater(jarManager)
        pluginUpdater = PluginUpdater(okHttpClient)
        localServiceVersionHandler = LocalServiceVersionHandler()
    }

    private fun startUpdateScheduler() {
        println("[UpdaterModule] Starting update scheduler (every ${config.updateIntervalHours} hours)")

        updateJob = updateScope.launch {
            while (isActive) {
                delay(config.updateIntervalHours * 60 * 60 * 1000L)

                println("[UpdaterModule] Running scheduled update...")
                runUpdate()
                registerServiceVersions()
            }
        }
    }

    private suspend fun runUpdate() {
        println("[UpdaterModule] === STARTING UPDATE PROCESS ===")

        var updateResult = false

        if (config.updateServerJars) {
            println("[UpdaterModule] Updating server JARs...")
            val results = serverVersionUpdater.updateAllServerJars()

            println("[UpdaterModule] Server JAR Update Results:")
            results.forEach { (type, success) ->
                println("[UpdaterModule] - $type: ${if (success) "SUCCESS ✓" else "FAILED ✗"}")
                if (success) updateResult = true
            }

            if (updateResult) {
                delay(1000)
                registerServiceVersions()
            }

            println("[UpdaterModule] Syncing JARs to templates...")
            serverVersionUpdater.syncJarsToTemplates()
        }

        if (config.updatePlugins) {
            println("[UpdaterModule] Updating plugins...")
            val pluginResults = pluginUpdater.updateAllPlugins(config.plugins)

            println("[UpdaterModule] Plugin Update Results:")
            pluginResults.forEach { (name, success) ->
                println("[UpdaterModule] - $name: ${if (success) "SUCCESS ✓" else "FAILED ✗"}")
            }

            if (config.syncPluginsToTemplates) {
                println("[UpdaterModule] Syncing plugins to templates...")
                pluginUpdater.syncPluginsToTemplates()
            }
        }

        println("[UpdaterModule] === UPDATE PROCESS COMPLETED ===")
    }

    private fun registerServiceVersions() {
        try {
            println("[UpdaterModule] === STARTING SERVICE VERSION REGISTRATION ===")

            val jarsPath = File(DirectoryPaths.paths.minecraftJarsPath)
            println("[UpdaterModule] Jars directory: ${jarsPath.absolutePath}")
            println("[UpdaterModule] Jars directory exists: ${jarsPath.exists()}")

            if (jarsPath.exists()) {
                val allJars = jarsPath.listFiles()?.filter { it.name.endsWith(".jar") } ?: emptyList()
                println("[UpdaterModule] Found ${allJars.size} jar files:")
                allJars.forEach { jar ->
                    println("[UpdaterModule] - ${jar.name} (${jar.length()} bytes)")
                }
            }

            val localVersionsFile = File(DirectoryPaths.paths.storagePath + "localServiceVersions.json")
            println("[UpdaterModule] Local versions file: ${localVersionsFile.absolutePath}")
            println("[UpdaterModule] Local versions file exists: ${localVersionsFile.exists()}")

            if (localVersionsFile.exists()) {
                val existingVersions = localServiceVersionHandler.loadVersions()
                println("[UpdaterModule] Existing local versions: ${existingVersions.size}")
                existingVersions.forEach { version ->
                    println("[UpdaterModule] - ${version.name} (${version.serviceAPIType})")
                }
            }

            var registeredCount = 0

            if (config.serverVersions.updateLeaf) {
                registeredCount += registerJarVersions("LEAF_", ServiceAPIType.SPIGOT, false)
            }

            if (config.serverVersions.updatePaper) {
                registeredCount += registerJarVersions("PAPER_", ServiceAPIType.SPIGOT, true)
            }

            if (config.serverVersions.updateVelocity) {
                registeredCount += registerJarVersions("VELOCITY_", ServiceAPIType.VELOCITY, false)
            }

            if (config.serverVersions.updateVelocityCtd) {
                registeredCount += registerJarVersions("VELOCITYCTD_", ServiceAPIType.VELOCITY, false)
            }

            println("[UpdaterModule] Registered $registeredCount new service versions")

            reloadServiceVersions()

            println("[UpdaterModule] === SERVICE VERSION REGISTRATION COMPLETED ===")
        } catch (e: Exception) {
            println("[UpdaterModule] ERROR in service version registration: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun registerJarVersions(prefix: String, serviceAPIType: ServiceAPIType, isPaperClip: Boolean): Int {
        return try {
            val latestJar = jarManager.getLatestJar(prefix)
            if (latestJar != null && latestJar.exists() && latestJar.length() > 0) {
                val version = extractVersionFromJarName(latestJar.name, prefix)
                if (version != null && version.isNotBlank()) {
                    val success = registerVersion(prefix, version, latestJar.toURI().toString(), serviceAPIType, isPaperClip)
                    if (success) {
                        println("[UpdaterModule] ✓ Registered: ${latestJar.name}")
                        return@registerJarVersions 1
                    } else {
                        println("[UpdaterModule] ✗ Failed to register: ${latestJar.name}")
                    }
                } else {
                    println("[UpdaterModule] ✗ Could not extract version from: ${latestJar.name}")
                }
            } else {
                println("[UpdaterModule] ✗ No valid jar found for prefix: $prefix")
            }
            0
        } catch (e: Exception) {
            println("[UpdaterModule] ERROR registering jar versions for $prefix: ${e.message}")
            e.printStackTrace()
            0
        }
    }

    private fun registerVersion(prefix: String, version: String, downloadUrl: String, serviceAPIType: ServiceAPIType, isPaperClip: Boolean): Boolean {
        return try {
            val formattedVersion = version.replace(".", "_").replace("-", "_")
            val serviceName = "$prefix$formattedVersion"

            println("[UpdaterModule] Creating ServiceVersion:")
            println("[UpdaterModule] - Name: $serviceName")
            println("[UpdaterModule] - Type: $serviceAPIType")
            println("[UpdaterModule] - URL: $downloadUrl")
            println("[UpdaterModule] - PaperClip: $isPaperClip")

            val serviceVersion = ServiceVersion(
                name = serviceName,
                serviceAPIType = serviceAPIType,
                downloadURL = downloadUrl,
                isPaperClip = isPaperClip
            )

            localServiceVersionHandler.saveServiceVersion(serviceVersion)
            println("[UpdaterModule] ✓ Successfully saved ServiceVersion: $serviceName")

            val savedVersions = localServiceVersionHandler.loadVersions()
            val found = savedVersions.find { it.name == serviceName }
            if (found != null) {
                println("[UpdaterModule] ✓ Verified ServiceVersion in file: $serviceName")
                true
            } else {
                println("[UpdaterModule] ✗ ServiceVersion not found in file after saving: $serviceName")
                false
            }

        } catch (e: Exception) {
            println("[UpdaterModule] ERROR saving ServiceVersion $prefix$version: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private fun reloadServiceVersions() {
        try {
            println("[UpdaterModule] Reloading service versions in manager...")
            val serviceVersionHandler = CloudAPI.instance.getServiceVersionHandler()
            if (serviceVersionHandler is ManagerServiceVersionHandler) {
                serviceVersionHandler.reloadServiceVersions()

                val allVersions = serviceVersionHandler.getAllVersions()
                println("[UpdaterModule] Total service versions loaded: ${allVersions.size}")

                val localVersions = allVersions.filter {
                    it.name.startsWith("LEAF_") || it.name.startsWith("PAPER_") ||
                            it.name.startsWith("VELOCITY_") || it.name.startsWith("VELOCITYCTD_")
                }
                println("[UpdaterModule] Local service versions loaded: ${localVersions.size}")
                localVersions.forEach { version ->
                    println("[UpdaterModule] - ${version.name}")
                }

                println("[UpdaterModule] ✓ Service versions reloaded successfully")
            } else {
                println("[UpdaterModule] ✗ ServiceVersionHandler is not ManagerServiceVersionHandler")
            }
        } catch (e: Exception) {
            println("[UpdaterModule] ERROR reloading service versions: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun extractVersionFromJarName(jarName: String, prefix: String): String? {
        return try {
            val versionPart = jarName.substringAfter(prefix).substringBefore(".jar")

            if (versionPart.isBlank()) {
                println("[UpdaterModule] Empty version part for jar: $jarName")
                return null
            }

            val cleanVersion = versionPart.replace("_", ".")
            println("[UpdaterModule] Extracted version '$cleanVersion' from jar: $jarName")
            cleanVersion

        } catch (e: Exception) {
            println("[UpdaterModule] Error extracting version from $jarName: ${e.message}")
            null
        }
    }

    private fun registerCommands() {
        Launcher.instance.commandManager.registerCommand(this, UpdaterCommand(this))
    }


    fun forceUpdate() {
        updateScope.launch {
            runUpdate()
        }
    }

    fun forceUpdateServers() {
        updateScope.launch {
            if (config.updateServerJars) {
                serverVersionUpdater.updateAllServerJars()
                registerServiceVersions()
                serverVersionUpdater.syncJarsToTemplates()
                println("[UpdaterModule] Server update completed")
            }
        }
    }

    fun forceUpdatePlugins() {
        updateScope.launch {
            if (config.updatePlugins) {
                pluginUpdater.updateAllPlugins(config.plugins)
                if (config.syncPluginsToTemplates) {
                    pluginUpdater.syncPluginsToTemplates()
                }
                println("[UpdaterModule] Plugin update completed")
            }
        }
    }

    fun forceSyncToTemplates() {
        try {
            println("[UpdaterModule] Starting manual template synchronization...")
            serverVersionUpdater.syncJarsToTemplates()
            if (config.syncPluginsToTemplates) {
                pluginUpdater.syncPluginsToTemplates()
            }
            println("[UpdaterModule] Manual template synchronization completed")
        } catch (e: Exception) {
            println("[UpdaterModule] Error during manual template synchronization: ${e.message}")
            e.printStackTrace()
        }
    }

    fun forceRegisterVersions() {
        registerServiceVersions()
    }

    fun getStatus(): UpdateStatus {
        return UpdateStatus(
            enabled = config.enabled,
            lastUpdate = System.currentTimeMillis(),
            nextUpdate = System.currentTimeMillis() + (config.updateIntervalHours * 60 * 60 * 1000L)
        )
    }

    suspend fun checkForUpdates(): Map<String, UpdateInfo> = withContext(Dispatchers.IO) {
        val updates = mutableMapOf<String, UpdateInfo>()

        val jarTypes = mapOf(
            "LEAF_" to "Leaf",
            "PAPER_" to "Paper",
            "VELOCITY_" to "Velocity",
            "VELOCITYCTD_" to "VelocityCtd"
        )

        jarTypes.forEach { (prefix, displayName) ->
            jarManager.getLatestJar(prefix)?.let { currentJar ->
                val currentVersion = extractVersionFromJarName(currentJar.name, prefix) ?: "unknown"
                updates[displayName] = UpdateInfo(
                    currentVersion = currentVersion,
                    latestVersion = "Check manually"
                )
            }
        }

        updates
    }

    data class UpdateStatus(
        val enabled: Boolean,
        val lastUpdate: Long,
        val nextUpdate: Long
    )

    data class UpdateInfo(
        val currentVersion: String,
        val latestVersion: String
    )

    fun getConfig(): UpdaterConfig = config
    fun getServerVersionUpdater(): ServerVersionUpdater = serverVersionUpdater
    fun getJarManager(): JarManager = jarManager
    fun getPluginUpdater(): PluginUpdater = pluginUpdater
}