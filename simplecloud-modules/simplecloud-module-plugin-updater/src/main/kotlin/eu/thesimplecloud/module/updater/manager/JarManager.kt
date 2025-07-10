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
        
        try {
            if (targetFile.exists()) {
                targetFile.delete()
            }
            
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "SimpleCloud-JarManager/2.0")
                .build()
                
            val response = okHttpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext false
            }
            
            response.body?.use { body ->
                targetFile.outputStream().use { output ->
                    body.byteStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }
            
            targetFile.exists() && targetFile.length() > 0
        } catch (e: Exception) {
            false
        }
    }
    
    fun cleanupOldVersions(prefix: String, keepLatest: Int = 1) {
        val files = minecraftJarsPath.listFiles { file ->
            file.name.startsWith(prefix) && file.name.endsWith(".jar")
        } ?: return
        
        files.sortedByDescending { it.lastModified() }
            .drop(keepLatest)
            .forEach { it.delete() }
    }
    
    fun getLatestJar(prefix: String): File? {
        return minecraftJarsPath.listFiles { file ->
            file.name.startsWith(prefix) && file.name.endsWith(".jar")
        }?.maxByOrNull { it.lastModified() }
    }
}