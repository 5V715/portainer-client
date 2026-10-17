# Portainer Client — Design

Date: 2026-10-17
Status: draft for review

## Intent

A native, mobile-first client for [Portainer](https://www.portainer.io/) so the
owner can maintain their own infrastructure from a phone. The Portainer web UI
works, but it is not usable on mobile. This app brings the day-to-day hands-on
work to a native UI, starting with Docker container management.

The defining workflow is:

> open a container → check its logs → change an env var or the image →
> recreate it

If that loop is fast and safe on a phone, v1 is a success.

## Users and environment

- Single user: the owner, managing their own servers. No teams, roles, or
  multi-user features.
- Portainer is reachable from the phone via the home network or a VPN. It is
  exposed but firewalled; the app never assumes public internet reachability.
- Transport is **plain HTTP**. Authentication is a **Portainer API access
  token** (sent as the `X-API-Key` header). No username/password login in v1.
- Mostly Docker environments. Containers are a mix of standalone containers and
  containers belonging to compose stacks.

## Goals (v1)

1. **Servers**
   - Add a Portainer server: display name, base URL (e.g. `http://192.168.1.10:9000`),
     API key. Validate on save by calling the API.
   - Save multiple servers; switch between them. Edit and delete servers.
   - After selecting a server, pick an environment (Portainer "endpoint").
     Only Docker environments are listed/selectable in v1; others are shown
     disabled or hidden.
2. **Container list** (per environment)
   - Show name, state (running / exited / paused / restarting / unhealthy via
     health status), image, and a stack badge when applicable.
   - Search by name/image; filter by state and by stack.
   - Pull-to-refresh.
   - Quick actions: start, stop, restart. Remove behind an explicit
     confirmation.
3. **Container detail**
   - Overview: image, status/health, created/started times, ports, volumes /
     mounts, networks, env vars, labels, restart policy.
   - **Logs**: live follow (streaming), timestamps toggle, "last N lines"
     (tail) selector, in-log text search, stdout/stderr distinguishable,
     auto-scroll that pauses when the user scrolls up.
   - **Edit & recreate**: edit env vars (add / change / remove) and the image
     reference (e.g. change tag). Option: "pull latest image" before recreate.
     Show a summary of what will change, require confirmation, then recreate.
   - **Stack warning**: if the container belongs to a compose stack, show a
     clear badge on the detail screen, and before recreate warn that the
     change will be lost on the next stack redeploy. The user may proceed.

## Non-goals (v1, planned later)

- Stack editing (compose file / stack env vars) and redeploy — **next
  priority** after v1, since it is the correct way to change stack containers.
- Container console / exec shell.
- Images, volumes, networks management screens.
- Swarm and Kubernetes environments.
- Push notifications / background monitoring.
- Username/password login, OAuth, HTTPS certificate management.

The code should be structured so these can be added without rework (e.g. the
API layer is organised by Portainer resource, and environment type is modelled
even though only Docker is handled).

## Anti-goals (would make v1 a failure even if it "works")

- **Recreate loses configuration.** A recreated container must keep everything
  the user did not change: volumes/mounts, networks (including aliases and
  static IPs where possible), port bindings, labels, restart policy, command,
  entrypoint, working dir, user, healthcheck, resource limits, capabilities,
  devices, privileged flag, log config, hostname, extra hosts, DNS, etc.
  Silently dropping any of these is the worst possible bug.
- **Recreate leaves the user with no container.** If creating the new
  container fails, the original must be restored (or never removed). See the
  recreate procedure below.
- Destructive actions (remove, recreate) without confirmation.
- A UI that is just the web UI squeezed onto a phone. It should feel native:
  thumb-reachable actions, readable logs, no horizontal scrolling of tables.
- Leaking the API key (logs, crash reports, plain-text storage on mobile).

## Constraints and decisions made

- **Tech stack (owner's decision):** Kotlin Multiplatform + Compose
  Multiplatform.
- **Targets (owner's decision):** Android, iOS, and an **experimental** web
  build using Kotlin/Wasm (`wasmJs`).
- **CI (owner's decision):** builds run on GitHub Actions.
- **Repo:** `5V715/portainer-client`, currently private and empty.

### Decisions made by the designer (reviewable)

- **UI:** Material 3, light + dark theme following the system setting.
  Nearly all UI lives in `commonMain`; platform code is limited to engines,
  secure storage, and platform config.
- **CI cost control:** the repo is private, so macOS minutes are limited
  (~200 effective macOS minutes/month on the free plan, 10× multiplier).
  - Every push / PR: build + unit tests for Android and wasmJs on Linux
    runners; upload the debug APK and the wasm distribution as artifacts.
  - iOS: build (simulator/framework compile, unsigned) only on pushes to
    `main`, version tags, and manual `workflow_dispatch`.
  - Signing, TestFlight, Play Store publishing are out of scope for v1. Making
    the repo public would make macOS runners free; that is the owner's call.
- **Cleartext HTTP:** Android must allow cleartext traffic (network security
  config); iOS must set ATS exceptions (`NSAllowsArbitraryLoads` or
  `NSAllowsLocalNetworking`, whichever reliably covers LAN IPs and VPN hosts).
- **Web build limitation (documented, not solved in v1):** a browser can only
  call Portainer directly if Portainer returns CORS headers for the page's
  origin, and an HTTPS-served page cannot call an HTTP API (mixed content). The
  wasm build is therefore expected to work only when served over HTTP from an
  origin Portainer accepts (or behind a reverse proxy the user sets up). The
  README must say this plainly. No proxy server is built for v1.
- **API key storage:** Android — encrypted with an Android Keystore-backed key;
  iOS — Keychain; wasm — browser `localStorage` (documented as not secure).
  Non-secret server metadata (name, URL, last environment) may use ordinary
  settings storage.
- **Recreate approach:** implemented client-side through Portainer's Docker
  proxy (`/api/endpoints/{id}/docker/...`), not by relying on a Portainer-only
  recreate endpoint, because env/image edits are needed and the full config
  must be preserved. (If Portainer offers a native recreate endpoint, it may be
  used for the no-edit "recreate / pull latest" case, at the builder's
  discretion.)

### Recreate procedure (required behaviour)

1. `GET .../containers/{id}/json` — full inspect.
2. Build a create request from the inspect result: `Config` (with the user's
   edits to `Env` and `Image`) + `HostConfig` + `NetworkingConfig` derived from
   `NetworkSettings.Networks` (endpoint config: aliases, IPAM config, links).
   Strip read-only / runtime-only fields that Docker rejects.
3. If "pull latest image" (or the image changed): pull the image first
   (`POST .../images/create`). If the pull fails, abort — nothing has been
   touched.
4. Stop the old container (if running) and **rename** it to a temporary name
   (e.g. `<name>-old-<timestamp>`).
5. Create the new container with the original name; connect any additional
   networks beyond the first.
6. Start it (only if the original was running).
7. On success: remove the old container. On failure at steps 5–6: remove the
   partially created container, rename the old one back, restart it if it was
   running, and show the error.
8. Show progress for each step in the UI.

### Image defaults when the image changes (designer's call)

Docker inspect mixes user settings with defaults baked into the image. When the
image reference changes (or "pull latest" yields a different image ID), the
create request drops any of `Env` entries, `Cmd`, `Entrypoint`, `WorkingDir`,
`User`, `Healthcheck`, `Labels`, `ExposedPorts`, `Volumes` whose value equals
the **old image's** config (`GET .../images/{oldImageId}/json`), so the new
image's defaults apply. Values that differ from the old image's defaults are
user settings and are kept. (Same approach as Watchtower.) If the old image
can no longer be inspected, keep everything and warn in the change summary.
The change summary lists inherited values that will be dropped.

### Self-dependency protection (designer's call)

Recreating, stopping, or removing Portainer itself or the Portainer agent
would cut the app off mid-operation and make rollback impossible. Containers
whose image matches `portainer/portainer*`, `portainer/agent*` (or the CE/EE
equivalents) are **blocked** from recreate / stop / remove in the app, with an
explanation. Every recreate confirmation also carries a general reminder that
if this container is something the phone needs to reach Portainer (VPN,
reverse proxy), connection loss will prevent rollback. User-flagged
"protected" containers are a later feature.

### Environment types (designer's call)

v1 supports Docker environments connected via local socket/API and the
Portainer Agent. Edge Agent environments (standard and async) are excluded in
v1 (shown disabled with "not supported yet") because Edge tunnels don't
reliably support streamed logs and async Edge has no live Docker proxy.

### Stack detection

A container belongs to a stack if it has the label
`com.docker.compose.project`. The badge shows that project name. (Portainer's
stack API may be used to match it to a Portainer-managed stack; optional in v1.)
The recreate warning must say the edit will be undone by any stack redeploy,
including Portainer's own git auto-update and webhooks, not only a manual
redeploy.

### Logs

Use `GET .../containers/{id}/logs?stdout=1&stderr=1&timestamps=...&tail=N&follow=1`.
For containers without a TTY, Docker returns a multiplexed stream (8-byte frame
header: stream type + length); it must be demultiplexed to separate stdout and
stderr. TTY containers return a raw stream. Streaming must work on Android and
iOS; on wasm, fall back to polling if streaming is not supported by the
engine. Keep a bounded in-memory buffer (e.g. last 5,000 lines) to avoid
unbounded memory growth on long follows.

## Quality bar

- Unit tests in `commonTest` for: API client (with Ktor `MockEngine`), log
  stream demultiplexing, inspect → create-request mapping (this is the
  highest-risk code; test it against real inspect JSON fixtures covering
  volumes, multiple networks, ports, healthcheck, etc.), recreate procedure
  including rollback paths, and view-model state.
- CI must be green on every push for Android + wasm; iOS on `main`.
- README: what the app does, how to create a Portainer API key, how to build
  each target, the HTTP/cleartext note, and the web build limitations.

## Left to the builder

- Library choices beyond the stack (expected: Ktor client with per-platform
  engines, kotlinx.serialization, kotlinx.coroutines, Compose Multiplatform
  navigation + lifecycle ViewModel, a lightweight DI approach or manual wiring,
  a multiplatform settings library). Use current stable versions.
- Module layout (single `composeApp` module vs. split `shared`/`app` modules).
- Package name (suggested: `dev.silas.portainer` — adjust if owner objects).
- Exact screen layouts and navigation structure, within the goals above.
- Minimum Portainer version supported (target current Portainer CE 2.x; note
  the version tested against in the README).
- Minimum Android SDK and iOS deployment target (sensible current defaults).
