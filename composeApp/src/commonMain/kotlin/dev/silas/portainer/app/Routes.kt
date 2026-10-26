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
data class Recreate(val serverId: String, val endpointId: Int, val containerId: String, val quick: Boolean = false)

@Serializable
data class Registries(val serverId: String)

@Serializable
data class RegistryEdit(val serverId: String, val registryId: Int?)

@Serializable
object About

@Serializable
data class Images(val serverId: String, val endpointId: Int)

@Serializable
data class ImageDetail(val serverId: String, val endpointId: Int, val imageId: String)

@Serializable
data class Volumes(val serverId: String, val endpointId: Int)

@Serializable
data class VolumeDetail(val serverId: String, val endpointId: Int, val name: String)

@Serializable
data class Networks(val serverId: String, val endpointId: Int)

@Serializable
data class NetworkDetail(val serverId: String, val endpointId: Int, val networkId: String)

/** The container editor; [containerId] `null` = New container. */
@Serializable
data class Editor(val serverId: String, val endpointId: Int, val containerId: String?)

@Serializable
data class Stacks(val serverId: String, val endpointId: Int)

/** A stack; [stackId] `null` = external (limited) stack, identified by [name]. */
@Serializable
data class StackDetail(val serverId: String, val endpointId: Int, val stackId: Int?, val name: String)
