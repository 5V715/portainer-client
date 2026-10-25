package dev.silas.portainer.core.cleanup

import dev.silas.portainer.core.model.ContainerState
import dev.silas.portainer.core.model.ContainerSummary
import dev.silas.portainer.core.model.ImageSummary
import dev.silas.portainer.core.model.NONE_TAG
import dev.silas.portainer.core.model.Network
import dev.silas.portainer.core.model.Volume
import dev.silas.portainer.core.resources.ResourceProtection
import dev.silas.portainer.core.resources.ResourceUsage

private val STOPPED = setOf(ContainerState.Exited, ContainerState.Created, ContainerState.Dead)

/** Builds the previewed cleanup lists. Nothing here deletes anything; protection always wins over selection. */
object CleanupPlanner {
    fun containers(cs: List<ContainerSummary>, protection: ResourceProtection): CleanupPlan {
        val stopped = cs.filter { it.state in STOPPED }
        // Fail safe: a container whose image is only an unresolved id might be Portainer, so never select it.
        val kept = stopped.filter { protection.isContainerProtected(it) || protection.isImageUnresolved(it) }
        val candidates = stopped - kept.toSet()
        return CleanupPlan(
            CleanupKind.Containers,
            toDelete = candidates.map(::item),
            kept = kept.map { if (protection.isImageUnresolved(it)) CleanupItem(it.id, "${it.name} (image unknown)", null) else item(it) },
        )
    }

    fun images(
        images: List<ImageSummary>,
        usage: ResourceUsage,
        protection: ResourceProtection,
        includeTagged: Boolean,
    ): CleanupPlan {
        val (kept, others) = images.partition(protection::isImageProtected)
        val selected = others.filter { !usage.isImageUsed(it.id) && (it.dangling || includeTagged) }
        return CleanupPlan(CleanupKind.Images, selected.map(::item), kept = kept.map(::item))
    }

    fun volumes(volumes: List<Volume>, usage: ResourceUsage, protection: ResourceProtection): CleanupPlan {
        val (kept, others) = volumes.partition { protection.isVolumeProtected(it.name) }
        val unused = others.filter { !usage.isVolumeUsed(it.name) }
        val (anonymous, named) = unused.partition { it.anonymous }
        return CleanupPlan(
            CleanupKind.Volumes,
            toDelete = anonymous.map(::item),
            namedVolumes = named.map(::item),
            kept = kept.map(::item),
        )
    }

    fun networks(networks: List<Network>, usage: ResourceUsage, protection: ResourceProtection): CleanupPlan {
        val listed = networks.filter { !it.predefined }
        val (kept, others) = listed.partition(protection::isNetworkProtected)
        return CleanupPlan(
            CleanupKind.Networks,
            toDelete = others.filter { !usage.isNetworkUsed(it.name) }.map(::item),
            kept = kept.map(::item),
        )
    }

    private fun item(c: ContainerSummary) = CleanupItem(c.id, c.name, null)

    private fun item(i: ImageSummary): CleanupItem {
        val tag = i.repoTags.firstOrNull { it != NONE_TAG }
        val label = tag ?: "<none> ${i.id.removePrefix("sha256:").take(12)}"
        return CleanupItem(i.id, label, i.size)
    }

    private fun item(v: Volume) =
        CleanupItem(v.name, if (v.anonymous) v.name.take(12) + " (anonymous)" else v.name, null)

    private fun item(n: Network) = CleanupItem(n.id, n.name, null)
}
