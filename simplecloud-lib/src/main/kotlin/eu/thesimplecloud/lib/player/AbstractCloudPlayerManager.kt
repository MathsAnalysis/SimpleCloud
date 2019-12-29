package eu.thesimplecloud.lib.player


import eu.thesimplecloud.clientserverapi.lib.promise.CommunicationPromise
import eu.thesimplecloud.clientserverapi.lib.promise.ICommunicationPromise
import kotlin.collections.ArrayList

abstract class AbstractCloudPlayerManager : ICloudPlayerManager {

    private val cachedPlayers = ArrayList<ICloudPlayer>()

    override fun updateCloudPlayer(cloudPlayer: ICloudPlayer) {
        val cachedCloudPlayer = getCachedCloudPlayer(cloudPlayer.getUniqueId())
        if (cachedCloudPlayer == null) {
            cachedPlayers.add(cloudPlayer)
            return
        }
        cachedCloudPlayer as CloudPlayer
        cachedCloudPlayer.setConnectedProxyName(cloudPlayer.getConnectedProxyName())
        cachedCloudPlayer.setConnectedServerName(cloudPlayer.getConnectedServerName())
    }

    override fun removeCloudPlayer(cloudPlayer: ICloudPlayer) {
        this.cachedPlayers.removeIf { it.getUniqueId() == cloudPlayer.getUniqueId() }
    }

    override fun getAllCachedCloudPlayers(): List<ICloudPlayer> = this.cachedPlayers

    /**
     * Creates a [ICommunicationPromise] with the [cloudPlayer]
     * If the [cloudPlayer] is not null it will returns a promise completed with the player.
     * If the [cloudPlayer] is null it will return a promise failed with [NoSuchElementException]
     */
    fun promiseOfNullablePlayer(cloudPlayer: ICloudPlayer?): ICommunicationPromise<ICloudPlayer> {
        return CommunicationPromise.ofNullable(cloudPlayer, NoSuchElementException("CloudPlayer not found."))
    }


}