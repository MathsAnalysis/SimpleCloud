package eu.thesimplecloud.simplecloud.autorestart.server.structure

data class RestartTarget(
    val name: String,
    val type: RestartTargetType,
    val priority: Int = 0,
    val restartTimeout: Long? = null,
    val healthCheckTimeout: Long? = null
)

enum class RestartTargetType {
    GROUP,
    SERVICE
}