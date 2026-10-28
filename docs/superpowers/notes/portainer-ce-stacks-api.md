# Portainer CE stacks API — verified notes (v1.2)

Single source of truth for stack-related field names in v1.2 Tasks 1, 7, 8, 9 and 10.

- **Release checked:** Portainer CE **2.45.3** (GitHub "Latest", LTS, released 2026-10-21; checked
  2026-10-26). Older lines compared: 2.33.7, 2.36.0, **2.39.8** (previous LTS line), 2.40.0, 2.41.1,
  2.42.0, 2.43.0, 2.44.0 (STS), 2.45.0.
- **How it was verified:** the `portainer/portainer` sources at each tag: handlers in
  `api/http/handler/stacks/*.go` (their swag `@param`/`@router` annotations generate the official API
  reference), types in `api/portainer.go` and `api/git/types/types.go`, and the CE web UI in `app/react/**`
  (what CE itself sends and offers). Official API reference: `https://api-docs.portainer.io/?edition=ce&version=2.45.3`
  (an SPA that could not be fetched from this environment, so the tag sources were used as the authority).
- Source links below are relative to `https://github.com/portainer/portainer/blob/2.45.3/`.
- All paths below are under the server base URL (`https://host[:port][/sub-path]`), auth header
  `X-API-Key`. Errors are `{"message": "...", "details": "..."}` (mapped by `PortainerClient.throwForStatus`).

## Enums and the Stack object

`api/portainer.go` (`Stack`, `StackType`, `StackStatus`, `AutoUpdateSettings`, `Pair`, `StackOption`,
`StackDeploymentStatus`, `StackDeploymentInfo`), `api/http/handler/stacks/response.go` (`stackResponse`).

- `Type`: `1` Swarm, `2` Compose (Docker standalone), `3` Kubernetes. Only `2` is in scope.
- `Status`: `1` active, `2` inactive; **since 2.41.0** also `3` deploying, `4` error (2.39.x and older: 1/2 only).
- JSON keys of a stack (Go field names are used verbatim where there is no `json` tag):

