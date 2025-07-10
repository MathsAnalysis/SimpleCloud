package eu.thesimplecloud.module.updater.updater

import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.jsonlib.JsonLib
import eu.thesimplecloud.module.updater.bootstrap.PluginUpdaterModule
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.file.*
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class AutomaticJarUpdater(private val module: PluginUpdaterModule) {

    private val watchService = FileSystems.getDefault().newWatchService()
    private val checksumFile = File(DirectoryPaths.paths.storagePath + "jar_checksums.json")
    private val lockFile = File(DirectoryPaths.paths.storagePath + "jar_update.lock")
    private val lastUpdateFile = File(DirectoryPaths.paths.storagePath + "last_jar_update.txt")
    private val minecraftJarsPath = File(DirectoryPaths.paths.minecraftJarsPath)
    private val templatesPath = File(DirectoryPaths.paths.templatesPath)

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val updateSemaphore = Semaphore(1)
    private val downloadSemaphore = Semaphore(2)

    private val isLinux = !System.getProperty("os.name").lowercase().contains("windows")
    private val useSystemd = isLinux && File("/bin/systemctl").exists()

    fun startAutomaticMonitoring() {
        if (!module.getConfig().enableAutomation) {
            println("[AutoJarUpdater] Automation disabled, skipping monitoring")
            return
        }

        println("[AutoJarUpdater] Starting automatic JAR monitoring and updates")

        minecraftJarsPath.mkdirs()
        templatesPath.mkdirs()

        GlobalScope.launch {
            try {
                setupFileWatching()
                startPeriodicChecks()
            } catch (e: Exception) {
                println("[AutoJarUpdater] Error starting monitoring: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private suspend fun setupFileWatching() = withContext(Dispatchers.IO) {
        val minecraftJarsWatchPath = Paths.get(minecraftJarsPath.absolutePath)
        val templatesWatchPath = Paths.get(templatesPath.absolutePath)

        listOf(minecraftJarsWatchPath, templatesWatchPath).forEach { path ->
            if (Files.exists(path)) {
                path.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE
                )
                println("[AutoJarUpdater] Monitoring: $path")
            }
        }

        while (true) {
            val key = watchService.take()

            for (event in key.pollEvents()) {
                val fileName = event.context().toString()

                if (fileName.endsWith(".jar")) {
                    println("[AutoJarUpdater] Detected change: $fileName")
                    handleJarFileChange(fileName, event.kind())
                }
            }

            if (!key.reset()) break
        }
    }

    private fun handleJarFileChange(fileName: String, eventKind: WatchEvent.Kind<*>) {
        when (eventKind) {
            StandardWatchEventKinds.ENTRY_CREATE -> {
                println("[AutoJarUpdater] New JAR detected: $fileName")
                validateJarIntegrity(fileName)
                updateChecksums()
            }
            StandardWatchEventKinds.ENTRY_MODIFY -> {
                println("[AutoJarUpdater] JAR modified: $fileName")
                validateJarIntegrity(fileName)
            }
            StandardWatchEventKinds.ENTRY_DELETE -> {
                println("[AutoJarUpdater] JAR deleted: $fileName")
                handleMissingJar(fileName)
            }
        }
    }

    private suspend fun startPeriodicChecks() {
        val updateInterval = TimeUnit.HOURS.toMillis(1)

        while (true) {
            try {
                if (shouldPerformUpdate()) {
                    println("[AutoJarUpdater] Starting hourly forced update cycle")
                    performForcedUpdate()
                }

                delay(updateInterval)
            } catch (e: Exception) {
                println("[AutoJarUpdater] Error in periodic check: ${e.message}")
                e.printStackTrace()
                delay(TimeUnit.MINUTES.toMillis(5))
            }
        }
    }

    private suspend fun performForcedUpdate() {
        if (!updateSemaphore.tryAcquire()) {
            println("[AutoJarUpdater] Update already in progress, skipping")
            return
        }

        try {
            if (!acquireUpdateLock()) {
                println("[AutoJarUpdater] Could not acquire update lock")
                return
            }

            println("[AutoJarUpdater] === STARTING FORCED HOURLY UPDATE ===")

            cleanupOldJars()

            downloadLatestJars()

            updateAllTemplates()

            verifyAllJarsIntegrity()

            updateChecksums()

            lastUpdateFile.writeText(System.currentTimeMillis().toString())

            println("[AutoJarUpdater] === FORCED HOURLY UPDATE COMPLETED ===")
            sendSystemNotification("JAR update completed successfully")

        } catch (e: Exception) {
            println("[AutoJarUpdater] Error during forced update: ${e.message}")
            e.printStackTrace()
            sendSystemNotification("JAR update failed: ${e.message}")
        } finally {
            releaseUpdateLock()
            updateSemaphore.release()
        }
    }

    private fun cleanupOldJars() {
        println("[AutoJarUpdater] Cleaning up old JAR files")

        minecraftJarsPath.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".jar")) {
                println("[AutoJarUpdater] Deleting old JAR: ${file.name}")
                file.delete()
            }
        }

        templatesPath.listFiles()?.forEach { templateDir ->
            if (templateDir.isDirectory) {
                templateDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(".jar")) {
                        val age = System.currentTimeMillis() - file.lastModified()
                        if (age > TimeUnit.HOURS.toMillis(2)) {
                            println("[AutoJarUpdater] Deleting old template JAR: ${templateDir.name}/${file.name}")
                            file.delete()
                        }
                    }
                }
            }
        }
    }

    private suspend fun downloadLatestJars() {
        println("[AutoJarUpdater] Downloading latest JAR versions")

        val versions = module.getServerVersionManager().getCurrentVersions()

        versions.forEach { serverVersion ->
            serverVersion.downloadLinks.forEach { downloadLink ->
                try {
                    downloadSemaphore.acquire()

                    val jarName = when (serverVersion.name.lowercase()) {
                        "leaf" -> "LEAF_${sanitizeVersion(downloadLink.version)}.jar"
                        "paper" -> "PAPER_${sanitizeVersion(downloadLink.version)}.jar"
                        "velocity" -> "VELOCITY_${sanitizeVersion(downloadLink.version)}.jar"
                        "velocityctd" -> "VELOCITYCTD_${sanitizeVersion(downloadLink.version)}.jar"
                        else -> "${serverVersion.name.uppercase()}_${sanitizeVersion(downloadLink.version)}.jar"
                    }

                    val targetFile = File(minecraftJarsPath, jarName)

                    if (downloadJar(downloadLink.link, targetFile)) {
                        println("[AutoJarUpdater] Downloaded: $jarName (${targetFile.length() / 1024}KB)")
                    } else {
                        println("[AutoJarUpdater] Failed to download: $jarName")
                    }

                } catch (e: Exception) {
                    println("[AutoJarUpdater] Error downloading ${serverVersion.name}: ${e.message}")
                } finally {
                    downloadSemaphore.release()
                }
            }
        }
    }

    private suspend fun downloadJar(url: String, targetFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "SimpleCloud-AutoUpdater/1.0")
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (response.isSuccessful) {
                response.body?.use { body ->
                    targetFile.writeBytes(body.bytes())
                }
                true
            } else {
                println("[AutoJarUpdater] HTTP ${response.code} for $url")
                false
            }
        } catch (e: Exception) {
            println("[AutoJarUpdater] Download error for $url: ${e.message}")
            false
        }
    }

    private fun updateAllTemplates() {
        println("[AutoJarUpdater] Updating all templates with new JAR files")

        templatesPath.listFiles()?.forEach { templateDir ->
            if (templateDir.isDirectory) {
                updateTemplateJars(templateDir)
            }
        }
    }

    private fun updateTemplateJars(templateDir: File) {
        val templateName = templateDir.name
        println("[AutoJarUpdater] Updating template: $templateName")

        val serverType = determineServerType(templateName)

        if (serverType != null) {
            val sourceJar = findLatestJarForType(serverType)

            if (sourceJar != null && sourceJar.exists()) {
                val targetFile = File(templateDir, sourceJar.name)
                try {
                    sourceJar.copyTo(targetFile, overwrite = true)
                    println("[AutoJarUpdater] Updated template $templateName with ${sourceJar.name}")
                } catch (e: Exception) {
                    println("[AutoJarUpdater] Error updating template $templateName: ${e.message}")
                }
            }
        }
    }

    private fun determineServerType(templateName: String): String? {
        val groupFile = File(DirectoryPaths.paths.storagePath + "groups/$templateName.json")
        if (groupFile.exists()) {
            try {
                val groupData = JsonLib.fromJsonFile(groupFile)
                val serviceVersion = groupData?.getString("serviceVersion")
                return when {
                    serviceVersion?.startsWith("LEAF_") == true -> "LEAF"
                    serviceVersion?.startsWith("PAPER_") == true -> "PAPER"
                    serviceVersion?.startsWith("VELOCITY_") == true -> "VELOCITY"
                    serviceVersion?.startsWith("VELOCITYCTD_") == true -> "VELOCITYCTD"
                    else -> null
                }
            } catch (e: Exception) {
                println("[AutoJarUpdater] Error reading group config for $templateName: ${e.message}")
            }
        }
        return null
    }

    private fun findLatestJarForType(serverType: String): File? {
        val jars = minecraftJarsPath.listFiles()?.filter {
            it.name.startsWith("${serverType}_") && it.name.endsWith(".jar")
        }?.sortedByDescending { it.lastModified() }

        return jars?.firstOrNull()
    }

    private fun verifyAllJarsIntegrity() {
        println("[AutoJarUpdater] Verifying JAR integrity")

        minecraftJarsPath.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".jar")) {
                val isValid = validateJarIntegrity(file.name)
                println("[AutoJarUpdater] JAR ${file.name} integrity: ${if (isValid) "VALID" else "INVALID"}")
            }
        }
    }

    private fun validateJarIntegrity(fileName: String): Boolean {
        val file = File(minecraftJarsPath, fileName)
        if (!file.exists()) return false

        try {
            val bytes = file.readBytes()
            val isValidJar = bytes.size >= 4 &&
                    bytes[0] == 0x50.toByte() &&
                    bytes[1] == 0x4B.toByte() &&
                    (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte()) &&
                    (bytes[3] == 0x04.toByte() || bytes[3] == 0x06.toByte() || bytes[3] == 0x08.toByte())

            if (!isValidJar) {
                println("[AutoJarUpdater] Invalid JAR format: $fileName")
                return false
            }

            if (file.length() < 1024 * 100) {
                println("[AutoJarUpdater] JAR too small: $fileName (${file.length()} bytes)")
                return false
            }

            return true

        } catch (e: Exception) {
            println("[AutoJarUpdater] Error validating JAR $fileName: ${e.message}")
            return false
        }
    }

    private fun handleMissingJar(fileName: String) {
        println("[AutoJarUpdater] Handling missing JAR: $fileName")

        GlobalScope.launch {
            try {
                downloadLatestJars()
            } catch (e: Exception) {
                println("[AutoJarUpdater] Error re-downloading missing JAR: ${e.message}")
            }
        }
    }

    private fun updateChecksums() {
        println("[AutoJarUpdater] Updating JAR checksums")

        val checksums = mutableMapOf<String, Map<String, String>>()

        minecraftJarsPath.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".jar")) {
                try {
                    val bytes = file.readBytes()
                    val md5 = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
                    val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

                    checksums[file.name] = mapOf(
                        "md5" to md5,
                        "sha256" to sha256,
                        "size" to file.length().toString(),
                        "lastModified" to file.lastModified().toString()
                    )

                } catch (e: Exception) {
                    println("[AutoJarUpdater] Error calculating checksum for ${file.name}: ${e.message}")
                }
            }
        }

        try {
            checksumFile.parentFile.mkdirs()
            checksumFile.writeText(JsonLib.fromObject(checksums).toString())
            println("[AutoJarUpdater] Checksum update completed")
        } catch (e: Exception) {
            println("[AutoJarUpdater] Error saving checksums: ${e.message}")
        }
    }

    private fun acquireUpdateLock(): Boolean {
        return try {
            if (lockFile.exists()) {
                val lockAge = System.currentTimeMillis() - lockFile.lastModified()
                if (lockAge > TimeUnit.HOURS.toMillis(1)) {
                    lockFile.delete()
                } else {
                    return false
                }
            }

            lockFile.parentFile.mkdirs()
            lockFile.writeText("${System.currentTimeMillis()}\n${ProcessHandle.current().pid()}")
            true

        } catch (e: Exception) {
            println("[AutoJarUpdater] Could not acquire lock: ${e.message}")
            false
        }
    }

    private fun releaseUpdateLock() {
        try {
            lockFile.delete()
        } catch (e: Exception) {
            println("[AutoJarUpdater] Could not release lock: ${e.message}")
        }
    }

    private fun sendSystemNotification(message: String) {
        if (!module.getConfig().enableNotifications) return

        try {
            when {
                useSystemd -> {
                    ProcessBuilder("systemd-notify", "--status=$message").start()
                }
                isLinux -> {
                    ProcessBuilder("notify-send", "SimpleCloud", message).start()
                }
                else -> {
                    println("[AutoJarUpdater] Notification: $message")
                }
            }
        } catch (e: Exception) {
            println("[AutoJarUpdater] Could not send notification: ${e.message}")
        }
    }

    private fun sanitizeVersion(version: String): String {
        return version.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
    }

    private fun shouldPerformUpdate(): Boolean {
        return true
    }
}
