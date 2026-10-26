package dev.silas.portainer.core.stacks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StackFeaturesTest {
    private fun StackFeatures.optional() = listOf(
        detachFromGit, refListing, savedGitCredentials, removeVolumesOnDelete, pruneOnUpdate, externalDelete,
        asyncDeploy, pollingOnSource, gitPathsEditable,
    )

    @Test
    fun unparseableVersionIsConservative() {
        for (v in listOf("garbage", "", "2", "2.x.1", "v", "latest")) {
            assertTrue(stackFeatures(v).optional().none { it }, "expected all off for '$v'")
        }
    }

    @Test
    fun newestCeMatchesNotes() {
        // CE 2.45.3, docs/superpowers/notes/portainer-ce-stacks-api.md "Version gates".
        val f = stackFeatures("2.45.3")
        assertTrue(f.detachFromGit)
        assertFalse(f.refListing)
        assertTrue(f.savedGitCredentials)
        assertFalse(f.removeVolumesOnDelete)
        assertTrue(f.pruneOnUpdate)
        assertFalse(f.externalDelete)
        assertTrue(f.asyncDeploy)
        assertTrue(f.pollingOnSource)
        assertTrue(f.gitPathsEditable)
    }

    @Test
    fun previousLtsLine() {
        val f = stackFeatures("2.39.8")
        assertTrue(f.detachFromGit)
        assertFalse(f.savedGitCredentials)
        assertFalse(f.pruneOnUpdate)
        assertFalse(f.asyncDeploy)
        assertFalse(f.pollingOnSource)
        assertFalse(f.gitPathsEditable)
    }

    @Test
    fun boundaries() {
        assertFalse(stackFeatures("2.32.9").detachFromGit)
        assertTrue(stackFeatures("2.33.0").detachFromGit)
        assertTrue(stackFeatures("2.40.0").pruneOnUpdate)
        assertTrue(stackFeatures("2.40.0").gitPathsEditable)
        assertFalse(stackFeatures("2.40.9").asyncDeploy)
        assertTrue(stackFeatures("2.41.0").asyncDeploy)
        assertFalse(stackFeatures("2.42.0").savedGitCredentials)
        assertTrue(stackFeatures("2.43.0").savedGitCredentials)
        assertFalse(stackFeatures("2.43.0").pollingOnSource)
        assertTrue(stackFeatures("2.44.0").pollingOnSource)
        // 3.x is unverified: its gates are deliberately left unasserted (see the notes' "Version gates").
        assertFalse(stackFeatures("1.24.2").detachFromGit)
    }

    @Test
    fun toleratesSuffixesAndTwoPartVersions() {
        assertTrue(stackFeatures("2.45.3-rc1").asyncDeploy)
        assertTrue(stackFeatures(" 2.45 ").asyncDeploy)
    }

    @Test
    fun webhookUrlFormat() {
        val id = "05de31a2-79fa-4644-9c12-faa67e5c49f0"
        assertEquals("https://p.example/api/stacks/webhooks/$id", stackFeatures("2.45.3").webhookUrl("https://p.example", id))
        assertEquals("https://p.example/sub/api/stacks/webhooks/$id", stackFeatures("garbage").webhookUrl("https://p.example/sub/", id))
    }
}
