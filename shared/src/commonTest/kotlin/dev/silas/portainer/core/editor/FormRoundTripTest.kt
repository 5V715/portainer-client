package dev.silas.portainer.core.editor

import dev.silas.portainer.core.RecreateFixtures
import dev.silas.portainer.core.recreate.RecreateEdits
import dev.silas.portainer.core.recreate.RecreatePlanner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormRoundTripTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private val full = obj(RecreateFixtures.INSPECT_FULL)
    private val netmode = obj(RecreateFixtures.INSPECT_CONTAINER_NETMODE)
    private val autoremove = obj(RecreateFixtures.INSPECT_AUTOREMOVE)
    private val compose = obj(RecreateFixtures.INSPECT_COMPOSE_ANON)
    private val imageInspect = obj(RecreateFixtures.IMAGE_CONFIG)

    @Test
    fun unedited_form_merges_to_identical_json_for_every_fixture() {
        for (inspect in listOf(full, netmode, autoremove, compose)) {
            val form = FormParser.fromInspect(inspect)
            val (merged, fields) = FormMerger.merge(inspect, form, form)
            assertEquals(inspect, merged)
            assertEquals(emptySet(), fields)
        }
    }

    @Test
    fun reparsed_form_is_equal_and_merges_to_identical_json() {
        for (inspect in listOf(full, netmode, autoremove, compose)) {
            val (merged, fields) = FormMerger.merge(inspect, FormParser.fromInspect(inspect), FormParser.fromInspect(inspect))
            assertEquals(inspect, merged)
            assertEquals(emptySet(), fields)
        }
    }

    @Test
    fun unedited_form_gives_the_same_create_body_as_v1_recreate() {
        val form = FormParser.fromInspect(full)
        val (merged, _) = FormMerger.merge(full, form, form)
        val edits = RecreateEdits(form.image, form.env, pullImage = false, registryId = null)
        assertEquals(
            RecreatePlanner.plan(full, imageInspect, edits).createBody,
            RecreatePlanner.plan(merged, imageInspect, edits).createBody,
        )
    }

    @Test
    fun parses_the_full_fixture() {
        val form = FormParser.fromInspect(full)
        assertEquals("ghcr.io/example/app:1.0", form.image)
        assertEquals("app-web", form.name)
        assertEquals(listOf("app", "serve"), form.command)
        assertEquals(listOf("/entrypoint.sh"), form.entrypoint)
        assertEquals("/app", form.workingDir)
        assertEquals("", form.user)
        assertEquals(
            listOf(PortRow("", "8080", 80, "tcp"), PortRow("127.0.0.1", "9090", 9090, "tcp")),
            form.ports,
        )
        assertFalse(form.publishAll)
        assertEquals(
            listOf(
                MountRow(MountType.Bind, "/srv/app/config", "/etc/app", readOnly = true, MountOrigin.Binds, 0),
                MountRow(MountType.Volume, "app-uploads", "/uploads", readOnly = false, MountOrigin.Binds, 1),
                MountRow(MountType.Volume, "app-cache", "/cache", readOnly = false, MountOrigin.Mounts, 0),
            ),
            form.mounts,
        )
        assertEquals(NetworkRow("frontend", listOf("web"), "", "", "02:42:ac:15:00:03"), form.primaryNetwork)
        assertEquals(listOf(NetworkRow("backend", listOf("app"), "172.20.0.10", "", "02:42:ac:14:00:0a")), form.extraNetworks)
        assertNull(form.networkModeReadOnly)
        assertEquals(5, form.env.size)
        assertEquals("com.docker.compose.config-hash", form.labels.first().first)
        assertEquals("traefik.enable" to "true", form.labels.last())
        assertEquals(RestartPolicyForm("unless-stopped", 0), form.restart)
        assertEquals(536870912L, form.memoryBytes)
        assertNull(form.memoryReservationBytes)
        assertEquals(1500000000L, form.nanoCpus)
        assertNull(form.cpuShares)
    }

    @Test
    fun parses_compose_anonymous_volume_and_binds_mounts_mix() {
        val form = FormParser.fromInspect(compose)
        assertEquals(
            listOf(
                MountRow(MountType.Bind, "/srv/shop/src", "/app/src", readOnly = false, MountOrigin.Binds, 0),
                MountRow(MountType.Volume, "shop_db", "/var/lib/data", readOnly = false, MountOrigin.Mounts, 0),
                MountRow(MountType.Volume, "", "/app/node_modules", readOnly = false, MountOrigin.Mounts, 1),
            ),
            form.mounts,
        )
        assertEquals("node", form.user)
        assertEquals(RestartPolicyForm("always", 0), form.restart)
        assertEquals(NetworkRow("shop_default", listOf("shop-node-1", "node"), "", "", "02:42:ac:16:00:02"), form.primaryNetwork)
    }

    @Test
    fun container_network_mode_is_read_only() {
        val form = FormParser.fromInspect(netmode)
        assertEquals("container:0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b", form.networkModeReadOnly)
        assertNull(form.primaryNetwork)
        assertEquals(emptyList(), form.extraNetworks)
        assertNull(form.entrypoint)
        assertEquals(emptyList(), form.ports)
        assertNull(form.memoryBytes)
        assertNull(form.nanoCpus)
    }

    @Test
    fun bridge_network_without_aliases() {
        val form = FormParser.fromInspect(autoremove)
        assertEquals(NetworkRow("bridge", emptyList(), "", "", "02:42:ac:11:00:04"), form.primaryNetwork)
        assertEquals(RestartPolicyForm("no", 0), form.restart)
        assertEquals(emptyList(), form.mounts)
    }

    @Test
    fun host_network_mode_is_read_only() {
        val host = withHostConfig(autoremove, "NetworkMode", "host")
        assertEquals("host", FormParser.fromInspect(host).networkModeReadOnly)
        assertNull(FormParser.fromInspect(host).primaryNetwork)
    }

    @Test
    fun cpu_quota_and_period_parse_as_cpu_limit() {
        var inspect = withHostConfigRaw(netmode, "CpuQuota", "50000")
        inspect = withHostConfigRaw(inspect, "CpuPeriod", "100000")
        assertEquals(500_000_000L, FormParser.fromInspect(inspect).nanoCpus)
    }

    @Test
    fun empty_form_defaults() {
        val form = FormParser.empty()
        assertEquals("", form.image)
        assertEquals("", form.name)
        assertNull(form.command)
        assertNull(form.entrypoint)
        assertEquals(emptyList(), form.ports)
        assertEquals(emptyList(), form.mounts)
        assertNull(form.primaryNetwork)
        assertNull(form.networkModeReadOnly)
        assertEquals(RestartPolicyForm("no", 0), form.restart)
        assertNull(form.memoryBytes)
    }

    @Test
    fun split_command_line_handles_quotes() {
        assertEquals(listOf("sh", "-c", "echo \"hi there\""), splitCommandLine("sh -c 'echo \"hi there\"'"))
        assertEquals(listOf("a b", "c\"d", "e f", "g\\h"), splitCommandLine("\"a b\" \"c\\\"d\" e\\ f 'g\\h'"))
        assertEquals(listOf("", "x"), splitCommandLine("'' x"))
        assertEquals(emptyList(), splitCommandLine("   "))
        assertEquals(listOf("a", "b"), splitCommandLine("  a \t b  "))
    }

    @Test
    fun join_then_split_round_trips() {
        val cases = listOf(
            emptyList(),
            listOf("app", "serve"),
            listOf("sh", "-c", "echo \"hi there\""),
            listOf("it's", "", "a\\b", "\$HOME", "tab\there", "new\nline", "ünï", "--opt=1,2"),
        )
        for (args in cases) assertEquals(args, splitCommandLine(joinCommandLine(args)))
        assertEquals("app serve", joinCommandLine(listOf("app", "serve")))
        assertEquals("sh -c 'echo \"hi there\"'", joinCommandLine(listOf("sh", "-c", "echo \"hi there\"")))
    }

    @Test
    fun command_from_inspect_round_trips_through_text() {
        val form = FormParser.fromInspect(full)
        assertEquals(form.command, splitCommandLine(joinCommandLine(form.command!!)))
        assertTrue(joinCommandLine(form.entrypoint!!).isNotEmpty())
        assertEquals("/entrypoint.sh", full.getValue("Config").jsonObject.getValue("Entrypoint").jsonArray.single().jsonPrimitive.content)
    }

    private fun withHostConfig(inspect: JsonObject, key: String, value: String) = withHostConfigRaw(inspect, key, "\"$value\"")

    private fun withHostConfigRaw(inspect: JsonObject, key: String, rawJson: String): JsonObject {
        val host = inspect.getValue("HostConfig").jsonObject
        return JsonObject(inspect + ("HostConfig" to JsonObject(host + (key to Json.parseToJsonElement(rawJson)))))
    }
}
