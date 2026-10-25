# Portainer Client v1.1 — Design

Date: 2026-10-25
Status: awaiting owner approval
Builds on: `docs/superpowers/specs/2026-10-17-portainer-client-design.md` (v1). Everything there
still applies (HTTPS + API key, Docker environments only, protection of Portainer's own
containers, recreate rollback guarantees, Portainer-blue design) unless changed here.

## Intent

v1 covers the owner's core loop (logs → edit env/image → recreate). The owner now wants the app
closer to Portainer's own web UI:

1. **Quick Recreate** split from editing: same settings, optional "Pull latest image".
2. **A full Edit / Duplicate form** like Portainer's (owner: "Full form like Portainer, ports
   volumes networks too"), able to replace the container or deploy a copy.
3. **Views for all resources**: containers, images, volumes, networks (owner confirmed images and
   networks too).
4. **Pruning** of unused resources, including an explicitly marked option to prune unused
   *named* volumes (owner: "offer named volumes as a marked option").

Approach and order were chosen by the owner: the edit form works **over the raw container
settings (approach A)**, shipped in **two phases**.

## Approaches considered

- **A. Form over raw settings (chosen).** The form exposes typed fields for what it covers and
  writes edits back into the container's original inspect JSON; everything it doesn't show is
  kept byte-for-byte. Replace / Copy / New all produce a create request through the same path,
  and Replace reuses the existing reviewed recreate planner + executor. Trade-off: the merge
  needs careful tests (a removed port must disappear, an untouched field must stay identical).
- **B. Complete typed model (rejected).** Simpler forms, but every setting not modelled is
  silently dropped on Replace — violates v1's worst-bug anti-goal.
- **Delivery: two phases (chosen)** vs one release. Phase 1 is low-risk and ships an APK sooner;
  the editor, the riskiest part, gets its own review round.

## Phase 1 — Resources, cleanup, quick Recreate

### Navigation (designer's call)
Inside an environment, a bottom navigation bar with four tabs: **Containers, Images, Volumes,
Networks**. The existing container list becomes the first tab. Registries and About stay reachable
as today. Back from any tab goes to the environment list.

### Common list behaviour (designer's call)
Search field; pull-to-refresh; an **"Unused"** badge on items no container uses; tap → detail
screen showing the item's properties and **which containers use it**, each linking to that
container's detail. Stack membership (label `com.docker.compose.project`) shown where present.

### Images
- List: repo:tag(s) (or `<none>` for dangling), short ID, size, created date, Unused badge.
- Detail: tags, ID, size, created, architecture/OS, env/cmd/entrypoint/exposed ports/labels from
  the image config, containers using it.
- **Remove** one image (confirm). If it has more than one tag or is used by a stopped container,
  offer **Force** (Docker `force=true`) with an explanation. Images used by a running container
  can't be removed (Docker refuses; show its message).
- **Pull image** by reference, choosing a registry (same matching as recreate: auto-match by host,
  "None (public)", or a configured registry), with progress text from the pull stream.

### Volumes
- List: name (anonymous volumes shown with a short ID and an "anonymous" tag), driver, created,
  Unused badge.
- Detail: driver, mountpoint, scope, labels, options, created, containers using it (with mount
  path and read-only flag).
- **Remove** one volume — only offered when unused (confirm, text says the data is deleted).
- **Create volume**: name (optional — empty = Docker generates one), driver (default `local`),
  driver options and labels as key/value rows.

### Networks
- List: name, driver, scope, subnet(s), number of connected containers, Unused badge.
- Detail: ID, driver, scope, internal/attachable flags, IPAM subnets/gateways, labels, connected
  containers with their IPs.
- **Remove** one network — only when no containers are connected; Docker's predefined networks
  (`bridge`, `host`, `none`) are never removable.
- **Create network**: name, driver (`bridge` default, `macvlan`, `ipvlan`, `overlay` shown only if
  the engine is in Swarm mode — otherwise hidden), optional subnet/gateway/IP range, internal
  flag, attachable flag, driver options, labels.

### Cleanup (prune)
A **"Clean up"** action (top-bar overflow) on each tab. Every cleanup first shows a **preview**
of exactly what will be deleted, then requires confirmation; after it runs, show what Docker
reports deleted and the space reclaimed.
- **Containers:** all stopped containers (Docker `containers/prune`).
- **Images:** dangling images by default (`images/prune`); option **"Also remove unused tagged
  images"** (`filters={"dangling":["false"]}`).
