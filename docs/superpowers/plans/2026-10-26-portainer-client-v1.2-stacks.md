# Portainer Client v1.2 — Stacks — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Portainer CE's stack management (web-editor and git/GitOps stacks, create, migrate) on Docker standalone environments, usable on a phone.

**Architecture:** `shared` gains `core/stacks/` (stack API client, compose analysis, line diff, protection, feature gates — UI-free, jvm-tested). `composeApp` gains a reusable code editor (`app/support/codeeditor/`), a Stacks tab (`app/stacks/`) with list, detail, editor, git settings and create flows, and an app-scoped `StackSessions` that coordinates with the existing Recreate/Cleanup/Deploy sessions.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform (Android, iOS, wasmJs), Ktor + MockEngine, kotlinx.serialization, one multiplatform YAML parser (chosen in Task 2; must support Android, iOS and wasmJs).

**Spec:** `docs/superpowers/specs/2026-10-26-portainer-client-v1.2-stacks-design.md` (approved). Builds on the v1 and v1.1 specs.

## Global Constraints

- Docker standalone environments only; Swarm/Kubernetes/Edge stacks are out of scope.
- Target the newest Portainer CE release at build time; gate version-dependent features on `PortainerClient.status().version` and hide them on servers that lack them; never send bodies an older server would silently ignore.
- A feature CE lacks is dropped, not faked; Business-Edition-only features are out of scope.
- HTTPS + `X-API-Key`; the API key never appears in logs, exceptions or UI (use existing `redact` / `safeMessage` / `failureMessage`).
- Git passwords/tokens are sent to Portainer only, never stored by the app, never shown back after saving.
- Protected rule = existing `isProtectedImage` / `PROTECTED_IMAGE_MESSAGE`; applies when the stack's containers (running or stopped) OR the images in its current compose file OR the new file being deployed match. Blocks redeploy, update, stop, delete, detach and migrate.
- Every redeploy, update, stop, delete and migrate shows the existing connection-dependency reminder.
- Delete and migrate require typing the stack name. Volume removal on delete (if CE offers it) is off by default, error-coloured, listed separately.
- Detach from git has its own confirmation explaining it is one-way.
- Web-editor update/redeploy: review shows a unified line diff of the compose file (unchanged regions collapsible) and env changes, then confirm. Git redeploy review shows ref, compose path(s), Re-pull image and Force redeployment values.
- YAML parse error blocks deploy; structural check (`services:` mapping; each service has `image` or `build`) is a warning; `build:` in a web-editor stack adds a warning that Portainer may have no build context.
- Editor: monospace, line numbers, YAML colouring (keys, scalars, strings, comments, anchors/aliases, `${VAR}`), horizontal scroll inside the editor only, Tab inserts 2 spaces, outdent, undo, redo, find next/previous, validation debounced ~300 ms, error line marked in the gutter.
- Env vars edited as name/value rows with a switch to edit as a `.env` block; duplicate names are an error.
- Leaving an edit screen with unsaved changes asks first; a failed update/redeploy keeps the user's edits.
- External (limited-control) stacks: offer exactly what Portainer CE's web UI offers them (no edit/redeploy), with the same safety rules.
- Stack operations are app-scoped sessions (survive leaving the screen, re-attach, dedicated client closed in `finally`); while one runs on an endpoint, cleanup/recreate/editor deploys touching that stack's containers are refused, and vice versa.
- View models never rethrow non-cancellation exceptions; `CancellationException` propagates.
- Mobile layout: no horizontal page scroll, thumb-reachable primary action, Material 3 Portainer-blue theme.
- Commit trailers: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01CjkrwyxmLmBP4ecHYfXzLq`. Branch `ccr-d86c4261-k8lw43`.
- Verification command (every task): `./gradlew :shared:jvmTest :shared:compileKotlinWasmJs :composeApp:testDebugUnitTest :composeApp:assembleDebug :composeApp:wasmJsBrowserDistribution --offline` (drop `--offline` only to fetch a new dependency). Tests record requests thread-safely (existing `MutableStateFlow` pattern).

## Review Focus

1. A stack with no containers (inactive/never started) → status inactive, protection decided from its compose file, actions still work. Test in Task 3 and Task 4.
2. A compose file using `extends`/`include:`/YAML anchors or a huge file (3,000 lines) → no false parse error, structural warning only, editor stays usable (validation off the main thread, debounced). Test in Task 2 (analysis) and Task 5 (editor perf guard: tokenizer on 3,000 lines under a time budget in a jvm test).
3. Network loss mid-redeploy or mid-delete → session ends with a clear error, edits kept, nothing reported as done that wasn't. Test in Task 4 and Task 7.
4. Env values containing `=`, quotes, `#`, empty values or leading/trailing spaces round-trip between rows and the `.env` block unchanged. Test in Task 7.
5. Git settings saved without retyping the password → Portainer keeps the stored credentials (the app sends "unchanged" correctly per the verified API) and never sends an empty password that would wipe them. Test in Task 8.

