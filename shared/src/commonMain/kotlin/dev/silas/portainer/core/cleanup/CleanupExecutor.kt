package dev.silas.portainer.core.cleanup

import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.api.PortainerException
import dev.silas.portainer.core.api.removeContainer
import dev.silas.portainer.core.api.removeImage
import dev.silas.portainer.core.api.removeNetwork
import dev.silas.portainer.core.api.removeVolume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface CleanupProgress {
    data class Deleted(val item: CleanupItem) : CleanupProgress
    data class Failed(val item: CleanupItem, val message: String) : CleanupProgress
    data class Finished(val deleted: Int, val failed: Int, val reclaimedBytes: Long) : CleanupProgress
}

/** Deletes exactly [items], one at a time and in order; a failure never stops the rest. */
class CleanupExecutor(private val delete: suspend (CleanupItem) -> Unit) {
    fun run(items: List<CleanupItem>): Flow<CleanupProgress> = flow {
        var deleted = 0
        var failed = 0
        var reclaimed = 0L
        for (item in items) {
            try {
                delete(item)
                deleted++
                reclaimed += item.sizeBytes ?: 0L
                emit(CleanupProgress.Deleted(item))
            } catch (e: CancellationException) {
                throw e
            } catch (e: PortainerException.NotFound) {
                failed++
                emit(CleanupProgress.Failed(item, "Already removed"))
            } catch (e: Exception) {
                failed++
                emit(CleanupProgress.Failed(item, e.message ?: "Delete failed"))
            }
        }
        emit(CleanupProgress.Finished(deleted, failed, reclaimed))
    }
}

/** Never forces: Docker refuses to remove anything still in use. */
fun CleanupKind.deleter(client: PortainerClient, endpointId: Int): suspend (CleanupItem) -> Unit = { item ->
    when (this) {
        CleanupKind.Containers -> client.removeContainer(endpointId, item.id, force = false)
        CleanupKind.Images -> client.removeImage(endpointId, item.id, force = false)
        CleanupKind.Volumes -> client.removeVolume(endpointId, item.id)
        CleanupKind.Networks -> client.removeNetwork(endpointId, item.id)
    }
}
