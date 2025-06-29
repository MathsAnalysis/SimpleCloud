package eu.thesimplecloud.base.manager.commands

import eu.thesimplecloud.api.command.ICommandSender
import eu.thesimplecloud.base.wrapper.startup.Wrapper
import eu.thesimplecloud.launcher.console.command.CommandType
import eu.thesimplecloud.launcher.console.command.ICommandHandler
import eu.thesimplecloud.launcher.console.command.annotations.Command
import eu.thesimplecloud.launcher.console.command.annotations.CommandArgument
import eu.thesimplecloud.launcher.console.command.annotations.CommandSubPath

@Command("port", CommandType.CONSOLE_AND_INGAME, "cloud.command.port")
class PortCommand : ICommandHandler {

    @CommandSubPath("stats", "Show port statistics")
    fun handleStats(commandSender: ICommandSender) {
        val stats = Wrapper.instance.portManager.getStats()
        commandSender.sendMessage("""
        PORT MANAGER STATISTICS
        ===========================
        Used ports: ${stats.usedPorts}
        Blocked ports: ${stats.blockedPorts}
        Force-closed ports: ${stats.forcedClosePorts}
        
        Used ports list: ${stats.usedPortsList.joinToString(", ")}
        """.trimIndent())
    }

    @CommandSubPath("brute-close <port>", "Forcefully close a port")
    fun handleBruteClose(
        commandSender: ICommandSender,
        @CommandArgument("port") port: Int
    ) {
        if (port < 1024 || port > 65535) {
            commandSender.sendMessage("Invalid port! Use a port between 1024 and 65535")
            return
        }

        commandSender.sendMessage("Attempting forced closure of port $port...")
        val success = Wrapper.instance.portManager.bruteClosePort(port)

        if (success) {
            commandSender.sendMessage("Port $port forcefully closed successfully!")
        } else {
            commandSender.sendMessage("Unable to forcefully close port $port")
        }
    }

    @CommandSubPath("force-kill <port>", "Forcefully kill service on port")
    fun handleForceKill(
        commandSender: ICommandSender,
        @CommandArgument("port") port: Int
    ) {
        if (port < 1024 || port > 65535) {
            commandSender.sendMessage("Invalid port! Use a port between 1024 and 65535")
            return
        }

        commandSender.sendMessage("Attempting to forcefully kill service on port $port...")
        val success = Wrapper.instance.portManager.forceKillService(port)

        if (success) {
            commandSender.sendMessage("Service on port $port forcefully killed successfully!")
        } else {
            commandSender.sendMessage("Unable to forcefully kill service on port $port")
        }
    }

    @CommandSubPath("check <port>", "Check port status")
    fun handleCheck(
        commandSender: ICommandSender,
        @CommandArgument("port") port: Int
    ) {
        val status = Wrapper.instance.portManager.getPortStatus(port)
        commandSender.sendMessage("Port $port: ${status.description}")
    }

    @CommandSubPath("find <startPort> <endPort>", "Find available ports in range")
    fun handleFind(
        commandSender: ICommandSender,
        @CommandArgument("startPort") startPort: Int,
        @CommandArgument("endPort") endPort: Int
    ) {
        if (startPort >= endPort) {
            commandSender.sendMessage("Start port must be lower than end port!")
            return
        }

        val availablePorts = Wrapper.instance.portManager.findAvailablePortsInRange(startPort, endPort)
        if (availablePorts.isNotEmpty()) {
            commandSender.sendMessage("Available ports in range $startPort-$endPort: ${availablePorts.joinToString(", ")}")
        } else {
            commandSender.sendMessage("No available ports in range $startPort-$endPort")
        }
    }

    @CommandSubPath("find <startPort> <endPort> <maxResults>", "Find available ports in range with limit")
    fun handleFindWithLimit(
        commandSender: ICommandSender,
        @CommandArgument("startPort") startPort: Int,
        @CommandArgument("endPort") endPort: Int,
        @CommandArgument("maxResults") maxResults: Int
    ) {
        if (startPort >= endPort) {
            commandSender.sendMessage("Start port must be lower than end port!")
            return
        }

        if (maxResults <= 0 || maxResults > 100) {
            commandSender.sendMessage("Maximum results must be between 1 and 100!")
            return
        }

        val availablePorts = Wrapper.instance.portManager.findAvailablePortsInRange(startPort, endPort, maxResults)
        if (availablePorts.isNotEmpty()) {
            commandSender.sendMessage("Available ports in range $startPort-$endPort: ${availablePorts.joinToString(", ")}")
        } else {
            commandSender.sendMessage("No available ports in range $startPort-$endPort")
        }
    }

    @CommandSubPath("cleanup", "Clean force-closed ports cache")
    fun handleCleanup(commandSender: ICommandSender) {
        Wrapper.instance.portManager.cleanupForcedClosedPorts()
        commandSender.sendMessage("Force-closed ports cache cleaned!")
    }

    @CommandSubPath("help", "Show help for port commands")
    fun handleHelp(commandSender: ICommandSender) {
        commandSender.sendMessage("""
        PORT MANAGER COMMANDS
        ================================
        port stats - Show port statistics
        port brute-close <port> - Forcefully close a port
        port force-kill <port> - Forcefully kill service on port
        port check <port> - Check port status
        port find <startPort> <endPort> [maxResults] - Find available ports in range
        port cleanup - Clean force-closed ports cache
        port help  - Show this help
        """.trimIndent())
    }
}