---

### Task 1: Verified stacks API notes, models and read/lifecycle client

**Files:**
- Create: `docs/superpowers/notes/portainer-ce-stacks-api.md` (verified endpoints, bodies, response fields, enum codes, version gates, CE-vs-BE notes, the CE release checked, with source links)
- Create: `shared/src/commonMain/kotlin/dev/silas/portainer/core/model/Stack.kt`
- Create: `shared/src/commonMain/kotlin/dev/silas/portainer/core/api/StacksApi.kt`
- Create: `shared/src/commonMain/kotlin/dev/silas/portainer/core/stacks/StackFeatures.kt`
- Test: `shared/src/commonTest/kotlin/dev/silas/portainer/core/api/StacksApiTest.kt`, `.../core/stacks/StackFeaturesTest.kt`

**Interfaces:**
- Produces:
  - `enum class StackType { Compose, Swarm, Kubernetes, Unknown }`, `enum class StackStatus { Active, Inactive }`, `enum class StackControl { Full, Limited }`
  - `data class GitConfig(val url: String, val referenceName: String, val configFilePath: String, val additionalFiles: List<String>, val authenticated: Boolean, val username: String?, val tlsSkipVerify: Boolean, val configHash: String?)`
  - `data class AutoUpdate(val interval: String?, val webhookId: String?, val forcePullImage: Boolean, val forceUpdate: Boolean)` (null on the stack = off)
  - `data class EnvVar(val name: String, val value: String)`
  - `data class Stack(val id: Int?, val name: String, val type: StackType, val endpointId: Int, val status: StackStatus, val control: StackControl, val git: GitConfig?, val autoUpdate: AutoUpdate?, val env: List<EnvVar>, val createdAt: Long?, val updatedAt: Long?, val updatedBy: String?, val fromTemplate: Boolean)` — `id == null` and `control == Limited` for external stacks.
  - `suspend fun PortainerClient.stacks(endpointId: Int): List<Stack>` (Portainer-managed compose stacks for that endpoint, plus external stacks discovered from container `com.docker.compose.project` labels when CE does not list them — whichever CE does, per the notes)
  - `suspend fun PortainerClient.stack(id: Int): Stack`, `suspend fun PortainerClient.stackFile(id: Int): String`
  - `suspend fun PortainerClient.startStack(id: Int, endpointId: Int)`, `stopStack(id: Int, endpointId: Int)`
  - `suspend fun PortainerClient.deleteStack(id: Int, endpointId: Int, removeVolumes: Boolean = false)`; `suspend fun PortainerClient.deleteExternalStack(name: String, endpointId: Int)` only if CE supports it (else omit and note it).
  - `data class StackFeatures(val detachFromGit: Boolean, val refListing: Boolean, val savedGitCredentials: Boolean, val removeVolumesOnDelete: Boolean, val pruneOnUpdate: Boolean, val externalDelete: Boolean, val webhookUrl: (baseUrl: String, webhookId: String) -> String)`; `fun stackFeatures(version: String): StackFeatures` (unknown/unparseable version → conservative: every optional feature false).

