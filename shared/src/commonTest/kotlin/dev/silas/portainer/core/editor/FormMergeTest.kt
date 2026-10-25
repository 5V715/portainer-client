package dev.silas.portainer.core.editor

import dev.silas.portainer.core.RecreateFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormMergeTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun json(s: String) = Json.parseToJsonElement(s)

    private val full = obj(RecreateFixtures.INSPECT_FULL)
    private val netmode = obj(RecreateFixtures.INSPECT_CONTAINER_NETMODE)
    private val compose = obj(RecreateFixtures.INSPECT_COMPOSE_ANON)

    private val JsonObject.config get() = getValue("Config").jsonObject
    private val JsonObject.hostConfig get() = getValue("HostConfig").jsonObject
    private val JsonObject.networks get() = getValue("NetworkSettings").jsonObject.getValue("Networks").jsonObject
    private fun JsonObject.without(vararg keys: String) = JsonObject(this - keys.toSet())

    /** Parses [inspect], applies [edit] and merges; returns the merged JSON and the edited fields. */
    private fun edit(inspect: JsonObject = full, edit: (ContainerForm) -> ContainerForm): Pair<JsonObject, Set<FormField>> {
        val before = FormParser.fromInspect(inspect)
        return FormMerger.merge(inspect, before, edit(before))
    }

    // ---- ports ----

    @Test
    fun adding_a_port_writes_binding_and_exposed_port_and_keeps_the_rest() {
        val (merged, fields) = edit { it.copy(ports = it.ports + PortRow("", "8443", 443, "tcp")) }
        assertEquals(setOf(FormField.Ports), fields)
        val bindings = merged.hostConfig.getValue("PortBindings").jsonObject
        assertEquals(json("""[{"HostIp": "", "HostPort": "8443"}]"""), bindings["443/tcp"])
        assertEquals(full.hostConfig.getValue("PortBindings").jsonObject["80/tcp"], bindings["80/tcp"])
        assertEquals(full.hostConfig.getValue("PortBindings").jsonObject["9090/tcp"], bindings["9090/tcp"])
        assertEquals(setOf("80/tcp", "9090/tcp", "443/tcp"), merged.config.getValue("ExposedPorts").jsonObject.keys)
        assertEquals(full.hostConfig.without("PortBindings"), merged.hostConfig.without("PortBindings"))
        assertEquals(full.config.without("ExposedPorts"), merged.config.without("ExposedPorts"))
        assertEquals(full.without("HostConfig", "Config"), merged.without("HostConfig", "Config"))
    }

    @Test
    fun changing_a_host_port_and_protocol() {
        val (merged, fields) = edit {
            it.copy(ports = listOf(PortRow("", "8081", 80, "tcp"), PortRow("127.0.0.1", "9090", 9090, "udp")))
        }
        assertEquals(setOf(FormField.Ports), fields)
        val bindings = merged.hostConfig.getValue("PortBindings").jsonObject
        assertEquals(setOf("80/tcp", "9090/udp"), bindings.keys)
        assertEquals(json("""[{"HostIp": "", "HostPort": "8081"}]"""), bindings["80/tcp"])
        assertEquals(setOf("80/tcp", "9090/udp"), merged.config.getValue("ExposedPorts").jsonObject.keys)
    }

    @Test
    fun removing_one_port_removes_its_binding_and_exposed_port() {
        val (merged, _) = edit { it.copy(ports = it.ports.take(1)) }
        assertEquals(setOf("80/tcp"), merged.hostConfig.getValue("PortBindings").jsonObject.keys)
        assertEquals(setOf("80/tcp"), merged.config.getValue("ExposedPorts").jsonObject.keys)
    }

    @Test
    fun deleting_the_last_port_removes_port_bindings_and_exposed_ports() {
        val (merged, fields) = edit { it.copy(ports = emptyList()) }
        assertEquals(setOf(FormField.Ports), fields)
        assertFalse("PortBindings" in merged.hostConfig)
        assertFalse("ExposedPorts" in merged.config)
    }

    @Test
    fun exposed_port_without_binding_is_kept_when_ports_change() {
        // netmode exposes 8000/tcp with no binding: adding a published port keeps it.
        val (merged, _) = edit(netmode) { it.copy(ports = listOf(PortRow("", "", 53, "udp"))) }
        assertEquals(setOf("8000/tcp", "53/udp"), merged.config.getValue("ExposedPorts").jsonObject.keys)
        assertEquals(json("""{"53/udp": [{"HostIp": "", "HostPort": ""}]}"""), merged.hostConfig["PortBindings"])
    }

    @Test
    fun ipv6_host_ip_round_trips() {
        val (merged, _) = edit { it.copy(ports = it.ports + PortRow("::", "8443", 443, "tcp")) }
        assertEquals(json("""[{"HostIp": "::", "HostPort": "8443"}]"""), merged.hostConfig.getValue("PortBindings").jsonObject["443/tcp"])
        assertEquals(PortRow("::", "8443", 443, "tcp"), FormParser.fromInspect(merged).ports.last())
    }

    @Test
    fun publish_all_toggle() {
        val (merged, fields) = edit { it.copy(publishAll = true) }
        assertEquals(setOf(FormField.Ports), fields)
        assertEquals(JsonPrimitive(true), merged.hostConfig["PublishAllPorts"])
        assertEquals(full.hostConfig["PortBindings"], merged.hostConfig["PortBindings"])
        assertEquals(full.config, merged.config)
    }

    // ---- mounts ----

    @Test
    fun read_only_toggle_on_a_binds_row_rewrites_only_that_bind() {
        val (merged, fields) = edit { f -> f.copy(mounts = f.mounts.map { if (it.target == "/etc/app") it.copy(readOnly = false) else it }) }
        assertEquals(setOf(FormField.Mounts), fields)
        assertEquals(json("""["/srv/app/config:/etc/app:rw", "app-uploads:/uploads"]"""), merged.hostConfig["Binds"])
        assertEquals(full.hostConfig["Mounts"], merged.hostConfig["Mounts"])
        assertEquals(full.hostConfig.without("Binds"), merged.hostConfig.without("Binds"))
        assertEquals(full.without("HostConfig"), merged.without("HostConfig"))
    }

    @Test
    fun read_only_toggle_on_a_mounts_row_stays_in_mounts() {
        val (merged, _) = edit { f -> f.copy(mounts = f.mounts.map { if (it.target == "/cache") it.copy(readOnly = true) else it }) }
        assertEquals(json("""[{"Type": "volume", "Source": "app-cache", "Target": "/cache", "ReadOnly": true}]"""), merged.hostConfig["Mounts"])
        assertEquals(full.hostConfig["Binds"], merged.hostConfig["Binds"])
        val reparsed = FormParser.fromInspect(merged).mounts.single { it.target == "/cache" }
        assertEquals(MountOrigin.Mounts, reparsed.origin)
        assertTrue(reparsed.readOnly)
    }

    @Test
    fun compose_anonymous_volume_toggle_keeps_its_options_and_no_source() {
        val (merged, _) = edit(compose) { f -> f.copy(mounts = f.mounts.map { if (it.target == "/app/node_modules") it.copy(readOnly = true) else it }) }
        assertEquals(
            json(
                """[{"Type": "volume", "Source": "shop_db", "Target": "/var/lib/data", "VolumeOptions": {}},
                    {"Type": "volume", "Target": "/app/node_modules", "VolumeOptions": {}, "ReadOnly": true}]""",
            ),
            merged.hostConfig["Mounts"],
        )
        assertEquals(compose.hostConfig["Binds"], merged.hostConfig["Binds"])
        assertEquals(compose.without("HostConfig"), merged.without("HostConfig"))
    }

    @Test
    fun compose_bind_toggle_keeps_binds_representation() {
        val (merged, _) = edit(compose) { f -> f.copy(mounts = f.mounts.map { if (it.target == "/app/src") it.copy(readOnly = true) else it }) }
        assertEquals(json("""["/srv/shop/src:/app/src:ro"]"""), merged.hostConfig["Binds"])
        assertEquals(compose.hostConfig["Mounts"], merged.hostConfig["Mounts"])
    }

    @Test
    fun new_bind_and_volume_rows_go_to_binds_and_tmpfs_to_mounts() {
        val (merged, fields) = edit {
            it.copy(
                mounts = it.mounts +
                    MountRow(MountType.Bind, "/srv/extra", "/extra", readOnly = true, MountOrigin.New, null) +
                    MountRow(MountType.Volume, "app-logs", "/logs", readOnly = false, MountOrigin.New, null) +
                    MountRow(MountType.Tmpfs, "", "/tmp", readOnly = false, MountOrigin.New, null),
            )
        }
        assertEquals(setOf(FormField.Mounts), fields)
        assertEquals(
            json("""["/srv/app/config:/etc/app:ro", "app-uploads:/uploads", "/srv/extra:/extra:ro", "app-logs:/logs"]"""),
            merged.hostConfig["Binds"],
        )
        assertEquals(
            json("""[{"Type": "volume", "Source": "app-cache", "Target": "/cache"}, {"Type": "tmpfs", "Target": "/tmp"}]"""),
            merged.hostConfig["Mounts"],
        )
    }

    @Test
    fun windows_bind_parses_and_toggles_read_only() {
        val inspect = withHostConfig(full, "Binds", """["c:\\data:c:\\app:ro", "\\\\.\\pipe\\docker_engine:\\\\.\\pipe\\docker_engine"]""")
        val rows = FormParser.fromInspect(inspect).mounts.filter { it.origin == MountOrigin.Binds }
        assertEquals(
            listOf(
                MountRow(MountType.Bind, "c:\\data", "c:\\app", readOnly = true, MountOrigin.Binds, 0),
                MountRow(MountType.Bind, "\\\\.\\pipe\\docker_engine", "\\\\.\\pipe\\docker_engine", readOnly = false, MountOrigin.Binds, 1),
            ),
            rows,
        )
        assertTrue(rows.all { FormMerger.isEditableInPlace(inspect, it) })
        val (merged, _) = edit(inspect) { f -> f.copy(mounts = f.mounts.map { if (it.target == "c:\\app") it.copy(readOnly = false) else it }) }
        assertEquals(
            json("""["c:\\data:c:\\app:rw", "\\\\.\\pipe\\docker_engine:\\\\.\\pipe\\docker_engine"]"""),
            merged.hostConfig["Binds"],
        )
    }

    @Test
    fun bind_the_grammar_cannot_reproduce_is_kept_verbatim() {
        val inspect = withHostConfig(full, "Binds", """["/a:/b:"]""")
        val row = FormParser.fromInspect(inspect).mounts.first()
        assertFalse(FormMerger.isEditableInPlace(inspect, row))
        val (merged, _) = edit(inspect) { f -> f.copy(mounts = f.mounts.map { if (it.target == "/b") it.copy(readOnly = true) else it }) }
        assertEquals(json("""["/a:/b:"]"""), merged.hostConfig["Binds"])
    }

    @Test
    fun explicit_rw_and_propagation_survive_toggle() {
        val inspect = withHostConfig(full, "Binds", """["/srv/x:/x:rshared", "/srv/y:/y:rw,rshared"]""")
        val (merged, _) = edit(inspect) { f -> f.copy(mounts = f.mounts.map { if (it.origin == MountOrigin.Binds) it.copy(readOnly = true) else it }) }
        assertEquals(json("""["/srv/x:/x:ro,rshared", "/srv/y:/y:ro,rshared"]"""), merged.hostConfig["Binds"])
        val (back, _) = edit(withHostConfig(full, "Binds", """["/srv/y:/y:ro,rshared"]""")) { f ->
            f.copy(mounts = f.mounts.map { if (it.origin == MountOrigin.Binds) it.copy(readOnly = false) else it })
        }
        assertEquals(json("""["/srv/y:/y:rw,rshared"]"""), back.hostConfig["Binds"])
    }

    @Test
    fun deleting_an_anonymous_binds_row() {
        val inspect = withHostConfig(full, "Binds", """["/data", "/srv/a:/a"]""")
        assertEquals(MountRow(MountType.Volume, "", "/data", readOnly = false, MountOrigin.Binds, 0), FormParser.fromInspect(inspect).mounts.first())
        val (merged, _) = edit(inspect) { f -> f.copy(mounts = f.mounts.filterNot { it.target == "/data" }) }
        assertEquals(json("""["/srv/a:/a"]"""), merged.hostConfig["Binds"])
    }

    @Test
    fun binds_indices_are_into_the_raw_array() {
        val inspect = withHostConfig(full, "Binds", """[null, "/x:/y"]""")
        val row = FormParser.fromInspect(inspect).mounts.first()
        assertEquals(1, row.originalIndex)
        val (merged, _) = edit(inspect) { f -> f.copy(mounts = f.mounts.map { if (it.target == "/y") it.copy(readOnly = true) else it }) }
        assertEquals(json("""["/x:/y:ro"]"""), merged.hostConfig["Binds"])
    }

    @Test
    fun uncovered_mount_types_keep_their_position() {
        val inspect = withHostConfig(
            full, "Mounts",
            """[{"Type": "volume", "Source": "a", "Target": "/a"}, {"Type": "npipe", "Source": "\\\\.\\pipe\\x", "Target": "\\\\.\\pipe\\x"},
                {"Type": "volume", "Source": "b", "Target": "/b"}]""",
        )
        val (merged, _) = edit(inspect) { f -> f.copy(mounts = f.mounts.map { if (it.target == "/b") it.copy(readOnly = true) else it }) }
        val mounts = merged.hostConfig.getValue("Mounts").jsonArray
        assertEquals(inspect.hostConfig.getValue("Mounts").jsonArray[0], mounts[0])
        assertEquals(inspect.hostConfig.getValue("Mounts").jsonArray[1], mounts[1])
        assertEquals(json("""{"Type": "volume", "Source": "b", "Target": "/b", "ReadOnly": true}"""), mounts[2])
    }

    @Test
    fun deleting_a_bind_row_and_all_bind_rows() {
        val (one, _) = edit { f -> f.copy(mounts = f.mounts.filterNot { it.target == "/uploads" }) }
        assertEquals(json("""["/srv/app/config:/etc/app:ro"]"""), one.hostConfig["Binds"])
        assertEquals(full.hostConfig["Mounts"], one.hostConfig["Mounts"])

        val (none, _) = edit { f -> f.copy(mounts = f.mounts.filter { it.origin == MountOrigin.Mounts }) }
        assertFalse("Binds" in none.hostConfig)
        assertEquals(full.hostConfig["Mounts"], none.hostConfig["Mounts"])

        val (empty, _) = edit { it.copy(mounts = emptyList()) }
        assertFalse("Binds" in empty.hostConfig)
        assertFalse("Mounts" in empty.hostConfig)
    }

    @Test
    fun changing_a_bind_source_keeps_its_other_options() {
        val inspect = withHostConfig(full, "Binds", """["/srv/app/config:/etc/app:ro,z"]""")
        val (merged, _) = edit(inspect) { f -> f.copy(mounts = f.mounts.map { if (it.target == "/etc/app") it.copy(source = "/srv/new", readOnly = false) else it }) }
        assertEquals(json("""["/srv/new:/etc/app:rw,z"]"""), merged.hostConfig["Binds"])
    }

    @Test
    fun changing_a_mounts_row_type_drops_options_of_the_old_type() {
        val (merged, _) = edit(compose) { f ->
            f.copy(mounts = f.mounts.map { if (it.target == "/var/lib/data") it.copy(type = MountType.Bind, source = "/srv/db") else it })
        }
        val first = merged.hostConfig.getValue("Mounts").jsonArray[0]
        assertEquals(json("""{"Type": "bind", "Source": "/srv/db", "Target": "/var/lib/data"}"""), first)
    }

    // ---- networks ----

    @Test
    fun adding_a_network_alias_rewrites_only_that_endpoint() {
        val (merged, fields) = edit { f -> f.copy(primaryNetwork = f.primaryNetwork!!.let { it.copy(aliases = it.aliases + "proxy") }) }
        assertEquals(setOf(FormField.Networks), fields)
        val frontend = merged.networks.getValue("frontend").jsonObject
        assertEquals(json("""["web", "proxy"]"""), frontend["Aliases"])
        val original = full.networks.getValue("frontend").jsonObject
        assertEquals(original.without("Aliases"), frontend.without("Aliases"))
        assertEquals(full.networks["backend"], merged.networks["backend"])
        assertEquals(full.hostConfig, merged.hostConfig)
        assertEquals(full.config, merged.config)
    }

    @Test
    fun changing_a_fixed_ip() {
        val (merged, _) = edit { f -> f.copy(extraNetworks = f.extraNetworks.map { it.copy(ipv4 = "172.20.0.20", ipv6 = "fd00::20") }) }
        assertEquals(
            json("""{"IPv4Address": "172.20.0.20", "IPv6Address": "fd00::20"}"""),
            merged.networks.getValue("backend").jsonObject["IPAMConfig"],
        )
        val (cleared, _) = edit { f -> f.copy(extraNetworks = f.extraNetworks.map { it.copy(ipv4 = "") }) }
        assertEquals(JsonNull, cleared.networks.getValue("backend").jsonObject["IPAMConfig"])
    }

    @Test
    fun switching_primary_network_sets_network_mode() {
        val (merged, _) = edit { f -> f.copy(primaryNetwork = f.extraNetworks.single(), extraNetworks = listOf(f.primaryNetwork!!)) }
        assertEquals(JsonPrimitive("backend"), merged.hostConfig["NetworkMode"])
        assertEquals(full.networks, merged.networks)
        assertEquals(full.hostConfig.without("NetworkMode"), merged.hostConfig.without("NetworkMode"))
    }

    @Test
    fun removing_and_adding_networks() {
        val (removed, _) = edit { it.copy(extraNetworks = emptyList()) }
        assertEquals(setOf("frontend"), removed.networks.keys)
        assertEquals(full.networks["frontend"], removed.networks["frontend"])

        val (added, _) = edit { it.copy(extraNetworks = it.extraNetworks + NetworkRow("monitoring", listOf("app-metrics"), "", "", "")) }
        assertEquals(json("""{"Aliases": ["app-metrics"]}"""), added.networks["monitoring"])
        assertEquals(full.networks["backend"], added.networks["backend"])
    }

    @Test
    fun removing_the_primary_network_promotes_the_first_extra() {
        val (merged, fields) = edit { it.copy(primaryNetwork = null) }
        assertEquals(setOf(FormField.Networks), fields)
        assertEquals(JsonPrimitive("backend"), merged.hostConfig["NetworkMode"])
        assertEquals(setOf("backend"), merged.networks.keys)
        assertEquals(full.networks["backend"], merged.networks["backend"])
    }

    @Test
    fun read_only_network_mode_is_never_rewritten() {
        val (merged, fields) = edit(netmode) { it.copy(env = it.env + "X=1") }
        assertEquals(setOf(FormField.Env), fields)
        assertEquals(netmode.hostConfig, merged.hostConfig)
        assertEquals(netmode["NetworkSettings"], merged["NetworkSettings"])
    }

    // ---- restart / resources ----

    @Test
    fun restart_policy_change() {
        val (merged, fields) = edit { it.copy(restart = RestartPolicyForm("on-failure", 5)) }
        assertEquals(setOf(FormField.Restart), fields)
        assertEquals(json("""{"Name": "on-failure", "MaximumRetryCount": 5}"""), merged.hostConfig["RestartPolicy"])
        assertEquals(full.hostConfig.without("RestartPolicy"), merged.hostConfig.without("RestartPolicy"))
    }

    @Test
    fun retry_count_only_with_on_failure() {
        val (merged, _) = edit { it.copy(restart = RestartPolicyForm("always", 3)) }
        assertEquals(json("""{"Name": "always", "MaximumRetryCount": 0}"""), merged.hostConfig["RestartPolicy"])
    }

    @Test
    fun memory_set_and_clear() {
        val (set, fields) = edit(netmode) { it.copy(memoryBytes = 268435456L) }
        assertEquals(setOf(FormField.Resources), fields)
        assertEquals(268435456L, set.hostConfig.getValue("Memory").jsonPrimitive.long)
        assertEquals(netmode.hostConfig.without("Memory"), set.hostConfig.without("Memory"))

        // full has MemorySwap 1 GiB: Docker rejects a swap limit without a memory limit, so it is reset.
        val (cleared, _) = edit { it.copy(memoryBytes = null) }
        assertEquals(0L, cleared.hostConfig.getValue("Memory").jsonPrimitive.long)
        assertEquals(0L, cleared.hostConfig.getValue("MemorySwap").jsonPrimitive.long)
        assertEquals(full.hostConfig["NanoCpus"], cleared.hostConfig["NanoCpus"])

        // Lowering memory below the swap limit keeps the swap limit.
        val (lowered, _) = edit { it.copy(memoryBytes = 268435456L) }
        assertEquals(1073741824L, lowered.hostConfig.getValue("MemorySwap").jsonPrimitive.long)
    }

    @Test
    fun cpu_reservation_and_shares() {
        val (merged, _) = edit { it.copy(nanoCpus = 2_000_000_000L, memoryReservationBytes = 134217728L, cpuShares = 512L) }
        assertEquals(2_000_000_000L, merged.hostConfig.getValue("NanoCpus").jsonPrimitive.long)
        assertEquals(134217728L, merged.hostConfig.getValue("MemoryReservation").jsonPrimitive.long)
        assertEquals(512L, merged.hostConfig.getValue("CpuShares").jsonPrimitive.long)
        assertEquals(full.hostConfig["Memory"], merged.hostConfig["Memory"])
    }

    @Test
    fun changing_cpu_limit_replaces_quota_and_period() {
        var inspect = withHostConfig(netmode, "CpuQuota", "50000")
        inspect = withHostConfig(inspect, "CpuPeriod", "100000")
        val (merged, _) = edit(inspect) { it.copy(nanoCpus = 2_000_000_000L) }
        assertEquals(2_000_000_000L, merged.hostConfig.getValue("NanoCpus").jsonPrimitive.long)
        assertEquals(0L, merged.hostConfig.getValue("CpuQuota").jsonPrimitive.long)
        assertEquals(0L, merged.hostConfig.getValue("CpuPeriod").jsonPrimitive.long)
    }

    // ---- config fields ----

    @Test
    fun config_fields_are_written_and_reported() {
        val (merged, fields) = edit {
            it.copy(
                image = "ghcr.io/example/app:2.0",
                name = "app-web-2",
                command = listOf("app", "serve", "--debug"),
                entrypoint = null,
                workingDir = "/srv",
                user = "1000:1000",
                env = listOf("A=1"),
                labels = listOf("x" to "y"),
            )
        }
        assertEquals(
            setOf(
                FormField.Image, FormField.Name, FormField.Command, FormField.Entrypoint, FormField.WorkingDir,
                FormField.User, FormField.Env, FormField.Labels,
            ),
            fields,
        )
        val config = merged.config
        assertEquals(JsonPrimitive("ghcr.io/example/app:2.0"), config["Image"])
        assertEquals(JsonPrimitive("/app-web-2"), merged["Name"])
        assertEquals(json("""["app", "serve", "--debug"]"""), config["Cmd"])
        assertEquals(JsonNull, config["Entrypoint"])
        assertEquals(JsonPrimitive("/srv"), config["WorkingDir"])
        assertEquals(JsonPrimitive("1000:1000"), config["User"])
        assertEquals(json("""["A=1"]"""), config["Env"])
        assertEquals(json("""{"x": "y"}"""), config["Labels"])
        assertEquals(full.hostConfig, merged.hostConfig)
        assertEquals(full["NetworkSettings"], merged["NetworkSettings"])
        assertEquals(full.config.without("Image", "Cmd", "Entrypoint", "WorkingDir", "User", "Env", "Labels"),
            config.without("Image", "Cmd", "Entrypoint", "WorkingDir", "User", "Env", "Labels"))
    }

    @Test
    fun deleting_all_labels_and_env_removes_them() {
        val (merged, _) = edit { it.copy(labels = emptyList(), env = emptyList()) }
        assertNull(merged.config["Labels"])
        assertNull(merged.config["Env"])
    }

    @Test
    fun null_label_value_reads_as_empty_and_survives_unedited() {
        val config = JsonObject(full.config + ("Labels" to json("""{"a": null, "b": "1"}""")))
        val inspect = JsonObject(full + ("Config" to config))
        val form = FormParser.fromInspect(inspect)
        assertEquals(listOf("a" to "", "b" to "1"), form.labels)
        assertEquals(inspect, FormMerger.merge(inspect, form, form.copy(env = form.env)).first)
        val (merged, _) = edit(inspect) { it.copy(env = it.env + "X=1") }
        assertEquals(json("""{"a": null, "b": "1"}"""), merged.config["Labels"])
    }

    @Test
    fun duplicate_label_keys_keep_the_last_value_and_are_reported() {
        val labels = listOf("a" to "1", "b" to "2", "a" to "3")
        assertEquals(setOf("a"), duplicateLabelKeys(labels))
        assertEquals(emptySet(), duplicateLabelKeys(listOf("a" to "1")))
        val (merged, _) = edit { it.copy(labels = labels) }
        assertEquals(json("""{"a": "3", "b": "2"}"""), merged.config["Labels"])
    }

    @Test
    fun blank_command_is_written_as_an_empty_array() {
        val (merged, fields) = edit { it.copy(command = emptyList()) }
        assertEquals(setOf(FormField.Command), fields)
        assertEquals(JsonArray(emptyList()), merged.config["Cmd"])
    }

    private fun withHostConfig(inspect: JsonObject, key: String, rawJson: String): JsonObject {
        val host = inspect.hostConfig
        val value: JsonElement = Json.parseToJsonElement(rawJson)
        return JsonObject(inspect + ("HostConfig" to JsonObject(host + (key to value))))
    }
}
