# Portainer Client v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Compose Multiplatform Portainer client (Android, iOS, experimental wasm web) for managing Docker containers: server/environment selection, container list and actions, live logs, safe edit-and-recreate, and private registry management.

**Architecture:** Two Gradle modules. `shared` is UI-free Kotlin Multiplatform logic (Portainer API client, log decoding, recreate planning/execution, storage) with an extra `jvm` target only so tests run fast. `composeApp` holds all Compose UI, view models, navigation and platform entry points; `iosApp` is the Xcode wrapper. Container inspect data is handled as raw `JsonObject` end to end so recreate never drops fields the app doesn't model.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform (Material 3), Ktor client (OkHttp / Darwin / Js engines, MockEngine in tests), kotlinx.serialization, kotlinx.coroutines, JetBrains navigation-compose (type-safe routes) and lifecycle-viewmodel-compose, multiplatform-settings, GitHub Actions. Use current stable versions of each, pinned in `gradle/libs.versions.toml`.

**Spec:** `docs/superpowers/specs/2026-10-17-portainer-client-design.md`

## Global Constraints

- Package / application id: `dev.silas.portainer` (`dev.silas.portainer.core` in `shared`, `dev.silas.portainer.app` in `composeApp`).
- Targets: `androidTarget`, `iosArm64`, `iosSimulatorArm64`, `wasmJs` (browser) in both modules; `shared` additionally `jvm` (tests only, never shipped).
- Auth: every Portainer request sends header `X-API-Key: <key>`. No other auth.
- Transport: HTTPS only, default certificate validation, no cleartext or ATS exceptions. iOS `Info.plist` sets `NSLocalNetworkUsageDescription` ("Used to connect to your Portainer server on your local network or VPN."). `http://` URLs are rejected with "Portainer must be reached over HTTPS".
- Docker access goes through Portainer's proxy: `{baseUrl}/api/endpoints/{endpointId}/docker/...`.
- Supported environments: Portainer endpoint `Type` 1 (Docker local) and 2 (Docker agent) only. Others listed disabled with text "Not supported yet".
- API key never appears in logs, exceptions, or UI after entry. Android: Keystore-backed AES/GCM. iOS: Keychain (`kSecClassGenericPassword`, service `dev.silas.portainer`, account = server id). wasm: `localStorage` (documented as not secure).
- Log buffer capacity: 5,000 lines. Default tail: 200 lines.
- Material 3, light/dark from system setting.
- Destructive actions (remove, recreate) always need explicit confirmation.
- Recreate/stop/remove are blocked for images matching `portainer/portainer*` or `portainer/agent*` (with optional `docker.io/` prefix), message: "This container runs Portainer. Changing it from this app would cut off the connection."
- CI: Linux job on every push/PR (shared tests, Android debug APK, wasm distribution, artifacts uploaded). macOS job only on `main`, tags `v*`, and `workflow_dispatch`.
- Local builds need `dl.google.com` (Google Maven + Android SDK). If the environment blocks it, verify through CI instead of skipping verification.

## Review Focus

1. **Anonymous volumes** (`Mounts[].Type == "volume"` with no matching `HostConfig.Binds`/`HostConfig.Mounts` entry): a recreated container must reattach the *same* volume by name, not get a fresh empty one — data loss otherwise. Test in Task 7.
2. **Image pull returns HTTP 200 but the stream contains `{"error": ...}`** (bad tag, auth failure): must be treated as failure and abort the recreate before anything is touched. Test in Task 6.
3. **`AutoRemove: true` containers**: stopping the old container deletes it, so rollback after the stop step is impossible; the user must be warned before confirming, and create-time failures must still roll back cleanly. Tests in Tasks 7 and 8.
4. **Non-default network modes** (`host`, `none`, `container:<id>`): no `NetworkingConfig`, no extra network connects, and `Hostname`/`MacAddress`/`ExposedPorts` must be omitted for `container:` mode or Docker rejects the create. Test in Task 7.
5. **Server URL as typed on a phone** (`portainer.example.com`, trailing `/`, pasted `.../api`, surrounding spaces, `http://`): normalised to `https://host[:port]`, or rejected for `http://`. Test in Task 3.

---

## File Structure

```
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradlew*
.github/workflows/build.yml
README.md
shared/build.gradle.kts
shared/src/commonMain/kotlin/dev/silas/portainer/core/
  api/PortainerClient.kt        – Ktor setup, auth header, error mapping, request helpers
  api/PortainerException.kt
  api/EndpointsApi.kt           – status, endpoints
  api/ContainersApi.kt          – list/inspect/actions/create/rename/connect/logs
  api/ImagesApi.kt              – inspect image, pull
  api/RegistriesApi.kt          – registry CRUD
  model/Endpoint.kt, model/ContainerSummary.kt, model/Registry.kt, model/ImageRef.kt
  containers/ContainerFilter.kt
  logs/LogDecoder.kt, logs/LogBuffer.kt, logs/LogFollower.kt
  registry/RegistryMatching.kt
  recreate/RecreatePlan.kt, recreate/RecreatePlanner.kt, recreate/Protection.kt
  recreate/DockerOps.kt, recreate/Recreator.kt
  storage/ServerConfig.kt, storage/SecretStore.kt, storage/ServerRepository.kt, storage/ServerValidation.kt
  platform/Platform.kt          – expect httpEngine(), platformSupportsStreaming
shared/src/{androidMain,iosMain,wasmJsMain,jvmMain}/kotlin/.../platform/ and storage/SecretStore.*.kt
shared/src/commonTest/kotlin/... and commonTest/resources/fixtures/*.json
composeApp/build.gradle.kts
composeApp/src/commonMain/kotlin/dev/silas/portainer/app/
  App.kt, AppContainer.kt, Routes.kt, theme/Theme.kt
  servers/ServerListScreen.kt, servers/ServerEditScreen.kt, servers/ServersViewModel.kt
  environments/EnvironmentsScreen.kt, environments/EnvironmentsViewModel.kt
  containers/ContainerListScreen.kt, containers/ContainerListViewModel.kt
  detail/ContainerDetailScreen.kt, detail/OverviewTab.kt, detail/LogsTab.kt, detail/ContainerDetailViewModel.kt, detail/LogsViewModel.kt
  recreate/RecreateScreen.kt, recreate/RecreateViewModel.kt
  registries/RegistriesScreen.kt, registries/RegistryEditScreen.kt, registries/RegistriesViewModel.kt
composeApp/src/androidMain/...MainActivity.kt, AndroidManifest.xml, res/xml/network_security_config.xml
composeApp/src/iosMain/...MainViewController.kt
composeApp/src/wasmJsMain/...main.kt, resources/index.html
composeApp/src/commonTest/kotlin/... (view model tests)
iosApp/ (Xcode project from the JetBrains template, Info.plist edited)
```