- [ ] **Step 1:** Identify the newest Portainer CE release and read its API (official API docs / Swagger and the `api/http/handler/stacks` sources of that tag). Write the notes file: each endpoint used by this plan (Tasks 1, 7, 8, 9, 10), method, path, query, body field names, response fields, `Type`/`Status` codes, and which CE versions differ. Mark anything BE-only. This file is the source of truth for field names in every later task.
- [ ] **Step 2: Failing tests** with MockEngine JSON copied from the notes: list maps type/status/control/git/autoUpdate/env/dates; a stack with `AutoUpdate == null` maps to `autoUpdate == null`; `stackFile` returns `StackFileContent`; start/stop/delete hit the noted paths with `endpointId`; `removeVolumes=false` by default never sends the volume flag; non-2xx maps to `PortainerException` with the key redacted; `stackFeatures("garbage")` all false; `stackFeatures(<newest CE>)` matches the notes.
- [ ] **Step 3:** Run `./gradlew :shared:jvmTest --offline` → FAIL (unresolved).
- [ ] **Step 4:** Implement with private DTOs (`@SerialName` per the notes), `execute`/`decodeBody`, defaulted fields so missing keys don't throw.
- [ ] **Step 5:** Verification command → PASS. Commit `feat(shared): stacks API client and feature gates`.

### Task 2: Compose analysis (YAML parse, structure, images)

**Files:**
- Modify: `gradle/libs.versions.toml`, `shared/build.gradle.kts` (YAML library)
- Create: `shared/src/commonMain/kotlin/dev/silas/portainer/core/stacks/ComposeAnalysis.kt`
- Test: `shared/src/commonTest/kotlin/dev/silas/portainer/core/stacks/ComposeAnalysisTest.kt`

**Interfaces:**
- Produces:
  - `data class YamlError(val line: Int /* 1-based */, val column: Int?, val message: String)`
  - `sealed interface ComposeWarning { data object NoServices; data class ServiceWithoutImage(val service: String); data class BuildWithoutContext(val service: String) }`
  - `data class ComposeAnalysis(val error: YamlError?, val warnings: List<ComposeWarning>, val images: List<String>, val services: List<String>)`
  - `fun analyzeCompose(text: String, webEditor: Boolean): ComposeAnalysis` — pure, no I/O.

- [ ] **Step 1:** Pick a YAML parser that publishes Android, iOS (arm64 + simulator) and wasmJs artifacts (verify on Maven Central); note the version and why in the commit message. If none supports wasmJs, implement a small line-based structural parser for the subset needed (mappings, sequences, scalars, comments, anchors) and say so.
- [ ] **Step 2: Failing tests:** bad indentation → `error.line` is the offending line; valid file → `error == null`, `images == ["nginx:1.27", "postgres:16"]`, `services == ["web","db"]`; file without `services:` → `NoServices` warning, no error; service with neither → `ServiceWithoutImage("x")`; `build:` with `webEditor = true` → `BuildWithoutContext`; `extends`, `include:`, anchors/aliases, `${VAR}` and `${VAR:-x}` in image → no error; image `${REGISTRY}/portainer/portainer-ce` reported verbatim; empty text → `NoServices`, no error; 3,000-line file analyzes under 500 ms on jvm.
- [ ] **Step 3–4:** RED → implement → GREEN (verification command). **Step 5:** Commit `feat(shared): compose analysis for stacks`.

### Task 3: Stacks tab, list and read-only detail

**Files:**
- Modify: `composeApp/.../app/environment/EnvironmentShell.kt` (add `EnvTab.Stacks("Stacks", <icon>)`), `app/Routes.kt` (`data class Stacks(serverId, endpointId)`, `data class StackDetail(serverId, endpointId, stackId: Int?, name: String)`), `app/App.kt`
- Create: `composeApp/.../app/stacks/StacksScreen.kt`, `StacksViewModel.kt`, `StackDetailScreen.kt`, `StackDetailViewModel.kt`
- Create: `shared/.../core/stacks/StackMembership.kt`
- Test: `composeApp/src/commonTest/.../app/stacks/StacksViewModelTest.kt`, `StackDetailViewModelTest.kt`; `shared/.../core/stacks/StackMembershipTest.kt`

