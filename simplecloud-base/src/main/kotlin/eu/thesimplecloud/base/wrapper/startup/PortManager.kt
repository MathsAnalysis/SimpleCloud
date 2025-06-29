package eu.thesimplecloud.base.wrapper.startup

import eu.thesimplecloud.launcher.startup.Launcher
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

class PortManager {

    private val usedPorts = ConcurrentHashMap.newKeySet<Int>()
    private val blockedPorts = ConcurrentHashMap.newKeySet<Int>()
    private val forcedClosePorts = ConcurrentHashMap.newKeySet<Int>()

    private val minPort = 1024
    private val maxPort = 65535
    private val maxRetries = 100

    companion object {
        private const val BLOCKED_PORTS_LIMIT = 500
        private const val SO_TIMEOUT = 1000
    }

    @Synchronized
    fun getUnusedPort(startPort: Int): Int {
        if (!isInUse(startPort) && !isPortBlockedByOtherApp(startPort)) {
            usedPorts.add(startPort)
            return startPort
        }

        return findRandomAvailablePort(startPort)
    }

    private fun findRandomAvailablePort(preferredStart: Int): Int {
        var attempts = 0
        val random = ThreadLocalRandom.current()

        while (attempts < maxRetries) {
            val randomPort = if (attempts < 20) {
                (preferredStart + random.nextInt(-100, 500)).coerceIn(minPort, maxPort)
            } else {
                random.nextInt(minPort, maxPort + 1)
            }

            if (!isInUse(randomPort) && !isPortBlockedByOtherApp(randomPort)) {
                usedPorts.add(randomPort)
                return randomPort
            }

            attempts++
        }

        throw RuntimeException("Unable to find available port after $maxRetries attempts")
    }

    fun forceKillService(port: Int): Boolean {
        var success = false

        try {
            val killed = killProcessUsingPort(port)
            val portClosed = bruteClosePort(port)

            usedPorts.remove(port)
            blockedPorts.remove(port)
            forcedClosePorts.add(port)

            success = killed || portClosed

            if (success) {
                Launcher.instance.logger.info("Service on port $port forcefully killed and cleaned up")
            } else {
                Launcher.instance.logger.warning("Failed to forcefully kill service on port $port")
            }

        } catch (e: Exception) {
            Launcher.instance.logger.severe("FORCE KILL: Error during forced service kill on port $port: ${e.message}")
        }

        return success
    }

    private fun killProcessUsingPort(port: Int): Boolean {
        return try {
            val os = System.getProperty("os.name").lowercase()
            val command = if (os.contains("win")) {
                arrayOf("cmd", "/c", "for /f \"tokens=5\" %a in ('netstat -aon ^| findstr :$port') do taskkill /f /pid %a")
            } else {
                arrayOf("sh", "-c", "lsof -ti:$port | xargs kill -9")
            }

            val process = ProcessBuilder(*command).start()
            val exitCode = process.waitFor()
            exitCode == 0

        } catch (e: Exception) {
            false
        }
    }

    fun bruteClosePort(port: Int): Boolean {
        var success = false

        try {
            usedPorts.remove(port)
            blockedPorts.remove(port)
            forcedClosePorts.add(port)

            try {
                ServerSocket(port).use { serverSocket ->
                    serverSocket.reuseAddress = true
                    serverSocket.soTimeout = SO_TIMEOUT
                    success = true
                }
            } catch (e: IOException) {
                // Ignore error
            }

            forceKillConnectionsOnPort(port)

            Thread.sleep(100)

            if (isPortAvailable(port)) {
                success = true
            }

        } catch (e: Exception) {
            Launcher.instance.logger.severe("BRUTE CLOSE: Error during forced closure of port $port: ${e.message}")
        }

        return success
    }

    private fun forceKillConnectionsOnPort(port: Int) {
        try {
            repeat(5) {
                try {
                    Socket("127.0.0.1", port).use {
                        // Force close connection
                    }
                } catch (e: IOException) {
                    // Ignore error
                }
            }
        } catch (e: Exception) {
            // Ignore error
        }
    }

    private fun isPortBlockedByOtherApp(port: Int): Boolean {
        if (blockedPorts.contains(port)) {
            return true
        }

        val isPortAvailable = isPortAvailable(port)
        if (!isPortAvailable) {
            addBlockedPort(port)
        }
        return !isPortAvailable
    }

    private fun isInUse(port: Int): Boolean {
        return usedPorts.contains(port)
    }

    private fun isPortAvailable(port: Int): Boolean {
        if (port < minPort || port > maxPort) {
            return false
        }

        try {
            Socket("127.0.0.1", port).use {
                return false
            }
        } catch (ignored: IOException) {
            // Port is free
        }

        try {
            ServerSocket(port).use {
                it.reuseAddress = true
                return true
            }
        } catch (e: IOException) {
            return false
        }
    }

    fun setPortUnused(port: Int) {
        usedPorts.remove(port)
        forcedClosePorts.remove(port)
    }

    private fun addBlockedPort(port: Int) {
        if (blockedPorts.size > BLOCKED_PORTS_LIMIT) {
            blockedPorts.clear()
        }
        blockedPorts.add(port)
    }

    fun getStats(): PortManagerStats {
        return PortManagerStats(
            usedPorts = usedPorts.size,
            blockedPorts = blockedPorts.size,
            forcedClosePorts = forcedClosePorts.size,
            usedPortsList = usedPorts.toList().sorted(),
            blockedPortsList = blockedPorts.toList().sorted()
        )
    }

    fun cleanupForcedClosedPorts() {
        forcedClosePorts.clear()
    }

    fun findAvailablePortsInRange(startPort: Int, endPort: Int, maxResults: Int = 10): List<Int> {
        val availablePorts = mutableListOf<Int>()

        for (port in startPort..endPort) {
            if (availablePorts.size >= maxResults) break

            if (!isInUse(port) && !isPortBlockedByOtherApp(port)) {
                availablePorts.add(port)
            }
        }

        return availablePorts
    }

    fun getPortStatus(port: Int): PortStatus {
        return when {
            usedPorts.contains(port) -> PortStatus.USED_BY_US
            blockedPorts.contains(port) -> PortStatus.BLOCKED_BY_OTHER
            forcedClosePorts.contains(port) -> PortStatus.FORCE_CLOSED
            !isPortAvailable(port) -> PortStatus.OCCUPIED_BY_OTHER
            else -> PortStatus.AVAILABLE
        }
    }
}

data class PortManagerStats(
    val usedPorts: Int,
    val blockedPorts: Int,
    val forcedClosePorts: Int,
    val usedPortsList: List<Int>,
    val blockedPortsList: List<Int>
)

enum class PortStatus(val description: String) {
    AVAILABLE("Port available"),
    USED_BY_US("Port used by our system"),
    BLOCKED_BY_OTHER("Port blocked by other application"),
    OCCUPIED_BY_OTHER("Port occupied by other application"),
    FORCE_CLOSED("Port forcefully closed")
}