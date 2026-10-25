package dev.silas.portainer.core.cleanup

import kotlin.test.Test
import kotlin.test.assertEquals

class RecoverableTest {
    private fun item(id: String, size: Long?) = CleanupItem(id, id, size)

    @Test
    fun sumsKnownSizesAndCountsUnknown() {
        val r = listOf(item("a", 100), item("b", null), item("c", 0), item("d", 5)).recoverable()
        assertEquals(Recoverable(knownBytes = 105, knownCount = 3, unknownCount = 1), r)
    }

    @Test
    fun emptyListHasNothing() {
        assertEquals(Recoverable(0, 0, 0), emptyList<CleanupItem>().recoverable())
    }

    @Test
    fun withVolumeSizesFillsOnlyMatchingNamesAndKeepsSelectionAndKept() {
        val plan = CleanupPlan(
            CleanupKind.Volumes,
            toDelete = listOf(item("anon", null)),
            namedVolumes = listOf(item("db", null), item("gone", null)),
            kept = listOf(item("portainer_data", null)),
        )
        val sized = plan.withVolumeSizes(mapOf("anon" to 10L, "db" to null, "portainer_data" to 99L))
        assertEquals(listOf(item("anon", 10)), sized.toDelete)
        assertEquals(listOf(item("db", null), item("gone", null)), sized.namedVolumes)
        assertEquals(plan.kept, sized.kept) // only deletable items are sized
    }
}