**Interfaces:**
- Consumes: Task 1 `stacks`, `stack`, `stackFile`, `stackFeatures`, `status`; existing `containers(endpointId)` and `COMPOSE_PROJECT_LABEL`; Task 2 `analyzeCompose`.
- Produces:
  - `fun stackContainers(stackName: String, containers: List<ContainerSummary>): List<ContainerSummary>` (label match, running + stopped)
  - `fun stackProtection(containers: List<ContainerSummary>, composeImages: List<String>, resolvedImages: Map<String, String> = emptyMap()): Boolean` (uses `isProtectedImage`; ID-only container images resolved via existing `resolveIdOnlyImages`)
  - `StackDetailViewModel` state: `stack`, `containers`, `file: String?`, `protected: Boolean`, `features: StackFeatures`, `error`.

- [ ] **Step 1: Failing tests:** list shows name/type/status/control and filters by search; external stack shown with `Limited`; detail lists running and stopped members; stack with no containers → `Inactive`, protection from the file's images; a member running `portainer/agent` → `protected == true`; file containing `portainer/portainer-ce` → protected; load failure → error text with key redacted, no crash.
- [ ] **Step 2–4:** RED → implement (tab navigation follows existing `switchTab`; member rows open existing `ContainerDetail`) → GREEN, app builds. **Step 5:** Commit `feat(app): stacks tab and stack detail`.

### Task 4: Stack lifecycle actions and StackSessions

**Files:**
- Create: `composeApp/.../app/stacks/StackSessions.kt`, `StackActions.kt` (confirm sheets: typed name, volume option, connection reminder)
- Modify: `app/AppContainer.kt` (`stackSessions`), `app/recreate/RecreateSessions.kt`, `app/cleanup/CleanupViewModel.kt`, `app/editor/EditorViewModel.kt` / `DeploySessions.kt` (refuse while a stack session runs on the endpoint), `StackDetailViewModel.kt`
- Test: `composeApp/src/commonTest/.../app/stacks/StackActionsTest.kt`, `StackSessionsTest.kt`

**Interfaces:**
- Produces:
  - `data class StackKey(val serverId: String, val endpointId: Int, val stackName: String)`
  - `sealed interface StackOp { data object Start; data object Stop; data class Delete(val removeVolumes: Boolean) }` — this task defines only these three. Later tasks add their own variants: Task 7 `Update(file: String, env: List<EnvVar>, pullImage: Boolean, prune: Boolean)`, Task 8 `GitSettings(request: GitSettingsRequest)`, `GitRedeploy(request: GitRedeployRequest)`, `Detach`, Task 10 `Migrate(targetEndpointId: Int, newName: String)`.
  - `class StackSessions(appScope: CoroutineScope, clientFactory: (serverId: String) -> PortainerClient)` with `fun start(key: StackKey, op: StackOp): Boolean`, `fun progress(key: StackKey): StateFlow<StackProgress?>`, `fun isRunning(serverId: String, endpointId: Int): Boolean`, `fun refusal(serverId: String, endpointId: Int): String?`, `fun clear(key: StackKey)`
  - `data class StackProgress(val op: StackOp, val running: Boolean, val error: String?)`

- [ ] **Step 1: Failing tests:** stop/delete/detach/migrate refused when `protected` with `PROTECTED_IMAGE_MESSAGE` and no request; delete needs the exact typed name (wrong case/whitespace → disabled); `removeVolumes` false unless toggled, toggle shown only when `features.removeVolumesOnDelete`; connection reminder text present on every confirm; a second op for the same key while running is refused (one request); leaving and re-creating the VM re-attaches to running/finished progress; network failure mid-op → `error` set, `running == false`, nothing claimed done; `RecreateSessions.start`, cleanup run and editor deploy refuse while a stack op runs on the same endpoint and vice versa; delete success navigates back to the list and refreshes.
- [ ] **Step 2–4:** RED → implement (dedicated client per op, closed in `finally`) → GREEN. **Step 5:** Commit `feat(app): stack start, stop and delete with sessions`.

