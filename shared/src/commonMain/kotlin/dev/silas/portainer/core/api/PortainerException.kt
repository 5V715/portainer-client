package dev.silas.portainer.core.api

/** Failures talking to Portainer. Messages never contain the API key. */
sealed class PortainerException(message: String) : Exception(message) {
    class Unauthorized : PortainerException("Portainer rejected the API key")
    class NotFound(message: String) : PortainerException(message)
    class Unreachable(message: String) : PortainerException(message)
    class Api(val status: Int, message: String) : PortainerException(message)
}
