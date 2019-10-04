package eu.thesimplecloud.lib.service

import eu.thesimplecloud.lib.bootstrap.ICloudBootstrapGetter

interface ICloudServiceManager : ICloudBootstrapGetter {

    /**
     * Updates or adds a [ICloudService]
     */
    fun updateCloudService(cloudService: ICloudService)

    /**
     * Removes the specified [ICloudService]
     */
    fun removeCloudService(cloudService: ICloudService)

    /**
     * Returns all registered [ICloudService]s
     */
    fun getAllCloudServices(): List<ICloudService>

    /**
     * Returns the [ICloudService] found by the specified name
     */
    fun getCloudService(name: String): ICloudService? = getAllCloudServices().firstOrNull { it.getName().equals(name, true) }

    /**
     * Returns a list of all registered services found by this group name
     */
    fun getCloudServicesByGroupName(groupName: String): List<ICloudService> = getAllCloudServices().filter { it.getGroupName().equals(groupName, true) }

    /**
     * Returns a list of services found by the specified group name which are in LOBBY state
     */
    fun getCloudServicesInLobbyStateByGroupName(groupName: String): List<ICloudService> = getCloudServicesByGroupName(groupName).filter { it.getState() == ServiceState.LOBBY }

    /**
     * Returns a list of services found by the specified group name which are in LOBBY state and are not full
     */
    fun getNotFullServicesInLobbyStateByGroupName(groupName: String): List<ICloudService> = getCloudServicesInLobbyStateByGroupName(groupName).filter { it.getOnlinePlayers() < it.getMaxPlayers() }

    /**
     * Returns a list of all services running on the specified wrapper
     */
    fun getServicesRunningOnWrapper(wrapperName: String): List<ICloudService> = getAllCloudServices().filter { it.getWrapperName().equals(wrapperName, true) }
}