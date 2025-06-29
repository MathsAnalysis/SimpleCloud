package eu.thesimplecloud.simplecloud.autorestart.server.group

import eu.thesimplecloud.simplecloud.autorestart.server.structure.RestartTarget

data class RestartGroup(
    val name: String,
    val targets: MutableList<RestartTarget> = mutableListOf(),
    var restartTime: String,
    var enabled: Boolean = true
) {
    fun addTarget(target: RestartTarget) {
        targets.add(target)
    }

    fun removeTarget(targetName: String): Boolean {
        return targets.removeIf { it.name == targetName }
    }
}