package dev.silas.portainer.app.registries

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.core.api.createRegistry
import dev.silas.portainer.core.api.registries
import dev.silas.portainer.core.api.updateRegistry
import dev.silas.portainer.core.model.Registry
import dev.silas.portainer.core.model.RegistryInput
import dev.silas.portainer.core.model.RegistryType
import kotlinx.coroutines.launch

/** Inline validation messages; null means the field is fine. */
data class RegistryFormErrors(
    val name: String? = null,
    val url: String? = null,
    val region: String? = null,
    val username: String? = null,
    val password: String? = null,
) {
    val any: Boolean get() = listOf(name, url, region, username, password).any { it != null }
}

/** Types the user can pick when adding; [RegistryType.Unknown] is read-only. */
val SELECTABLE_TYPES: List<RegistryType> = listOf(
    RegistryType.DockerHub, RegistryType.GitHub, RegistryType.GitLab, RegistryType.Quay,
    RegistryType.Azure, RegistryType.ProGet, RegistryType.Ecr, RegistryType.Custom,
)

/** The URL is fixed (not editable) for these types. */
fun fixedUrl(type: RegistryType): String? = when (type) {
    RegistryType.DockerHub -> "docker.io"
    RegistryType.GitHub -> "ghcr.io"
    else -> null
}

class RegistryEditViewModel(
    private val container: AppContainer,
    private val serverId: String,
    private val registryId: Int?,
) : ViewModel() {
    val isEdit: Boolean = registryId != null

    var loading by mutableStateOf(isEdit)
        private set
    var type by mutableStateOf(RegistryType.Custom)
    var name by mutableStateOf("")
    var url by mutableStateOf("")
    var baseUrl by mutableStateOf("")
    var region by mutableStateOf("")
    var authentication by mutableStateOf(false)
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var errors by mutableStateOf(RegistryFormErrors())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var saved by mutableStateOf(false)
        private set

    private var original: Registry? = null

    val urlEditable: Boolean get() = fixedUrl(type) == null
    val showBaseUrl: Boolean get() = type == RegistryType.ProGet
    val showRegion: Boolean get() = type == RegistryType.Ecr

    init {
        if (registryId != null) loadExisting(registryId)
    }

    private fun loadExisting(id: Int) {
        viewModelScope.launch {
            val failure = runGuarded {
                val found = container.client(serverId).registries().firstOrNull { it.id == id }
                if (found == null) {
                    error = "Registry not found"
                } else if (found.type == RegistryType.Unknown) {
                    error = "This registry type can't be edited here"
                } else {
                    original = found
                    type = found.type
                    name = found.name
                    url = found.url
                    authentication = found.authentication
                    username = found.username ?: ""
                }
            }
            if (failure != null) error = failure
            loading = false
        }
    }

    fun save() {
        if (saving || loading || (isEdit && original == null)) return
        val found = validate()
        errors = found
        if (found.any) return
        val input = buildInput()
        saving = true
        error = null
        viewModelScope.launch {
            try {
                val failure = runGuarded {
                    val client = container.client(serverId)
                    if (registryId == null) client.createRegistry(input) else client.updateRegistry(registryId, input)
                }
                if (failure == null) {
                    password = ""
                    saved = true
                } else {
                    error = failure
                }
            } finally {
                saving = false
            }
        }
    }

    private fun validate(): RegistryFormErrors {
        val needsPassword = authentication && (!isEdit || original?.authentication == false)
        return RegistryFormErrors(
            name = if (name.isBlank()) "Enter a name" else null,
            url = if (urlEditable && url.isBlank()) "Enter a URL" else null,
            region = if (showRegion && !isEdit && region.isBlank()) "Enter a region" else null,
            username = if (authentication && username.isBlank()) "Enter a username" else null,
            password = if (needsPassword && password.isEmpty()) "Enter a password" else null,
        )
    }

    private fun buildInput() = RegistryInput(
        name = name.trim(),
        type = type,
        url = fixedUrl(type) ?: url.trim(),
        authentication = authentication,
        username = if (authentication) username.trim() else null,
        // Empty on edit means unchanged: omitted from the request.
        password = if (authentication) password.ifEmpty { null } else null,
        baseUrl = if (showBaseUrl) baseUrl.trim().ifEmpty { null } else null,
        ecrRegion = if (showRegion) region.trim().ifEmpty { null } else null,
    )
}
