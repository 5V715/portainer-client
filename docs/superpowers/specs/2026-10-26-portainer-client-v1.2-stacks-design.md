# Portainer Client v1.2 — Stacks — Design

Date: 2026-10-26
Status: awaiting owner approval
Builds on: v1 (`2026-10-17-portainer-client-design.md`) and v1.1
(`2026-10-25-portainer-client-v1.1-design.md`). Everything there still applies (HTTPS + API key,
Docker standalone environments only, protection of Portainer's own containers, Portainer-blue
design, mobile-first, API key never logged or shown) unless changed here.

## Intent

Owner: "Can we also edit stacks?" → "Stack editing with gitops settings" → "The same features that
the original client gives." The owner wants Portainer's own stack management, with GitOps
settings, usable from a phone. Stack editing is also the correct way to change containers that
belong to a stack: v1/v1.1 only warn that a direct container edit is undone by the next stack
redeploy.

Owner's environment: **latest Portainer CE 2.x** (Community Edition). Portainer itself is **not**
part of a stack.

Success: everything the Portainer CE web UI offers for stacks on a Docker standalone environment
can be done in the app, comfortably on a phone, without risking the owner's infrastructure.

## Goals

1. **Stacks tab** inside an environment (fifth bottom-bar tab next to Containers, Images, Volumes,
   Networks).
   - List: name, deployment type (web editor / git / upload — Portainer shows upload as a
     web-editor stack after creation), status (active / inactive), Portainer control level
     (full control vs. limited/"external": stacks Portainer found but did not create).
   - Search by name; pull-to-refresh.
   - External (limited-control) stacks are view-only: containers listed, no edit/redeploy/delete.