---

### Task 1: Project scaffold and CI

**Files:**
- Create: everything under the root listed above except feature code; `shared/src/commonMain/.../platform/Platform.kt` + actuals; `composeApp` entry points showing a placeholder `App()`; `.github/workflows/build.yml`; `README.md` (skeleton); `.gitignore`.
- Test: `shared/src/commonTest/kotlin/dev/silas/portainer/core/SmokeTest.kt`

**Interfaces:**
- Produces: `expect fun httpEngine(): HttpClientEngine` (Android OkHttp, iOS Darwin, wasm Js, jvm CIO); `expect val platformSupportsStreaming: Boolean` (true on Android/iOS/jvm, false on wasm); `@Composable fun App()` in `composeApp`.

- [ ] **Step 1:** Start from JetBrains' Compose Multiplatform template on GitHub (`JetBrains/compose-multiplatform-template` or the current KMP wizard output with Android + iOS + Web/wasm). Take its `iosApp/` Xcode project and Gradle layout; rename packages to `dev.silas.portainer.*`; add the `shared` module with the targets in Global Constraints. Generate the Gradle wrapper (latest stable Gradle supported by the chosen AGP/Kotlin).
- [ ] **Step 2:** Add the iOS `Info.plist` key from Global Constraints. No cleartext/ATS exceptions anywhere.
- [ ] **Step 3: Write the smoke test** — `SmokeTest.engineIsAvailable()` asserts `httpEngine()` returns non-null.
- [ ] **Step 4: Verify** — `./gradlew :shared:jvmTest :composeApp:assembleDebug :composeApp:wasmJsBrowserDistribution` → BUILD SUCCESSFUL. (`dl.google.com` blocked → push and confirm the Linux CI job instead.)
- [ ] **Step 5: CI workflow** `build.yml`: triggers `push`, `pull_request`, `workflow_dispatch`. Job `linux` (ubuntu-latest, Temurin 21, `gradle/actions/setup-gradle`): runs `./gradlew :shared:jvmTest :shared:wasmJsTest :composeApp:assembleDebug :composeApp:wasmJsBrowserDistribution`, uploads `composeApp/build/outputs/apk/debug/*.apk` as `android-debug-apk` and `composeApp/build/dist/wasmJs/productionExecutable` as `web`. Job `pages` (needs `linux`, only on `main`): deploys the `web` artifact with `actions/upload-pages-artifact` + `actions/deploy-pages` (permissions `pages: write`, `id-token: write`); `continue-on-error: true` until Pages is enabled for the repo. Job `ios` (macos-latest) with `if: github.ref == 'refs/heads/main' || startsWith(github.ref, 'refs/tags/v') || github.event_name == 'workflow_dispatch'`: runs `./gradlew :shared:iosSimulatorArm64Test :composeApp:linkDebugFrameworkIosSimulatorArm64`, then `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -configuration Debug CODE_SIGNING_ALLOWED=NO build`.
- [ ] **Step 6: Commit** `chore: scaffold KMP Compose project with CI`, push, confirm the Linux job is green.

### Task 2: Portainer API client core and endpoints

**Files:**
- Create: `api/PortainerClient.kt`, `api/PortainerException.kt`, `api/EndpointsApi.kt`, `model/Endpoint.kt`
- Test: `shared/src/commonTest/.../api/EndpointsApiTest.kt`

**Interfaces:**
- Produces:
  - `class PortainerClient(baseUrl: String, apiKey: String, engine: HttpClientEngine = httpEngine())` with internal `http: HttpClient` (JSON with `ignoreUnknownKeys`, 15 s request timeout by default) and `fun close()`. Internal helper `dockerPath(endpointId: Int, path: String): String` → `"/api/endpoints/$endpointId/docker/$path"`.
  - `sealed class PortainerException(message: String) : Exception(message)`: `Unauthorized`, `NotFound(message)`, `Unreachable(message)`, `Api(status: Int, message: String)`. Mapping: 401/403 → `Unauthorized`; 404 → `NotFound`; other non-2xx → `Api` with the body's `message` (+ `details` if present) field, falling back to the status text; connection/timeout exceptions → `Unreachable`. The API key must not appear in any exception message.
  - `suspend fun PortainerClient.status(): PortainerStatus` (`GET /api/status`, `data class PortainerStatus(val version: String)` from `Version`).
  - `suspend fun PortainerClient.endpoints(): List<Endpoint>` (`GET /api/endpoints`).
  - `data class Endpoint(val id: Int, val name: String, val type: EndpointType, val online: Boolean)` (`online` = `Status == 1`); `enum class EndpointType(val code: Int) { DockerLocal(1), DockerAgent(2), Azure(3), EdgeAgent(4), KubernetesLocal(5), KubernetesAgent(6), KubernetesEdge(7), Unknown(-1); val supported: Boolean }` — `supported` true only for `DockerLocal`, `DockerAgent`.

