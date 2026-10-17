package dev.silas.portainer.app

import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

/** Holds the application context; set from MainActivity before [createSettings] is used. */
object AppContext {
    @Volatile
    lateinit var context: Context
}

actual fun createSettings(): Settings =
    SharedPreferencesSettings(AppContext.context.getSharedPreferences("portainer", Context.MODE_PRIVATE))
