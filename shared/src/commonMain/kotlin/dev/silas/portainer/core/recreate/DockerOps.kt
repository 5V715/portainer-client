package dev.silas.portainer.core.recreate

import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.api.connectNetwork
import dev.silas.portainer.core.api.createContainer
import dev.silas.portainer.core.api.pullImage
import dev.silas.portainer.core.api.removeContainer
import dev.silas.portainer.core.api.renameContainer
import dev.silas.portainer.core.api.startContainer
import dev.silas.portainer.core.api.stopContainer
import dev.silas.portainer.core.model.ImageRef
import kotlinx.serialization.json.JsonObject

/** The Docker operations a recreate needs, so [Recreator] can be tested without HTTP. */
interface DockerOps {
    suspend fun pull(image: ImageRef, registryId: Int?)
    suspend fun rename(id: String, newName: String)
    suspend fun create(name: String, body: JsonObject): String
    suspend fun connect(network: String, containerId: String, config: JsonObject)
    suspend fun stop(id: String)
    suspend fun start(id: String)

    /** Removes the container only; volumes are never removed. */
    suspend fun remove(id: String, force: Boolean)
}

class PortainerDockerOps(private val client: PortainerClient, private val endpointId: Int) : DockerOps {
    override suspend fun pull(image: ImageRef, registryId: Int?) = client.pullImage(endpointId, image, registryId)
    override suspend fun rename(id: String, newName: String) = client.renameContainer(endpointId, id, newName)
    override suspend fun create(name: String, body: JsonObject): String = client.createContainer(endpointId, name, body)
    override suspend fun connect(network: String, containerId: String, config: JsonObject) =
        client.connectNetwork(endpointId, network, containerId, config)
    override suspend fun stop(id: String) = client.stopContainer(endpointId, id)
    override suspend fun start(id: String) = client.startContainer(endpointId, id)

    // Never `v=true`: the new container uses the old one's volumes.
    override suspend fun remove(id: String, force: Boolean) = client.removeContainer(endpointId, id, force)
}