| key | type | notes |
|---|---|---|
| `Id` | int | |
| `Name` | string | normalised lowercase (see name rule) |
| `Type` | int | see above |
| `EndpointId` | int | |
| `SwarmId` | string | empty for compose |
| `EntryPoint` | string | compose file path (mirrors `GitConfig.ConfigFilePath` for git stacks) |
| `Env` | `[{"name","value"}]` or `null` | lower-case keys (`Pair`) |
| `ResourceControl` | object/null | access control, not used |
| `Status` | int | see above |
| `DeploymentStatus` | `[{"Status","Time","Message"}]` | 2.41+; `Message` set on error entries; last entry wins |
| `CreationDate`, `UpdateDate` | int64 | unix **seconds**; `0` = unknown |
| `CreatedBy`, `UpdatedBy` | string | usernames |
| `AdditionalFiles` | `[string]`/null | extra compose paths (git stacks) — on the stack, not in `GitConfig` |
| `AutoUpdate` | object/null | `{"Interval","Webhook","JobID","ForceUpdate","ForcePullImage"}`; `null` = GitOps updates off |
| `Option` | object/null | `{"Prune": bool, "HelmAtomic": bool}` |
| `GitConfig` | object/null | `null` = not a git stack. See below |
| `GitSourceId` | int | **2.43+, only on `GET /api/stacks/{id}`** (and on `POST /{id}/git`'s response); not on the list |
| `CurrentDeploymentInfo` | object/null | `{"ConfigHash","RepositoryURL","ConfigFilePath","ReferenceName","AdditionalFiles",...}` |
| `FromAppTemplate` | bool | also set to `true` by Portainer when a git stack is detached (see Detach) |
| `ProjectPath`, `Namespace`, `WorkflowID` | | not used |

- `GitConfig` (`gittypes.RepoConfig`): `URL`, `ReferenceName` (e.g. `refs/heads/main`), `ConfigFilePath`,
  `Authentication` (omitted when none: `{"Username","Password","Provider","AuthorizationType","GitCredentialID"}` —
  **`Password` is always returned empty**, `SanitizeRepoConfig`), `ConfigHash` (commit), `TLSSkipVerify`.
  The URL is returned with any embedded userinfo stripped.
- AutoUpdate "on" test (CE UI `parseAutoUpdateResponse`, `app/react/portainer/gitops/AutoUpdateFieldset/utils.ts`):
  on iff the object is non-null **and** `Interval` or `Webhook` is non-empty. The app maps everything else to `autoUpdate == null`.
- 2.44+: `AutoUpdate.Interval` in responses is back-filled from the linked GitOps Source (`fillAutoUpdateInterval`).
  **App behaviour (Task 8):** when this stack turns polling off on 2.44+ (auto-updates off, or switched to the
  webhook), the app clears the Source interval (`""`, shared — warned) and sends `AutoUpdate: null` when no webhook
  remains. **Known stale display:** if *another* stack (or CE's UI) clears the Source interval, this stack still has its
  stored `AutoUpdate` object; the back-filled `Interval` is then `""`, so with no webhook the app shows automatic
  updates as off (CE's own "on" rule) although the stack's stored object was never cleared. Because the app's model
  then holds `autoUpdate == null`, any later save from the app sends `AutoUpdate: null` and clears the stored object
  too (matching what is displayed).

## Listing — `GET /api/stacks`

`stack_list.go`. Query `filters` = JSON, e.g. `filters={"EndpointID":3}` (URL-encoded). Keys: `EndpointID`,
`SwarmID`, `IncludeOrphanedStacks` (admins; not used). With `EndpointID` set and no `SwarmID`, CE returns
compose stacks whose `EndpointId` matches (orphaned stacks only with `IncludeOrphanedStacks`). The app still
filters `Type == 2 && EndpointId == endpointId` client-side. Same in all checked versions.

**External ("limited") stacks are NOT returned** ("Limited stacks will not be returned by this endpoint").
CE's UI discovers them itself (`app/portainer/services/api/stackService.js` `externalComposeStacks`,
`app/portainer/helpers/stackHelper.js`): list the endpoint's containers (`all=1`), take distinct
`com.docker.compose.project` label values, and union with the managed list **by exact name**
(`_.unionWith(..., a.Name === b.Name)`). The app does the same: externals have no id (`id == null`),
`control == Limited`, type Compose; status = Active if any member container is running, else Inactive
(CE shows no status for externals).

## Read

- `GET /api/stacks/{id}` → stack object (+ `GitSourceId` on 2.43+). `stack_inspect.go`.
- `GET /api/stacks/{id}/file` → `{"StackFileContent": "..."}`. No query parameters. `stack_file.go`.
  **2.45**: returns **409** "Stack git settings have changed. Redeploy the stack…" when a git stack's URL or
  compose path was saved but not yet redeployed (`gitStackPendingRedeploy`).
- The file endpoint returns the main compose file only. The app's protection check reads its images with `${VAR}` /
  `${VAR:-default}` substituted from the stack's `Env` (`interpolateImage`); images in a git stack's `AdditionalFiles`
  are **not** checked (the stack's containers, running or stopped, cover what it runs).

## Start / stop — `POST /api/stacks/{id}/start|stop?endpointId=N`

`stack_start.go`, `stack_stop.go`. No body. `endpointId` required. Synchronous (compose up/stop runs inside the
request; can take minutes → the client uses a long per-request timeout). Response: the stack object.
Start returns **409** when the stack is already active, deploying, or (2.41+) in `Status 4` error (message = last
deployment error). Stop returns 409 while deploying. Same paths in all checked versions.

## Delete — `DELETE /api/stacks/{id}?endpointId=N`

`stack_delete.go`. 204, no body. `endpointId` is optional server-side, but a missing value (→ 0) or any id other than the
stack's endpoint is treated as an orphaned-stack delete that needs admin; the app always sends the stack's endpoint id.
Synchronous (compose down).

- **No volume-removal flag for standalone/compose stacks in CE** (any checked version): teardown calls
  `UndeployComposeStack` without volume options (`api/stacks/teardown/teardown.go`); `ComposeDownOptions.RemoveVolumes`
  exists in `api/portainer.go` but is unused; `RemoveVolumes` exists only for Edge stacks. → `removeVolumesOnDelete = false`.
- **External delete**: `DELETE /api/stacks/{name}?external=true&endpointId=N` exists but the handler always
  treats the stack as **Swarm** (`deleteExternalStack` builds `Type: DockerSwarmStack`) — "Only external Swarm stacks
  are supported". CE's UI offers **no** action for external compose stacks: they are not row-selectable in the list
  (`StacksDatatable.tsx` `allowSelection`) and the detail page doesn't load a stack so `StackActions` isn't rendered
  (`ItemView.tsx`, `StackInfoTab.tsx`). → `externalDelete = false`; `deleteExternalStack` is not implemented.
  External stacks are view-only (containers listed).

## Update web-editor stack (also detach) — `PUT /api/stacks/{id}?endpointId=N`

`stack_update.go` (`updateComposeStackPayload`). Body:

```json
{"StackFileContent": "...", "Env": [{"name":"A","value":"1"}], "Prune": false,
 "RepullImageAndRedeploy": false, "PullImage": false}
```

- `RepullImageAndRedeploy` **2.36+**; `PullImage` is the older name, deprecated in 2.36 but still honoured
  (`RepullImageAndRedeploy || PullImage`) through 2.45.3. Sending both with the same value works on every checked
  version.
- `Prune` for compose: **2.40.0+** (absent in 2.39.x's compose payload — ignored there → only send when
  `pruneOnUpdate`). Saved to `Option.Prune`.
- Response: the stack. **2.41+ the deploy runs asynchronously** after the response (`go stackDeploy`): status becomes
  `3` deploying, then `1`/`4`; poll `GET /api/stacks/{id}` until not `3` and report `DeploymentStatus` last
  `Message` on `4`. 2.40 and older: synchronous, errors come back as the HTTP error. A second update while
  deploying → 409.
- **Updating a stopped (inactive) stack starts it**, on every checked version: the update always deploys
  (compose up, `CreateComposeStackDeploymentConfigTx(...).Deploy`), never just saves the file. ≤2.40 set
  `stack.Status = StackStatusActive` in the request after a successful deploy (`updateStackInTx`, 2.33.7, 2.39.8,
  2.40.0). 2.41+ set Deploying first (`PrepareStackStatusForDeployment`, which keeps the old status in
  `DeploymentStartStatus`). Then `stackDeploy` deploys and `UpdateStackStatusFromDeploymentResult` sets **Active** on
  success, Error on failure (2.41.1, 2.45.3). Only a stack whose `DeploymentStartStatus` was Error is undeployed first.
  → The app expects Active after an update, whatever the status before, and says in the review that a stopped
  stack will be started. A stack that reads Inactive after a successful update is not what CE does (only a stop
  running at the same time could cause it), so it is reported, not assumed done.
- **Detach from git** = this same `PUT` on a git stack, with the current file (CE UI `StackActions.tsx`
  `handleDetachFromGit`: `{stackFileContent, env: stack.Env, prune: false}`). Portainer clears git config and
  `AutoUpdate` and sets `FromAppTemplate = true`. Available in all checked versions (2.33.7 → 2.45.3; earlier
  unverified). One-way.

## Git stacks

### Update git settings — `POST /api/stacks/{id}/git?endpointId=N`

`stack_update_git.go` (`stackGitUpdatePayload`). Body (2.45.3):

```json
{"RepositoryReferenceName": "refs/heads/main", "ConfigFilePath": "docker-compose.yml",
 "AdditionalFiles": ["override.yml"], "AutoUpdate": <current or edited>, "Env": [<current or edited>], "Prune": false,
 "SourceID": 0,
 "RepositoryURL": "", "RepositoryAuthentication": true, "RepositoryUsername": "u",
 "RepositoryPassword": "", "TLSSkipVerify": false}
```

- `ConfigFilePath` and `AdditionalFiles` in this payload: **2.40.0+** (older: ref/auth/TLS/AutoUpdate/Env/Prune only;
  compose path not editable).
- `Prune` is applied only to Swarm stacks here (ignored for compose).
- **Replaced vs kept** (`stack_update_git.go` at 2.33.7, 2.39.8, 2.40.0, 2.41.1, 2.45.3):
  - Replaced wholesale on **every** version: `RepositoryReferenceName` (`""` empties the ref), `Env` (omitted, `null` or
    `[]` clears all variables), `AutoUpdate` (`null`/omitted turns GitOps updates off); with `SourceID == 0` also `TLSSkipVerify` and the credentials
    (see below).
  - Kept when empty, **2.40+** only: `ConfigFilePath: ""`, `AdditionalFiles: null`/omitted, `RepositoryURL: ""` keep
    the stored values (`[]` for `AdditionalFiles` clears them).
  - **Rule (Task 8):** always send the stack's current ref, env and AutoUpdate (and TLS) unless the user changed them.
- ≤2.39 payloads also have `RepositoryAuthorizationType` (int, `gittypes.GitCredentialAuthType`); omit it (0 = basic).
- **Credentials ("keep stored" vs "no auth")** — critical, applies when `SourceID == 0` (all versions):
  - `RepositoryAuthentication: true` + `RepositoryPassword: ""` → **keeps the stored password** (username replaced by
    `RepositoryUsername`).
  - `RepositoryAuthentication: false` → **removes** authentication.
  - `TLSSkipVerify` is always overwritten by the payload value (send the current value to keep it).
  - With auth on, Portainer test-fetches the repo (`LatestCommitID`) before saving; bad creds → 500.
- **2.43+ GitOps Sources**: URL/credentials/TLS live on a shared *Source* (`GitSourceId`). `SourceID != 0` →
  the inline URL/auth/TLS fields are ignored and the stack is (re)pointed at that Source; CE 2.45's own UI always
  sends `SourceID` (its "Edit stack settings" modal has no inline credential fields). With `SourceID == 0`, changed
  auth/TLS are written **into the shared Source**, which affects every stack using it.
- **AutoUpdate**: `{"Interval":"5m","Webhook":"<uuid>","ForceUpdate":bool,"ForcePullImage":bool}` or `null` (off).
  Validation (`api/git/update/validate.go`): `Webhook` or `Interval` required; `Webhook` must be a UUID (client-generated,
  CE uses `uuid()`); `Interval` = Go duration ≥ 1m (`1m30s`, `5m`, `1h`); webhook id must be unique (409).
  **2.44+: `AutoUpdate.Interval` is ignored for scheduling** — polling lives on the Source
  (`migrate_2_44_0.go`, `gitops/scheduling/scheduler.go`); CE 2.45's UI sends `Interval: ""` always and offers only
  the webhook here. On 2.44+ the polling interval is set with `PUT /api/gitops/sources/{GitSourceId}`
  `{"interval":"5m"}` (`""` = off) — shared by all stacks on that Source. 2.43 and older: `AutoUpdate.Interval` works.
- Response: the stack (2.43+: `stackResponse` incl. `GitSourceId`).

### GitOps Source update — `PUT /api/gitops/sources/{id}` (2.43+; `interval` 2.44+)

`api/http/handler/gitops/sources/update_git.go`. Body, every field optional (`null`/absent = unchanged):
`{"name","url","tlsSkipVerify","authentication":{"username","password"},"interval"}`.
`authentication: {}` (both null) → removes auth; `password` absent/null → keeps stored password.
`GET /api/gitops/sources/{id}` reads it (password never returned).

### Pull and redeploy — `PUT /api/stacks/{id}/git/redeploy?endpointId=N`

`stack_update_git_redeploy.go`. Body (2.45.3):

```json
{"RepositoryReferenceName": "", "RepositoryAuthentication": false, "RepositoryUsername": "",
 "RepositoryPassword": "", "Env": [...], "Prune": false, "RepullImageAndRedeploy": true, "PullImage": true}
```

- Keep rules exist **only from 2.41.0**: `RepositoryReferenceName: ""` keeps the stored ref (`cmp.Or`), and `Env`
  omitted/`null` keeps env (`if payload.Env != nil`). On **≤2.40** (incl. 2.33.7, 2.36, 2.39 LTS) both are assigned
  unconditionally: an empty ref empties `GitConfig.ReferenceName`, an omitted Env clears all variables.
- **Rule (Task 8): always send the stack's current `RepositoryReferenceName` and current `Env` on redeploy, on every
  version.**
- `Prune` is `*bool` on 2.41+ (omit = keep `Option.Prune`); plain bool on ≤2.40 (omitted = false). CE UI sends
  `Prune: stack.Option?.Prune` — always send the stack's current `prune`.
- ≤2.39 payloads also have `RepositoryAuthorizationType` (int); omit it (0 = basic).
- Credentials: **2.44+** stored (Source) credentials are used unless `RepositoryAuthentication: true` **and** a non-empty
  `RepositoryPassword` is sent (then it replaces them). **≤2.43**: credentials are used only when
  `RepositoryAuthentication: true` (+ username); empty password = keep stored.
- **No "force redeployment" field** for a manual redeploy: compose redeploy always force-recreates
  (`CreateComposeStackDeploymentConfigTx(..., prune, pullImage, true)`). "Force redeployment" exists only as
  `AutoUpdate.ForceUpdate` (auto-updates even when the repo hasn't changed). CE's "Pull and redeploy" dialog offers just
  "Re-pull image and redeploy" (`GitPullButton.tsx`, `confirm-stack-update.ts`).
- 2.41+: async deploy (see PUT). 409 while deploying.

### Git ref listing — **Business Edition only**

CE has no `/api/gitops/repo/refs` route in any checked version (`api/http/handler/gitops/handler.go` has only
`POST /gitops/repo/file/preview`); CE's UI shows a plain text field (`app/react/portainer/gitops/RefField/RefField.tsx`:
the ref selector is `isBE` only). → `refListing = false`.

### Saved git credentials

The per-user "git credentials" API (`/api/users/{id}/gitcredentials`) is **BE-only** (absent from CE's API).
CE **2.43+** has GitOps **Sources** (saved URL + credentials + TLS, referenced by `SourceID`/`GitSourceId`) — the app
treats these as CE's saved git credentials: `savedGitCredentials = version >= 2.43.0`.

### Webhook URL

`{baseUrl}/api/stacks/webhooks/{webhookId}` (`app/portainer/helpers/webhookHelper.ts` `stackWebhookUrl`), public
`POST` (`webhook_invoke.go`). `webhookId` = the stack's `AutoUpdate.Webhook` (a UUID the client generates when enabling
it). Same in all checked versions.

## Create (Docker standalone)

`create_compose_stack.go`, `stack_create.go`. All `POST ...?endpointId=N`, response = the created stack.

- From text: `POST /api/stacks/create/standalone/string` —
  `{"Name": "web", "StackFileContent": "...", "Env": [...], "FromAppTemplate": false}`.
- From repository: `POST /api/stacks/create/standalone/repository` —
  `{"Name","RepositoryURL","RepositoryReferenceName","RepositoryAuthentication","RepositoryUsername",
  "RepositoryPassword","ComposeFile","AdditionalFiles","AutoUpdate","Env","FromAppTemplate","TLSSkipVerify",
  "SourceID"}` (`SourceID` 2.43+; when set the inline URL/auth/TLS fields are ignored). `ComposeFile` defaults to
  `docker-compose.yml`. With `RepositoryAuthentication: true` a non-empty password is required. Same `AutoUpdate`
  rules as above (2.44+: `Interval` ignored for scheduling).
- From file (multipart `Name`, `Env` JSON, `file`): `POST /api/stacks/create/standalone/file` — not needed (upload
  fills the text create).
- **No GitOps Source picker at create in v1.2** (ruling): the app never sends `SourceID` on create. If another stack
  already uses the repository, Portainer may attach the new stack to the same Source (including its polling); the
  repository form says so. Follow-up: offer choosing an existing Source at create (2.43+).
- **2.44+: create returns before the deploy finishes** (`BuildAndAsyncDeploy`): poll status as for PUT.
- **Name rule** (`app/constants.ts` `STACK_NAME_VALIDATION_REGEX = '^[-_a-z0-9]+$'`, `NameField.tsx`): lower-case
  alphanumerics, `-`, `_`; message "This field must consist of lower case alphanumeric characters, '_' or '-'
  (e.g. 'my-name', or 'abc-123')."; unique among the endpoint's stacks ("Name should be unique"). Server side the name is
  lower-cased and stripped of `[^-_a-z0-9]` (`api/exec/common.go`), then must not collide with a Portainer stack or a
  container `com.docker.compose.project` label on that endpoint (409).

## Custom templates

`api/http/handler/customtemplates/`. `GET /api/custom_templates?type=2&edge=false` (repeat `type` for several;
`2` = compose standalone). CE 2.45's create-stack form (`CustomTemplateSelector.tsx`) passes no filter at all; the app
asks for `type=2&edge=false` and also filters `Type == 2 && !EdgeTemplate` client-side. Item keys:
`Id`, `Title`, `Description`, `Note`, `Logo`, `Platform` (1 linux, 2 windows), `Type`, `EdgeTemplate`, `GitConfig`,
`Variables: [{"name","label","defaultValue","description"}]`. CE does not filter templates by the endpoint's
platform; neither does the app.

- File: `GET /api/custom_templates/{id}/file` → `{"FileContent": "..."}`. For a git-backed template (`GitConfig`
  non-null) CE's UI instead calls `PUT /api/custom_templates/{id}/git_fetch` (same response; fetches the current file
  from the repository; `customtemplate_git_fetch.go`, `useCustomTemplateFile.ts` `getCustomTemplateGitFetch`); the app
  does the same.
- **Variables are Business Edition only in the UI**: `isTemplateVariablesEnabled = isBE`
  (`app/react/portainer/custom-templates/components/utils.ts`), so CE shows no variable inputs. CE still renders the
  file with the defaults (`useTemplateInitialization.ts` → `getVariablesFieldDefaultValues` → `renderTemplate`):
  Mustache, no HTML escaping, each variable → its `defaultValue`, else left as `{{ NAME }}`. The app does the same
  (defaults listed read-only; the result opens in the web editor). Mustache would blank tags that aren't template
  variables; the app leaves them untouched.
- Deploy = create from text with **`FromAppTemplate: true`** (CE 2.45 `CreateStackForm.tsx`, `method: 'template'`).
  The web editor and upload send `false`.

## Migrate / duplicate — `POST /api/stacks/{id}/migrate?endpointId=<source>`

`stack_migrate.go`. Body `{"EndpointID": <target>, "SwarmID": "", "Name": "new-name"}` (`Name` optional; empty =
keep; not normalised server-side, so validate client-side). Deploys on the target, then removes the stack from the
source, synchronously; 409 if the name is used on the target. Duplicate = create on the target (CE's
`StackDuplicationForm` uses the create API). Same in all checked versions.

## Version gates (`StackFeatures`, `core/stacks/StackFeatures.kt`)

Versions are compared numerically on `major.minor.patch` from `/api/status` `Version`; unparseable → every
optional feature off. Below 2.33.0 (unverified) → off. A future major (3.x) is unverified: the implementation's
`>=` comparisons currently turn its gates on, but this is deliberately **not** asserted by tests — re-verify the gates
when a 3.x CE release exists.

| feature | gate | evidence |
|---|---|---|
| `detachFromGit` | ≥ 2.33.0 | PUT on git stack clears git config in every checked tag |
| `refListing` | never | BE-only |
| `savedGitCredentials` (GitOps Sources) | ≥ 2.43.0 | `SourceID`, `GitSourceId` first in 2.43.0 |
| `removeVolumesOnDelete` | never | no CE flag |
| `pruneOnUpdate` | ≥ 2.40.0 | compose `Prune` first in 2.40.0 |
| `externalDelete` | never | Swarm-only server side, no CE UI action |
| `asyncDeploy` (status 3/4, poll after update/redeploy) | ≥ 2.41.0 | `StackStatusDeploying`, `go stackDeploy` |
| `pollingOnSource` (interval via `/api/gitops/sources/{id}`) | ≥ 2.44.0 | `migrate_2_44_0.go` |
| `gitPathsEditable` (`ConfigFilePath`/`AdditionalFiles` on `POST /git`) | ≥ 2.40.0 | payload fields first in 2.40.0 |
| `webhookUrl` | all | `{base}/api/stacks/webhooks/{id}` |

Pull-image flag: send `RepullImageAndRedeploy` and `PullImage` with the same value (works 2.33 → 2.45.3).
`/api/status` is marked deprecated in 2.45 (`/api/system/status` is the replacement) but still served.