### Task 5: Code editor component

**Files:**
- Create: `composeApp/.../app/support/codeeditor/YamlTokenizer.kt` (pure), `EditOps.kt` (pure: indent, outdent, undo/redo history, find), `CodeEditor.kt` (composable)
- Test: `composeApp/src/commonTest/.../app/support/codeeditor/YamlTokenizerTest.kt`, `EditOpsTest.kt`

**Interfaces:**
- Produces:
  - `enum class TokenKind { Key, Scalar, StringLit, Comment, Anchor, Alias, Interpolation, Punct, Plain }`; `data class Token(val start: Int, val end: Int, val kind: TokenKind)`; `fun tokenizeYaml(text: String): List<Token>`
  - `data class EditorText(val text: String, val selection: IntRange)`; `fun indent(t: EditorText): EditorText` (2 spaces at each selected line start), `fun outdent(t: EditorText): EditorText`, `class EditHistory(limit: Int = 200) { fun push(t: EditorText); fun undo(): EditorText?; fun redo(): EditorText? }`, `fun findAll(text: String, query: String, ignoreCase: Boolean = true): List<IntRange>`
  - `@Composable fun CodeEditor(value: String, onValueChange: (String) -> Unit, errorLine: Int?, readOnly: Boolean, modifier: Modifier = Modifier)` — gutter with line numbers, error line marked, highlighting via `VisualTransformation`, horizontal scroll inside, toolbar (indent, outdent, undo, redo, find bar with next/previous and match count).

- [ ] **Step 1: Failing tests:** tokens for keys, quoted strings, `# comment`, `&anchor`, `*alias`, `${VAR}` inside a string; `#` inside a quoted string is not a comment; indent/outdent on single and multi-line selections keep selection on the same text; undo/redo round-trip and history limit; `findAll` case-insensitive with overlapping-safe ranges; tokenizer on 3,000 lines under 200 ms (jvm).
- [ ] **Step 2–4:** RED → implement → GREEN, app builds on all targets. **Step 5:** Commit `feat(app): YAML code editor component`.

### Task 6: Line diff

**Files:**
- Create: `shared/.../core/stacks/LineDiff.kt`
- Test: `shared/.../core/stacks/LineDiffTest.kt`

**Interfaces:**
- Produces: `sealed interface DiffLine { data class Same(val text: String, val oldNo: Int, val newNo: Int); data class Added(val text: String, val newNo: Int); data class Removed(val text: String, val oldNo: Int) }`; `data class DiffHunk(val lines: List<DiffLine>, val collapsedBefore: Int)`; `fun lineDiff(old: String, new: String, context: Int = 3): List<DiffHunk>`; `fun envDiff(old: List<EnvVar>, new: List<EnvVar>): List<String>` (human lines: `Added X`, `Changed X`, `Removed X` — values never shown for names matching `(?i)pass|secret|token|key`).

- [ ] **Step 1: Failing tests:** identical → no hunks; one changed line → Removed+Added with 3 lines context; far-apart changes → two hunks with `collapsedBefore` counts; CRLF vs LF treated equal; trailing newline difference shown; envDiff hides secret-looking values.
- [ ] **Step 2–4:** RED → implement (Myers or LCS; 3,000-line files under 300 ms jvm) → GREEN. **Step 5:** Commit `feat(shared): line and env diff for stack review`.

### Task 7: Web-editor stack editing and update

**Files:**
- Create: `composeApp/.../app/stacks/StackEditorScreen.kt`, `StackEditorViewModel.kt`, `EnvVarsEditor.kt`, `StackReviewSheet.kt`
- Modify: `shared/.../core/api/StacksApi.kt` (`updateStack`), `StackDetailScreen.kt` (Editor tab for web-editor stacks; read-only file view for git stacks)
- Test: `composeApp/src/commonTest/.../app/stacks/StackEditorViewModelTest.kt`, `EnvVarsTest.kt`; `shared/.../core/api/StacksApiTest.kt`

