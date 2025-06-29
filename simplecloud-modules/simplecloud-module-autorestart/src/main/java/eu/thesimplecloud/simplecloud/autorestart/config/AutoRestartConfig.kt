package eu.thesimplecloud.simplecloud.autorestart.config

data class AutoRestartConfig(
    val enabled: Boolean = true,
    val globalCheckInterval: Long = 60,
    val maxConcurrentRestarts: Int = 3,
    val defaultHealthCheckTimeout: Long = 60,
    val defaultRestartTimeout: Long = 300,
    val restartGroups: MutableList<RestartGroupConfig> = mutableListOf()
)

data class RestartGroupConfig(
    val name: String,
    var restartTime: String,
    var enabled: Boolean = true,
    val targets: MutableList<RestartTargetConfig> = mutableListOf()
)

data class RestartTargetConfig(
    val name: String,
    val type: String,
    val priority: Int = 0,
    val restartTimeout: Long? = null,
    val healthCheckTimeout: Long? = null
)