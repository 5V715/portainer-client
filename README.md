# Portainer Client

A Kotlin Multiplatform + Compose Multiplatform client for managing Docker containers through a Portainer server. It targets Android, iOS and (experimental) the web via wasmJs, and talks to Portainer over HTTPS using an API key.

## Build

```sh
# Shared tests (JVM) and wasm tests
./gradlew :shared:jvmTest :shared:wasmJsTest

# Android debug APK
./gradlew :composeApp:assembleDebug

# Web distribution (experimental)
./gradlew :composeApp:wasmJsBrowserDistribution

# iOS (macOS only)
./gradlew :shared:iosSimulatorArm64Test :composeApp:linkDebugFrameworkIosSimulatorArm64
```

Android builds need the Android SDK (`sdk.dir` in `local.properties`).
