package dev.silas.portainer.app.support

actual fun prefersReducedMotion(): Boolean = matchesReducedMotion()

private fun matchesReducedMotion(): Boolean = js("window.matchMedia('(prefers-reduced-motion: reduce)').matches")
