package dev.silas.portainer.core.recreate

import dev.silas.portainer.core.model.ImageRef
import kotlinx.serialization.json.JsonObject

/** What the user changed on the recreate screen. [env] is the complete desired `Env` list. */
data class RecreateEdits(val image: String, val env: List<String>, val pullImage: Boolean, val registryId: Int?)

/** Human-facing summary shown before the user confirms a recreate. */
data class ChangeSummary(
    /** (old image, new image) when the image reference changes. */
    val imageChange: Pair<String, String>?,
    val envAdded: List<String>,
    /** (old entry, new entry) for keys present in both with a different value. */
    val envChanged: List<Pair<String, String>>,
    val envRemoved: List<String>,
    /** Values inherited from the old image that are left out so the new image's defaults apply. */
    val droppedImageDefaults: List<String>,
    /** Old-image `K=V` entries that apply again because the user removed key K (Docker merges image Env at create). */
    val envReinherited: List<String> = emptyList(),
)

sealed interface RecreateWarning {
    /** Container belongs to compose project [project]; a stack redeploy will undo the edit. */
    data class StackMember(val project: String) : RecreateWarning

    /** `HostConfig.AutoRemove`: stopping the old container deletes it, so rollback is impossible after the stop. */
    data object AutoRemove : RecreateWarning

    /** Old image could not be inspected: every inherited value is kept as-is. */
    data object ImageConfigUnavailable : RecreateWarning

    /** General reminder: if the app reaches Portainer through this container, rollback can't happen. Always present. */
    data object ConnectionDependency : RecreateWarning
}

data class RecreatePlan(
    val containerName: String,
    val wasRunning: Boolean,
    /** Body for `POST /containers/create?name=<containerName>`: Config fields + `HostConfig` + `NetworkingConfig`. */
    val createBody: JsonObject,
    /** Networks to connect after create: network name → its `EndpointConfig` (for `POST /networks/{name}/connect`). */
    val extraNetworks: Map<String, JsonObject>,
    val image: ImageRef,
    val pull: Boolean,
    val registryId: Int?,
    val changes: ChangeSummary,
    val warnings: List<RecreateWarning>,
)
