package dev.silas.portainer.app

import kotlinx.serialization.Serializable

@Serializable
object ServerList

@Serializable
data class ServerEdit(val serverId: String?)

@Serializable
data class Environments(val serverId: String)

@Serializable
data class Containers(val serverId: String, val endpointId: Int)

@Serializable
data class ContainerDetail(val serverId: String, val endpointId: Int, val containerId: String)

@Serializable
data class Recreate(val serverId: String, val endpointId: Int, val containerId: String)

@Serializable
data class Registries(val serverId: String)

@Serializable
data class RegistryEdit(val serverId: String, val registryId: Int?)

@Serializable
object About
