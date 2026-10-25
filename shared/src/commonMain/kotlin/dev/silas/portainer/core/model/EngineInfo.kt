package dev.silas.portainer.core.model

/** Facts about the Docker engine behind an environment. */
data class EngineInfo(val apiVersion: String, val swarmActive: Boolean)
