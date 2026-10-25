# Portainer Client

A Kotlin Multiplatform + Compose Multiplatform client for managing Docker containers through a [Portainer](https://www.portainer.io/) server. Targets Android, iOS and an experimental web build (wasmJs). It talks to Portainer over HTTPS using an API key.

## What it does

- Multiple Portainer servers (URL + API key, HTTPS only).
- Docker environments: local and agent. Edge and Kubernetes environments are listed as "Not supported yet".
- Container list with search, state filters and stack filter; start, stop, restart and remove.
- Container detail: overview and live logs (tail, search, timestamps).
- Environment tabs for Containers, Images, Volumes and Networks, each with a detail screen. Pull and remove images; create and remove volumes and networks.
- Clean up on each tab: a preview shows exactly what will be deleted, and items are deleted one by one. Portainer's own resources are kept. Unused named volumes are only offered through a clearly marked option and need a second confirmation. Images and volumes show their sizes and a storage total: "Up to X can be recovered" for images (layers can be shared with images that stay), the exact total for volumes.
- Container editor (**Edit / Duplicate** on the container detail): a full form for image, name and command, ports, volumes, networks (incl. fixed IPs), environment variables, labels, restart policy and resources. Settings the form doesn't edit (devices, capabilities, log driver, healthcheck, DNS, extra hosts…) are shown read-only under Advanced and kept as they are. Pull latest and private registry auth via Portainer.
  - **Replace** recreates the container with the edits: a change summary with warnings, and a rollback that never leaves you without a container.
  - **Deploy as copy** creates a new container next to the original and never touches the original. The copy gets fresh anonymous volumes and doesn't take the original's hostname or MAC address. Before deploying it checks for a name already in use, host ports and fixed IPs held by other containers, and named volumes the containers would share.
- New container: the **+** button on the container list opens the same form, empty.
- Quick Recreate (**Recreate** on the container detail): recreates a container with its current settings, with "Pull latest image" on by default.
- Registries management (list, add, edit, remove registries configured in Portainer).
- Portainer's own containers are protected from stop, remove and recreate, since that would cut off the connection.

Tested against Portainer CE 2.x (the API shapes used). The registry API field names have not yet been verified against a live server.

## Create a Portainer API key

In Portainer: **My account → Access tokens → Add access token**. Copy the token when it is shown; Portainer displays it only once.

The key is stored in the Android Keystore (Android), the Keychain (iOS) or browser `localStorage` (web, which is **not secure**).

## Add a server

Accepted URL formats:

- `https://host` or `https://host:port`
- a bare `host` (https is assumed)
- a sub-path is kept for Portainer behind a reverse proxy (`https://host/portainer`)
- a trailing `/` or `/api` is stripped, and so is a pasted browser URL's `#!/…` fragment or `?…` query
  (`https://host:9443/#!/2/docker/containers` becomes `https://host:9443`)
- `http://` is rejected: Portainer must be reached over HTTPS

## Build

```sh
# Shared tests (JVM) and wasm tests
./gradlew :shared:jvmTest :shared:wasmJsTest

# App and ViewModel tests
./gradlew :composeApp:testDebugUnitTest

# Android debug APK
./gradlew :composeApp:assembleDebug

# Web distribution (experimental)
./gradlew :composeApp:wasmJsBrowserDistribution

# iOS (macOS only)
./gradlew :shared:iosSimulatorArm64Test :composeApp:linkDebugFrameworkIosSimulatorArm64
```

Android builds need the Android SDK (`sdk.dir` in `local.properties`).

## CI artifacts

Every push builds the `android-debug-apk` and `web` artifacts (Actions → run → Artifacts). iOS builds (unsigned, simulator) run on `main`, `v*` tags and manual dispatch. iOS signing, TestFlight and store publishing are out of scope.

## Web build on GitHub Pages

CI deploys the web build to GitHub Pages on pushes to `main` once Pages is enabled for the repository (public repo or paid plan).

The browser enforces CORS, so Portainer (or a reverse proxy in front of it) must allow the Pages origin and answer the `OPTIONS` preflight:

- `Access-Control-Allow-Origin: https://<owner>.github.io`
- `Access-Control-Allow-Headers: X-API-Key, Content-Type`
- `Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS`

Caddy:

```caddy
portainer.example.com {
    @preflight method OPTIONS
    header Access-Control-Allow-Origin "https://<owner>.github.io"
    header Access-Control-Allow-Headers "X-API-Key, Content-Type"
    header Access-Control-Allow-Methods "GET, POST, PUT, DELETE, OPTIONS"
    respond @preflight 204
    reverse_proxy portainer:9000
}
```

nginx:

```nginx
location / {
    add_header Access-Control-Allow-Origin "https://<owner>.github.io" always;
    add_header Access-Control-Allow-Headers "X-API-Key, Content-Type" always;
    add_header Access-Control-Allow-Methods "GET, POST, PUT, DELETE, OPTIONS" always;
    if ($request_method = OPTIONS) { return 204; }
    proxy_pass http://portainer:9000;
}
```

Check the preflight with:

```sh
curl -i -X OPTIONS https://YOUR-PORTAINER/api/endpoints \
  -H "Origin: https://5v715.github.io" \
  -H "Access-Control-Request-Method: GET" \
  -H "Access-Control-Request-Headers: x-api-key"
```

Chrome may ask once for local-network permission when the host resolves to a private IP.

## Feed my Claude

🍺 Feed my Claude – a tip keeps the tokens flowing. Entirely optional.

- [PayPal](https://www.paypal.com/donate/?hosted_button_id=M6D3G2TRTEQXG)
- [Ko-fi](https://ko-fi.com/silasschwarz)
- [GitHub Sponsors](https://github.com/sponsors/5V715)
