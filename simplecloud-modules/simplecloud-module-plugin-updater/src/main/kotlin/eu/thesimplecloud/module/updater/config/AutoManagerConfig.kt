package eu.thesimplecloud.module.updater.config

import com.fasterxml.jackson.annotation.JsonProperty
import eu.thesimplecloud.jsonlib.JsonLib

data class AutoManagerConfig(
    @JsonProperty("externalSources")
    val externalSources: List<String> = emptyList(),

    @JsonProperty("enableAutomation")
    var enableAutomation: Boolean = true,

    @JsonProperty("enableServerVersionUpdates")
    val enableServerVersionUpdates: Boolean = true,

    @JsonProperty("enablePluginUpdates")
    val enablePluginUpdates: Boolean = true,

    @JsonProperty("enableTemplateSync")
    val enableTemplateSync: Boolean = true,

    @JsonProperty("enableNotifications")
    val enableNotifications: Boolean = false,

    @JsonProperty("enableBackup")
    val enableBackup: Boolean = true,

    @JsonProperty("enableDebug")
    val enableDebug: Boolean = false,

    @JsonProperty("updateInterval")
    val updateInterval: String = "24h",

    @JsonProperty("updateTime")
    val updateTime: String = "04:00",

    @JsonProperty("serverSoftware")
    val serverSoftware: List<String> = listOf("paper", "leaf"),

    @JsonProperty("plugins")
    val plugins: List<PluginConfig> = emptyList(),

    @JsonProperty("templates")
    val templates: TemplateConfig = TemplateConfig(),

    @JsonProperty("networking")
    val networking: NetworkingConfig = NetworkingConfig(),

    @JsonProperty("logging")
    val logging: LoggingConfig = LoggingConfig(),

    @JsonProperty("performance")
    val performance: PerformanceConfig = PerformanceConfig(),

    @JsonProperty("security")
    val security: SecurityConfig = SecurityConfig()
) {

    data class PluginConfig(
        @JsonProperty("name")
        val name: String,

        @JsonProperty("enabled")
        val enabled: Boolean = true,

        @JsonProperty("platforms")
        val platforms: List<String> = listOf("bukkit"),

        @JsonProperty("customUrl")
        val customUrl: String? = null,

        @JsonProperty("priority")
        val priority: Int = 0,

        @JsonProperty("autoUpdate")
        val autoUpdate: Boolean = true,

        @JsonProperty("checksum")
        val checksum: String? = null,

        @JsonProperty("dependencies")
        val dependencies: List<String> = emptyList()
    )

    data class TemplateConfig(
        @JsonProperty("autoCreateBaseTemplates")
        val autoCreateBaseTemplates: Boolean = true,

        @JsonProperty("syncOnStart")
        val syncOnStart: Boolean = true,

        @JsonProperty("cleanupOldFiles")
        val cleanupOldFiles: Boolean = true,

        @JsonProperty("maxFileAgeHours")
        val maxFileAgeHours: Int = 168,

        @JsonProperty("excludePatterns")
        val excludePatterns: List<String> = listOf("*.log", "*.tmp", "world/session.lock"),

        @JsonProperty("compressionEnabled")
        val compressionEnabled: Boolean = false,

        @JsonProperty("syncStaticServices")
        val syncStaticServices: Boolean = true
    )

    data class NetworkingConfig(
        @JsonProperty("connectTimeoutSeconds")
        val connectTimeoutSeconds: Int = 30,

        @JsonProperty("readTimeoutSeconds")
        val readTimeoutSeconds: Int = 60,

        @JsonProperty("writeTimeoutSeconds")
        val writeTimeoutSeconds: Int = 60,

        @JsonProperty("maxRetries")
        val maxRetries: Int = 3,

        @JsonProperty("retryDelaySeconds")
        val retryDelaySeconds: Int = 5,

        @JsonProperty("userAgent")
        val userAgent: String = "SimpleCloud-AutoUpdater",

        @JsonProperty("followRedirects")
        val followRedirects: Boolean = true,

        @JsonProperty("enableHttps")
        val enableHttps: Boolean = true,

        @JsonProperty("customHeaders")
        val customHeaders: Map<String, String> = emptyMap()
    )

    data class LoggingConfig(
        @JsonProperty("enableFileLogging")
        val enableFileLogging: Boolean = true,

        @JsonProperty("logDirectory")
        val logDirectory: String = "logs/updater",

        @JsonProperty("maxLogFiles")
        val maxLogFiles: Int = 10,

        @JsonProperty("maxLogSizeBytes")
        val maxLogSizeBytes: Long = 10 * 1024 * 1024,

        @JsonProperty("logLevel")
        val logLevel: String = "INFO",

        @JsonProperty("enableTimestamps")
        val enableTimestamps: Boolean = true,

        @JsonProperty("timestampFormat")
        val timestampFormat: String = "yyyy-MM-dd HH:mm:ss",

        @JsonProperty("enableConsoleColors")
        val enableConsoleColors: Boolean = true
    )

    data class PerformanceConfig(
        @JsonProperty("maxConcurrentDownloads")
        val maxConcurrentDownloads: Int = 3,

        @JsonProperty("maxFileSizeBytes")
        val maxFileSizeBytes: Long = 100 * 1024 * 1024,

        @JsonProperty("bufferSizeBytes")
        val bufferSizeBytes: Int = 8192,

        @JsonProperty("enableChecksumValidation")
        val enableChecksumValidation: Boolean = true,

        @JsonProperty("enableProgressReporting")
        val enableProgressReporting: Boolean = false,

        @JsonProperty("tempDirectory")
        val tempDirectory: String = "temp/updater",

        @JsonProperty("cleanupTempFiles")
        val cleanupTempFiles: Boolean = true
    )

    data class SecurityConfig(
        @JsonProperty("enableSignatureValidation")
        val enableSignatureValidation: Boolean = false,

        @JsonProperty("trustedCertificates")
        val trustedCertificates: List<String> = emptyList(),

        @JsonProperty("allowedHosts")
        val allowedHosts: List<String> = listOf(
            "api.spiget.org",
            "api.papermc.io",
            "github.com",
            "raw.githubusercontent.com"
        ),

        @JsonProperty("enableHostnameVerification")
        val enableHostnameVerification: Boolean = true,

        @JsonProperty("blockUnknownSources")
        val blockUnknownSources: Boolean = false,

        @JsonProperty("quarantineDirectory")
        val quarantineDirectory: String = "quarantine"
    )

    companion object {
        fun getDefault(): AutoManagerConfig {
            return AutoManagerConfig(
                enableAutomation = true,
                enableServerVersionUpdates = true,
                enablePluginUpdates = true,
                enableTemplateSync = true,
                enableNotifications = false,
                enableBackup = true,
                enableDebug = false,
                updateInterval = "24h",
                updateTime = "04:00",
                serverSoftware = listOf("paper", "leaf", "velocity"),
                plugins = listOf(
                    PluginConfig(
                        name = "LuckPerms",
                        enabled = true,
                        platforms = listOf("bukkit", "bungeecord", "velocity"),
                        priority = 10,
                        autoUpdate = true
                    ),
                    PluginConfig(
                        name = "Spark",
                        enabled = true,
                        platforms = listOf("bukkit", "bungeecord", "velocity"),
                        priority = 5,
                        autoUpdate = true
                    ),
                    PluginConfig(
                        name = "ProtocolLib",
                        enabled = true,
                        platforms = listOf("bukkit"),
                        priority = 8,
                        autoUpdate = true
                    ),
                    PluginConfig(
                        name = "PlaceholderAPI",
                        enabled = true,
                        platforms = listOf("bukkit"),
                        priority = 7,
                        autoUpdate = true
                    ),
                    PluginConfig(
                        name = "Floodgate",
                        enabled = false,
                        platforms = listOf("bukkit", "bungeecord", "velocity"),
                        priority = 6,
                        autoUpdate = true
                    ),
                    PluginConfig(
                        name = "Geyser",
                        enabled = false,
                        platforms = listOf("bukkit", "bungeecord", "velocity"),
                        priority = 6,
                        autoUpdate = true,
                        dependencies = listOf("Floodgate")
                    ),
                    PluginConfig(
                        name = "WorldEdit",
                        enabled = false,
                        platforms = listOf("bukkit"),
                        priority = 4,
                        autoUpdate = true
                    ),
                    PluginConfig(
                        name = "WorldGuard",
                        enabled = false,
                        platforms = listOf("bukkit"),
                        priority = 3,
                        autoUpdate = true,
                        dependencies = listOf("WorldEdit")
                    ),
                    PluginConfig(
                        name = "EssentialsX",
                        enabled = false,
                        platforms = listOf("bukkit"),
                        priority = 2,
                        autoUpdate = true
                    ),
                    PluginConfig(
                        name = "Vault",
                        enabled = false,
                        platforms = listOf("bukkit"),
                        priority = 9,
                        autoUpdate = true
                    )
                ),
                templates = TemplateConfig(
                    autoCreateBaseTemplates = true,
                    syncOnStart = true,
                    cleanupOldFiles = true,
                    maxFileAgeHours = 168,
                    excludePatterns = listOf("*.log", "*.tmp", "world/session.lock", "cache/**"),
                    compressionEnabled = false,
                    syncStaticServices = true
                ),
                networking = NetworkingConfig(
                    connectTimeoutSeconds = 30,
                    readTimeoutSeconds = 60,
                    writeTimeoutSeconds = 60,
                    maxRetries = 3,
                    retryDelaySeconds = 5,
                    userAgent = "SimpleCloud-AutoUpdater/1.0",
                    followRedirects = true,
                    enableHttps = true
                ),
                logging = LoggingConfig(
                    enableFileLogging = true,
                    logDirectory = "logs/updater",
                    maxLogFiles = 10,
                    maxLogSizeBytes = 10 * 1024 * 1024,
                    logLevel = "INFO",
                    enableTimestamps = true,
                    timestampFormat = "yyyy-MM-dd HH:mm:ss",
                    enableConsoleColors = true
                ),
                performance = PerformanceConfig(
                    maxConcurrentDownloads = 3,
                    maxFileSizeBytes = 100 * 1024 * 1024,
                    bufferSizeBytes = 8192,
                    enableChecksumValidation = true,
                    enableProgressReporting = false,
                    tempDirectory = "temp/updater",
                    cleanupTempFiles = true
                ),
                security = SecurityConfig(
                    enableSignatureValidation = false,
                    trustedCertificates = emptyList(),
                    allowedHosts = listOf(
                        "api.spiget.org",
                        "api.papermc.io",
                        "github.com",
                        "raw.githubusercontent.com",
                        "ci.lucko.me",
                        "hangar.papermc.io"
                    ),
                    enableHostnameVerification = true,
                    blockUnknownSources = false,
                    quarantineDirectory = "quarantine"
                )
            )
        }

        fun getDebugConfig(): AutoManagerConfig {
            return getDefault().copy(
                enableDebug = true,
                logging = LoggingConfig(
                    enableFileLogging = true,
                    logDirectory = "logs/updater-debug",
                    maxLogFiles = 20,
                    maxLogSizeBytes = 50 * 1024 * 1024,
                    logLevel = "DEBUG",
                    enableTimestamps = true,
                    timestampFormat = "yyyy-MM-dd HH:mm:ss.SSS",
                    enableConsoleColors = true
                ),
                performance = PerformanceConfig(
                    maxConcurrentDownloads = 1,
                    maxFileSizeBytes = 100 * 1024 * 1024,
                    bufferSizeBytes = 4096,
                    enableChecksumValidation = true,
                    enableProgressReporting = true,
                    tempDirectory = "temp/updater-debug",
                    cleanupTempFiles = false
                )
            )
        }

        fun fromJson(jsonLib: JsonLib): AutoManagerConfig {
            return try {
                jsonLib.getObject(AutoManagerConfig::class.java) ?: getDefault()
            } catch (e: Exception) {
                println("[AutoManagerConfig] Error parsing JSON config: ${e.message}")
                println("[AutoManagerConfig] Using default configuration")
                getDefault()
            }
        }

        fun toJson(autoManagerConfig: AutoManagerConfig): JsonLib {
            return JsonLib.fromObject(autoManagerConfig)
        }
    }

    fun isDebugMode(): Boolean = enableDebug

    fun getEnabledPlugins(): List<PluginConfig> = plugins.filter { it.enabled }

    fun getPluginByName(name: String): PluginConfig? = plugins.find { it.name.equals(name, ignoreCase = true) }

    fun getPluginsByPlatform(platform: String): List<PluginConfig> =
        plugins.filter { it.enabled && platform in it.platforms }

    fun getPrioritizedPlugins(): List<PluginConfig> =
        getEnabledPlugins().sortedByDescending { it.priority }

    fun validateConfig(): List<String> {
        val errors = mutableListOf<String>()

        if (!updateInterval.matches(Regex("\\d+[smhd]"))) {
            errors.add("Invalid update interval format: $updateInterval")
        }

        if (updateTime.isNotEmpty() && !updateTime.matches(Regex("\\d{2}:\\d{2}"))) {
            errors.add("Invalid update time format: $updateTime")
        }

        plugins.forEach { plugin ->
            if (plugin.name.isBlank()) {
                errors.add("Plugin name cannot be blank")
            }
            if (plugin.platforms.isEmpty()) {
                errors.add("Plugin ${plugin.name} must have at least one platform")
            }
        }

        if (networking.connectTimeoutSeconds <= 0) {
            errors.add("Connect timeout must be positive")
        }

        if (performance.maxConcurrentDownloads <= 0) {
            errors.add("Max concurrent downloads must be positive")
        }

        return errors
    }

    fun isValid(): Boolean = validateConfig().isEmpty()

    fun toDebugString(): String {
        return buildString {
            appendLine("AutoManagerConfig:")
            appendLine("  Automation: $enableAutomation")
            appendLine("  Debug Mode: $enableDebug")
            appendLine("  Server Updates: $enableServerVersionUpdates")
            appendLine("  Plugin Updates: $enablePluginUpdates")
            appendLine("  Template Sync: $enableTemplateSync")
            appendLine("  Update Interval: $updateInterval")
            appendLine("  Update Time: $updateTime")
            appendLine("  Server Software: ${serverSoftware.joinToString(", ")}")
            appendLine("  Enabled Plugins: ${getEnabledPlugins().size}/${plugins.size}")
            getEnabledPlugins().forEach { plugin ->
                appendLine("    - ${plugin.name} [${plugin.platforms.joinToString(", ")}] (Priority: ${plugin.priority})")
            }
            appendLine("  Templates: autoCreate=${templates.autoCreateBaseTemplates}, syncOnStart=${templates.syncOnStart}")
            appendLine("  Networking: timeout=${networking.connectTimeoutSeconds}s, retries=${networking.maxRetries}")
            appendLine("  Performance: maxDownloads=${performance.maxConcurrentDownloads}, maxSize=${performance.maxFileSizeBytes / 1024 / 1024}MB")
            appendLine("  Logging: level=${logging.logLevel}, fileLogging=${logging.enableFileLogging}")
            appendLine("  Security: hostnameVerification=${security.enableHostnameVerification}, allowedHosts=${security.allowedHosts.size}")
        }
    }
}