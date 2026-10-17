package dev.silas.portainer.app.support

import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

actual fun prefersReducedMotion(): Boolean = UIAccessibilityIsReduceMotionEnabled()
