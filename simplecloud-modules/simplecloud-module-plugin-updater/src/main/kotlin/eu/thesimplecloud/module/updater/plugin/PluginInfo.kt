package eu.thesimplecloud.module.updater.plugin

import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat
import java.util.*

data class PluginInfo(

    @JsonProperty("name")
    val name: String,

    @JsonProperty("version")
    val version: String,

    @JsonProperty("platforms")
    val platforms: Map<String, String>,

    @JsonProperty("lastUpdated")
    val lastUpdated: String,

    @JsonProperty("checksum")
    val checksum: String? = null,

    @JsonProperty("fileSize")
    val fileSize: Long? = null,

    @JsonProperty("downloadedAt")
    val downloadedAt: Long? = null
) {
    companion object {
        fun create(
            name: String,
            version: String,
            platforms: Map<String, String>,
            checksum: String? = null,
            fileSize: Long? = null
        ): PluginInfo {
            return PluginInfo(
                name = name,
                version = version,
                platforms = platforms,
                lastUpdated = System.currentTimeMillis().toString(),
                checksum = checksum,
                fileSize = fileSize,
                downloadedAt = null
            )
        }
    }

    fun toJson(): String {
        return """
            {
                "name": "$name",
                "version": "$version",
                "platforms": ${platformsToJson()},
                "lastUpdated": "$lastUpdated",
                "checksum": ${checksum?.let { "\"$it\"" } ?: "null"},
                "fileSize": ${fileSize ?: "null"},
                "downloadedAt": ${downloadedAt ?: "null"}
            }
        """.trimIndent()
    }

    private fun platformsToJson(): String {
        val platformEntries = platforms.entries.joinToString(",") { (platform, url) ->
            "\"$platform\": \"$url\""
        }
        return "{$platformEntries}"
    }

    fun getFormattedLastUpdated(): String {
        return try {
            val timestamp = lastUpdated.toLong()
            val date = Date(timestamp)
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(date)
        } catch (e: Exception) {
            lastUpdated
        }
    }

    fun getFormattedDownloadedAt(): String? {
        return downloadedAt?.let { timestamp ->
            try {
                val date = Date(timestamp)
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(date)
            } catch (e: Exception) {
                timestamp.toString()
            }
        }
    }

    fun getFormattedFileSize(): String? {
        return fileSize?.let { size ->
            when {
                size >= 1024 * 1024 -> "${size / (1024 * 1024)}MB"
                size >= 1024 -> "${size / 1024}KB"
                else -> "${size}B"
            }
        }
    }

    fun hasBeenDownloaded(): Boolean {
        return downloadedAt != null && downloadedAt > 0
    }

    fun isOutdated(maxAgeHours: Long = 24): Boolean {
        val downloadTime = downloadedAt ?: return true
        val ageHours = (System.currentTimeMillis() - downloadTime) / (1000 * 60 * 60)
        return ageHours > maxAgeHours
    }

    fun needsUpdate(other: PluginInfo): Boolean {
        return this.version != other.version ||
                this.platforms != other.platforms ||
                this.isOutdated()
    }

    fun isPlatformSupported(platform: String): Boolean {
        return platforms.containsKey(platform)
    }

    fun getDownloadUrl(platform: String): String? {
        return platforms[platform]
    }

    fun getAllSupportedPlatforms(): Set<String> {
        return platforms.keys.toSet()
    }

    fun hasValidUrls(): Boolean {
        return platforms.values.all { url ->
            url.isNotBlank() && (url.startsWith("https://") || url.startsWith("https://"))
        }
    }

    fun withUpdatedDownloadTime(): PluginInfo {
        return copy(downloadedAt = System.currentTimeMillis())
    }

    fun withFileInfo(newChecksum: String? = null, newFileSize: Long? = null): PluginInfo {
        return copy(
            checksum = newChecksum ?: checksum,
            fileSize = newFileSize ?: fileSize,
            downloadedAt = System.currentTimeMillis()
        )
    }

    fun withVersion(newVersion: String): PluginInfo {
        return copy(
            version = newVersion,
            lastUpdated = System.currentTimeMillis().toString(),
            downloadedAt = System.currentTimeMillis()
        )
    }

    fun isValid(): Boolean {
        return name.isNotBlank() &&
                version.isNotBlank() &&
                platforms.isNotEmpty() &&
                platforms.values.all { it.isNotBlank() }
    }

    fun getValidationErrors(): List<String> {
        val errors = mutableListOf<String>()

        if (name.isBlank()) {
            errors.add("Plugin name cannot be blank")
        }

        if (version.isBlank()) {
            errors.add("Plugin version cannot be blank")
        }

        if (platforms.isEmpty()) {
            errors.add("Plugin must support at least one platform")
        }

        platforms.forEach { (platform, url) ->
            if (platform.isBlank()) {
                errors.add("Platform name cannot be blank")
            }
            if (url.isBlank()) {
                errors.add("URL for platform '$platform' cannot be blank")
            }
        }

        return errors
    }

    fun toDebugString(): String {
        return buildString {
            appendLine("PluginInfo(")
            appendLine("  name='$name',")
            appendLine("  version='$version',")
            appendLine("  platforms=${platforms.size} (${platforms.keys.joinToString(", ")}),")
            appendLine("  lastUpdated='${getFormattedLastUpdated()}',")
            appendLine("  checksum=${checksum ?: "null"},")
            appendLine("  fileSize=${getFormattedFileSize() ?: "null"},")
            appendLine("  downloadedAt='${getFormattedDownloadedAt() ?: "null"}'")
            append(")")
        }
    }

    fun toLogSummary(): String {
        return "$name v$version [${platforms.keys.joinToString(", ")}]" +
                (fileSize?.let { " (${getFormattedFileSize()})" } ?: "")
    }
}