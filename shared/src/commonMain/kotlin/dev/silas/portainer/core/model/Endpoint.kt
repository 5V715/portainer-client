package dev.silas.portainer.core.model

enum class EndpointType(val code: Int) {
    DockerLocal(1),
    DockerAgent(2),
    Azure(3),
    EdgeAgent(4),
    KubernetesLocal(5),
    KubernetesAgent(6),
    KubernetesEdge(7),
    Unknown(-1),
    ;

    val supported: Boolean get() = this == DockerLocal || this == DockerAgent

    companion object {
        fun fromCode(code: Int): EndpointType = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

data class Endpoint(val id: Int, val name: String, val type: EndpointType, val online: Boolean)
