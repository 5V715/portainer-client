# Portainer Client v1.1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add images/volumes/networks views, previewed cleanup, quick Recreate (phase 1) and a full Edit / Duplicate / New container form (phase 2).

**Architecture:** Same two modules as v1. `shared` gains one API file per resource, a container-usage index, a cleanup planner/executor and (phase 2) a form model that edits an inspect-shaped JSON plus planner options for Replace/Copy and a builder for New. `composeApp` gains a bottom-tab shell per environment, resource list/detail screens, cleanup sheets, a recreate confirm sheet and the editor.

**Tech Stack:** unchanged from v1 (Kotlin Multiplatform, Compose Multiplatform Material 3, Ktor + MockEngine, kotlinx.serialization, multiplatform-settings).

**Spec:** `docs/superpowers/specs/2026-10-25-portainer-client-v1.1-design.md` (builds on `docs/superpowers/specs/2026-10-17-portainer-client-design.md`).

## Global Constraints

- All v1 Global Constraints in `docs/superpowers/plans/2026-10-17-portainer-client-v1.md` still apply (HTTPS + `X-API-Key`, Docker proxy path, protected-image rules and `PROTECTED_IMAGE_MESSAGE`, confirmation for destructive actions, Material 3 Portainer-blue theme from `theme/`, commit trailers).
- New shared API code: one file per resource in `shared/src/commonMain/kotlin/dev/silas/portainer/core/api/`, models in `core/model/`, logic in a package named after the feature (`core/resources/`, `core/cleanup/`, `core/editor/`).
- "Unused" = no container, running or stopped, references the item.
- Cleanup deletes exactly the previewed items one by one, never `force`; never calls Docker prune endpoints.
- Protected (never removed or cleaned up): containers with a protected image (even stopped), images whose any tag matches the protected pattern, volumes/networks used by any protected container, any volume named `portainer_data`, predefined networks `bridge`, `host`, `none`. Cleanup previews list protected items as "Kept (Portainer)" (predefined networks are simply not listed).
- Named-volume cleanup option: error colour, warning text "Named volumes often hold databases and app data. Everything in them is deleted permanently.", second explicit confirmation.
- Quick Recreate "Pull latest image" defaults to on.
- Copy defaults: name `<name>-copy`; anonymous volumes not reattached; generated hostname dropped; per-endpoint MACs dropped unless typed.
- New container: "Start after creating" defaults to on; image pull switch defaults to on.
- Each phase ends with green CI (push + workflow_dispatch incl. iOS) and a debug APK for the owner.

## Review Focus

1. A volume (or image/network) used only by a **stopped** container must not be "Unused" and must never appear in a cleanup — test in Task 1 and Task 5.
2. State changes between preview and run (an item became in use, or is already gone) — the delete fails or 404s, is reported per item, nothing else is deleted, no force — test in Task 5.
3. An **unedited** editor form doing Replace must produce exactly v1's create body — test in Task 13.
4. A copy of a container with an anonymous volume must not reference the original's anonymous volume, and a copy must never touch the original container — tests in Tasks 13 and 16.
5. Portainer stopped on an agent host: its container, image, `portainer_data` volume and its networks are kept out of every cleanup — test in Task 5.

---

## Phase 1

### Task 1: Container usage index and engine facts

**Files:**
- Modify: `shared/.../core/model/ContainerSummary.kt`, `shared/.../core/api/ContainersApi.kt` (parse new fields)
- Create: `shared/.../core/api/EngineApi.kt`, `shared/.../core/model/EngineInfo.kt`, `shared/.../core/resources/ResourceUsage.kt`
- Test: `shared/src/commonTest/.../resources/ResourceUsageTest.kt`, `.../api/EngineApiTest.kt`, extend `ContainersApiTest`

**Interfaces:**
- Produces:
  - `ContainerSummary` gains `val imageId: String`, `val volumeNames: List<String>` (from `Mounts[]` with `Type == "volume"`, `Name`), `val networkNames: List<String>` (keys of `NetworkSettings.Networks`) — default empty lists so existing callers compile.
  - `data class EngineInfo(val apiVersion: String, val swarmActive: Boolean)`; `suspend fun PortainerClient.engineInfo(endpointId: Int): EngineInfo` (`GET version` → `ApiVersion`; `GET info` → `Swarm.LocalNodeState == "active"`).
  - `class ResourceUsage(containers: List<ContainerSummary>)` with `fun containersUsingImage(imageId: String): List<ContainerSummary>`, `fun containersUsingVolume(name: String): List<ContainerSummary>`, `fun containersUsingNetwork(name: String): List<ContainerSummary>`, and `isImageUsed / isVolumeUsed / isNetworkUsed` booleans.

