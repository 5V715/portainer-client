package dev.silas.portainer.app.servers

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.core.storage.ServerConfig
import dev.silas.portainer.core.storage.ValidationResult
import dev.silas.portainer.core.storage.normalizeBaseUrl
import dev.silas.portainer.core.storage.validateServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Why a save attempt failed; shown inline under the form. */
sealed interface SaveError {
    data class BadUrl(val message: String) : SaveError
    data object MissingKey : SaveError
    data object InvalidKey : SaveError
    data class Unreachable(val message: String) : SaveError
    data class Failed(val message: String) : SaveError

    val text: String
        get() = when (this) {
            is BadUrl -> message
            MissingKey -> "Enter an API key"
            InvalidKey -> "Invalid API key"
            is Unreachable -> "Can't reach server: $message"
            is Failed -> message
        }
}

class ServerEditViewModel(
    private val container: AppContainer,
    private val serverId: String?,
) : ViewModel() {
    private val existing = serverId?.let { id -> container.servers.servers.value.firstOrNull { it.id == id } }

    val isEdit: Boolean = existing != null

    var name by mutableStateOf(existing?.name ?: "")
    var url by mutableStateOf(existing?.baseUrl ?: "")
    var apiKey by mutableStateOf("")
    var error by mutableStateOf<SaveError?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var saved by mutableStateOf(false)
        private set

    fun save() {
        if (saving) return
        val baseUrl = normalizeBaseUrl(url).getOrElse {
            error = SaveError.BadUrl(it.message ?: "Invalid address")
            return
        }
        saving = true
        error = null
        viewModelScope.launch {
            try {
                val typedKey = apiKey.trim().ifEmpty { null }
                val keyToCheck = typedKey ?: existing?.let { container.servers.apiKey(it.id) }
                if (keyToCheck == null) {
                    error = SaveError.MissingKey
                    return@launch
                }
                when (val result = validateServer(baseUrl, keyToCheck, container.engineFactory())) {
                    is ValidationResult.Ok -> {
                        val id = existing?.id ?: Random.nextLong().toULong().toString(16)
                        val displayName = name.trim().ifEmpty { baseUrl.substringAfter("://") }
                        try {
                            container.saveServer(
                                ServerConfig(id, displayName, baseUrl, existing?.lastEndpointId),
                                typedKey,
                            )
                            apiKey = ""
                            saved = true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = SaveError.Failed("Could not store the server: ${e.message ?: "storage error"}")
                        }
                    }
                    ValidationResult.InvalidKey -> error = SaveError.InvalidKey
                    is ValidationResult.Unreachable -> error = SaveError.Unreachable(result.message)
                    is ValidationResult.Failed -> error = SaveError.Failed(result.message)
                }
            } finally {
                saving = false
            }
        }
    }
}
