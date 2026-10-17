package dev.silas.portainer.core.containers

import dev.silas.portainer.core.model.ContainerState
import dev.silas.portainer.core.model.ContainerSummary

data class ContainerFilter(
    val query: String = "",
    val states: Set<ContainerState> = emptySet(),
    val stack: String? = null,
)

fun List<ContainerSummary>.applyFilter(filter: ContainerFilter): List<ContainerSummary> {
    val q = filter.query.trim()
    return filter { c ->
        (q.isEmpty() || c.name.contains(q, ignoreCase = true) || c.image.contains(q, ignoreCase = true)) &&
            (filter.states.isEmpty() || c.state in filter.states) &&
            (filter.stack == null || c.stack == filter.stack)
    }.sortedBy { it.name.lowercase() }
}
