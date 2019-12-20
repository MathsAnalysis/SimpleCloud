package eu.thesimplecloud.base.manager.impl

import eu.thesimplecloud.base.manager.startup.Manager
import eu.thesimplecloud.clientserverapi.lib.packet.communicationpromise.CommunicationPromise
import eu.thesimplecloud.clientserverapi.lib.packet.communicationpromise.ICommunicationPromise
import eu.thesimplecloud.clientserverapi.lib.packet.packetresponse.responsehandler.ObjectPacketResponseHandler
import eu.thesimplecloud.clientserverapi.server.client.connectedclient.IConnectedClient
import eu.thesimplecloud.lib.CloudLib
import eu.thesimplecloud.lib.network.packets.player.*
import eu.thesimplecloud.lib.player.AbstractCloudPlayerManager
import eu.thesimplecloud.lib.player.ICloudPlayer
import eu.thesimplecloud.lib.player.IOfflineCloudPlayer
import eu.thesimplecloud.lib.player.text.CloudText
import eu.thesimplecloud.lib.service.ICloudService
import eu.thesimplecloud.lib.service.ServiceType
import java.lang.IllegalStateException
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class CloudPlayerManagerImpl : AbstractCloudPlayerManager() {

    /**
     * This maps contains player unique ids and service names.
     * The uuid is the unique id of the player.
     */
    val playerUpdates = HashMap<UUID, MutableList<String>>()

    override fun updateCloudPlayer(cloudPlayer: ICloudPlayer) {
        super.updateCloudPlayer(cloudPlayer)
        val proxyClient = cloudPlayer.getConnectedProxy()?.let { Manager.instance.communicationServer.getClientManager().getClientByClientValue(it) }
        val serverClient = cloudPlayer.getConnectedServer()?.let { Manager.instance.communicationServer.getClientManager().getClientByClientValue(it) }
        val playerUpdatePacket = PacketIOUpdateCloudPlayer(cloudPlayer)

        proxyClient?.sendQuery(playerUpdatePacket)
        serverClient?.sendQuery(playerUpdatePacket)

        val requestedPlayerUpdatesServices = playerUpdates[cloudPlayer.getUniqueId()]
        requestedPlayerUpdatesServices?.mapNotNull { getCloudClientByServiceName(it) }?.forEach { it.sendQuery(playerUpdatePacket) }
    }

    override fun getCloudPlayer(uniqueId: UUID): ICommunicationPromise<ICloudPlayer> {
        return promiseOfNullablePlayer(getCachedCloudPlayer(uniqueId))
    }

    override fun getCloudPlayer(name: String): ICommunicationPromise<ICloudPlayer> {
        return promiseOfNullablePlayer(getCachedCloudPlayer(name))
    }

    override fun sendMessageToPlayer(cloudPlayer: ICloudPlayer, cloudText: CloudText) {
        val proxyClient = getProxyClientOfCloudPlayer(cloudPlayer)
        proxyClient?.sendQuery(PacketIOSendMessageToCloudPlayer(cloudPlayer, cloudText))
    }

    override fun connectPlayer(cloudPlayer: ICloudPlayer, cloudService: ICloudService): ICommunicationPromise<Boolean> {
        if (cloudService.getServiceType() == ServiceType.PROXY) throw IllegalArgumentException("Cannot send player to a proxy service")
        val proxyClient = getProxyClientOfCloudPlayer(cloudPlayer)
        proxyClient ?: return CommunicationPromise.of(false)
        return proxyClient.sendQuery(PacketIOConnectCloudPlayer(cloudPlayer, cloudService), ObjectPacketResponseHandler.new())
    }

    override fun kickPlayer(cloudPlayer: ICloudPlayer, message: String) {
        val proxyClient = getProxyClientOfCloudPlayer(cloudPlayer)
        proxyClient?.sendQuery(PacketIOKickCloudPlayer(cloudPlayer, message))
    }

    override fun sendTitle(cloudPlayer: ICloudPlayer, title: String, subTitle: String, fadeIn: Int, stay: Int, fadeOut: Int) {
        val proxyClient = getProxyClientOfCloudPlayer(cloudPlayer)
        proxyClient?.sendQuery(PacketIOSendTitleToCloudPlayer(cloudPlayer, title, subTitle, fadeIn, stay, fadeOut))
    }

    override fun forcePlayerCommandExecution(cloudPlayer: ICloudPlayer, command: String) {
        val proxyClient = getProxyClientOfCloudPlayer(cloudPlayer)
        proxyClient?.sendQuery(PacketIOCloudPlayerForceCommandExecution())
    }

    override fun sendActionbar(cloudPlayer: ICloudPlayer, actionbar: String) {
        val proxyClient = getProxyClientOfCloudPlayer(cloudPlayer)
        proxyClient?.sendQuery(PacketIOSendActionbarToCloudPlayer(cloudPlayer, actionbar))
    }

    override fun setUpdates(cloudPlayer: ICloudPlayer, update: Boolean, serviceName: String) {
        val list = playerUpdates.getOrPut(cloudPlayer.getUniqueId()) { ArrayList() }
        if (update) list.add(serviceName) else list.remove(serviceName)
    }

    override fun getOfflineCloudPlayer(name: String): ICommunicationPromise<IOfflineCloudPlayer> = TODO()

    override fun getOfflineCloudPlayer(uniqueId: UUID): ICommunicationPromise<IOfflineCloudPlayer> = TODO()

    private fun getProxyClientOfCloudPlayer(cloudPlayer: ICloudPlayer): IConnectedClient<*>? {
        val connectedProxy = cloudPlayer.getConnectedProxy() ?: return null
        return Manager.instance.communicationServer.getClientManager().getClientByClientValue(connectedProxy)
    }

    private fun getCloudClientByServiceName(serviceName: String): IConnectedClient<*>? {
        val cloudService = CloudLib.instance.getCloudServiceManger().getCloudService(serviceName)
        return cloudService?.let { Manager.instance.communicationServer.getClientManager().getClientByClientValue(it) }
    }

}