- [ ] **Step 1: Failing tests:** usage index with a running container using image A + volume `db` + network `backend` and a **stopped** container using volume `cache` → `isVolumeUsed("cache") == true`, `isVolumeUsed("other") == false`, `containersUsingNetwork("backend")` = the running one; summary parsing reads `ImageID`, volume mount names (bind mounts ignored) and network names from the existing fixture extended with `Mounts`/`NetworkSettings`; engineInfo parses `ApiVersion "1.47"` and `LocalNodeState "inactive"` → `swarmActive == false`.
- [ ] **Step 2:** `./gradlew :shared:jvmTest` → FAIL. **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(shared): container usage index and engine info`.

### Task 2: Images API

**Files:**
- Create: `shared/.../core/model/ImageSummary.kt`; Modify: `shared/.../core/api/ImagesApi.kt`
- Test: `shared/src/commonTest/.../api/ImagesListApiTest.kt`

**Interfaces:**
- Produces: `data class ImageSummary(val id: String, val repoTags: List<String>, val size: Long, val created: Long, val labels: Map<String, String>) { val dangling: Boolean /* repoTags empty or only "<none>:<none>" */ }`; `suspend fun PortainerClient.images(endpointId: Int): List<ImageSummary>` (`GET images/json`); `suspend fun PortainerClient.removeImage(endpointId: Int, id: String, force: Boolean = false)` (`DELETE images/{id}?force=`); existing `inspectImage`, `pullImage` unchanged; `fun ImageSummary.isProtected(): Boolean` = any tag passes `isProtectedImage`.

- [ ] **Step 1: Failing tests:** list parses two images (one tagged `nginx:1.27`, one `<none>:<none>` → `dangling`); `removeImage(force = true)` sends `DELETE …/images/sha256:abc?force=true`; a 409 maps to `PortainerException.Api` with Docker's message; `ImageSummary(repoTags = listOf("portainer/portainer-ce:2.21.4")).isProtected() == true`.
- [ ] **Step 2–4:** RED → implement → GREEN. **Step 5: Commit** `feat(shared): images list and remove`.

### Task 3: Volumes API

**Files:**
- Create: `shared/.../core/model/Volume.kt`, `shared/.../core/api/VolumesApi.kt`
- Test: `shared/src/commonTest/.../api/VolumesApiTest.kt`

**Interfaces:**
- Produces: `data class Volume(val name: String, val driver: String, val mountpoint: String, val scope: String, val createdAt: String?, val labels: Map<String, String>, val options: Map<String, String>) { val anonymous: Boolean }` — anonymous when label `com.docker.volume.anonymous` is present **or** the name is 64 lowercase hex chars; `suspend fun PortainerClient.volumes(endpointId: Int): List<Volume>` (`GET volumes` → `Volumes` array, may be null); `inspectVolume(endpointId, name): Volume`; `createVolume(endpointId, name: String?, driver: String = "local", driverOpts: Map<String,String> = emptyMap(), labels: Map<String,String> = emptyMap()): Volume` (`POST volumes/create`, omit `Name` when null/blank); `removeVolume(endpointId, name)` (`DELETE volumes/{name}`, never `force`).

- [ ] **Step 1: Failing tests:** list with `Volumes: null` → empty; anonymous detection (hex name; label); create with blank name omits `Name` and sends `Driver: "local"`; remove path; 409 in-use → `Api` with message.
- [ ] **Step 2–4:** RED → implement → GREEN. **Step 5: Commit** `feat(shared): volumes API`.

### Task 4: Networks API

**Files:**
- Create: `shared/.../core/model/Network.kt`, `shared/.../core/api/NetworksApi.kt`
- Test: `shared/src/commonTest/.../api/NetworksApiTest.kt`

**Interfaces:**
- Produces: `data class Network(val id: String, val name: String, val driver: String, val scope: String, val internal: Boolean, val attachable: Boolean, val subnets: List<IpamSubnet>, val labels: Map<String, String>, val containers: Map<String, NetworkMember>) { val predefined: Boolean /* name in bridge, host, none */ }`; `data class IpamSubnet(val subnet: String, val gateway: String?, val ipRange: String?)`; `data class NetworkMember(val name: String, val ipv4: String?, val ipv6: String?)`; `networks(endpointId): List<Network>` (`GET networks`), `inspectNetwork(endpointId, id): Network` (`GET networks/{id}` — includes `Containers`), `createNetwork(endpointId, input: NetworkInput): String` (returns `Id`), `removeNetwork(endpointId, id)`; `data class NetworkInput(val name: String, val driver: String = "bridge", val subnet: String? = null, val gateway: String? = null, val ipRange: String? = null, val internal: Boolean = false, val attachable: Boolean = false, val options: Map<String,String> = emptyMap(), val labels: Map<String,String> = emptyMap())` — IPAM block only when `subnet` set.

- [ ] **Step 1: Failing tests:** list parse incl. IPAM; `predefined` for bridge/host/none; create body with/without IPAM; remove path.
- [ ] **Step 2–4:** RED → implement → GREEN. **Step 5: Commit** `feat(shared): networks API`.

### Task 5: Cleanup planner and executor

**Files:**
- Create: `shared/.../core/cleanup/CleanupPlan.kt`, `shared/.../core/cleanup/CleanupPlanner.kt`, `shared/.../core/cleanup/CleanupExecutor.kt`, `shared/.../core/resources/ResourceProtection.kt`
- Test: `shared/src/commonTest/.../cleanup/CleanupPlannerTest.kt`, `.../cleanup/CleanupExecutorTest.kt`

**Interfaces:**
- Consumes: Tasks 1–4.
- Produces:
  - `class ResourceProtection(containers: List<ContainerSummary>)` with `isContainerProtected(c)`, `isImageProtected(image: ImageSummary)`, `isVolumeProtected(name)`, `isNetworkProtected(network: Network)` per Global Constraints.
  - `enum class CleanupKind { Containers, Images, Volumes, Networks }`; `data class CleanupItem(val id: String, val label: String, val sizeBytes: Long?)`; `data class CleanupPlan(val kind: CleanupKind, val toDelete: List<CleanupItem>, val namedVolumes: List<CleanupItem>, val kept: List<CleanupItem>)` — `namedVolumes` only for Volumes (deleted only when the option is on).
  - `object CleanupPlanner { fun containers(cs, protection): CleanupPlan` (states Exited/Created/Dead) `; fun images(images, usage, protection, includeTagged: Boolean): CleanupPlan; fun volumes(volumes, usage, protection): CleanupPlan` (anonymous unused → `toDelete`, named unused → `namedVolumes`) `; fun networks(networks, usage, protection): CleanupPlan` (unused, not predefined) `}`.
  - `class CleanupExecutor(private val delete: suspend (CleanupItem) -> Unit) { fun run(items: List<CleanupItem>): Flow<CleanupProgress> }`; `sealed interface CleanupProgress { data class Deleted(val item: CleanupItem); data class Failed(val item: CleanupItem, val message: String); data class Finished(val deleted: Int, val failed: Int, val reclaimedBytes: Long) }` — every item attempted once, failures don't stop the rest, `NotFound` counts as Failed("Already removed").
  - Helper: `fun CleanupKind.deleter(client: PortainerClient, endpointId: Int): suspend (CleanupItem) -> Unit` (removeContainer/removeImage/removeVolume/removeNetwork, never force).

- [ ] **Step 1: Failing tests:** containers plan excludes running and a stopped protected Portainer container (listed in `kept`); images plan default selects only unused dangling, `includeTagged` adds unused tagged, never an image used by a stopped container, never a protected image; volumes plan: unused anonymous → toDelete, unused named → namedVolumes, `portainer_data` and a volume used only by a stopped Portainer container → kept; networks plan excludes predefined and networks used by stopped containers; executor: second item throws `Api(409)` → Failed, third still Deleted, Finished(2, 1, sum of the deleted items' sizes); 404 → Failed "Already removed"; deleter never sends `force=true` (MockEngine assertion).
- [ ] **Step 2–4:** RED → implement → GREEN. **Step 5: Commit** `feat(shared): previewed cleanup planner and executor`.

### Task 6: Environment tab shell and Images screens

**Files:**
- Create: `composeApp/.../app/environment/EnvironmentShell.kt` (bottom NavigationBar: Containers, Images, Volumes, Networks), `app/images/ImagesScreen.kt`, `app/images/ImagesViewModel.kt`, `app/images/ImageDetailScreen.kt`, `app/images/PullImageSheet.kt`
- Modify: `app/Routes.kt` (add `Images(serverId, endpointId)`, `ImageDetail(serverId, endpointId, imageId)`, `Volumes(...)`, `VolumeDetail(..., name)`, `Networks(...)`, `NetworkDetail(..., networkId)`), `app/App.kt`
- Test: `composeApp/src/commonTest/.../images/ImagesViewModelTest.kt`

**Interfaces:**
- Consumes: `images`, `removeImage`, `inspectImage`, `pullImage`, `registries`, `matchRegistry`, `ImageRef.parse`, `ResourceUsage`, `containers`.
- Produces: `@Composable fun EnvironmentShell(selected: EnvTab, onSelect: (EnvTab) -> Unit, content: @Composable () -> Unit)`, `enum class EnvTab { Containers, Images, Volumes, Networks }` — tab switches navigate with `popUpTo(Containers)` + `launchSingleTop` so Back from any tab returns to Environments. Volumes/Networks routes render a temporary "Coming in this release" placeholder until Tasks 7–8.
- `ImagesViewModel`: list with search + Unused badge + loadJob cancellation (container-list pattern); `remove(id, force)` (refused for protected with message "This image runs Portainer and can't be removed from this app."); `pull(reference, registryId)` with progress text and refresh after.

- [ ] **Step 1: Failing VM tests:** Unused badge computed against a stopped container's image; protected image removal refused without API call; remove 409 shows Docker's message and offers force; pull success refreshes the list.
- [ ] **Step 2–4:** RED → implement (detail: tags, ID, size, created, arch/OS, config, containers using it linking to `ContainerDetail`) → GREEN; `:composeApp:assembleDebug :composeApp:wasmJsBrowserDistribution` build.
- [ ] **Step 5: Commit** `feat(app): environment tabs and images`.

### Task 7: Volumes screens

**Files:** Create `app/volumes/VolumesScreen.kt`, `VolumesViewModel.kt`, `VolumeDetailScreen.kt`, `CreateVolumeSheet.kt`; Modify `App.kt` (replace placeholder). Test `.../volumes/VolumesViewModelTest.kt`.

**Interfaces:** Consumes Task 3 API, `ResourceUsage`, `ResourceProtection`. Remove offered only when unused and not protected; confirm text "Delete volume <name>? Everything stored in it is deleted permanently."; anonymous volumes show a short ID (first 12 chars) + "anonymous" chip; detail lists containers with mount path + read-only.

- [ ] **Step 1: Failing VM tests:** remove hidden/refused for a volume used by a stopped container and for `portainer_data`; create with blank name sends no Name; list search.
- [ ] **Step 2–4:** RED → implement → GREEN; app builds. **Step 5: Commit** `feat(app): volumes`.

### Task 8: Networks screens

**Files:** Create `app/networks/NetworksScreen.kt`, `NetworksViewModel.kt`, `NetworkDetailScreen.kt`, `CreateNetworkSheet.kt`; Modify `App.kt`. Test `.../networks/NetworksViewModelTest.kt`.

**Interfaces:** Consumes Task 4 API, `engineInfo` (overlay driver offered only when `swarmActive`), `ResourceUsage`, `ResourceProtection`. Remove only when no containers connected, not predefined, not protected.

- [ ] **Step 1: Failing VM tests:** predefined never removable; overlay hidden when swarm inactive; create validation (name required; subnet CIDR shape `a.b.c.d/n` or IPv6 when given).
- [ ] **Step 2–4:** RED → implement → GREEN; app builds. **Step 5: Commit** `feat(app): networks`.

### Task 9: Cleanup UI

**Files:** Create `app/cleanup/CleanupSheet.kt`, `app/cleanup/CleanupViewModel.kt`; Modify the four list screens (top-bar overflow "Clean up"). Test `.../cleanup/CleanupViewModelTest.kt`.

**Interfaces:** Consumes `CleanupPlanner`, `CleanupExecutor`, `deleter`, lists. Phases: `Preview(plan, includeTagged / includeNamed)` → `ConfirmNamed` (second confirmation, only when named volumes selected) → `Running(progress)` → `Done(deleted, failed, reclaimedBytes, failures)`. Preview shows "Will delete" (with sizes where known), named volumes as a separate marked section with the exact warning text, and "Kept (Portainer)". Empty plan → "Nothing to clean up." Run in `AppContainer.appScope` (like recreate) so leaving the screen doesn't stop it; the list refreshes on completion.

- [ ] **Step 1: Failing VM tests:** named volumes deleted only after the option and the second confirmation; toggling `includeTagged` rebuilds the preview; a failed item appears in Done.failures; the delete list sent equals the preview list exactly.
- [ ] **Step 2–4:** RED → implement → GREEN; app builds. **Step 5: Commit** `feat(app): previewed cleanup`.

### Task 10: Quick Recreate

**Files:** Create `app/detail/QuickRecreateSheet.kt`, `app/detail/QuickRecreateViewModel.kt`; Modify `ContainerDetailScreen.kt` (new "Recreate" action next to "Edit & recreate"), navigation to the existing recreate progress screen. Test `.../detail/QuickRecreateViewModelTest.kt`.

**Interfaces:** Consumes `inspectContainer`, `inspectImage`, `registries`, `matchRegistry`, `RecreatePlanner.plan` with `RecreateEdits(image = Config.Image, env = Config.Env, pullImage = switch, registryId)`, `RecreateSessions.start` with `AppContainer.dedicatedClient`. Sheet: "Pull latest image" switch (default on), registry picker (only when pull on), warnings from the plan, "Recreate" button; then show the existing `RecreateScreen` progress (reuse its Running/Done panes or navigate to the `Recreate` route which re-attaches to the session).

- [ ] **Step 1: Failing VM tests:** plan edits equal current image/env (empty env diff); pull switch default true and passed through; protected container → no sheet, message; confirm starts a session via a dedicated client.
- [ ] **Step 2–4:** RED → implement → GREEN; app builds. **Step 5: Commit** `feat(app): quick recreate with optional pull`.

### Task 11: Phase 1 release

**Files:** Modify `README.md` (features: resource tabs, cleanup with preview and named-volume option, quick recreate).

- [ ] **Step 1:** README update. **Step 2:** full `./gradlew :shared:jvmTest :shared:wasmJsTest :composeApp:testDebugUnitTest :composeApp:assembleDebug :composeApp:wasmJsBrowserDistribution` green. **Step 3:** push, trigger `build.yml` workflow_dispatch on the branch, linux + ios green. **Step 4: Commit** `docs: README for v1.1 phase 1`.

## Phase 2

### Task 12: Container form model (parse + merge)

**Files:** Create `shared/.../core/editor/ContainerForm.kt` (model), `shared/.../core/editor/FormParser.kt`, `shared/.../core/editor/FormMerger.kt`. Test `shared/src/commonTest/.../editor/FormRoundTripTest.kt`, `FormMergeTest.kt`.

**Interfaces:**
- Produces:
  - `data class ContainerForm(val image: String, val name: String, val command: List<String>?, val entrypoint: List<String>?, val workingDir: String, val user: String, val ports: List<PortRow>, val publishAll: Boolean, val mounts: List<MountRow>, val primaryNetwork: NetworkRow?, val extraNetworks: List<NetworkRow>, val networkModeReadOnly: String?, val env: List<String>, val labels: List<Pair<String,String>>, val restart: RestartPolicyForm, val memoryBytes: Long?, val memoryReservationBytes: Long?, val nanoCpus: Long?, val cpuShares: Long?)`
  - `data class PortRow(val hostIp: String, val hostPort: String, val containerPort: Int, val protocol: String /* tcp|udp */)`; `enum class MountType { Bind, Volume, Tmpfs }`; `enum class MountOrigin { Binds, Mounts, New }`; `data class MountRow(val type: MountType, val source: String, val target: String, val readOnly: Boolean, val origin: MountOrigin, val originalIndex: Int?)`; `data class NetworkRow(val name: String, val aliases: List<String>, val ipv4: String, val ipv6: String, val mac: String)`; `data class RestartPolicyForm(val name: String /* no|always|on-failure|unless-stopped */, val maxRetries: Int)`.
  - `enum class FormField { Image, Name, Command, Entrypoint, WorkingDir, User, Ports, Mounts, Networks, Env, Labels, Restart, Resources }`
  - `object FormParser { fun fromInspect(inspect: JsonObject): ContainerForm; fun empty(): ContainerForm }`
  - `object FormMerger { fun merge(original: JsonObject, before: ContainerForm, after: ContainerForm): Pair<JsonObject, Set<FormField>> }` — returns the inspect-shaped JSON with only changed sections rewritten and the set of edited fields; unchanged sections' JSON is identical (structural equality); mounts written back in their original representation; deleting all port rows removes the `PortBindings` entries and their `ExposedPorts`.
  - `fun splitCommandLine(s: String): List<String>` / `fun joinCommandLine(args: List<String>): String` (POSIX-ish quoting: spaces, single/double quotes, backslash escapes; round-trips).

- [ ] **Step 1: Failing tests:** `merge(original, f, f)` returns JSON equal to original and an empty field set (use v1's recreate fixtures incl. compose anonymous volume and Binds+Mounts mix); per-section round trips (ports add/remove/change, mount read-only toggle keeps origin `Binds` vs `Mounts`, network alias add, restart change, memory set/clear); deleting the last port removes both `PortBindings` and `ExposedPorts` entries; `splitCommandLine("sh -c 'echo \"hi there\"'") == listOf("sh", "-c", "echo \"hi there\"")` and join/split round-trip.
- [ ] **Step 2–4:** RED → implement → GREEN. **Step 5: Commit** `feat(shared): container form model with lossless merge`.

### Task 13: Planner options, Copy mode and New builder

**Files:** Modify `shared/.../core/recreate/RecreatePlanner.kt` (options), `RecreatePlan.kt` (ChangeSummary new sections); Create `shared/.../core/editor/NewContainerBuilder.kt`. Test `shared/src/commonTest/.../recreate/PlannerOptionsTest.kt`, `.../editor/NewContainerBuilderTest.kt`.

**Interfaces:**
- Produces:
  - `enum class PlanMode { Replace, Copy }`; `data class PlanOptions(val mode: PlanMode = PlanMode.Replace, val userEdited: Set<FormField> = emptySet(), val typedMacs: Set<String> = emptySet())`; `RecreatePlanner.plan(inspect, oldImageConfig, edits, options: PlanOptions = PlanOptions())` — default keeps v1 behaviour exactly.
  - Edited fields skip image-default stripping for their section; mounts the user deleted are not reattached; Copy: no anonymous-volume reattachment, drop hostname equal to old short ID, drop per-endpoint `MacAddress` not in `typedMacs`, drop `Config.MacAddress`.
  - `ChangeSummary` gains `val otherChanges: List<String> = emptyList()` (human-readable lines per edited non-image/env section, e.g. "Ports: added 8080→80/tcp", "Restart policy: always → unless-stopped") built by `fun describeChanges(before: ContainerForm, after: ContainerForm): List<String>` in `editor/`.
  - `object NewContainerBuilder { fun build(form: ContainerForm): Pair<String /* name */, JsonObject /* create body */> }` with the minimal skeleton from the spec.

- [ ] **Step 1: Failing tests:** **unedited form → `plan(merged, img, editsFromForm, PlanOptions())` createBody equals v1 `plan(inspect, img, RecreateEdits(currentImage, currentEnv, pull, reg))` createBody**; deliberately blanked Command with Cmd equal to image default stays blank (not stripped/reinherited — `Cmd: []` sent); deleted anonymous-volume row not reattached; Copy mode: compose fixture's anonymous volume absent from Binds and Mounts Source, hostname dropped, MACs dropped unless typed; NewContainerBuilder: restart `no`, primary network in `NetworkingConfig`, ports → `ExposedPorts` + `PortBindings`.
- [ ] **Step 2–4:** RED → implement → GREEN; all existing recreate tests still pass. **Step 5: Commit** `feat(shared): planner options for edit, copy and new`.

### Task 14: Conflict checker

**Files:** Create `shared/.../core/editor/ConflictChecker.kt`. Test `.../editor/ConflictCheckerTest.kt`.

**Interfaces:** `sealed interface Conflict { data class NameTaken(val name: String); data class HostPortInUse(val port: String, val protocol: String, val byContainer: String, val running: Boolean); data class FixedIpInUse(val network: String, val ip: String, val byContainer: String); data class SharedNamedVolume(val volume: String) }`; `fun Conflict.blocking: Boolean` (NameTaken, FixedIpInUse, HostPortInUse with running = true block; others warn); `object ConflictChecker { fun check(body: JsonObject, name: String, others: List<ContainerSummary>, networks: List<Network>, excludeContainerId: String?): List<Conflict> }` — uses `ContainerSummary.Ports` (add `ports: List<PublishedPort>` to ContainerSummary in this task if missing: `data class PublishedPort(val ip: String?, val publicPort: Int?, val privatePort: Int, val type: String)`) and `Network.containers` IPs; Replace passes the replaced container's id as `excludeContainerId`.

- [ ] **Step 1: Failing tests:** name taken (blocking); host port held by a running container on 0.0.0.0 vs specific IP conflict rules (0.0.0.0 conflicts with any IP for same port/proto); stopped holder → warning only; fixed IP in use blocks; named volume shared → warning; Replace excludes itself.
- [ ] **Step 2–4:** RED → implement → GREEN. **Step 5: Commit** `feat(shared): conflict checks for copy and new`.

### Task 15: Editor screen

**Files:** Create `app/editor/EditorScreen.kt` (sections per spec incl. read-only Advanced summary), `app/editor/EditorViewModel.kt`, `app/editor/sections/*.kt` (one composable per section); Modify `Routes.kt` (`Editor(serverId, endpointId, containerId: String?)` — null = New). Test `.../editor/EditorViewModelTest.kt`.

**Interfaces:** Consumes `FormParser`, `FormMerger`, `describeChanges`, `volumes`, `networks`, `registries`, `matchRegistry`. VM state: `form`, `before`, `mode` (Edit/New), validation errors per section (image required; container port 1–65535; host port blank or 1–65535; mount target absolute path; bind source absolute path; IPv4/IPv6 syntax when given; memory parse "512m"/"1g"/bytes; CPU cores decimal > 0). Protected image container → Edit opens read-only with `PROTECTED_IMAGE_MESSAGE` and only "Deploy as copy" enabled.

- [ ] **Step 1: Failing VM tests:** load from inspect populates all sections; editing a port marks `FormField.Ports` dirty only; validation messages; New starts from `FormParser.empty()` with pull on and start-after-create on.
- [ ] **Step 2–4:** RED → implement → GREEN; app builds. **Step 5: Commit** `feat(app): container editor form`.

### Task 16: Editor finish flows

**Files:** Create `app/editor/EditorReviewPane.kt`, `app/editor/DeploySessions.kt` (app-scoped like RecreateSessions for Copy/New: steps Pull → Create → ConnectNetworks → Start); Modify `ContainerDetailScreen.kt` (replace "Edit & recreate" with "Edit / Duplicate"; remove the old recreate edit form's entry while keeping `RecreateScreen` progress panes for Replace and Quick Recreate), `ContainerListScreen.kt` ("+" FAB → `Editor(containerId = null)`). Test `.../editor/EditorFinishTest.kt`.

**Interfaces:** Replace: re-inspect, `FormMerger.merge` → `RecreatePlanner.plan(..., PlanOptions(Replace, edited))` → existing review (ChangeSummary incl. `otherChanges`) → `RecreateSessions.start`. Copy: name required (default `<name>-copy`), `plan(..., PlanOptions(Copy, edited, typedMacs))` → `ConflictChecker` → review listing conflicts (blocking ones disable Deploy) → `DeploySessions`. New: `NewContainerBuilder.build` → `ConflictChecker` → review → `DeploySessions` (start only if switch on). Success → navigate to the new container's detail; failure of Create removes nothing else; failure after Create leaves the created container and says so.

- [ ] **Step 1: Failing tests:** Copy never calls any API on the original container (no rename/stop/remove on its id — MockEngine assertion); blocking conflict prevents deploy; Replace uses `RecreateSessions` and the plan includes the user's port change; New with start off doesn't call start.
- [ ] **Step 2–4:** RED → implement → GREEN; app builds. **Step 5: Commit** `feat(app): replace, deploy as copy and create from the editor`.

### Task 17: Phase 2 release

- [ ] **Step 1:** README: editor, duplicate, new container. **Step 2:** full verification command green. **Step 3:** push + workflow_dispatch, linux + ios green. **Step 4: Commit** `docs: README for v1.1`.
