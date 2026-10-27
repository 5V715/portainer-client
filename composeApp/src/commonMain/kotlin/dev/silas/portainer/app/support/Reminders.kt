package dev.silas.portainer.app.support

/**
 * The connection-dependency reminder, shown before every operation that takes containers down (recreate, stack
 * stop/delete/redeploy, migrate): the one wording for all of them.
 */
const val CONNECTION_DEPENDENCY_REMINDER =
    "If this is needed to reach Portainer (VPN, reverse proxy), the app may lose its connection."
