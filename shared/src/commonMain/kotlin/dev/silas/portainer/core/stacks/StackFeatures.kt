package dev.silas.portainer.core.stacks

/**
 * Stack features that depend on the Portainer CE version (`PortainerClient.status().version`).
 * Gates and evidence: docs/superpowers/notes/portainer-ce-stacks-api.md, "Version gates".
 */
data class StackFeatures(
    /** Detach a git stack (PUT the current file as a web-editor stack). */
    val detachFromGit: Boolean,
    /** Listing a repository's branches/tags: Business Edition only, never in CE. */
    val refListing: Boolean,
    /** CE GitOps Sources (`SourceID` / `GitSourceId`) hold saved URL, credentials and TLS. */
    val savedGitCredentials: Boolean,
    /** Never in CE: compose stack delete has no volume option. */
    val removeVolumesOnDelete: Boolean,
    /** `Prune` on the web-editor stack update. */
    val pruneOnUpdate: Boolean,
    /** Never in CE for compose stacks (server deletes external stacks as Swarm only; CE's UI offers none). */
    val externalDelete: Boolean,
    val webhookUrl: (baseUrl: String, webhookId: String) -> String,
    /** Update/redeploy return before the deploy ends; poll the stack while `Status` is deploying (3). */
    val asyncDeploy: Boolean = false,
    /** The polling interval lives on the GitOps Source; `AutoUpdate.Interval` on the stack is ignored. */
    val pollingOnSource: Boolean = false,
    /** `ConfigFilePath` and `AdditionalFiles` can be changed in the git settings update. */
    val gitPathsEditable: Boolean = false,
)

private data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
}

private val VERSION_PATTERN = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?(?:[-+].*)?$""")

private fun parseVersion(text: String): Version? {
    val match = VERSION_PATTERN.matchEntire(text.trim()) ?: return null
    val (major, minor, patch) = match.destructured
    return Version(
        major.toIntOrNull() ?: return null,
        minor.toIntOrNull() ?: return null,
        if (patch.isEmpty()) 0 else patch.toIntOrNull() ?: return null,
    )
}

/** `{baseUrl}/api/stacks/webhooks/{webhookId}`, as CE's UI builds it; [baseUrl] may end with `/`. */
private fun stackWebhookUrl(baseUrl: String, webhookId: String): String =
    "${baseUrl.trimEnd('/')}/api/stacks/webhooks/$webhookId"

/** Feature gates for [version]; an unparseable version turns every optional feature off. */
fun stackFeatures(version: String): StackFeatures {
    val v = parseVersion(version)
    fun atLeast(major: Int, minor: Int, patch: Int = 0) = v != null && v >= Version(major, minor, patch)
    return StackFeatures(
        detachFromGit = atLeast(2, 33),
        refListing = false,
        savedGitCredentials = atLeast(2, 43),
        removeVolumesOnDelete = false,
        pruneOnUpdate = atLeast(2, 40),
        externalDelete = false,
        webhookUrl = ::stackWebhookUrl,
        asyncDeploy = atLeast(2, 41),
        pollingOnSource = atLeast(2, 44),
        gitPathsEditable = atLeast(2, 40),
    )
}
