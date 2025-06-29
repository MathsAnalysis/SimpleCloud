package eu.thesimplecloud.base.wrapper.process.queue

import eu.thesimplecloud.api.service.ICloudService
import eu.thesimplecloud.api.service.ServiceState
import eu.thesimplecloud.base.wrapper.process.CloudServiceProcess
import eu.thesimplecloud.base.wrapper.process.ICloudServiceProcess
import eu.thesimplecloud.base.wrapper.startup.Wrapper
import eu.thesimplecloud.launcher.startup.Launcher
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

class CloudServiceProcessQueue {
    private val cloudServiceProcessManager = Wrapper.instance.cloudServiceProcessManager

    private val queue = PriorityQueue<ICloudServiceProcess> { a, b ->
        val priorityA = a.getCloudService().getServiceGroup().getStartPriority()
        val priorityB = b.getCloudService().getServiceGroup().getStartPriority()
        priorityB.compareTo(priorityA)
    }

    private val startingServices = CopyOnWriteArrayList<ICloudServiceProcess>()

    private val queueLock = ReentrantLock()

    private fun getMaxSimultaneouslyStartingServices() =
        Wrapper.instance.getThisWrapper().getMaxSimultaneouslyStartingServices()

    fun addToQueue(cloudService: ICloudService) {
        Launcher.instance.consoleSender.sendProperty("wrapper.service.queued", cloudService.getName())
        val cloudServiceProcess = CloudServiceProcess(cloudService)

        queueLock.withLock {
            queue.offer(cloudServiceProcess)
        }

        Wrapper.instance.cloudServiceProcessManager.registerServiceProcess(cloudServiceProcess)
        Wrapper.instance.updateWrapperData()
    }

    fun getStartingOrQueuedServiceAmount(): Int {
        return queueLock.withLock { queue.size } + startingServices.size
    }

    fun startThread() {
        thread(start = true, isDaemon = true) {
            while (true) {
                val startingServicesRemoved = startingServices.removeIf { cloudServiceProcess ->
                    cloudServiceProcess.getCloudService().getState() == ServiceState.VISIBLE ||
                            cloudServiceProcess.getCloudService().getState() == ServiceState.INVISIBLE ||
                            cloudServiceProcess.getCloudService().getState() == ServiceState.CLOSED
                }

                val queueNotEmpty = queueLock.withLock { queue.isNotEmpty() }
                val canStartService = queueNotEmpty &&
                        startingServices.size < getMaxSimultaneouslyStartingServices()

                if (canStartService) {
                    val cloudServiceProcess = queueLock.withLock { queue.poll() }

                    if (cloudServiceProcess != null) {
                        thread { cloudServiceProcess.start() }
                        startingServices.add(cloudServiceProcess)
                    }
                }

                if (startingServicesRemoved || canStartService) {
                    Wrapper.instance.updateWrapperData()
                }

                Thread.sleep(200)
            }
        }
    }

    fun removeFromQueue(cloudService: ICloudService) {
        val cloudServiceProcess = this.cloudServiceProcessManager.getCloudServiceProcessByService(cloudService)
        cloudServiceProcess?.let { process ->
            queueLock.withLock {
                queue.remove(process)
            }
            this.cloudServiceProcessManager.unregisterServiceProcess(process)
        }
        Wrapper.instance.updateWrapperData()
    }

    fun clearQueue() {
        queueLock.withLock {
            queue.forEach { Wrapper.instance.cloudServiceProcessManager.unregisterServiceProcess(it) }
            queue.clear()
        }
    }
}