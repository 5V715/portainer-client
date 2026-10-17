package dev.silas.portainer.app

import com.russhwolf.settings.Settings
import com.russhwolf.settings.StorageSettings

actual fun createSettings(): Settings = StorageSettings()
