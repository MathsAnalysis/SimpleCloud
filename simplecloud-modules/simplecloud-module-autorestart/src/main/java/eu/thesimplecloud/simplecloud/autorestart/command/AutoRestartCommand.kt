package eu.thesimplecloud.simplecloud.autorestart.command

import eu.thesimplecloud.api.command.ICommandSender
import eu.thesimplecloud.launcher.console.command.CommandType
import eu.thesimplecloud.launcher.console.command.ICommandHandler
import eu.thesimplecloud.launcher.console.command.annotations.Command
import eu.thesimplecloud.launcher.console.command.annotations.CommandArgument
import eu.thesimplecloud.launcher.console.command.annotations.CommandSubPath
import eu.thesimplecloud.simplecloud.autorestart.module.AutoRestartModule
import eu.thesimplecloud.simplecloud.autorestart.server.structure.RestartTarget
import eu.thesimplecloud.simplecloud.autorestart.server.structure.RestartTargetType

@Command("autorestart", CommandType.CONSOLE, "cloud.command.autorestart")
class AutoRestartCommand(
    private val autoRestartModule: AutoRestartModule
) : ICommandHandler {

    private fun getPriorityDescription(priority: Int): String {
        return when {
            priority >= 200 -> "Very High"
            priority >= 100 -> "High"
            priority >= 50 -> "Medium"
            priority >= 10 -> "Low"
            else -> "Very Low"
        }
    }

    @CommandSubPath("status", "Shows autorestart system status")
    fun statusCommand(commandSender: ICommandSender) {
        val status = autoRestartModule.autoRestartManager.getStatus()
        commandSender.sendMessage("AutoRestart System Status:")
        commandSender.sendMessage("Running: ${status["running"]}")
        commandSender.sendMessage("Groups: ${status["groups_count"]}")
        commandSender.sendMessage("Enabled Groups: ${status["enabled_groups"]}")
        commandSender.sendMessage("Total Targets: ${status["total_targets"]}")
        commandSender.sendMessage("Next Restart: ${status["next_restart"] ?: "None scheduled"}")
    }

    @CommandSubPath("start", "Starts the autorestart scheduler")
    fun startCommand(commandSender: ICommandSender) {
        autoRestartModule.autoRestartManager.startScheduler()
        commandSender.sendMessage("AutoRestart scheduler started")
    }

    @CommandSubPath("stop", "Stops the autorestart scheduler")
    fun stopCommand(commandSender: ICommandSender) {
        autoRestartModule.autoRestartManager.stopScheduler()
        commandSender.sendMessage("AutoRestart scheduler stopped")
    }

    @CommandSubPath("reload", "Reloads the autorestart configuration")
    fun reloadCommand(commandSender: ICommandSender) {
        autoRestartModule.reloadConfig()
        commandSender.sendMessage("AutoRestart configuration reloaded")
    }

    @CommandSubPath("list", "Lists all restart groups and their targets")
    fun listCommand(commandSender: ICommandSender) {
        val groups = autoRestartModule.autoRestartManager.listGroups()
        if (groups.isEmpty()) {
            commandSender.sendMessage("No restart groups configured")
            return
        }

        commandSender.sendMessage("Configured Restart Groups:")
        for ((groupName, groupData) in groups) {
            val enabled = if (groupData["enabled"] as Boolean) "ENABLED" else "DISABLED"
            commandSender.sendMessage("Group: $groupName [$enabled]")
            commandSender.sendMessage("  Restart Time: ${groupData["restart_time"]}")
            val targets = groupData["targets"] as List<Map<String, Any>>
            commandSender.sendMessage("  Targets (${targets.size}):")
            for (target in targets) {
                commandSender.sendMessage("    - ${target["name"]} (${target["type"]}, priority: ${target["priority"]} - ${getPriorityDescription(target["priority"] as Int)})")
            }
        }
    }

    @CommandSubPath("enable <group>", "Enables a restart group")
    fun enableCommand(commandSender: ICommandSender, @CommandArgument("group") groupName: String) {
        val success = autoRestartModule.autoRestartManager.enableGroup(groupName, true)
        if (success) {
            commandSender.sendMessage("Restart group '$groupName' enabled")
        } else {
            commandSender.sendMessage("Restart group '$groupName' not found")
        }
    }

    @CommandSubPath("disable <group>", "Disables a restart group")
    fun disableCommand(commandSender: ICommandSender, @CommandArgument("group") groupName: String) {
        val success = autoRestartModule.autoRestartManager.enableGroup(groupName, false)
        if (success) {
            commandSender.sendMessage("Restart group '$groupName' disabled")
        } else {
            commandSender.sendMessage("Restart group '$groupName' not found")
        }
    }

    @CommandSubPath("restart <group>", "Manually restarts all targets in a group")
    fun restartCommand(commandSender: ICommandSender, @CommandArgument("group") groupName: String) {
        autoRestartModule.autoRestartManager.restartGroup(groupName)
        commandSender.sendMessage("Restart initiated for group '$groupName'")
    }

    @CommandSubPath("settime <group> <time>", "Sets restart time for a group (HH:MM format)")
    fun setTimeCommand(
        commandSender: ICommandSender,
        @CommandArgument("group") groupName: String,
        @CommandArgument("time") time: String
    ) {
        val success = autoRestartModule.autoRestartManager.setGroupTime(groupName, time)
        if (success) {
            commandSender.sendMessage("Restart time for group '$groupName' set to $time")
        } else {
            commandSender.sendMessage("Failed to set time. Check group name and time format (HH:MM)")
        }
    }

    @CommandSubPath("addgroup <name> <time>", "Adds a new restart group")
    fun addGroupCommand(
        commandSender: ICommandSender,
        @CommandArgument("name") name: String,
        @CommandArgument("time") time: String
    ) {
        val success = autoRestartModule.autoRestartManager.addGroup(name, time)
        if (success) {
            autoRestartModule.saveConfig()
            commandSender.sendMessage("Restart group '$name' added with time $time")
        } else {
            commandSender.sendMessage("Failed to add group. Check if name already exists or time format (HH:MM)")
        }
    }

    @CommandSubPath("addtarget <group> <targetName> <type> [priority]", "Adds a target to a restart group")
    fun addTargetCommand(
        commandSender: ICommandSender,
        @CommandArgument("group") groupName: String,
        @CommandArgument("targetName") targetName: String,
        @CommandArgument("type") type: String,
        @CommandArgument("priority") priorityStr: String = "0"
    ) {
        val targetType = try {
            RestartTargetType.valueOf(type.uppercase())
        } catch (e: IllegalArgumentException) {
            commandSender.sendMessage("Invalid type. Use GROUP or SERVICE")
            return
        }

        val priority = try {
            priorityStr.toInt()
        } catch (e: NumberFormatException) {
            commandSender.sendMessage("Invalid priority. Must be a number")
            return
        }

        val target = RestartTarget(targetName, targetType, priority)
        val success = autoRestartModule.autoRestartManager.addTargetToGroup(groupName, target)
        if (success) {
            autoRestartModule.saveConfig()
            commandSender.sendMessage("Target '$targetName' added to group '$groupName' with priority $priority (${getPriorityDescription(priority)})")
        } else {
            commandSender.sendMessage("Failed to add target. Check if group exists or target already added")
        }
    }

    @CommandSubPath("removetarget <group> <targetName>", "Removes a target from a restart group")
    fun removeTargetCommand(
        commandSender: ICommandSender,
        @CommandArgument("group") groupName: String,
        @CommandArgument("targetName") targetName: String
    ) {
        val success = autoRestartModule.autoRestartManager.removeTargetFromGroup(groupName, targetName)
        if (success) {
            autoRestartModule.saveConfig()
            commandSender.sendMessage("Target '$targetName' removed from group '$groupName'")
        } else {
            commandSender.sendMessage("Failed to remove target. Check if group and target exist")
        }
    }

    @CommandSubPath("logs", "Shows recent restart logs")
    fun logsCommand(commandSender: ICommandSender) {
        val logs = autoRestartModule.autoRestartManager.getRecentLogs(20)
        if (logs.isEmpty()) {
            commandSender.sendMessage("No restart logs available")
            return
        }

        commandSender.sendMessage("Recent Restart Logs:")
        for (log in logs) {
            val status = log["status"].toString().uppercase()
            val timestamp = log["timestamp"].toString().substringBefore('T')
            val time = log["timestamp"].toString().substringAfter('T').substringBefore('.')
            commandSender.sendMessage("[$timestamp $time] ${log["target_name"]} ($status)")
            if (log["error_message"] != null) {
                commandSender.sendMessage("  Error: ${log["error_message"]}")
            }
        }
    }
}