2. **Stack detail**
   - Containers of the stack (by `com.docker.compose.project` label), with state, each tapping
     into the existing container detail.
   - Actions: **Start**, **Stop**, **Delete**, **Duplicate / Migrate** to another environment
     (Portainer's "Stack duplication / migration": same or other environment, new name).
   - Shows created/updated dates and who updated (as Portainer returns them).
3. **Web-editor stacks** (also covers stacks created by upload)
   - **Editor tab**: the compose file in a code editor (see below) and the stack's environment
     variables in a separate list.
   - **Update the stack** with options "Re-pull image and redeploy" (Portainer's PullImage) and
     "Prune services" if Portainer CE offers it for standalone (builder verifies; omit if not).
4. **Git stacks** (GitOps)
   - View the compose file read-only (Portainer does not allow editing a git stack's file).
   - **Git settings**: repository URL (read-only after creation, as in Portainer), reference
     (branch/tag), compose file path, additional compose paths, authentication (username +
     password/token; Portainer CE's saved git credentials only if CE offers them — builder
     verifies), skip TLS verification.
   - **GitOps updates**: automatic updates on/off; mechanism **Polling** (interval, e.g. `5m`) or
     **Webhook** (the webhook URL shown with a copy button); **Re-pull image**; **Force
     redeployment**.
   - **Pull and redeploy** now (with its own Re-pull image / Force options as Portainer offers).
   - **Detach from git** → becomes a web-editor stack with the current file.
   - Stack environment variables editable.
   - Reference picker: if Portainer CE exposes listing a repo's refs (it does for its own UI), offer
     a branch/tag picker, else a text field.
5. **Create stack** — Portainer's four methods:
   - Web editor (name, compose file, env vars).
   - Upload (pick a compose file from the device; the file content goes to Portainer).
   - Repository (all git settings above, incl. GitOps updates).
   - Custom template (list Portainer's custom templates for Docker standalone, fill its variables
     if any, deploy).
   - Name validation as Portainer requires (lowercase, alphanumeric, `-`/`_`; builder verifies the
     exact rule).
6. **Link from containers**: the existing "belongs to stack X" warning on container detail, the
   recreate review and the editor gets an **Open stack** button when Portainer manages that stack.

## Non-goals

- Swarm stacks, Kubernetes, Edge stacks (app supports Docker standalone only).
- Business Edition-only features: relative path volumes, webhooks for non-git stacks, stack-level
  access control/ownership changes beyond what CE shows. (Owner runs CE.)
- Creating/editing custom templates themselves (only deploying from them). Later, if wanted.
- Compose-aware structured editing (forms per service). The file is edited as text.
- Autocomplete, folding, multi-cursor in the editor.

## Anti-goals

- **A redeploy, stop or delete that cuts the app off from Portainer mid-operation without a
  warning.** Every redeploy/stop/delete shows the existing reminder that if the stack runs
  something the phone needs to reach Portainer (VPN, reverse proxy), the app will lose its
  connection.
- **Redeploying a stack that contains Portainer or its agent.** Blocked (redeploy, stop, delete,
  detach), using the existing protected-image rule on the stack's containers. Owner says Portainer
  isn't in a stack; this is cheap insurance.
- **Deploying something the user didn't review.** Web-editor redeploy shows a diff of the compose
  file (old vs new) and the variable changes, then requires confirmation. Git redeploy shows the
  ref, compose path(s) and the Re-pull / Force switches that will apply.
- **Losing data by accident.** Delete requires typing the stack name. If Portainer CE offers
  removing the stack's volumes on delete, that option is off by default, red, and listed
  separately (same style as v1.1's named-volume cleanup option).
- **Silently losing edits.** Leaving the editor with unsaved changes asks first. A failed
  update/redeploy keeps the edited file and variables so the user can fix and retry.
- **Credentials on the phone.** Git passwords/tokens are sent to Portainer only, never stored by
  the app, never shown back after saving (Portainer doesn't return them; the form shows
  "unchanged" placeholders).
- **Detach from git by accident.** One-way; separate confirmation explaining it.

## Approaches considered (owner chose A)

- **A. Own code editor in Compose (chosen).** A text field with a line-number gutter, YAML syntax
  colouring and an error-line marker; a multiplatform YAML parser validates as you type. Same on
  Android, iOS and web; native feel; fits existing code and tests. Trade-off: no folding /
  autocomplete / multi-cursor; very large files (thousands of lines) may type slower.
- **B. Embedded CodeMirror in a web view (rejected).** Full IDE-grade editor, but needs separate
  web-view code per platform plus a different approach on the wasm web build, web-page keyboard
  behaviour, fragile and hard to test. Could be added later as an optional full-screen editor.
- **C. Third-party Compose code-editor library (rejected).** The few that exist are young, mostly
  lack iOS/wasm or YAML support; dependency risk.

## Compose editor (owner: "Code editor with YAML validation")

- Monospace text, line numbers, YAML colouring (keys, scalars, strings, comments, anchors/aliases,
  `${VAR}` interpolation highlighted).
- Long lines scroll horizontally **inside the editor only**; the page never scrolls sideways.
- Toolbar: Tab/indent (inserts 2 spaces), outdent, undo, redo, find (with next/previous).
- **Validation while typing** (debounced): YAML parse errors show line + message; the error line
  is marked in the gutter. A second structural check before deploy: top-level `services:` mapping
  exists and each service has `image` or `build`. Deploy is disabled while either check fails.
  Portainer/Docker remain the final authority; their error is shown verbatim (API key redacted).
- Environment variables are edited in a separate list below the editor (name/value rows, like
  Portainer's "Environment variables"), with a switch to paste/edit them as a `.env` block.
  Duplicate names are an error.
- The editor is a reusable component so it can later serve other text editing.

## Safety rules (owner approved)

- Protected containers inside a stack (Portainer/agent images, existing rule) → stack redeploy,
  stop, delete and detach blocked with the existing protected message.
- Connection-dependency reminder on every redeploy/stop/delete.
- Review step before every update/redeploy: compose diff + env changes (web editor); ref, paths,
  switches (git).
- Typed-name confirmation for delete; volume removal option off by default and red.
- Detach from git: separate confirmation.
- Git credentials: sent to Portainer only, never stored or re-shown.
- Coordination with v1.1 sessions: while a stack operation runs on an endpoint, container cleanup,
  recreate and editor deploys involving that stack's containers are refused with a clear message
  (and vice versa), reusing the session pattern (app-scoped, survives leaving the screen,
  re-attaches, dedicated client).

## Portainer CE 2.x API (to verify by the builder)

The builder verifies every endpoint and body against the current Portainer CE 2.x API
(Swagger/API docs for the latest 2.x CE release) before relying on it, as was done for registry
auth in v1. Expected (from Portainer's web UI behaviour):

- `GET /api/stacks` (filter by endpoint), `GET /api/stacks/{id}`, `GET /api/stacks/{id}/file`.
- `PUT /api/stacks/{id}?endpointId=` — update web-editor stack (file content, env, prune, pull).
- `POST /api/stacks/{id}/start`, `POST /api/stacks/{id}/stop` (with `endpointId`).
- `DELETE /api/stacks/{id}?endpointId=` (external stacks and volume removal flags as supported).
- `POST /api/stacks/create/standalone/{string|file|repository}?endpointId=`.
- `POST /api/stacks/{id}/git?endpointId=` — update git settings (ref, auth, TLS, auto-update, env).
- `PUT /api/stacks/{id}/git/redeploy?endpointId=` — pull and redeploy.
- Detach from git, migrate/duplicate (`POST /api/stacks/{id}/migrate`), custom templates
  (`/api/custom_templates`), git ref listing (`/api/gitops/repo/refs` or equivalent).
- Stack webhook URL format for git auto-update by webhook.

If an endpoint differs or is missing in CE, the builder adapts to what CE actually offers and
notes the deviation; a feature CE lacks is dropped (not faked).

## Constraints

- Kotlin Multiplatform + Compose Multiplatform; Android, iOS, experimental wasm web. Same module
  split (`shared` UI-free with jvm tests; `composeApp` UI + view models).
- Any new library must support Android, iOS and wasmJs (e.g. the YAML parser); verify before
  choosing.
- CI as today (Linux build/tests on every push; iOS on main/tags/dispatch; Pages on main).
- Tests: shared API client tests with MockEngine for every stack endpoint used; YAML validation and
  structural check; diff; view-model tests for every safety rule above (protected block, typed
  delete, review before deploy, credentials never stored, unsaved-changes guard, session
  coordination).

## Decided by the designer (reviewable)

- Fifth bottom-bar tab "Stacks".
- Upload-created stacks are treated as web-editor stacks (as Portainer does).
- Ref picker only when CE exposes ref listing; else free text.
- Editor indentation: 2 spaces; Tab inserts spaces.
- Validation debounce ~300 ms.
- Compose diff: line-based unified diff, collapsible unchanged regions.

## Left to the builder

- YAML library choice (multiplatform incl. wasmJs) and the highlighter implementation.
- Exact layout of the stack detail and git settings screens within the mobile rules.
- How the stack list derives status and container membership (Portainer fields vs. container
  labels), as long as external stacks are shown.
- Phasing/plan order (suggest: read-only list/detail + start/stop/delete → editor + web-editor
  update → git settings + redeploy → create flows + templates + migrate).
