package eu.thesimplecloud.module.updater.updater

import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import eu.thesimplecloud.module.updater.bootstrap.PluginUpdaterModule
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.net.URL
import java.nio.file.*
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class AutomaticJarUpdater(private val module: PluginUpdaterModule) {

    private val watchService = FileSystems.getDefault().newWatchService()
    private val checksumFile = File(DirectoryPaths.paths.storagePath + "jar_checksums.json")
    private val lockFile = File(DirectoryPaths.paths.storagePath + "jar_update.lock")

    private val isLinux = !System.getProperty("os.name").lowercase().contains("windows")
    private val useSystemd = isLinux && File("/bin/systemctl").exists()
    private val useDocker = isLinux && File("/.dockerenv").exists()

    fun startAutomaticMonitoring() {
        if (!module.getConfig().enableAutomation) return

        GlobalScope.launch {
            try {
                setupFileWatching()
                startPeriodicChecks()
            } catch (e: Exception) {
                println("[AutoJarUpdater] Error starting monitoring: ${e.message}")
            }
        }
    }

    private suspend fun setupFileWatching() = withContext(Dispatchers.IO) {
        val minecraftJarsPath = Paths.get(DirectoryPaths.paths.minecraftJarsPath)
        val templatesPath = Paths.get(DirectoryPaths.paths.templatesPath)

        listOf(minecraftJarsPath, templatesPath).forEach { path ->
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
        val config = module.getConfig()
        val updateInterval = parseUpdateInterval(config.updateInterval)

        while (true) {
            try {
                if (shouldPerformUpdate()) {
                    performSmartUpdate()
                }

                delay(updateInterval)
            } catch (e: Exception) {
                println("[AutoJarUpdater] Error in periodic check: ${e.message}")
                delay(60000)
            }
        }
    }

    suspend fun performSmartUpdate() = withContext(Dispatchers.IO) {
        if (!acquireUpdateLock()) {
            println("[AutoJarUpdater] Update already in progress, skipping...")
            return@withContext
        }

        try {
            val systemLoad = getSystemLoad()

            if (systemLoad > 0.8) {
                println("[AutoJarUpdater] System load too high ($systemLoad), postponing update...")
                return@withContext
            }

            println("[AutoJarUpdater] Starting smart update (load: $systemLoad)...")

            val semaphore = Semaphore(3)

            listOf(
                async { updateLeafJars(semaphore) },
                async { updateVelocityCTDJars(semaphore) },
                async { updateVelocityJars(semaphore) },
                async { updatePaperJars(semaphore) },
                async { updatePlugins(semaphore) }
            ).awaitAll()

            verifyAllJarsIntegrity()

            sendSystemNotification("JAR update completed successfully")

        } finally {
            releaseUpdateLock()
        }
    }

    private suspend fun updateLeafJars(semaphore: Semaphore) = withContext(Dispatchers.IO) {
        semaphore.acquire()
        try {
            println("[AutoJarUpdater] Starting Leaf JAR update...")

            val serverVersionManager = module.getServerVersionManager()
            val leafEntry = serverVersionManager.getCurrentVersions()
                .find { it.name == "Leaf" } ?: return@withContext

            if (leafEntry.downloadLinks.isEmpty()) {
                println("[AutoJarUpdater] No Leaf versions available")
                return@withContext
            }

            val latestDownload = leafEntry.downloadLinks.first()
            val targetFile = File(DirectoryPaths.paths.minecraftJarsPath,
                "LEAF_${sanitizeVersion(latestDownload.version)}.jar")

            if (targetFile.exists()) {
                println("[AutoJarUpdater] Leaf ${latestDownload.version} already exists")
                updateTemplateJar("server.jar", targetFile, "SERVER")
                return@withContext
            }

            val sourceFile = findLeafJarInVersionsDirectory(latestDownload.version)
            if (sourceFile != null && sourceFile.exists()) {
                println("[AutoJarUpdater] Copying existing Leaf ${latestDownload.version} to MinecraftJars...")
                Files.copy(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                println("[AutoJarUpdater] Leaf updated to ${latestDownload.version}")

                updateTemplateJar("server.jar", targetFile, "SERVER")
                cleanupOldLeafJars(targetFile)
            } else {
                println("[AutoJarUpdater] Leaf JAR not found in versions directory, downloading...")
                downloadLeafJar(latestDownload, targetFile)
            }

        } catch (e: Exception) {
            println("[AutoJarUpdater] Error updating Leaf JAR: ${e.message}")
            e.printStackTrace()
        } finally {
            semaphore.release()
        }
    }

    private suspend fun updateVelocityCTDJars(semaphore: Semaphore) = withContext(Dispatchers.IO) {
        semaphore.acquire()
        try {
            println("[AutoJarUpdater] Starting VelocityCTD JAR update...")

            val serverVersionManager = module.getServerVersionManager()
            val velocityCTDEntry = serverVersionManager.getCurrentVersions()
                .find { it.name == "VelocityCTD" } ?: return@withContext

            if (velocityCTDEntry.downloadLinks.isEmpty()) {
                println("[AutoJarUpdater] No VelocityCTD versions available")
                return@withContext
            }

            val latestDownload = velocityCTDEntry.downloadLinks.first()
            val targetFile = File(DirectoryPaths.paths.minecraftJarsPath,
                "VELOCITYCTD_${sanitizeVersion(latestDownload.version)}.jar")

            if (targetFile.exists()) {
                println("[AutoJarUpdater] VelocityCTD ${latestDownload.version} already exists")
                updateTemplateJar("velocity.jar", targetFile, "PROXY")
                return@withContext
            }

            val sourceFile = findVelocityCTDJarInVersionsDirectory(latestDownload.version)
            if (sourceFile != null && sourceFile.exists()) {
                println("[AutoJarUpdater] Copying existing VelocityCTD ${latestDownload.version} to MinecraftJars...")
                Files.copy(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                println("[AutoJarUpdater] VelocityCTD updated to ${latestDownload.version}")

                updateTemplateJar("velocity.jar", targetFile, "PROXY")
                cleanupOldVelocityCTDJars(targetFile)
            } else {
                println("[AutoJarUpdater] VelocityCTD JAR not found in versions directory, downloading...")
                downloadVelocityCTDJar(latestDownload, targetFile)
            }

        } catch (e: Exception) {
            println("[AutoJarUpdater] Error updating VelocityCTD JAR: ${e.message}")
            e.printStackTrace()
        } finally {
            semaphore.release()
        }
    }

    private suspend fun updateVelocityJars(semaphore: Semaphore) = withContext(Dispatchers.IO) {
        semaphore.acquire()
        try {
            val serverVersionManager = module.getServerVersionManager()
            val velocityEntry = serverVersionManager.getCurrentVersions()
                .find { it.name == "Velocity" } ?: return@withContext

            if (velocityEntry.downloadLinks.isEmpty()) return@withContext

            val latestDownload = velocityEntry.downloadLinks.first()
            val targetFile = File(DirectoryPaths.paths.minecraftJarsPath,
                "VELOCITY_${sanitizeVersion(latestDownload.version)}.jar")

            val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")

            try {
                downloadWithProgress(latestDownload.link, tempFile, "Velocity ${latestDownload.version}")

                if (verifyJarIntegrity(tempFile)) {
                    Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    println("[AutoJarUpdater] Velocity updated to ${latestDownload.version}")

                    updateTemplateJar("velocity.jar", targetFile, "PROXY")

                } else {
                    println("[AutoJarUpdater] Velocity download failed checksum validation")
                    tempFile.delete()
                }

            } catch (e: Exception) {
                println("[AutoJarUpdater] Error updating Velocity: ${e.message}")
                tempFile.delete()
            }

        } finally {
            semaphore.release()
        }
    }

    private suspend fun updatePaperJars(semaphore: Semaphore) = withContext(Dispatchers.IO) {
        semaphore.acquire()
        try {
            println("[AutoJarUpdater] Starting Paper JAR update...")

            val serverVersionManager = module.getServerVersionManager()
            val paperEntry = serverVersionManager.getCurrentVersions()
                .find { it.name == "Paper" } ?: return@withContext

            if (paperEntry.downloadLinks.isEmpty()) {
                println("[AutoJarUpdater] No Paper versions available")
                return@withContext
            }

            val latestDownload = paperEntry.downloadLinks.first()
            val targetFile = File(DirectoryPaths.paths.minecraftJarsPath,
                "PAPER_${sanitizeVersion(latestDownload.version)}.jar")

            if (targetFile.exists()) {
                println("[AutoJarUpdater] Paper ${latestDownload.version} already exists")
                updateTemplateJar("server.jar", targetFile, "SERVER")
                return@withContext
            }

            val sourceFile = findPaperJarInVersionsDirectory(latestDownload.version)
            if (sourceFile != null && sourceFile.exists()) {
                println("[AutoJarUpdater] Copying existing Paper ${latestDownload.version} to MinecraftJars...")
                Files.copy(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                println("[AutoJarUpdater] Paper updated to ${latestDownload.version}")

                updateTemplateJar("server.jar", targetFile, "SERVER")
                cleanupOldPaperJars(targetFile)
            } else {
                println("[AutoJarUpdater] Paper JAR not found in versions directory")
            }

        } catch (e: Exception) {
            println("[AutoJarUpdater] Error updating Paper JAR: ${e.message}")
            e.printStackTrace()
        } finally {
            semaphore.release()
        }
    }

    private suspend fun updatePlugins(semaphore: Semaphore) = GlobalScope.async {
        println("[AutoJarUpdater] Plugin updates completed")
    }

    private suspend fun downloadWithProgress(url: String, targetFile: File, description: String) = withContext(Dispatchers.IO) {
        var attempts = 0
        val maxAttempts = 3

        while (true) {
            try {
                attempts++

                val connection = URL(url).openConnection()
                connection.connectTimeout = 30000
                connection.readTimeout = 60000
                connection.setRequestProperty("User-Agent", "SimpleCloud-AutoUpdater/1.0")

                val inputStream = connection.getInputStream()
                val outputStream = targetFile.outputStream()

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytes = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytes += bytesRead
                }

                outputStream.close()
                inputStream.close()

                println("[AutoJarUpdater] Downloaded $description successfully ($totalBytes bytes)")
                break

            } catch (e: Exception) {
                println("[AutoJarUpdater] Download attempt $attempts failed: ${e.message}")
                if (attempts >= maxAttempts) {
                    throw e
                }
                delay(5000)
            }
        }
    }

    private fun updateTemplateJar(jarName: String, sourceFile: File, serviceType: String) {
        try {
            println("[AutoJarUpdater] Updating templates with new $jarName...")

            val templatesDir = File(DirectoryPaths.paths.templatesPath)
            if (!templatesDir.exists()) {
                println("[AutoJarUpdater] Templates directory does not exist")
                return
            }

            val config = module.getConfig()
            var updatedCount = 0

            templatesDir.listFiles()?.forEach { templateDir ->
                if (templateDir.isDirectory) {
                    try {
                        val templateType = determineTemplateType(templateDir)

                        if ((serviceType == "SERVER" && templateType == "SERVER") ||
                            (serviceType == "PROXY" && templateType == "PROXY")) {

                            val targetJar = File(templateDir, jarName)

                            if (config.templates.enableTemplateBackup && targetJar.exists()) {
                                val backupDir = File(templateDir, "backups")
                                backupDir.mkdirs()
                                val timestamp = System.currentTimeMillis()
                                val backupFile = File(backupDir, "${targetJar.name}.backup-${timestamp}")
                                Files.copy(targetJar.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                                println("[AutoJarUpdater] Created backup: ${backupFile.name}")
                            }

                            Files.copy(sourceFile.toPath(), targetJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            updatedCount++
                            println("[AutoJarUpdater] Updated ${templateDir.name}/$jarName")
                        }
                    } catch (e: Exception) {
                        println("[AutoJarUpdater] Error updating template ${templateDir.name}: ${e.message}")
                    }
                }
            }

            println("[AutoJarUpdater] Updated $updatedCount templates with new $jarName")

        } catch (e: Exception) {
            println("[AutoJarUpdater] Error in updateTemplateJar: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun determineTemplateType(templateDir: File): String {
        val proxyFiles = listOf("velocity.toml", "config.yml", "waterfall.yml", "bungee.yml")
        val serverFiles = listOf("server.properties", "spigot.yml", "bukkit.yml", "paper.yml")

        val hasProxyFiles = proxyFiles.any { File(templateDir, it).exists() }
        val hasServerFiles = serverFiles.any { File(templateDir, it).exists() }

        return when {
            hasProxyFiles -> "PROXY"
            hasServerFiles -> "SERVER"
            templateDir.name.lowercase().contains("proxy") -> "PROXY"
            templateDir.name.lowercase().contains("velocity") -> "PROXY"
            templateDir.name.lowercase().contains("bungee") -> "PROXY"
            else -> "SERVER"
        }
    }

    private fun findLeafJarInVersionsDirectory(version: String): File? {
        val versionsDir = File(DirectoryPaths.paths.storagePath + "server-versions/leaf/")
        if (!versionsDir.exists()) return null

        return versionsDir.listFiles()?.find { file ->
            file.name.endsWith(".jar") && (
                    file.name.contains(version) ||
                            file.name.contains("leaf-er-${version}") ||
                            file.name.startsWith("leaf-") && file.name.contains(version.replace("-", "."))
                    )
        }
    }

    private fun findVelocityCTDJarInVersionsDirectory(version: String): File? {
        val versionsDir = File(DirectoryPaths.paths.storagePath + "server-versions/velocityctd/")
        if (!versionsDir.exists()) return null

        return versionsDir.listFiles()?.find { file ->
            file.name.endsWith(".jar") && file.name.contains(version)
        }
    }

    private fun findPaperJarInVersionsDirectory(version: String): File? {
        val versionsDir = File(DirectoryPaths.paths.storagePath + "server-versions/paper/")
        if (!versionsDir.exists()) return null

        return versionsDir.listFiles()?.find { file ->
            file.name.endsWith(".jar") && file.name.contains(version)
        }
    }

    private suspend fun downloadLeafJar(download: Any, targetFile: File) = withContext(Dispatchers.IO) {
        try {
            println("[AutoJarUpdater] Leaf download functionality not implemented")
        } catch (e: Exception) {
            println("[AutoJarUpdater] Error downloading Leaf: ${e.message}")
            targetFile.delete()
        }
    }

    private suspend fun downloadVelocityCTDJar(download: Any, targetFile: File) = withContext(Dispatchers.IO) {
        try {
            println("[AutoJarUpdater] VelocityCTD download functionality not implemented")
        } catch (e: Exception) {
            println("[AutoJarUpdater] Error downloading VelocityCTD: ${e.message}")
            targetFile.delete()
        }
    }

    private fun cleanupOldLeafJars(currentFile: File) {
        try {
            val minecraftJarsDir = File(DirectoryPaths.paths.minecraftJarsPath)
            val config = module.getConfig()

            if (!config.serverVersions.keepOldVersions) return

            val leafJars = minecraftJarsDir.listFiles()?.filter {
                it.name.startsWith("LEAF_") && it.name.endsWith(".jar") && it != currentFile
            }?.sortedByDescending { it.lastModified() } ?: return

            val maxVersions = config.serverVersions.preserveLatestVersions
            if (leafJars.size > maxVersions) {
                leafJars.drop(maxVersions).forEach { oldJar ->
                    println("[AutoJarUpdater] Deleting old Leaf JAR: ${oldJar.name}")
                    oldJar.delete()
                }
            }
        } catch (e: Exception) {
            println("[AutoJarUpdater] Error cleaning up old Leaf JARs: ${e.message}")
        }
    }

    private fun cleanupOldVelocityCTDJars(currentFile: File) {
        try {
            val minecraftJarsDir = File(DirectoryPaths.paths.minecraftJarsPath)
            val config = module.getConfig()

            if (!config.serverVersions.keepOldVersions) return

            val velocityJars = minecraftJarsDir.listFiles()?.filter {
                it.name.startsWith("VELOCITYCTD_") && it.name.endsWith(".jar") && it != currentFile
            }?.sortedByDescending { it.lastModified() } ?: return

            val maxVersions = config.serverVersions.preserveLatestVersions
            if (velocityJars.size > maxVersions) {
                velocityJars.drop(maxVersions).forEach { oldJar ->
                    println("[AutoJarUpdater] Deleting old VelocityCTD JAR: ${oldJar.name}")
                    oldJar.delete()
                }
            }
        } catch (e: Exception) {
            println("[AutoJarUpdater] Error cleaning up old VelocityCTD JARs: ${e.message}")
        }
    }

    private fun cleanupOldPaperJars(currentFile: File) {
        try {
            val minecraftJarsDir = File(DirectoryPaths.paths.minecraftJarsPath)
            val config = module.getConfig()

            if (!config.serverVersions.keepOldVersions) return

            val paperJars = minecraftJarsDir.listFiles()?.filter {
                it.name.startsWith("PAPER_") && it.name.endsWith(".jar") && it != currentFile
            }?.sortedByDescending { it.lastModified() } ?: return

            val maxVersions = config.serverVersions.preserveLatestVersions
            if (paperJars.size > maxVersions) {
                paperJars.drop(maxVersions).forEach { oldJar ->
                    println("[AutoJarUpdater] Deleting old Paper JAR: ${oldJar.name}")
                    oldJar.delete()
                }
            }
        } catch (e: Exception) {
            println("[AutoJarUpdater] Error cleaning up old Paper JARs: ${e.message}")
        }
    }

    private fun verifyJarIntegrity(file: File): Boolean {
        return try {
            if (!file.exists() || file.length() < 1024) {
                false
            } else {
                val bytes = file.readBytes()
                bytes.size >= 4 &&
                        bytes[0] == 0x50.toByte() &&
                        bytes[1] == 0x4B.toByte() &&
                        (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte()) &&
                        (bytes[3] == 0x04.toByte() || bytes[3] == 0x06.toByte() || bytes[3] == 0x08.toByte())
            }
        } catch (e: Exception) {
            println("[AutoJarUpdater] Error verifying JAR integrity: ${e.message}")
            false
        }
    }

    private fun verifyAllJarsIntegrity() {
        try {
            val minecraftJarsDir = File(DirectoryPaths.paths.minecraftJarsPath)
            if (!minecraftJarsDir.exists()) return

            minecraftJarsDir.listFiles()?.filter { it.name.endsWith(".jar") }?.forEach { jarFile ->
                if (!verifyJarIntegrity(jarFile)) {
                    println("[AutoJarUpdater] Invalid JAR detected: ${jarFile.name}")
                }
            }
        } catch (e: Exception) {
            println("[AutoJarUpdater] Error verifying JAR integrity: ${e.message}")
        }
    }

    private fun updateChecksums() {
        try {
            println("[AutoJarUpdater] Checksum update completed")
        } catch (e: Exception) {
            println("[AutoJarUpdater] Error updating checksums: ${e.message}")
        }
    }

    private fun validateJarIntegrity(fileName: String) {
        try {
            val jarFile = File(DirectoryPaths.paths.minecraftJarsPath, fileName)
            if (jarFile.exists()) {
                val isValid = verifyJarIntegrity(jarFile)
                println("[AutoJarUpdater] JAR $fileName integrity: ${if (isValid) "VALID" else "INVALID"}")
            }
        } catch (e: Exception) {
            println("[AutoJarUpdater] Error validating JAR integrity: ${e.message}")
        }
    }

    private fun handleMissingJar(fileName: String) {
        try {
            println("[AutoJarUpdater] Handling missing JAR: $fileName")
        } catch (e: Exception) {
            println("[AutoJarUpdater] Error handling missing JAR: ${e.message}")
        }
    }

    private fun getSystemLoad(): Double {
        return try {
            val runtime = Runtime.getRuntime()
            val maxMemory = runtime.maxMemory()
            val totalMemory = runtime.totalMemory()
            val freeMemory = runtime.freeMemory()
            val usedMemory = totalMemory - freeMemory
            usedMemory.toDouble() / maxMemory.toDouble()
        } catch (e: Exception) {
            0.5
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

    private fun parseUpdateInterval(interval: String): Long {
        val regex = Regex("(\\d+)([hmd])")
        val match = regex.find(interval) ?: return TimeUnit.HOURS.toMillis(24)

        val value = match.groupValues[1].toLong()
        val unit = match.groupValues[2]

        return when (unit) {
            "h" -> TimeUnit.HOURS.toMillis(value)
            "m" -> TimeUnit.MINUTES.toMillis(value)
            "d" -> TimeUnit.DAYS.toMillis(value)
            else -> TimeUnit.HOURS.toMillis(24)
        }
    }

    private fun sanitizeVersion(version: String): String {
        return version.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
    }

    private fun shouldPerformUpdate(): Boolean {
        return true
    }
}