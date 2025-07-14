package eu.thesimplecloud.module.updater.manager

import eu.thesimplecloud.api.directorypaths.DirectoryPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class JarManager(
    private val okHttpClient: OkHttpClient
) {
    private val minecraftJarsPath = File(DirectoryPaths.paths.minecraftJarsPath)

    init {
        minecraftJarsPath.mkdirs()
    }

    suspend fun downloadJar(url: String, jarName: String): Boolean = withContext(Dispatchers.IO) {
        val targetFile = File(minecraftJarsPath, jarName)

        println("[JarManager] Downloading $jarName from $url")

        if (targetFile.exists()) {
            targetFile.delete()
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "SimpleCloud-JarManager/2.0")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                println("[JarManager] Failed to download $jarName: HTTP ${response.code}")
                return@withContext false
            }

            response.body.use { body ->
                targetFile.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val bytes = input.copyTo(output)
                        println("[JarManager] Downloaded $jarName: ${bytes / 1024 / 1024} MB")
                    }
                }
            }
        }

        val success = targetFile.exists() && targetFile.length() > 0
        if (success) {
            println("[JarManager] Successfully downloaded: $jarName")
        }
        success
    }

    fun cleanupOldVersions(prefix: String, keepLatest: Int = 1) {
        val files = minecraftJarsPath.listFiles { file ->
            file.name.startsWith(prefix) && file.name.endsWith(".jar")
        } ?: return

        files.sortedByDescending { it.lastModified() }
            .drop(keepLatest)
            .forEach {
                println("[JarManager] Deleting old version: ${it.name}")
                it.delete()
            }
    }

    fun getLatestJar(prefix: String): File? {
        return minecraftJarsPath.listFiles { file ->
            file.name.startsWith(prefix) && file.name.endsWith(".jar")
        }?.maxByOrNull { it.lastModified() }
    }
}