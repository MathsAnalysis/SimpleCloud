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


//Not implemented for now, but can be used in the future
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
    
    private suspend fun performSmartUpdate() = withContext(Dispatchers.IO) {
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
                async { updateVelocityJars(semaphore) },
                async { updateLeafJars(semaphore) },
                async { updatePaperJars(semaphore) },
                async { updatePlugins(semaphore) }
            ).awaitAll()

            verifyAllJarsIntegrity()

            sendSystemNotification("JAR update completed successfully")
            
        } finally {
            releaseUpdateLock()
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

                    updateTemplateJar("velocity.jar", targetFile)
                    
                } else { println("[AutoJarUpdater]  Velocity download failed checksum validation")
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
    
    private suspend fun downloadWithProgress(url: String, targetFile: File, description: String) = withContext(Dispatchers.IO) {
        var attempts = 0
        val maxAttempts = 3
        
        while (true) {
            try {
                attempts++
                
                val connection = URL(url).openConnection()
                val totalSize = connection.contentLength.toLong()
                
                connection.getInputStream().use { input ->
                    targetFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        var bytesRead: Int
                        
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead

                            if (totalSize > 0 && downloaded % (totalSize / 10) < 8192) {
                                val percentage = (downloaded * 100 / totalSize).toInt()
                                println("[AutoJarUpdater] $description: $percentage% (${downloaded}/${totalSize} bytes)")
                            }
                        }
                    }
                }
                
                println("[AutoJarUpdater] Download completed: $description")
                return@withContext
                
            } catch (e: Exception) {
                println("[AutoJarUpdater] Download attempt $attempts failed for $description: ${e.message}")
                
                if (attempts < maxAttempts) {
                    delay(5000L * attempts)
                } else {
                    throw e
                }
            }
        }
    }
    
    private fun verifyJarIntegrity(jarFile: File): Boolean {
        if (!jarFile.exists() || jarFile.length() < 1000) return false
        
        try {
            java.util.jar.JarFile(jarFile).use { jar ->
                if (jar.manifest == null) return false
            }

            val md5 = calculateChecksum(jarFile, "MD5")
            val sha256 = calculateChecksum(jarFile, "SHA-256")

            saveChecksum(jarFile.name, md5, sha256)
            
            return true
            
        } catch (e: Exception) {
            println("[AutoJarUpdater] JAR integrity check failed for ${jarFile.name}: ${e.message}")
            return false
        }
    }
    
    private fun calculateChecksum(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    
    private fun getSystemLoad(): Double {
        return try {
            if (isLinux) {
                val loadavg = File("/proc/loadavg").readText().split(" ")[0].toDouble()
                val cpuCores = Runtime.getRuntime().availableProcessors()
                loadavg / cpuCores
            } else {
                val osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                if (osBean is com.sun.management.OperatingSystemMXBean) {
                    osBean.processCpuLoad
                } else {
                    0.5
                }
            }
        } catch (e: Exception) {
            println("[AutoJarUpdater] Could not read system load: ${e.message}")
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
    
    private fun updateLeafJars(semaphore: Semaphore) = GlobalScope.async { }
    private fun updatePaperJars(semaphore: Semaphore) = GlobalScope.async { }
    private fun updatePlugins(semaphore: Semaphore) = GlobalScope.async { }
    private fun verifyAllJarsIntegrity() {}
    private fun updateChecksums() {}
    private fun validateJarIntegrity(fileName: String) {}
    private fun handleMissingJar(fileName: String) {}
    private fun saveChecksum(fileName: String, md5: String, sha256: String) {}
    private fun updateTemplateJar(templateName: String, sourceFile: File) {}
}