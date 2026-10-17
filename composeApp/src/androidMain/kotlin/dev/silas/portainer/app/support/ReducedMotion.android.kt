package dev.silas.portainer.app.support

import android.provider.Settings
import dev.silas.portainer.app.AppContext

actual fun prefersReducedMotion(): Boolean =
    Settings.Global.getFloat(AppContext.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
