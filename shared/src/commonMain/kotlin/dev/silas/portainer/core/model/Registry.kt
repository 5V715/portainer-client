package dev.silas.portainer.core.model

enum class RegistryType(val code: Int) {
    Quay(1), Azure(2), Custom(3), GitLab(4), ProGet(5), DockerHub(6), Ecr(7), GitHub(8), Unknown(0);

    companion object {
        fun fromCode(code: Int): RegistryType = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

data class Registry(
    val id: Int,
    val name: String,
    val type: RegistryType,
    val url: String,
    val authentication: Boolean,
    val username: String?,
)

/** [password] == null on update means "unchanged": the field is omitted from the request. */
data class RegistryInput(
    val name: String,
    val type: RegistryType,
    val url: String,
    val authentication: Boolean,
    val username: String?,
    val password: String?,
    val baseUrl: String? = null,
    val ecrRegion: String? = null,
) {
    override fun toString(): String =
        "RegistryInput(name=$name, type=$type, url=$url, authentication=$authentication, username=$username, " +
            "password=${if (password == null) "null" else "***"}, baseUrl=$baseUrl, ecrRegion=$ecrRegion)"
}
