package dev.silas.portainer.core.cleanup

import dev.silas.portainer.core.model.ContainerState
import dev.silas.portainer.core.model.ContainerSummary
import dev.silas.portainer.core.model.ImageSummary
import dev.silas.portainer.core.model.Network
import dev.silas.portainer.core.model.Volume
import dev.silas.portainer.core.resources.ResourceProtection
import dev.silas.portainer.core.resources.ResourceUsage
import kotlin.test.Test
import kotlin.test.assertEquals

class CleanupPlannerTest {
    private val hex = "b".repeat(64)

    private fun c(
        id: String,
        state: ContainerState = ContainerState.Exited,
        image: String = "app:1",
        imageId: String = "",
        volumes: List<String> = emptyList(),
        networks: List<String> = emptyList(),
    ) = ContainerSummary(id, "n-$id", image, state, "", null, null, emptyMap(), imageId, volumes, networks)

    private fun net(id: String, name: String) = Network(id, name, "bridge", "local", false, false, emptyList(), emptyMap(), emptyMap())
    private fun img(id: String, vararg tags: String, size: Long = 10) = ImageSummary(id = id, repoTags = tags.toList(), size = size)

    private fun ids(items: List<CleanupItem>) = items.map { it.id }

    @Test
    fun containersPlanSelectsStoppedAndKeepsProtected() {
        val cs = listOf(
            c("run", ContainerState.Running),
            c("exited"),
            c("created", ContainerState.Created),
            c("dead", ContainerState.Dead),
            c("paused", ContainerState.Paused),
            c("ptn", image = "portainer/portainer-ce:2.21"),
            c("ptn-run", ContainerState.Running, image = "portainer/agent"),
        )
        val plan = CleanupPlanner.containers(cs, ResourceProtection(cs))
        assertEquals(listOf("exited", "created", "dead"), ids(plan.toDelete))
        assertEquals(listOf("ptn"), ids(plan.kept))
        assertEquals("n-exited", plan.toDelete[0].label)
    }

    @Test
    fun containerWithIdOnlyImageIsProtectedViaResolvedImages() {
        val cs = listOf(c("x", image = "sha256:$hex"), c("y", image = hex))
        val plan = CleanupPlanner.containers(cs, ResourceProtection(cs, mapOf("x" to "docker.io/portainer/portainer-ee:2")))
        assertEquals(listOf("y"), ids(plan.toDelete))
        assertEquals(listOf("x"), ids(plan.kept))
    }

    @Test
    fun imagesDefaultSelectsOnlyUnusedDangling() {
        val dangling = img("sha256:d1", "<none>:<none>", size = 5)
        val danglingEmpty = img("sha256:d2")
        val usedDangling = img("sha256:d3")
        val tagged = img("sha256:t1", "nginx:1")
        val cs = listOf(c("s", imageId = "sha256:d3"))
        val images = listOf(dangling, danglingEmpty, usedDangling, tagged)
        val plan = CleanupPlanner.images(images, ResourceUsage(cs), ResourceProtection(cs), includeTagged = false)
        assertEquals(listOf("sha256:d1", "sha256:d2"), ids(plan.toDelete))
        assertEquals(5L, plan.toDelete[0].sizeBytes)
    }

    @Test
    fun includeTaggedAddsUnusedTaggedButNeverUsedByStoppedContainer() {
        val cs = listOf(c("s", imageId = "sha256:t2"))
        val images = listOf(img("sha256:t1", "nginx:1"), img("sha256:t2", "redis:7"), img("sha256:d1"))
        val plan = CleanupPlanner.images(images, ResourceUsage(cs), ResourceProtection(cs), includeTagged = true)
        assertEquals(listOf("sha256:t1", "sha256:d1"), ids(plan.toDelete))
        assertEquals("nginx:1", plan.toDelete[0].label)
        assertEquals("<none> d1", plan.toDelete[1].label)
    }

    @Test
    fun protectedImagesAreKeptEvenWhenUnused() {
        val cs = listOf(c("p", image = "portainer/portainer-ce", imageId = "sha256:untagged"))
        val images = listOf(
            img("sha256:p1", "portainer/portainer-ce:2.21", "other:1"),
            img("sha256:untagged"),
            img("sha256:free", "nginx:1"),
        )
        val plan = CleanupPlanner.images(images, ResourceUsage(cs), ResourceProtection(cs), includeTagged = true)
        assertEquals(listOf("sha256:free"), ids(plan.toDelete))
        assertEquals(listOf("sha256:p1", "sha256:untagged"), ids(plan.kept))
    }

    @Test
    fun volumesSplitAnonymousNamedAndKeepProtected() {
        val cs = listOf(
            c("p", image = "portainer/portainer-ce", volumes = listOf("ptn-vol")),
            c("s", volumes = listOf("used-vol")),
        )
        val volumes = listOf(
            Volume(name = hex),
            Volume(name = "named"),
            Volume(name = "portainer_data"),
            Volume(name = "ptn-vol"),
            Volume(name = "used-vol"),
        )
        val plan = CleanupPlanner.volumes(volumes, ResourceUsage(cs), ResourceProtection(cs))
        assertEquals(listOf(hex), ids(plan.toDelete))
        assertEquals("bbbbbbbbbbbb (anonymous)", plan.toDelete[0].label)
        assertEquals(listOf("named"), ids(plan.namedVolumes))
        assertEquals(listOf("portainer_data", "ptn-vol"), ids(plan.kept))
    }

    @Test
    fun networksExcludePredefinedUsedAndKeepProtected() {
        val cs = listOf(
            c("s", networks = listOf("used")),
            c("p", image = "portainer/agent", networks = listOf("ptn-net")),
        )
        val nets = listOf(net("1", "bridge"), net("2", "host"), net("3", "none"), net("4", "used"), net("5", "free"), net("6", "ptn-net"))
        val plan = CleanupPlanner.networks(nets, ResourceUsage(cs), ResourceProtection(cs))
        assertEquals(listOf("5"), ids(plan.toDelete))
        assertEquals("free", plan.toDelete[0].label)
        assertEquals(listOf("6"), ids(plan.kept))
    }
}