- [ ] **Step 1: Write failing tests** (MockEngine): `sendsApiKeyHeader` (request header `X-API-Key == "k"`), `parsesEndpoints` (JSON `[{"Id":1,"Name":"local","Type":1,"Status":1},{"Id":3,"Name":"edge","Type":4,"Status":2}]` → two endpoints, second `supported == false`, `online == false`), `mapsUnauthorized` (401 → `PortainerException.Unauthorized`), `mapsApiErrorMessage` (500 `{"message":"boom","details":"x"}` → `Api(500, message contains "boom")`), `errorNeverContainsKey` (any mapped exception's message does not contain the key).
- [ ] **Step 2:** Run `./gradlew :shared:jvmTest --tests '*EndpointsApiTest*'` → FAIL (unresolved).
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run again → PASS.
- [ ] **Step 5: Commit** `feat(shared): Portainer client core and endpoints`.

### Task 3: Server storage and validation

**Files:**
- Create: `storage/ServerConfig.kt`, `storage/SecretStore.kt` (+ `SecretStore.android.kt`, `.ios.kt`, `.wasmJs.kt`, `.jvm.kt`), `storage/ServerRepository.kt`, `storage/ServerValidation.kt`
- Test: `storage/ServerRepositoryTest.kt`, `storage/NormalizeBaseUrlTest.kt`, `storage/ServerValidationTest.kt`

**Interfaces:**
- Consumes: `PortainerClient`, `status()`, `endpoints()`, `PortainerException` (Task 2).
- Produces:
  - `@Serializable data class ServerConfig(val id: String, val name: String, val baseUrl: String, val lastEndpointId: Int? = null)`
  - `interface SecretStore { suspend fun get(key: String): String?; suspend fun put(key: String, value: String); suspend fun remove(key: String) }`, `expect fun createSecretStore(): SecretStore` (jvm actual: in-memory map).
  - `class ServerRepository(settings: Settings, secrets: SecretStore)`: `val servers: StateFlow<List<ServerConfig>>`; `suspend fun save(config: ServerConfig, apiKey: String?)` (null key = keep existing); `suspend fun delete(id: String)` (also removes secret); `suspend fun apiKey(id: String): String?`; `suspend fun setLastEndpoint(id: String, endpointId: Int)`. Persisted as a JSON list under settings key `servers`.
  - `fun normalizeBaseUrl(input: String): Result<String>` — failure with message "Portainer must be reached over HTTPS" for `http://`.
  - `sealed interface ValidationResult { data class Ok(val version: String, val dockerEnvironments: Int); data object InvalidKey; data class Unreachable(val message: String); data class Failed(val message: String) }`, `suspend fun validateServer(baseUrl: String, apiKey: String, engine: HttpClientEngine = httpEngine()): ValidationResult`.

- [ ] **Step 1: Write failing tests:** `NormalizeBaseUrlTest` table — `" portainer.example.com "` → `"https://portainer.example.com"`; `"https://nas.example:9443/"` → `"https://nas.example:9443"`; `"https://nas.example/api"` → `"https://nas.example"`; `"https://nas.example/api/"` → `"https://nas.example"`; `"https://p.example"` → unchanged; `"http://nas.example"` → failure. `ServerRepositoryTest` (with `MapSettings` + jvm `SecretStore`): save then `servers.value` contains it and `apiKey(id) == key`; key is not present in any `MapSettings` value; `save(config, null)` keeps old key; `delete` removes both; `setLastEndpoint` persists across a new repository instance on the same settings. `ServerValidationTest` (MockEngine): 200 on status + endpoints with one Type-1 and one Type-5 → `Ok(version, 1)`; 401 → `InvalidKey`; engine throwing `IOException` → `Unreachable`.
- [ ] **Step 2:** Run `./gradlew :shared:jvmTest --tests '*storage*'` → FAIL.
- [ ] **Step 3:** Implement. Android `SecretStore`: AES/GCM key alias `portainer_api_keys` in `AndroidKeyStore`, ciphertext+IV base64 in a dedicated settings file. iOS: Keychain via `platform.Security`. wasm: `localStorage` with prefix `portainer.secret.`.
- [ ] **Step 4:** Run → PASS. Also `./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinWasmJs` compiles (iOS compile only on macOS/CI).
- [ ] **Step 5: Commit** `feat(shared): server storage, secure key store, validation`.

### Task 4: Container list API, model and filtering

**Files:**
- Create: `api/ContainersApi.kt` (list + simple actions), `model/ContainerSummary.kt`, `containers/ContainerFilter.kt`
- Test: `api/ContainersApiTest.kt`, `containers/ContainerFilterTest.kt`, fixture `fixtures/containers.json` (real `/containers/json?all=1` output with a running healthy, running unhealthy, exited, and a compose-labelled container)

**Interfaces:**
- Produces:
  - `data class ContainerSummary(val id: String, val name: String, val image: String, val state: ContainerState, val statusText: String, val health: Health?, val stack: String?, val labels: Map<String, String>)` — `name` without leading `/`; `stack` = label `com.docker.compose.project`; `health` parsed from `Status` text: `(healthy)`, `(unhealthy)`, `(health: starting)`.
  - `enum class ContainerState { Running, Exited, Paused, Restarting, Created, Dead, Removing, Unknown }`, `enum class Health { Healthy, Unhealthy, Starting }`.
  - `suspend fun PortainerClient.containers(endpointId: Int): List<ContainerSummary>` (`GET containers/json?all=1`).
  - `suspend fun PortainerClient.startContainer(endpointId: Int, id: String)`, `stopContainer(...)`, `restartContainer(...)`, `removeContainer(endpointId: Int, id: String, force: Boolean = false)`. Docker 304 (already started/stopped) counts as success.
  - `data class ContainerFilter(val query: String = "", val states: Set<ContainerState> = emptySet(), val stack: String? = null)`; `fun List<ContainerSummary>.applyFilter(filter: ContainerFilter): List<ContainerSummary>` — query matches name or image case-insensitively; empty `states` = all; result sorted by name.

- [ ] **Step 1: Write failing tests:** `parsesSummaries` (fixture → names without `/`, unhealthy one has `Health.Unhealthy`, compose one has its project as `stack`); `startTreats304AsSuccess`; `removeSendsForceFlag` (`DELETE .../containers/abc?force=true`); filter tests: query `"NGINX"` matches image `nginx:1.27`; `states = {Exited}` returns only exited; `stack = "media"` returns only that stack.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(shared): container list, actions and filtering`.

### Task 5: Logs — decoding, buffering, following

**Files:**
- Create: `logs/LogDecoder.kt`, `logs/LogBuffer.kt`, `logs/LogFollower.kt`; add `containerLogs` to `api/ContainersApi.kt`
- Test: `logs/LogDecoderTest.kt`, `logs/LogBufferTest.kt`, `logs/LogFollowerTest.kt`

**Interfaces:**
- Produces:
  - `enum class LogStream { Stdout, Stderr }`, `data class LogLine(val stream: LogStream, val timestamp: String?, val text: String)`.
  - `class LogDecoder(private val tty: Boolean) { fun feed(bytes: ByteArray): List<LogLine>; fun flush(): List<LogLine> }` — non-TTY: Docker multiplexed frames (byte 0 = stream type 1 stdout / 2 stderr, bytes 4–7 = big-endian payload length); TTY: raw bytes, all `Stdout`. Splits on `\n`, strips trailing `\r`, keeps partial lines (and partial frame headers) across `feed` calls, decodes UTF-8 only on complete lines. Each line's leading RFC3339 timestamp + space (logs are always requested with `timestamps=1`) goes to `timestamp`.
  - `class LogBuffer(val capacity: Int = 5000) { val lines: List<LogLine>; fun addAll(new: List<LogLine>); fun clear() }` — drops oldest beyond capacity.
  - `data class LogOptions(val tail: Int? = 200, val follow: Boolean = true, val since: Long? = null)` (tail null = `all`).
  - `fun PortainerClient.containerLogs(endpointId: Int, id: String, options: LogOptions): Flow<ByteArray>` — `GET containers/{id}/logs?stdout=1&stderr=1&timestamps=1&tail=..&follow=..&since=..`, streamed chunks, no request timeout when `follow`.
  - `fun followLogs(client: PortainerClient, endpointId: Int, containerId: String, tty: Boolean, options: LogOptions, streaming: Boolean = platformSupportsStreaming, pollIntervalMs: Long = 2000): Flow<List<LogLine>>` — streaming: one follow request, emits decoded batches. Polling: initial `tail` request with `follow=false`, then every interval request `since = <last timestamp's epoch seconds>`, `follow=false`, dropping lines whose `timestamp <= lastTimestamp` (string compare; Docker emits UTC `Z` timestamps).

- [ ] **Step 1: Write failing tests:** decoder — single stdout frame `"2026-10-17T10:00:00.000000001Z hello\n"` → `LogLine(Stdout, "2026-10-17T10:00:00.000000001Z", "hello")`; stderr frame → `Stderr`; frame split across three `feed` calls (header split at byte 3, payload mid-UTF-8 multibyte char) → one correct line; two lines in one frame → two lines; TTY mode with `\r\n` → text without `\r`; line without trailing newline only appears after `flush()`. Buffer — adding 5,001 lines keeps the last 5,000 (first kept line is #2). Follower (MockEngine, `streaming = false`, `pollIntervalMs = 10`, virtual time) — second poll's request has `since` param and a duplicate line at the boundary timestamp is not emitted twice.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(shared): container log decoding and following`.

### Task 6: Images, registries and authenticated pulls

**Files:**
- Create: `model/ImageRef.kt`, `model/Registry.kt`, `registry/RegistryMatching.kt`, `api/ImagesApi.kt`, `api/RegistriesApi.kt`
- Test: `model/ImageRefTest.kt`, `registry/RegistryMatchingTest.kt`, `api/ImagesApiTest.kt`, `api/RegistriesApiTest.kt`

**Interfaces:**
- Produces:
  - `data class ImageRef(val name: String, val tag: String?, val digest: String?) { val registryHost: String; companion object { fun parse(ref: String): ImageRef } }` — tag = text after the last `:` that comes after the last `/`; digest after `@`; `registryHost` = first path segment if it contains `.` or `:` or equals `localhost`, else `docker.io`. `toString()` reproduces the reference.
  - `enum class RegistryType(val code: Int) { Quay(1), Azure(2), Custom(3), GitLab(4), ProGet(5), DockerHub(6), Ecr(7), GitHub(8), Unknown(0) }`
  - `data class Registry(val id: Int, val name: String, val type: RegistryType, val url: String, val authentication: Boolean, val username: String?)`
  - `data class RegistryInput(val name: String, val type: RegistryType, val url: String, val authentication: Boolean, val username: String?, val password: String?, val baseUrl: String? = null, val ecrRegion: String? = null)` — `password == null` on update means "unchanged" (omit the field); verify field names against the Portainer version targeted.
  - `suspend fun PortainerClient.registries(): List<Registry>`, `createRegistry(input: RegistryInput): Registry`, `updateRegistry(id: Int, input: RegistryInput)`, `deleteRegistry(id: Int)` (`/api/registries`).
  - `fun matchRegistry(ref: ImageRef, registries: List<Registry>): Registry?` — compares `ref.registryHost` with each registry URL normalised (scheme and trailing `/` stripped, host[:port] part only); `DockerHub` type matches `docker.io`; returns null when nothing matches.
  - `fun registryAuthHeader(registryId: Int): String` = base64 of `{"registryId":<id>}`.
  - `suspend fun PortainerClient.inspectImage(endpointId: Int, imageId: String): JsonObject?` (404 → null).
  - `suspend fun PortainerClient.pullImage(endpointId: Int, ref: ImageRef, registryId: Int?)` — `POST images/create?fromImage=<name>&tag=<tag ?: digest ?: "latest">`, header `X-Registry-Auth` only when `registryId != null`, no request timeout, reads the newline-delimited JSON progress stream to the end and throws `PortainerException.Api(200, <error text>)` if any line has `error` or `errorDetail.message`. Message for 401/denied text: "Image pull was denied. Check the registry credentials in Registries."

- [ ] **Step 1: Write failing tests:** ImageRef table — `nginx` (host `docker.io`, tag null), `nginx:1.27`, `ghcr.io/me/app:2`, `registry.local:5000/team/app` (host `registry.local:5000`, tag null), `localhost/app:dev`, `me/app@sha256:abc` (digest). Matching — `ghcr.io/me/app` matches registry URL `https://ghcr.io/`; `nginx` matches a DockerHub registry; unknown host → null. Images — **pull stream `{"status":"Pulling"}\n{"error":"manifest unknown"}` with HTTP 200 throws `Api` containing "manifest unknown"**; pull with `registryId = 4` sends `X-Registry-Auth` that base64-decodes to `{"registryId":4}`; pull without registry sends no such header; `inspectImage` 404 → null. Registries — create posts `Name`, `Type`, `URL`, `Authentication`, `Username`, `Password`; update with `password = null` sends no `Password` key.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(shared): image refs, registries, authenticated pulls`.

### Task 7: Recreate planner (inspect → create request)

The highest-risk code. Pure function over JSON, no I/O.

**Files:**
- Create: `recreate/RecreatePlan.kt`, `recreate/RecreatePlanner.kt`, `recreate/Protection.kt`
- Test: `recreate/RecreatePlannerTest.kt`, `recreate/ProtectionTest.kt`; fixtures `fixtures/inspect_full.json` (bind + named + anonymous volume, two user networks with aliases and a static IP, ports, healthcheck, labels, restart policy, caps, devices, log config, extra hosts, DNS), `fixtures/inspect_container_netmode.json` (`NetworkMode: container:<id>`), `fixtures/inspect_autoremove.json`, `fixtures/image_config.json` (old image config with Env `PATH=...`, `APP_VERSION=1`, Cmd, Entrypoint, a label, ExposedPorts, Volumes). Use real `docker inspect` output where possible.

**Interfaces:**
- Consumes: `ImageRef` (Task 6).
- Produces:
  - `data class RecreateEdits(val image: String, val env: List<String>, val pullImage: Boolean, val registryId: Int?)`
  - `data class ChangeSummary(val imageChange: Pair<String, String>?, val envAdded: List<String>, val envChanged: List<Pair<String, String>>, val envRemoved: List<String>, val droppedImageDefaults: List<String>)`
  - `sealed interface RecreateWarning { data class StackMember(val project: String); data object AutoRemove; data object ImageConfigUnavailable; data object ConnectionDependency }` — `ConnectionDependency` is always included.
  - `data class RecreatePlan(val containerName: String, val wasRunning: Boolean, val createBody: JsonObject, val extraNetworks: Map<String, JsonObject>, val image: ImageRef, val pull: Boolean, val registryId: Int?, val changes: ChangeSummary, val warnings: List<RecreateWarning>)`
  - `object RecreatePlanner { fun plan(inspect: JsonObject, oldImageConfig: JsonObject?, edits: RecreateEdits): RecreatePlan }`
  - `fun isProtectedImage(image: String): Boolean`

Mapping rules (decisions the tests pin):
- Body = `inspect.Config` (minus `Hostname` when it equals the first 12 chars of the container id, minus `Image` replaced by `edits.image`, `Env` replaced by `edits.env`) + `HostConfig` as-is + `NetworkingConfig`.
- Image defaults (when `oldImageConfig != null`): drop `Env` entries identical to an entry in the old image's `Env`; drop `Cmd`, `Entrypoint`, `WorkingDir`, `User`, `Healthcheck`, `StopSignal` when equal to the old image's; drop `Labels` entries with identical key and value; drop `ExposedPorts` and `Volumes` keys present in the old image. Each dropped item listed in `droppedImageDefaults`. When `oldImageConfig == null`: keep everything and add `ImageConfigUnavailable`.
- Anonymous volumes: every `Mounts[]` with `Type == "volume"` whose `Destination` is not already a target in `HostConfig.Binds` or `HostConfig.Mounts` is appended to `HostConfig.Binds` as `"<Name>:<Destination>"` (+ `":ro"` when `RW == false`).
- Networks: when `HostConfig.NetworkMode` is `host`, `none`, or starts with `container:` → no `NetworkingConfig`, empty `extraNetworks`; for `container:` also remove `Hostname`, `Domainname`, `MacAddress`, `ExposedPorts`. Otherwise the network named by `NetworkMode` (or the first one in `NetworkSettings.Networks`) goes in `NetworkingConfig.EndpointsConfig`, the rest in `extraNetworks`. Each endpoint config keeps only `IPAMConfig`, `Links`, `Aliases` (minus any alias equal to the old container's 12-char id), `DriverOpts`.
- `containerName` = `inspect.Name` without leading `/`; `wasRunning` = `State.Running`; `pull` = `edits.pullImage || edits.image != Config.Image`.
- Warnings: `StackMember` if label `com.docker.compose.project` present; `AutoRemove` if `HostConfig.AutoRemove == true`.
- Env diff by key (text before first `=`) between original `Config.Env` and `edits.env`.

- [ ] **Step 1: Write failing tests:** `preservesHostConfig` (binds, port bindings, restart policy, caps, devices, log config, extra hosts, DNS equal to fixture); `appliesImageAndEnvEdits`; `dropsInheritedImageDefaults` (`PATH`/`APP_VERSION=1` dropped, user `DB_HOST` kept, listed in summary); `keepsEverythingWithoutImageConfig` (+ warning); **`reattachesAnonymousVolumeByName`**; `splitsPrimaryAndExtraNetworks` (static IP in `IPAMConfig` kept, short-id alias removed); **`containerNetworkModeOmitsNetworkingAndHostname`**; **`autoRemoveProducesWarning`**; `stackLabelProducesWarning`; `alwaysIncludesConnectionDependency`; `stripsLeadingSlashFromName`; `envDiff` (added/changed/removed). Protection: `portainer/portainer-ce:2.21`, `docker.io/portainer/agent`, `portainer/portainer-ee@sha256:..` → true; `nginx`, `myportainer/x` → false.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(shared): recreate planner preserving full container config`.

### Task 8: Recreate executor with rollback

**Files:**
- Create: `recreate/DockerOps.kt`, `recreate/Recreator.kt`; add `renameContainer`, `createContainer`, `connectNetwork`, `inspectContainer` to `api/ContainersApi.kt`
- Test: `recreate/RecreatorTest.kt` (fake `DockerOps` recording calls, configurable failure step), `api/ContainersApiTest.kt` (new endpoints)

**Interfaces:**
- Consumes: `RecreatePlan`, `PortainerClient.pullImage`.
- Produces:
  - `suspend fun PortainerClient.inspectContainer(endpointId: Int, id: String): JsonObject`, `renameContainer(endpointId: Int, id: String, newName: String)`, `createContainer(endpointId: Int, name: String, body: JsonObject): String` (returns `Id`), `connectNetwork(endpointId: Int, network: String, containerId: String, endpointConfig: JsonObject)`.
  - `interface DockerOps { suspend fun pull(image: ImageRef, registryId: Int?); suspend fun rename(id: String, newName: String); suspend fun create(name: String, body: JsonObject): String; suspend fun connect(network: String, containerId: String, config: JsonObject); suspend fun stop(id: String); suspend fun start(id: String); suspend fun remove(id: String, force: Boolean) }`, `class PortainerDockerOps(client: PortainerClient, endpointId: Int) : DockerOps`.
  - `enum class RecreateStep { Pull, RenameOld, CreateNew, ConnectNetworks, StopOld, StartNew, RemoveOld }`
  - `sealed interface RecreateEvent { data class StepStarted(val step: RecreateStep); data class StepDone(val step: RecreateStep); data class Succeeded(val newId: String); data class Failed(val step: RecreateStep, val message: String, val rolledBack: Boolean) }`
  - `class Recreator(ops: DockerOps, nowEpochSeconds: () -> Long) { fun run(oldId: String, plan: RecreatePlan): Flow<RecreateEvent> }`

Order (refines spec step order so the old container keeps running until the new one exists; spec intent — never end with no container — is unchanged):
1. `Pull` (if `plan.pull`) — failure → `Failed(rolledBack = true)`, nothing else called.
2. `RenameOld` → `<name>-old-<epochSeconds>`.
3. `CreateNew` with `plan.containerName`. 4. `ConnectNetworks` for each `extraNetworks` entry (skipped event when empty).
   Failure in 2–4 → remove new container if created (force), rename old back; old was never stopped.
5. `StopOld` (if `wasRunning`). 6. `StartNew` (if `wasRunning`).
   Failure in 5–6 → remove new (force), rename old back, start old if `wasRunning`. If the plan has `AutoRemove` and the old container is gone after stop, `rolledBack = false` and the message says the old container was removed by Docker.
7. `RemoveOld` (force) — on failure emit `Failed(RemoveOld, message, rolledBack = false)`; the new container is already running, and the UI tells the user a leftover `<name>-old-<ts>` container exists.

- [ ] **Step 1: Write failing tests:** `happyPathCallOrder` (exact op sequence for a running container with one extra network); `stoppedContainerIsNotStarted`; `pullFailureTouchesNothing`; `createFailureRenamesOldBackWithoutStopping`; `startFailureRestoresAndRestartsOld` (op sequence includes remove new, rename back, start old); `autoRemoveStartFailureReportsNoRollback`; `removeOldFailureReported`; API tests: create posts body to `containers/create?name=web` and returns `Id`; rename posts `containers/abc/rename?name=x`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(shared): recreate executor with rollback`.

### Task 9: App shell, servers and environments UI

**Files:**
- Create: `App.kt`, `AppContainer.kt`, `Routes.kt`, `theme/Theme.kt`, `servers/*`, `environments/*`
- Test: `composeApp/src/commonTest/.../servers/ServersViewModelTest.kt`, `environments/EnvironmentsViewModelTest.kt`

**Interfaces:**
- Consumes: `ServerRepository`, `validateServer`, `normalizeBaseUrl`, `PortainerClient`, `endpoints()`.
- Produces:
  - `class AppContainer(val servers: ServerRepository, private val engineFactory: () -> HttpClientEngine = ::httpEngine) { suspend fun client(serverId: String): PortainerClient }` — caches one client per server; invalidated on save/delete. Created once in `App()`.
  - `@Serializable` routes: `ServerList`, `ServerEdit(val serverId: String?)`, `Environments(val serverId: String)`, `Containers(val serverId: String, val endpointId: Int)`, `ContainerDetail(val serverId: String, val endpointId: Int, val containerId: String)`, `Recreate(val serverId: String, val endpointId: Int, val containerId: String)`, `Registries(val serverId: String)`, `RegistryEdit(val serverId: String, val registryId: Int?)`.
  - Start route: `ServerList`; if exactly one server with `lastEndpointId`, open its `Containers` directly (back goes to `Environments`).
  - `ServersViewModel`: state `servers`; `ServerEditViewModel` save flow: normalise URL → `validateServer` → on `Ok` save and navigate; otherwise show inline error ("Invalid API key", "Can't reach server: …"). API key field masked; on edit it's empty with hint "Leave empty to keep current key".
  - `EnvironmentsViewModel`: loads endpoints; unsupported ones disabled with "Not supported yet"; selecting one calls `setLastEndpoint` and navigates. Top bar action to `Registries`.

- [ ] **Step 1: Write failing VM tests** (MockEngine-backed `AppContainer`, `MapSettings`): saving with a 401 server yields error state `InvalidKey` and nothing saved; saving valid server persists normalised URL; environments state marks Type 4 disabled.
- [ ] **Step 2:** Run `./gradlew :composeApp:testDebugUnitTest` → FAIL. **Step 3:** Implement VMs and screens (empty state on server list with an "Add server" button). **Step 4:** Run → PASS; `./gradlew :composeApp:assembleDebug :composeApp:wasmJsBrowserDistribution` succeeds.
- [ ] **Step 5: Commit** `feat(app): navigation, server management, environments`.

### Task 10: Container list screen

**Files:**
- Create: `containers/ContainerListScreen.kt`, `containers/ContainerListViewModel.kt`
- Test: `containers/ContainerListViewModelTest.kt`

**Interfaces:**
- Consumes: `containers()`, start/stop/restart/remove, `applyFilter`, `isProtectedImage`.
- Produces: `ContainerListViewModel(container: AppContainer, serverId: String, endpointId: Int)` with `state: StateFlow<ContainerListState>` (`items`, `filter`, `loading`, `error`, `busyIds: Set<String>`), `refresh()`, `setFilter(ContainerFilter)`, `start/stop/restart/remove(id)`.

UI: search field in top bar; filter chips for state and a stack dropdown; pull-to-refresh; each row shows name, image, state dot (green running / grey exited / amber unhealthy or restarting) and a stack badge; row overflow menu with Start/Stop/Restart/Remove (remove opens a confirm dialog "Remove <name>? This can't be undone."); stop/remove disabled for protected images. Tapping a row opens `ContainerDetail`.

- [ ] **Step 1: Write failing VM tests:** refresh loads and filters; stop on a protected image is refused without an API call; action failure sets `error` and clears busy id; successful action triggers refresh.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS; app builds.
- [ ] **Step 5: Commit** `feat(app): container list with search, filters, actions`.

### Task 11: Container detail — overview and logs

**Files:**
- Create: `detail/ContainerDetailScreen.kt`, `detail/OverviewTab.kt`, `detail/LogsTab.kt`, `detail/ContainerDetailViewModel.kt`, `detail/LogsViewModel.kt`
- Test: `detail/LogsViewModelTest.kt`, `detail/ContainerDetailViewModelTest.kt`

**Interfaces:**
- Consumes: `inspectContainer`, `followLogs`, `LogBuffer`, `LogOptions`.
- Produces: `ContainerDetailViewModel` exposes parsed overview (`image`, `state`, `health`, `created`, `startedAt`, ports, mounts, networks, env, labels, restart policy, `stack`, `tty`) from the inspect `JsonObject`. `LogsViewModel(container, serverId, endpointId, containerId, tty)`: `state` (`lines`, `showTimestamps`, `tail`, `query`, `following`, `error`), `setTail(Int?)` (choices 100 / 200 / 1000 / All) restarts the follow, `setQuery`, `toggleTimestamps`, `pause()/resume()`; collection stops in `onCleared` and while the app is backgrounded (lifecycle), resumes with `since` = last timestamp.

UI: tabs Overview | Logs; top bar action "Edit & recreate" (hidden for protected images); stack badge with a note "Part of stack <name>". Logs: monospace, stderr lines tinted error colour, query highlights matches and offers a "matches only" toggle, auto-scroll that pauses when the user scrolls up and shows a "Jump to latest" button.

- [ ] **Step 1: Write failing VM tests:** logs append through the buffer and cap at 5,000; changing tail cancels the previous collection and starts a new one; `query` filters when "matches only" is on; overview parses ports `80/tcp → 0.0.0.0:8080` from fixture.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS; app builds.
- [ ] **Step 5: Commit** `feat(app): container detail overview and live logs`.

### Task 11b: Design pass (owner feedback) and "Feed my Claude"

Owner asked (after seeing screenshots): use Portainer's blue, fix the 7 design points below, and bring over the "Feed my Claude" mascot from `5V715/games-with-friends` (`public/js/donate.js`, donation box markup in `public/index.html`, styles in `public/css/style.css` under `.donate`).

**Files:**
- Modify: `theme/Theme.kt`, `containers/ContainerListScreen.kt`, `servers/ServerListScreen.kt`, `environments/EnvironmentsScreen.kt`, `detail/*` (only for theme/status consistency)
- Create: `support/FeedClaude.kt` (mascot canvas + donation box), `support/FeedClaudeModel.kt` (pure simulation state), `composeApp/src/commonMain/composeResources/font/press_start_2p.ttf` + `composeResources/font/OFL.txt`
- Test: `composeApp/src/commonTest/.../support/FeedClaudeModelTest.kt`, `containers/ContainerActionsTest.kt`

**Interfaces:**
- Produces: `fun availableActions(state: ContainerState): List<ContainerAction>` (`enum class ContainerAction { Start, Stop, Restart, Remove }`) — Running/Restarting/Paused → Stop, Restart, Remove; Exited/Created/Dead → Start, Remove; Unknown → all. `class FeedClaudeModel(width: Float, random: Random)` with `fun step(dtSeconds: Float, nowMs: Long)`, exposing mascot x, coins (x, kind, eaten, pop), current speech line or null. `@Composable fun FeedClaudeBox(modifier: Modifier = Modifier)`.

Design decisions:
- Colour: Material 3 schemes seeded from Portainer blue `#13BEF9`. Light scheme primary is a darker accessible shade of it (contrast ≥ 4.5:1 on white for text/buttons, e.g. `#0A6E9E`); dark scheme primary `#13BEF9` with dark on-primary. Use it consistently (buttons, links, chips, progress).
- The 7 fixes: (1) row menu shows only actions valid for the container's state (`availableActions`); (2) health/state as a small text label next to the dot ("running", "healthy", "unhealthy", "starting", "exited", "paused", "restarting") with distinct colours; unhealthy uses the error colour; (3) protected container menu: Stop/Remove shown disabled with a "Locked" label, the explanation shown once at the bottom of the menu; (4) remove confirmation: "Remove" button in the error colour, title smaller (titleLarge → headlineSmall max), body text "This can't be undone."; (5) server rows: Edit/Delete move into a ⋮ overflow menu; (6) Registries top-bar action uses a registry/storage-style icon (e.g. `Icons.Filled.Inventory2` or `Storage` from icons-extended only if already available — otherwise a text action "Registries"), not a padlock; (7) filter chips are `FilterChip`s with a clear selected state (check icon + container colour).
- "Feed my Claude": a box at the bottom of the server list screen (the start screen), also reachable from an "About" item in the server list's top-bar overflow menu which opens a small About screen containing the same box. Port `donate.js` faithfully to Compose `Canvas`: same 12×10 sprite and palette (`#d97757`, `#a9533a`, `#1a0f2e`, `#f0a07f`), two walking frames, chomping mouth, coins that spin, 18% treats (beer, burger, pizza, donut, GPU) with their pixel maps, "+1"/treat pop text, speech bubbles every 6–13 s for 2.6 s with the English lines from `LINES` (THINKING with animated dots), wrap-around tunnel, respawn coins 1.2 s after all eaten, pause when not visible (stop the frame loop when the composable leaves composition or the app is backgrounded), reduced motion → static mascot centred. Pixel font: Press Start 2P (OFL) bundled as a Compose resource with its licence. Box copy: title "INSERT COIN", text "🍺 Feed my Claude – a tip keeps the tokens flowing. Entirely optional.", buttons PayPal (`https://www.paypal.com/donate/?hosted_button_id=M6D3G2TRTEQXG`), Ko-fi (`https://ko-fi.com/silasschwarz`), Sponsor (`https://github.com/sponsors/5V715`) opened with `LocalUriHandler`. Colours of the box follow the source (dark navy box, magenta border/glow, gold title).

- [ ] **Step 1: Write failing tests:** `availableActions` table for each state; `FeedClaudeModel`: mascot advances by speed×dt when not talking, eats a coin when its mouth passes it (coin.eaten, pop = 1), wraps to the left after passing width+10, respawns coins 1.2 s after all eaten, never talks within 6 s of start, talking stops movement.
- [ ] **Step 2:** Run `./gradlew :composeApp:testDebugUnitTest` → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS; `:composeApp:assembleDebug :composeApp:wasmJsBrowserDistribution` succeed.
- [ ] **Step 5: Commit** `feat(app): Portainer-blue design pass and Feed my Claude`.

### Task 12: Edit & recreate flow

**Files:**
- Create: `recreate/RecreateScreen.kt`, `recreate/RecreateViewModel.kt`
- Test: `recreate/RecreateViewModelTest.kt`

**Interfaces:**
- Consumes: `inspectContainer`, `inspectImage`, `registries`, `matchRegistry`, `RecreatePlanner.plan`, `Recreator`, `PortainerDockerOps`, `isProtectedImage`.
- Produces: `RecreateViewModel` with phases `Editing → Reviewing(plan) → Running(steps) → Done(success | failure)`; `setImage`, `setEnv(List<String>)` (edit / add / remove rows), `setPull(Boolean)`, `setRegistry(Int?)`, `review()`, `confirm()`.

UI: Editing — image field, "Pull latest image" switch, registry dropdown preselected by `matchRegistry` ("None (public)" option), env list with key/value rows, add and delete. Reviewing — change summary (image change, env added/changed/removed, dropped image defaults) and warnings: stack ("This container belongs to stack <name>. Any stack redeploy — manual, git auto-update or webhook — will undo this change."), auto-remove ("This container is removed by Docker when stopped. If starting the new one fails, the old one can't be restored."), connection ("If this container is needed to reach Portainer (VPN, reverse proxy), the app may lose connection and can't roll back."), image config unavailable. Buttons "Cancel" / "Recreate". Running — step list with progress per `RecreateStep`. Done — success navigates back to detail with the new id; failure shows step, message, and whether the original was restored.

- [ ] **Step 1: Write failing VM tests:** protected image → screen enters an error state and `review()` is unavailable; review builds a plan whose `changes.envAdded` reflects an added row; confirm runs the recreator and maps events to step states; failure event shows `rolledBack` text.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS; app builds.
- [ ] **Step 5: Commit** `feat(app): edit and recreate containers`.

### Task 13: Registries screens

**Files:**
- Create: `registries/RegistriesScreen.kt`, `registries/RegistryEditScreen.kt`, `registries/RegistriesViewModel.kt`
- Test: `registries/RegistriesViewModelTest.kt`

**Interfaces:**
- Consumes: `registries`, `createRegistry`, `updateRegistry`, `deleteRegistry`, `RegistryType`.
- Produces: list + edit VMs. Edit form fields by type: all types — name, authentication switch, username, password (masked; empty on edit = unchanged). URL editable for `Custom`, `GitLab`, `Quay`, `Azure`, `ProGet` (+ base URL), fixed for `DockerHub` (`docker.io`) and `GitHub` (`ghcr.io`); `Ecr` adds region. Delete behind confirmation.

- [ ] **Step 1: Write failing VM tests:** list loads; saving a new GitHub registry posts URL `ghcr.io`; edit with empty password sends no `Password`; delete calls API after confirm.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS; app builds.
- [ ] **Step 5: Commit** `feat(app): manage Portainer registries`.

### Task 14: README and full CI verification

**Files:**
- Modify: `README.md`, `.github/workflows/build.yml` (only if fixes are needed)

- [ ] **Step 1:** README sections: what it does; creating a Portainer API key (Portainer → My account → Access tokens); adding a server (URL formats accepted); build commands per target; downloading CI artifacts; HTTPS-only note; **web build on GitHub Pages** (enabling Pages; CORS: Portainer or a reverse proxy must allow the Pages origin, headers `X-API-Key`, `Content-Type`, methods GET/POST/PUT/DELETE, with a Caddy/nginx example; Chrome local-network prompt; key stored in `localStorage`); Edge environments not supported; the Portainer version tested against; iOS signing out of scope. Final section "Feed my Claude": one line "🍺 Feed my Claude – a tip keeps the tokens flowing. Entirely optional." followed by the three donation links as a list: PayPal (https://www.paypal.com/donate/?hosted_button_id=M6D3G2TRTEQXG), Ko-fi (https://ko-fi.com/silasschwarz), GitHub Sponsors (https://github.com/sponsors/5V715) — same three options as the in-app box (owner request).
- [ ] **Step 2:** Push to the branch, trigger `workflow_dispatch` so the `ios` job runs too; both jobs green.
- [ ] **Step 3: Commit** `docs: README for v1`.
