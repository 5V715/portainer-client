package dev.silas.portainer.core.storage

import kotlinx.serialization.Serializable

/** A saved Portainer server. The API key is never part of this; see [SecretStore]. */
@Serializable
data class ServerConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val lastEndpointId: Int? = null,
)
