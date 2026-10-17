package dev.silas.portainer.core.recreate

import dev.silas.portainer.core.RecreateFixtures
import dev.silas.portainer.core.model.ImageRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecreatePlannerTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private val full = obj(RecreateFixtures.INSPECT_FULL)
    private val netmode = obj(RecreateFixtures.INSPECT_CONTAINER_NETMODE)
    private val autoremove = obj(RecreateFixtures.INSPECT_AUTOREMOVE)
    private val imageInspect = obj(RecreateFixtures.IMAGE_CONFIG)

    private val JsonObject.config get() = getValue("Config").jsonObject
    private val JsonObject.hostConfig get() = getValue("HostConfig").jsonObject
    private fun JsonObject.envOf() = config.getValue("Env").jsonArray.map { it.jsonPrimitive.content }
    private fun JsonObject.binds() = hostConfig.getValue("Binds").jsonArray.map { it.jsonPrimitive.content }

    private fun edits(inspect: JsonObject, image: String? = null, env: List<String>? = null, pull: Boolean = false, registryId: Int? = null) =
        RecreateEdits(image ?: inspect.config.getValue("Image").jsonPrimitive.content, env ?: inspect.envOf(), pull, registryId)

    private fun plan(inspect: JsonObject = full, imageConfig: JsonObject? = imageInspect, edits: RecreateEdits = edits(inspect)) =
        RecreatePlanner.plan(inspect, imageConfig, edits)

    /** Returns [this] with `Config.<key>` / `HostConfig.<key>` replaced. */
    private fun JsonObject.withSection(section: String, key: String, value: JsonElement) =
        JsonObject(this + (section to JsonObject(getValue(section).jsonObject + (key to value))))

    private fun strings(vararg s: String) = JsonArray(s.map { JsonPrimitive(it) })

    @Test
    fun preservesHostConfig() {
        val host = plan().createBody.hostConfig
        val original = full.hostConfig
        for (key in listOf("PortBindings", "RestartPolicy", "CapAdd", "CapDrop", "Devices", "LogConfig", "ExtraHosts", "Dns", "DnsOptions", "DnsSearch", "Mounts", "Ulimits", "Memory", "NanoCpus", "NetworkMode")) {
            assertEquals(original.getValue(key), host[key], key)
        }
        // Everything else is carried over untouched; only Binds grows (anonymous volume).
        assertEquals(original - "Binds", host - "Binds")
        assertEquals(listOf("/srv/app/config:/etc/app:ro", "app-uploads:/uploads"), plan().createBody.binds().take(2))
    }

    @Test
    fun appliesImageAndEnvEdits() {
        val p = plan(imageConfig = null, edits = edits(full, image = "ghcr.io/example/app:1.1", env = listOf("DB_HOST=db2", "NEW=1"), registryId = 4))
        assertEquals(JsonPrimitive("ghcr.io/example/app:1.1"), p.createBody["Image"])
        assertEquals(strings("DB_HOST=db2", "NEW=1"), p.createBody["Env"])
        assertEquals(ImageRef.parse("ghcr.io/example/app:1.1"), p.image)
        assertTrue(p.pull)
        assertEquals(4, p.registryId)
        assertEquals("ghcr.io/example/app:1.0" to "ghcr.io/example/app:1.1", p.changes.imageChange)
    }

    @Test
    fun pullOnlyWhenRequestedOrImageChanged() {
        assertFalse(plan().pull)
        assertNull(plan().changes.imageChange)
        assertTrue(plan(edits = edits(full, pull = true)).pull)
    }

    @Test
    fun dropsInheritedImageDefaults() {
        val p = plan()
        val body = p.createBody
        assertEquals(strings("DB_HOST=db", "TZ=Europe/Berlin", "LOG_LEVEL=info"), body["Env"])
        for (key in listOf("Cmd", "Entrypoint", "WorkingDir", "StopSignal")) assertFalse(key in body, key)
        assertEquals(full.config["Healthcheck"], body["Healthcheck"], "user healthcheck differs from image → kept")
        val labels = body.getValue("Labels").jsonObject
        assertFalse("org.opencontainers.image.version" in labels)
        assertFalse("org.opencontainers.image.title" in labels)
        assertEquals(JsonPrimitive("true"), labels["traefik.enable"])
        assertEquals(JsonPrimitive("shop"), labels["com.docker.compose.project"])
        assertEquals(obj("""{"9090/tcp": {}}"""), body["ExposedPorts"])
        assertEquals(JsonObject(emptyMap()), body["Volumes"])
        assertEquals(
            listOf(
                "Env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "Env APP_VERSION=1",
                "Cmd",
                "Entrypoint",
                "WorkingDir",
                "StopSignal",
                "Label org.opencontainers.image.title=app",
                "Label org.opencontainers.image.version=1.0",
                "ExposedPort 80/tcp",
                "Volume /data",
            ).sorted(),
            p.changes.droppedImageDefaults.sorted(),
        )
        assertFalse(RecreateWarning.ImageConfigUnavailable in p.warnings)
    }

    @Test
    fun userEditedEnvValueThatDiffersFromImageIsKept() {
        val env = full.envOf().map { if (it == "APP_VERSION=1") "APP_VERSION=2" else it }
        assertTrue("APP_VERSION=2" in plan(edits = edits(full, env = env)).createBody.getValue("Env").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun keepsCmdWhenEntrypointIsUserSet() {
        // Docker only inherits the image Cmd when the create request has no Entrypoint.
        val inspect = full.withSection("Config", "Entrypoint", strings("/custom.sh"))
        val p = plan(inspect)
        assertEquals(strings("/custom.sh"), p.createBody["Entrypoint"])
        assertEquals(strings("app", "serve"), p.createBody["Cmd"])
        assertFalse("Cmd" in p.changes.droppedImageDefaults)
    }

    @Test
    fun acceptsBareImageConfigObject() {
        assertEquals(plan().createBody, plan(imageConfig = imageInspect.getValue("Config").jsonObject).createBody)
    }

    @Test
    fun keepsEverythingWithoutImageConfig() {
        val p = plan(imageConfig = null)
        val configPart = p.createBody - "HostConfig" - "NetworkingConfig"
        assertEquals(full.config - "Hostname", configPart)
        assertTrue(p.changes.droppedImageDefaults.isEmpty())
        assertTrue(RecreateWarning.ImageConfigUnavailable in p.warnings)
    }

    @Test
    fun preservesUnknownFields() {
        val inspect = full
            .withSection("Config", "FutureConfigField", JsonPrimitive("x"))
            .withSection("HostConfig", "FutureHostField", obj("""{"a": [1, 2]}"""))
        val body = plan(inspect).createBody
        assertEquals(JsonPrimitive("x"), body["FutureConfigField"])
        assertEquals(obj("""{"a": [1, 2]}"""), body.hostConfig["FutureHostField"])
    }

    @Test
    fun hostnameRules() {
        assertFalse("Hostname" in plan().createBody, "generated hostname (short id) is dropped")
        val custom = full.withSection("Config", "Hostname", JsonPrimitive("myhost"))
        assertEquals(JsonPrimitive("myhost"), plan(custom).createBody["Hostname"])
    }

    @Test
    fun reattachesAnonymousVolumeByName() {
        assertEquals(
            listOf(
                "/srv/app/config:/etc/app:ro",
                "app-uploads:/uploads",
                "9b1e0d7c4a3f2e8b5c6d1a0f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d:/data",
            ),
            plan().createBody.binds(),
            "named volumes (Binds) and --mount volumes (HostConfig.Mounts) are not duplicated",
        )
        // Binds null in inspect, read-only anonymous volume.
        assertEquals(
            listOf("f1e2d3c4b5a6978877665544332211ffeeddccbbaa99887766554433221100ff:/data:ro"),
            plan(autoremove, null).createBody.binds(),
        )
    }

    @Test
    fun splitsPrimaryAndExtraNetworks() {
        val p = plan()
        val endpoints = p.createBody.getValue("NetworkingConfig").jsonObject.getValue("EndpointsConfig").jsonObject
        assertEquals(setOf("frontend"), endpoints.keys)
        assertEquals(
            obj("""{"Links": ["shop-db-1:db"], "Aliases": ["web"], "DriverOpts": {"com.docker.network.endpoint.sysctls": "net.ipv4.conf.IFNAME.log_martians=1"}}"""),
            endpoints["frontend"],
        )
        assertEquals(setOf("backend"), p.extraNetworks.keys)
        assertEquals(obj("""{"IPAMConfig": {"IPv4Address": "172.20.0.10"}, "Aliases": ["app"]}"""), p.extraNetworks["backend"])
    }

    @Test
    fun primaryNetworkFallsBackToBridgeOrFirst() {
        val default = autoremove.withSection("HostConfig", "NetworkMode", JsonPrimitive("default"))
        val endpoints = plan(default, null).createBody.getValue("NetworkingConfig").jsonObject.getValue("EndpointsConfig").jsonObject
        assertEquals(setOf("bridge"), endpoints.keys)

        val unknown = full.withSection("HostConfig", "NetworkMode", JsonPrimitive("gone"))
        val p = plan(unknown)
        assertEquals(setOf("backend"), p.createBody.getValue("NetworkingConfig").jsonObject.getValue("EndpointsConfig").jsonObject.keys)
        assertEquals(setOf("frontend"), p.extraNetworks.keys)
    }

    @Test
    fun containerNetworkModeOmitsNetworkingAndHostname() {
        val p = plan(netmode, null)
        val body = p.createBody
        assertFalse("NetworkingConfig" in body)
        assertTrue(p.extraNetworks.isEmpty())
        for (key in listOf("Hostname", "Domainname", "MacAddress", "ExposedPorts")) assertFalse(key in body, key)
        assertEquals(netmode.hostConfig["NetworkMode"], body.hostConfig["NetworkMode"])
        assertFalse(p.wasRunning)
    }

    @Test
    fun hostAndNoneModesOmitNetworking() {
        for (mode in listOf("host", "none")) {
            val p = plan(full.withSection("HostConfig", "NetworkMode", JsonPrimitive(mode)))
            assertFalse("NetworkingConfig" in p.createBody, mode)
            assertTrue(p.extraNetworks.isEmpty(), mode)
        }
    }

    @Test
    fun autoRemoveProducesWarning() {
        assertTrue(RecreateWarning.AutoRemove in plan(autoremove, null).warnings)
        assertFalse(RecreateWarning.AutoRemove in plan().warnings)
        assertEquals(JsonPrimitive(true), plan(autoremove, null).createBody.hostConfig["AutoRemove"])
    }

    @Test
    fun stackLabelProducesWarning() {
        assertTrue(RecreateWarning.StackMember("shop") in plan().warnings)
        assertTrue(plan(autoremove, null).warnings.none { it is RecreateWarning.StackMember })
    }

    @Test
    fun alwaysIncludesConnectionDependency() {
        assertTrue(RecreateWarning.ConnectionDependency in plan().warnings)
        assertTrue(RecreateWarning.ConnectionDependency in plan(netmode, null).warnings)
        assertTrue(RecreateWarning.ConnectionDependency in plan(autoremove, null).warnings)
    }

    @Test
    fun stripsLeadingSlashFromName() {
        assertEquals("app-web", plan().containerName)
        assertEquals("sidecar", plan(netmode, null).containerName)
        assertTrue(plan().wasRunning)
    }

    @Test
    fun envDiff() {
        val env = listOf(
            "DB_HOST=db2",
            "TZ=Europe/Berlin",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "APP_VERSION=1",
            "NEW_FLAG=on",
            "BARE",
        )
        val c = plan(edits = edits(full, env = env)).changes
        assertEquals(listOf("NEW_FLAG=on", "BARE"), c.envAdded)
        assertEquals(listOf("DB_HOST=db" to "DB_HOST=db2"), c.envChanged)
        assertEquals(listOf("LOG_LEVEL=info"), c.envRemoved)
    }

    @Test
    fun nullConfigSectionsAreTolerated() {
        val inspect = netmode.withSection("Config", "Env", JsonNull).withSection("Config", "Labels", JsonNull)
        val p = plan(inspect, obj("""{"Config": {"Env": null, "Labels": null, "Cmd": null}}"""), RecreateEdits("alpine:3.20", emptyList(), false, null))
        assertEquals(JsonArray(emptyList()), p.createBody["Env"])
        assertTrue(p.changes.envAdded.isEmpty() && p.changes.envRemoved.isEmpty())
    }
}