**Interfaces:**
- Consumes: Tasks 1–6.
- Produces: `suspend fun PortainerClient.updateStack(id: Int, endpointId: Int, file: String, env: List<EnvVar>, pullImage: Boolean, prune: Boolean)` (body per notes; `prune` only sent when `features.pruneOnUpdate`); `fun parseDotEnv(text: String): Pair<List<EnvVar>, List<String /* line errors */>>`, `fun formatDotEnv(env: List<EnvVar>): String`.

- [ ] **Step 1: Failing tests:** edits → review lists `lineDiff` hunks and `envDiff`; YAML error disables "Update"; structural warnings shown but allow proceed; new file adding a protected image → blocked; duplicate env names → error; `.env` round-trip for values with `=`, quotes, `#`, empty and spaced values; unsaved changes → back asks; update failure (500 and network loss) keeps file and env, shows redacted error; success refreshes detail; external stack has no Editor; update runs through `StackSessions` with `StackOp.Update`.
- [ ] **Step 2–4:** RED → implement (validation via `analyzeCompose` on `Dispatchers.Default`, debounced 300 ms) → GREEN. **Step 5:** Commit `feat(app): edit and update web-editor stacks`.

### Task 8: Git settings, redeploy and detach

**Files:**
- Create: `composeApp/.../app/stacks/GitSettingsScreen.kt`, `GitSettingsViewModel.kt`, `GitRedeploySheet.kt`
- Modify: `shared/.../core/api/StacksApi.kt`, `StackSessions.kt` (ops)
- Test: `composeApp/src/commonTest/.../app/stacks/GitSettingsViewModelTest.kt`; `shared/.../core/api/StacksGitApiTest.kt`

**Interfaces:**
- Produces: `data class GitAuth(val username: String, val password: String?)` (`password == null` = keep stored); `data class GitSettingsRequest(val referenceName: String, val configFilePath: String, val additionalFiles: List<String>, val auth: GitAuth?, val tlsSkipVerify: Boolean, val autoUpdate: AutoUpdate?, val env: List<EnvVar>)`; `data class GitRedeployRequest(val referenceName: String, val auth: GitAuth?, val env: List<EnvVar>, val pullImage: Boolean, val forceRedeploy: Boolean)`; `suspend fun PortainerClient.updateGitSettings(id: Int, endpointId: Int, r: GitSettingsRequest)`, `gitRedeploy(id: Int, endpointId: Int, r: GitRedeployRequest)`, `detachFromGit(id: Int, endpointId: Int)` (gated), `gitRefs(url: String, auth: GitAuth?, tlsSkipVerify: Boolean): List<String>` (gated).

- [ ] **Step 1: Failing tests:** load shows url (read-only), ref, paths, auth username (password field empty with "unchanged" hint), TLS, auto-update mechanism Polling (interval validated like `5m`, `1h`) or Webhook (URL from `features.webhookUrl`, copy action); saving without typing a password sends the "keep stored" form per the notes and never an empty password; turning auth off sends the noted "no auth" form; redeploy review shows ref, paths, Re-pull image, Force redeployment; protected stack blocks redeploy/detach; detach needs its own confirm, gated by `features.detachFromGit`; ref picker appears only when `features.refListing`, else text field; password never appears in VM state after save, logs or error text.
- [ ] **Step 2–4:** RED → implement → GREEN. **Step 5:** Commit `feat(app): git stack settings, redeploy and detach`.

### Task 9: Create stack (web editor, upload, repository, custom template)

**Files:**
- Create: `composeApp/.../app/stacks/CreateStackScreen.kt`, `CreateStackViewModel.kt`, `app/support/FilePicker.kt` (expect) + `FilePicker.android.kt`, `FilePicker.ios.kt`, `FilePicker.wasmJs.kt` (actuals: SAF, UIDocumentPicker, `<input type=file>`; returns text ≤ 1 MB)
- Modify: `shared/.../core/api/StacksApi.kt` (`createStackFromText`, `createStackFromRepository`, `customTemplates`, `customTemplateFile`), `Routes.kt` (`CreateStack(serverId, endpointId)`), `StacksScreen.kt` ("+" FAB)
- Test: `composeApp/src/commonTest/.../app/stacks/CreateStackViewModelTest.kt`; `shared/.../core/api/StacksCreateApiTest.kt`