- **Networks:** unused networks (`networks/prune`).
- **Volumes:** unused **anonymous** volumes by default (`volumes/prune`); option
  **"Also remove unused named volumes"** (`filters={"all":["true"]}`), visually marked as
  dangerous (error colour, warning text: "Named volumes often hold databases and app data.
  Everything in them is deleted permanently."), and listed separately in the preview. Requires a
  second explicit confirmation step.
- **Execution (designer's call): delete exactly the previewed items one by one** (container/image/
  volume/network DELETE calls, never `force`), instead of calling Docker's prune endpoints. This
  guarantees nothing outside the preview is deleted even if state changed in between. Items that
  fail (e.g. became in use) are reported, not retried; reclaimed space is summed from the
  previewed sizes of the items actually deleted. The prune filters named above are therefore
  descriptive of *what is selected*, not API calls.
- The preview is computed client-side from the current lists (stopped containers; images not used
  by any container and, for the default, untagged; networks with no containers and not
  predefined; volumes not mounted by any container, split into anonymous / named). Sizes are
  shown where the API provides them; the builder may use `GET /system/df` for volume sizes if it
  is cheap enough.
- **"Unused" (designer's call):** an image, volume or network is unused when **no container,
  running or stopped**, references it. Default image cleanup selects unused images that are
  untagged (dangling); the tagged option adds unused tagged images.
- **Engine facts (designer's call):** add `GET /version` (ApiVersion) and `GET /info`
  (Swarm LocalNodeState) per environment, fetched when the environment's tabs open and cached
  for that session; used to show the overlay network driver only in Swarm mode. (Because
  cleanup deletes items individually, no API-version gate is needed for named volumes.)

### Protection (extends v1)
- Images whose reference matches the protected pattern (`portainer/portainer*`,
  `portainer/agent*`, with optional `docker.io/` / `registry-1.docker.io/` / `index.docker.io/`
  prefix) can't be removed from the app.
- Volumes and networks used by a protected container can't be removed from the app.
- Cleanups (designer's call): items are excluded from every preview and deletion if they are
  protected — protected containers (even when stopped), protected images, volumes and networks
  mounted/connected by any protected container (running or stopped), and any volume named
  `portainer_data` (Portainer's default data volume). Excluded items are listed in the preview as
  "Kept (Portainer)".

### Quick Recreate
- On container detail, a **Recreate** action opens a confirm sheet with a **"Pull latest image"**
  switch (default on, like Portainer), the registry used for the pull (auto-matched, changeable),
  and the existing warnings (stack member, auto-remove, connection dependency).
- It recreates with exactly the same settings (edits = current image + current env), using the
  existing `RecreatePlanner`, `Recreator` and app-scoped `RecreateSessions`, and the existing
  progress / success / failure screens.
- Blocked for protected containers (same message as v1).
- In phase 1 the existing "Edit & recreate" screen stays as is; phase 2 replaces it with the full
  form.

## Phase 2 — Full Edit / Duplicate / New form

### Entry points
- Container detail → **Edit / Duplicate** (replaces v1's "Edit & recreate"): form pre-filled from
  the container's inspect.
- Containers tab → **"+" New container**: the same form, empty.

### Sections (owner: like Portainer, incl. ports, volumes, networks)
1. **Image**: reference, registry (auto-match / none / configured), "Pull image" switch
   (default on for New and when the image changed).
2. **Name**; **Command** and **Entrypoint** (shell-like single-line input, split into the JSON
   array; show existing array values faithfully), **Working dir**, **User**.
3. **Ports**: rows of host IP (optional), host port (optional = random), container port,
   protocol TCP/UDP; "Publish all exposed ports" switch.
4. **Volumes / mounts**: rows of type (bind / named volume / tmpfs), source (host path, or volume
   picker incl. "create new"), container path, read-only.
5. **Networks**: primary network (picker, incl. host / none / bridge), aliases, optional fixed
   IPv4/IPv6; additional networks as extra rows with the same fields. `container:` network mode
   shown read-only if present.
6. **Environment variables** (as today), **Labels** (key/value rows).
7. **Restart policy**: no / always / on-failure (+ max retries) / unless-stopped.
8. **Resources**: memory limit, memory reservation, CPU limit (cores, maps to NanoCPUs), CPU
   shares — empty = unlimited / unchanged.
9. **Advanced (read-only summary)**: devices, capabilities, privileged, log config, healthcheck,
   DNS, extra hosts — shown so the user knows they're kept; not editable in this version.

### How it finishes
- **Replace** (edit mode only): builds the edited inspect-shaped settings, then plans and runs
  through the existing recreate planner + executor (same review screen: change summary incl. the
  new sections, warnings, re-inspect at review, rollback guarantees). The change summary must
  list port / mount / network / label / restart / resource changes, not only image and env.
- **Deploy as copy** (edit mode): creates a **new** container from the edited settings; the
  original is untouched. Required: a new name (pre-filled `<name>-copy`).
- **Create** (new mode): creates the container; "Start after creating" switch (default on).

### Copy / New safety (owner-approved behaviour, details designer's call)
Before deploying a copy or a new container, check and report conflicts in the review step:
- **Name** already used by another container → block until changed.
- **Host ports** already published by any container (running or stopped) on the same host IP /
  protocol → warn; for a running holder, block (Docker would fail on start).
- **Fixed IPs** already in use on that network → block until changed.
- **Copy-specific transforms:** don't copy a generated hostname (equal to the old short ID); drop
  per-endpoint generated MAC addresses (only keep a MAC the user explicitly typed); **anonymous
  volumes are NOT reattached** (the copy gets fresh ones) — named volumes and bind mounts are
  shared, and the review says so ("Both containers will use volume X").
- Copies/new containers of protected images are allowed (creating doesn't touch Portainer).

### How the form uses the planner (designer's calls)
- The form writes edits into an **inspect-shaped JSON** (`Config`, `HostConfig`,
  `NetworkSettings.Networks`) copied from the original inspect, then calls the planner with it.
- `RecreatePlanner` gains an options parameter: `mode` (Replace / Copy) and the set of
  **user-edited fields**. Fields the user edited are exempt from image-default stripping (a
  deliberately blanked Command stays blank; a kept value equal to the image default is fine
  either way), and mounts the user deleted are not reattached as anonymous volumes.
- **Replace:** planner as today with the edited JSON; anonymous volumes still reattached by name
  for rows the user kept.
- **Copy:** planner in Copy mode: no anonymous-volume reattachment, generated hostname dropped,
  per-endpoint MACs dropped unless typed in the form, name from the form.
- **New:** a separate small builder creates the create body from the form only, starting from a
  minimal skeleton (`Image`, `Env`, `Cmd`/`Entrypoint` if given, `Labels`, `ExposedPorts`,
  `HostConfig` with `RestartPolicy {Name:"no"}`, binds/mounts/ports/limits from the form,
  `NetworkingConfig` for the primary network). No planner, no image-default stripping.
- **Baseline for the "unedited" test:** an unedited form with the same image must produce a create
  body identical to `RecreatePlanner.plan(inspect, oldImageConfig, RecreateEdits(currentImage,
  currentEnv, pull, registry))`.
- **Pulling:** the Recreator already pulls inside its flow (`Pull` step) when `plan.pull`; Replace
  sets pull when the switch is on or the image changed. Copy/New pull before create with the same
  pull API and registry handling, shown as a step.

### Merge rules (approach A)
- **Mounts keep their original representation (designer's call):** each mount row remembers
  whether it came from `HostConfig.Binds` (string) or `HostConfig.Mounts` (object); edited rows
  are written back in that same form; untouched rows are not rewritten. New rows: bind and named
  volume → `Binds` strings; tmpfs → `HostConfig.Mounts` entry of type `tmpfs`.
- The form model holds typed values for the fields it covers plus the original inspect JSON.
- On save, write each covered field back into the JSON: replaced when edited, **removed** when the
  user deleted all rows, untouched (identical JSON) when not edited.
- Fields not covered by the form are never modified.
- Tests must cover: unedited form → create body identical to v1 recreate's for the same
  container; removing the last port removes `PortBindings`/`ExposedPorts` entries; each section's
  round trip.

## Non-goals (v1.1)
Stacks (editing/redeploy — still the next priority after this), Swarm services, Kubernetes,
container console/exec, building/pushing images, browsing volume contents, connecting/disconnecting
existing containers to networks from the network screen, editing advanced settings (devices,
caps, log config, healthcheck), image import/export.

## Anti-goals
- A cleanup that deletes something the preview didn't show, or deletes named volumes without the
  marked option and second confirmation.
- Replace dropping any setting the user didn't change (v1 anti-goal, now across more fields).
- A copy that takes over the original's resources (ports, IP, name, anonymous volumes) or breaks
  the original.
- Removing or pruning Portainer's own container, image, or data.

## Constraints
- Same stack, modules and patterns as v1 (shared = API + logic with tests in commonTest; composeApp =
  screens + view models; MockEngine tests; CI on every push).
- Docker Engine API through Portainer's proxy (`/api/endpoints/{id}/docker/...`).
- Each phase ends with green CI (linux + ios via dispatch) and a debug APK for the owner.

## Left to the builder
Exact layouts, which shared files the new APIs live in (one file per resource suggested),
the shell-splitting implementation for Command/Entrypoint, whether to use `/system/df` for sizes,
pagination (lists are expected to be small), icon choices within material-icons-core.
