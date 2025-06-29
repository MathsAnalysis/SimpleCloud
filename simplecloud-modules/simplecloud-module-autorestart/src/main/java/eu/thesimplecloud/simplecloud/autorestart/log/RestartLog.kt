package eu.thesimplecloud.simplecloud.autorestart.log

import eu.thesimplecloud.simplecloud.autorestart.type.RestartStatus
import java.time.LocalDateTime

data class RestartLog(
    val timestamp: LocalDateTime,
    val serverName: String,
    val groupName: String,
    var status: RestartStatus,
    var errorMessage: String? = null
)