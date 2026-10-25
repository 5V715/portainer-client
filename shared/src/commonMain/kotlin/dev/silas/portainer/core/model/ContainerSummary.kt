package dev.silas.portainer.core.model

/** Docker Compose label naming the project (stack) a container belongs to. */
const val COMPOSE_PROJECT_LABEL = "com.docker.compose.project"

enum class ContainerState {
    Running,
    Exited,
    Paused,
    Restarting,
    Created,
    Dead,
    Removing,
    Unknown,
    ;

    companion object {
        fun fromDocker(value: String?): ContainerState =
            entries.firstOrNull { it != Unknown && it.name.equals(value, ignoreCase = true) } ?: Unknown
    }
}

enum class Health { Healthy, Unhealthy, Starting }

data class ContainerSummary(
    val id: String,
    val name: String,
    val image: String,
    val state: ContainerState,
    val statusText: String,
    val health: Health?,
    val stack: String?,
    val labels: Map<String, String>,
    /** Full `sha256:` image id the container was created from; empty when the list omitted it. */
    val imageId: String = "",
    /** Names of named/anonymous volumes mounted (`Mounts[].Type == "volume"`); bind mounts are excluded. */
    val volumeNames: List<String> = emptyList(),
    /** Networks the container is attached to (keys of `NetworkSettings.Networks`). */
    val networkNames: List<String> = emptyList(),
)
