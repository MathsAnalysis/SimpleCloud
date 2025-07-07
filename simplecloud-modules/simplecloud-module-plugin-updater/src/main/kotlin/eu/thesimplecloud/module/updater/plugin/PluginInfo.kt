package eu.thesimplecloud.module.updater.plugin

import com.fasterxml.jackson.annotation.JsonProperty
import eu.thesimplecloud.jsonlib.JsonLib
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun fromJson(jsonLib: JsonLib): PluginInfo? {
            return try {
                jsonLib.getObject(PluginInfo::class.java)
            } catch (e: Exception) {
                null
            }
        }

        fun create(
            name: String,
            version: String,
            platforms: Map<String, String>,
            checksum: String? = null,
            fileSize: Long? = null
        ): PluginInfo {
            val now = System.currentTimeMillis()
            return PluginInfo(
                name = name,
                version = version,
                platforms = platforms,
                lastUpdated = now.toString(),
                checksum = checksum,
                fileSize = fileSize,
                downloadedAt = now
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

    fun toJsonLib(): JsonLib {
        return JsonLib.fromObject(this)
    }

    private fun platformsToJson(): String {
        val platformEntries = platforms.entries.joinToString(",") { (platform, url) ->
            "\"$platform\": \"$url\""
        }
        return "{$platformEntries}"
    }

    fun getUrlForPlatform(platform: String): String? {
        return platforms[platform]
    }

    fun supportsPlatform(platform: String): Boolean {
        return platforms.containsKey(platform)
    }

    fun getSupportedPlatforms(): Set<String> {
        return platforms.keys
    }

    fun getFormattedLastUpdated(): String {
        return try {
            val timestamp = lastUpdated.toLongOrNull() ?: return lastUpdated
            val instant = Instant.ofEpochMilli(timestamp)
            val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            dateTime.format(DATE_FORMATTER)
        } catch (e: Exception) {
            lastUpdated
        }
    }

    fun getFormattedDownloadedAt(): String? {
        return downloadedAt?.let { timestamp ->
            try {
                val instant = Instant.ofEpochMilli(timestamp)
                val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                dateTime.format(DATE_FORMATTER)
            } catch (e: Exception) {
                timestamp.toString()
            }
        }
    }

    fun getFormattedFileSize(): String? {
        return fileSize?.let { size ->
            when {
                size < 1024 -> "${size}B"
                size < 1024 * 1024 -> "${size / 1024}KB"
                size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)}MB"
                else -> "${size / (1024 * 1024 * 1024)}GB"
            }
        }
    }

    fun isRecentlyDownloaded(withinHours: Int = 24): Boolean {
        return downloadedAt?.let { timestamp ->
            val hoursAgo = System.currentTimeMillis() - (withinHours * 60 * 60 * 1000)
            timestamp > hoursAgo
        } ?: false
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