**Interfaces:**
- Produces: `fun validStackName(name: String): String?` (error text or null, rule from the notes); `suspend fun PortainerClient.createStackFromText(endpointId: Int, name: String, file: String, env: List<EnvVar>): Stack`; `createStackFromRepository(endpointId: Int, name: String, url: String, git: GitSettingsRequest): Stack`; `customTemplates(): List<CustomTemplate>`; `data class CustomTemplate(val id: Int, val title: String, val description: String, val variables: List<TemplateVariable>)`; `data class TemplateVariable(val name: String, val label: String, val defaultValue: String)`.
- Upload uses the picker to fill the web editor (same create path as web editor; the multipart file endpoint is not needed unless the notes say text create can't be used).

- [ ] **Step 1: Failing tests:** name rule rejects uppercase/space/duplicate-of-existing; web editor create sends file+env, blocks on YAML error, warns on structure; repository create sends git settings (password per Task 8 rules); custom template list filtered to Docker standalone, variables pre-filled with defaults and substituted per the notes; success navigates to the new stack's detail; failure keeps the form; protected image in the new file allowed to create (creating doesn't touch the running Portainer) but warns.
- [ ] **Step 2–4:** RED → implement → GREEN on all targets. **Step 5:** Commit `feat(app): create stacks from editor, upload, git or template`.

### Task 10: Duplicate / migrate

**Files:**
- Create: `composeApp/.../app/stacks/MigrateStackSheet.kt`
- Modify: `shared/.../core/api/StacksApi.kt` (`migrateStack`), `StackDetailViewModel.kt`, `StackSessions.kt`
- Test: `composeApp/src/commonTest/.../app/stacks/MigrateStackTest.kt`; `shared/.../core/api/StacksApiTest.kt`

**Interfaces:**
- Produces: `suspend fun PortainerClient.migrateStack(id: Int, fromEndpointId: Int, toEndpointId: Int, newName: String?)`; duplicate = `createStackFromText` (web editor) or `createStackFromRepository` (git) on the chosen endpoint with a new name, source untouched.

- [ ] **Step 1: Failing tests:** target list shows only Docker standalone endpoints; migrate requires typed name, shows the connection reminder, blocked when protected; duplicate never calls any API on the source stack except reads; same-endpoint duplicate requires a different name; failures keep the sheet.
- [ ] **Step 2–4:** RED → implement → GREEN. **Step 5:** Commit `feat(app): duplicate and migrate stacks`.

### Task 11: Open stack from containers

**Files:**
- Modify: `app/detail/ContainerDetailScreen.kt` (stack badge → "Open stack"), `app/recreate/RecreateScreen.kt` and `app/editor/EditorReviewPane.kt` (stack-member warning gets "Open stack")
- Test: `composeApp/src/commonTest/.../app/stacks/OpenStackLinkTest.kt`

**Interfaces:** `fun stackLinkFor(container: ContainerSummary, stacks: List<Stack>): StackDetail?` (route when a stack with that project name exists on the endpoint; external stacks link by name).

- [ ] **Step 1: Failing tests:** managed stack → route with id; external → route with `stackId = null` and name; non-member → null.
- [ ] **Step 2–4:** RED → implement → GREEN. **Step 5:** Commit `feat(app): open a container's stack`.

### Task 12: v1.2 release

- [ ] **Step 1:** README: Stacks tab, web-editor editing with YAML-checked code editor and diff review, GitOps settings (polling/webhook, re-pull, force), pull and redeploy, detach, create (editor/upload/git/template), duplicate/migrate, safety rules; keep donation section.
- [ ] **Step 2:** Verification command green; `:composeApp:testDebugUnitTest --rerun-tasks` twice.
- [ ] **Step 3:** Push; workflow_dispatch `build.yml` on the branch; linux + ios green; debug APK to the owner.
- [ ] **Step 4:** Commit `docs: README for v1.2`.
