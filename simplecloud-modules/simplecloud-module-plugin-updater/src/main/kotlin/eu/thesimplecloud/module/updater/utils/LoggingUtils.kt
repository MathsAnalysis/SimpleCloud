package eu.thesimplecloud.module.updater.utils

import eu.thesimplecloud.module.updater.bootstrap.PluginUpdaterModule

object LoggingUtils {

    private fun isDebugEnabled(): Boolean {
        return try {
            PluginUpdaterModule.instance.getConfig().enableDebug
        } catch (e: Exception) {
            false
        }
    }

    fun debug(tag: String, message: String) {
        if (isDebugEnabled()) {
            println("[$tag] $message")
        }
    }

    fun info(tag: String, message: String) {
        println("[$tag] $message")
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        println("[$tag] ERROR: $message")
        throwable?.printStackTrace()
    }
}