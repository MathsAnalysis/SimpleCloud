package eu.thesimplecloud.module.updater.utils

import eu.thesimplecloud.module.updater.bootstrap.PluginUpdaterModule
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object LoggingUtils {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    private fun isDebugEnabled(): Boolean {
        return try {
            PluginUpdaterModule.instance.getConfig().enableDebug
        } catch (e: Exception) {
            false
        }
    }

    private fun getCurrentTime(): String {
        return LocalDateTime.now().format(timeFormatter)
    }

    fun debug(tag: String, message: String) {
        if (isDebugEnabled()) {
            val timestamp = getCurrentTime()
            println("[$timestamp] [DEBUG] [$tag] $message")
        }
    }

    fun info(tag: String, message: String) {
        val timestamp = getCurrentTime()
        println("[$timestamp] [INFO] [$tag] $message")
    }

    fun warn(tag: String, message: String) {
        val timestamp = getCurrentTime()
        println("[$timestamp] [WARN] [$tag] $message")
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val timestamp = getCurrentTime()
        println("[$timestamp] [ERROR] [$tag] $message")
        throwable?.printStackTrace()
    }

    fun init(tag: String, message: String) {
        val timestamp = getCurrentTime()
        println("[$timestamp] [INIT] [$tag] $message")
    }

    fun debugStart(tag: String, operation: String) {
        debug(tag, "Starting $operation...")
    }

    fun debugSuccess(tag: String, operation: String) {
        debug(tag, "Successfully completed $operation")
    }

    fun debugFailure(tag: String, operation: String, reason: String) {
        debug(tag, "Failed to complete $operation: $reason")
    }

    fun debugConfig(tag: String, configKey: String, configValue: Any) {
        debug(tag, "Config [$configKey]: $configValue")
    }

    fun debugNetwork(tag: String, operation: String, url: String) {
        debug(tag, "$operation -> $url")
    }

    fun debugFile(tag: String, operation: String, filePath: String) {
        debug(tag, "$operation -> $filePath")
    }

    fun debugStats(tag: String, stats: Map<String, Any>) {
        if (isDebugEnabled()) {
            debug(tag, "Statistics:")
            stats.forEach { (key, value) ->
                debug(tag, "  $key: $value")
            }
        }
    }
}