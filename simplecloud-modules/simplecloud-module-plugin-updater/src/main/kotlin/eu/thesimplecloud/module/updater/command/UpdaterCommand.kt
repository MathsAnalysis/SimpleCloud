package eu.thesimplecloud.module.updater.command

import eu.thesimplecloud.api.command.ICommandSender
import eu.thesimplecloud.launcher.console.command.CommandType
import eu.thesimplecloud.launcher.console.command.ICommandHandler
import eu.thesimplecloud.launcher.console.command.annotations.Command
import eu.thesimplecloud.launcher.console.command.annotations.CommandSubPath
import eu.thesimplecloud.module.updater.bootstrap.UpdaterModule

@Command("updater", CommandType.CONSOLE, "updater")
class UpdaterCommand(
    private val module: UpdaterModule
) : ICommandHandler {
    
    @CommandSubPath("update", "Force update all components")
    fun handleUpdate(sender: ICommandSender) {
        sender.sendMessage("Starting forced update...")
        module.forceUpdate()
        sender.sendMessage("Update initiated. Check console for progress.")
    }
    
    @CommandSubPath("status", "Show updater status")
    fun handleStatus(sender: ICommandSender) {
        val status = module.getStatus()
        sender.sendMessage("Updater Status:")
        sender.sendMessage("- Enabled: ${status.enabled}")
        sender.sendMessage("- Last Update: ${formatTime(status.lastUpdate)}")
        sender.sendMessage("- Next Update: ${formatTime(status.nextUpdate)}")
    }
    
    private fun formatTime(timestamp: Long): String {
        val date = java.util.Date(timestamp)
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        return format.format(date)
    }
}