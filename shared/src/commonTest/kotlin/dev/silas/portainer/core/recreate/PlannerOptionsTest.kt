package dev.silas.portainer.core.recreate

import dev.silas.portainer.core.RecreateFixtures
import dev.silas.portainer.core.editor.ContainerForm
import dev.silas.portainer.core.editor.FormField
import dev.silas.portainer.core.editor.FormMerger
import dev.silas.portainer.core.editor.FormParser
import dev.silas.portainer.core.editor.MountOrigin
import dev.silas.portainer.core.editor.MountRow
import dev.silas.portainer.core.editor.MountType
import dev.silas.portainer.core.editor.PortRow
import dev.silas.portainer.core.editor.RestartPolicyForm
import dev.silas.portainer.core.editor.describeChanges
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlannerOptionsTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private val full = obj(RecreateFixtures.INSPECT_FULL)
    private val netmode = obj(RecreateFixtures.INSPECT_CONTAINER_NETMODE)
    private val autoremove = obj(RecreateFixtures.INSPECT_AUTOREMOVE)
    private val compose = obj(RecreateFixtures.INSPECT_COMPOSE_ANON)
    private val imageInspect = obj(RecreateFixtures.IMAGE_CONFIG)

    private val composeAnon = "5c0d8e2f4a6b8c0d2e4f6a8b0c2d4e6f8a0b2c4d6e8f0a2b4c6d8e0f2a4b6c8d"
    private val fullAnon = "9b1e0d7c4a3f2e8b5c6d1a0f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d"
    private val autoremoveAnon = "f1e2d3c4b5a6978877665544332211ffeeddccbbaa99887766554433221100ff"

    private val JsonObject.hostConfig get() = getValue("HostConfig").jsonObject
    private fun JsonObject.binds() = hostConfig["Binds"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    private fun JsonObject.endpoints() = getValue("NetworkingConfig").jsonObject.getValue("EndpointsConfig").jsonObject
    private fun strings(vararg s: String) = JsonArray(s.map { JsonPrimitive(it) })

    private class Edited(val original: JsonObject, val before: ContainerForm, val after: ContainerForm, val merged: JsonObject, val fields: Set<FormField>)

    private fun edit(inspect: JsonObject, change: (ContainerForm) -> ContainerForm = { it }): Edited {
        val before = FormParser.fromInspect(inspect)
        val after = change(before)
        val (merged, fields) = FormMerger.merge(inspect, before, after)
        return Edited(inspect, before, after, merged, fields)
    }

    private fun Edited.plan(
        imageConfig: JsonObject? = imageInspect,
        mode: PlanMode = PlanMode.Replace,
        typedMacs: Set<String> = emptySet(),
        typedAliases: Set<String> = emptySet(),
    ) = RecreatePlanner.plan(
        merged,
        imageConfig,
        RecreateEdits(after.image, after.env, false, null),
        PlanOptions(mode, fields, typedMacs, typedAliases, original),
    )

    private fun JsonObject.withConfig(key: String, value: kotlinx.serialization.json.JsonElement) =
        JsonObject(this + ("Config" to JsonObject(getValue("Config").jsonObject + (key to value))))

    private fun JsonObject.withHost(key: String, value: kotlinx.serialization.json.JsonElement) =
        JsonObject(this + ("HostConfig" to JsonObject(hostConfig + (key to value))))

    // ---- baseline ----

    @Test
    fun unedited_form_plans_exactly_like_v1() {
        for ((inspect, image) in listOf(full to imageInspect, netmode to null, autoremove to null, compose to null)) {
            val form = FormParser.fromInspect(inspect)
            val (merged, fields) = FormMerger.merge(inspect, form, form)
            assertTrue(fields.isEmpty())
            val v1 = RecreatePlanner.plan(inspect, image, RecreateEdits(form.image, form.env, true, 3))
            val v11 = RecreatePlanner.plan(merged, image, RecreateEdits(form.image, form.env, true, 3), PlanOptions())
            assertEquals(v1.createBody, v11.createBody)
            assertEquals(v1, v11)
            assertEquals(v1, RecreatePlanner.plan(merged, image, RecreateEdits(form.image, form.env, true, 3), PlanOptions(original = inspect)))
        }
    }

    // ---- user-edited fields vs image defaults ----

    @Test
    fun blanked_command_stays_blank() {
        // full's Cmd equals the image default; the user deliberately clears it.
        val e = edit(full) { it.copy(command = emptyList()) }
        assertEquals(setOf(FormField.Command), e.fields)
        val p = e.plan()
        assertEquals(JsonArray(emptyList()), p.createBody["Cmd"])
        // Docker re-inherits the image Cmd when the request has no Entrypoint: keep it so the blank sticks.
        assertEquals(strings("/entrypoint.sh"), p.createBody["Entrypoint"])
        assertFalse("Cmd" in p.changes.droppedImageDefaults)
        assertFalse("Entrypoint" in p.changes.droppedImageDefaults)
    }

    @Test
    fun edited_field_set_back_to_image_default_is_kept() {
        // The container ran with WorkingDir /srv; the user sets it to /app, which is also the image default.
        val srv = JsonObject(full + ("Config" to JsonObject(full.getValue("Config").jsonObject + ("WorkingDir" to JsonPrimitive("/srv")))))
        val e = edit(srv) { it.copy(workingDir = "/app") }
        assertEquals(setOf(FormField.WorkingDir), e.fields)
        val p = e.plan()
        assertEquals(JsonPrimitive("/app"), p.createBody["WorkingDir"])
        assertFalse("WorkingDir" in p.changes.droppedImageDefaults)
        // Not edited: v1 stripping still applies to the other single-value fields.
        assertFalse("Entrypoint" in p.createBody)
    }

    @Test
    fun unedited_sections_are_still_stripped_when_others_are_edited() {
        val p = edit(full) { it.copy(restart = RestartPolicyForm("always", 0)) }.plan()
        for (key in listOf("Cmd", "Entrypoint", "WorkingDir", "StopSignal")) assertFalse(key in p.createBody, key)
        assertEquals(JsonPrimitive("always"), p.createBody.hostConfig.getValue("RestartPolicy").jsonObject["Name"])
    }

    // ---- mounts ----

    @Test
    fun deleted_anonymous_volume_row_is_not_reattached() {
        val e = edit(compose) { f -> f.copy(mounts = f.mounts.filterNot { it.target == "/app/node_modules" }) }
        val host = e.plan(null).createBody.hostConfig
        assertEquals(JsonArray(listOf(obj("""{"Type": "volume", "Source": "shop_db", "Target": "/var/lib/data", "VolumeOptions": {}}"""))), host["Mounts"])
        assertEquals(strings("/srv/shop/src:/app/src:rw"), host["Binds"])
        assertFalse(composeAnon in host.toString())
    }

    @Test
    fun anonymous_volume_row_with_changed_target_is_not_reattached() {
        val e = edit(compose) { f -> f.copy(mounts = f.mounts.map { if (it.target == "/app/node_modules") it.copy(target = "/app/deps") else it }) }
        val host = e.plan(null).createBody.hostConfig
        assertFalse(composeAnon in host.toString())
        assertTrue(host.getValue("Mounts").jsonArray.any { it.jsonObject["Target"] == JsonPrimitive("/app/deps") })
    }

    @Test
    fun deleted_named_volume_row_is_not_reattached() {
        val e = edit(full) { f -> f.copy(mounts = f.mounts.filterNot { it.source == "app-uploads" }) }
        val binds = e.plan().createBody.binds()
        assertEquals(listOf("/srv/app/config:/etc/app:ro", "$fullAnon:/data"), binds, "image VOLUME /data was never a row: still reattached")
    }

    @Test
    fun kept_anonymous_rows_and_image_volumes_are_reattached_when_mounts_are_edited() {
        val tmp = MountRow(MountType.Tmpfs, "", "/tmp", false, MountOrigin.New, null)
        val composePlan = edit(compose) { it.copy(mounts = it.mounts + tmp) }.plan(null)
        val mounts = composePlan.createBody.hostConfig.getValue("Mounts").jsonArray
        assertTrue(mounts.any { it.jsonObject["Source"] == JsonPrimitive(composeAnon) })

        val autoremovePlan = edit(autoremove) { it.copy(mounts = it.mounts + tmp) }.plan(null)
        assertEquals(listOf("$autoremoveAnon:/data:ro"), autoremovePlan.createBody.binds())
    }

    // ---- networks ----

    @Test
    fun removing_the_only_network_falls_back_to_bridge() {
        val e = edit(compose) { it.copy(primaryNetwork = null, extraNetworks = emptyList()) }
        assertEquals(setOf(FormField.Networks), e.fields)
        assertEquals(JsonPrimitive("bridge"), e.merged.hostConfig["NetworkMode"])
        val p = e.plan(null)
        assertEquals(JsonPrimitive("bridge"), p.createBody.hostConfig["NetworkMode"])
        assertFalse("shop_default" in p.createBody.toString())
        assertEquals(JsonObject(emptyMap()), p.createBody.endpoints())
        assertTrue(p.extraNetworks.isEmpty())
    }

    // ---- copy mode ----

    @Test
    fun copy_does_not_reattach_anonymous_volumes() {
        val p = edit(compose) { it.copy(name = "shop-node-1-copy") }.plan(null, PlanMode.Copy)
        val host = p.createBody.hostConfig
        assertEquals(
            JsonArray(
                listOf(
                    obj("""{"Type": "volume", "Source": "shop_db", "Target": "/var/lib/data", "VolumeOptions": {}}"""),
                    obj("""{"Type": "volume", "Target": "/app/node_modules", "VolumeOptions": {}}"""),
                ),
            ),
            host["Mounts"],
        )
        assertEquals(strings("/srv/shop/src:/app/src:rw"), host["Binds"])
        assertFalse(composeAnon in p.createBody.toString())
        assertEquals("shop-node-1-copy", p.containerName)

        val fullCopy = edit(full) { it.copy(name = "app-web-copy") }.plan(mode = PlanMode.Copy)
        assertEquals(listOf("/srv/app/config:/etc/app:ro", "app-uploads:/uploads"), fullCopy.createBody.binds(), "named volumes shared, image VOLUME fresh")
        assertFalse(fullAnon in fullCopy.createBody.toString())
    }

    @Test
    fun copy_drops_generated_hostname_and_macs() {
        val legacyMac = JsonObject(full + ("Config" to JsonObject(full.getValue("Config").jsonObject + ("MacAddress" to JsonPrimitive("02:42:ac:15:00:99")))))
        val p = edit(legacyMac) { it.copy(name = "app-web-copy") }.plan(mode = PlanMode.Copy)
        assertFalse("Hostname" in p.createBody)
        assertFalse("MacAddress" in p.createBody)
        // Generated MACs, aliases the user didn't type and links are the original's identity: dropped.
        assertEquals(
            obj("""{"DriverOpts": {"com.docker.network.endpoint.sysctls": "net.ipv4.conf.IFNAME.log_martians=1"}, "GwPriority": 0}"""),
            p.createBody.endpoints().getValue("frontend").jsonObject,
        )
        assertEquals(obj("""{"IPAMConfig": {"IPv4Address": "172.20.0.10"}, "GwPriority": 10}"""), p.extraNetworks.getValue("backend"))
    }

    @Test
    fun copy_keeps_typed_macs_and_custom_hostname() {
        val custom = JsonObject(full + ("Config" to JsonObject(full.getValue("Config").jsonObject + ("Hostname" to JsonPrimitive("myhost")))))
        val p = edit(custom) { f ->
            f.copy(name = "app-web-copy", extraNetworks = f.extraNetworks.map { it.copy(mac = "02:42:ac:14:00:77", aliases = listOf("app", "app2")) })
        }.plan(mode = PlanMode.Copy, typedMacs = setOf("02:42:ac:14:00:77"), typedAliases = setOf("app2"))
        assertEquals(JsonPrimitive("myhost"), p.createBody["Hostname"])
        assertEquals(JsonPrimitive("02:42:ac:14:00:77"), p.extraNetworks.getValue("backend")["MacAddress"])
        assertEquals(strings("app2"), p.extraNetworks.getValue("backend")["Aliases"])
        assertFalse("MacAddress" in p.createBody.endpoints().getValue("frontend").jsonObject)
    }

    @Test
    fun copy_has_no_rollback_warnings() {
        val p = edit(autoremove) { it.copy(name = "scratchpad-copy") }.plan(null, PlanMode.Copy)
        assertFalse(RecreateWarning.AutoRemove in p.warnings)
        assertFalse(RecreateWarning.ConnectionDependency in p.warnings)
        assertTrue(RecreateWarning.ImageConfigUnavailable in p.warnings)
    }

    @Test
    fun replace_keeps_macs() {
        val p = edit(full) { it.copy(restart = RestartPolicyForm("always", 0)) }.plan()
        assertEquals(JsonPrimitive("02:42:ac:15:00:03"), p.createBody.endpoints().getValue("frontend").jsonObject["MacAddress"])
    }

    // ---- change descriptions ----

    @Test
    fun describes_other_changes() {
        val before = FormParser.fromInspect(full)
        val after = before.copy(
            ports = before.ports.filterNot { it.containerPort == 9090 } + PortRow("", "8443", 443, "tcp"),
            restart = RestartPolicyForm("always", 0),
            mounts = before.mounts.filterNot { it.source == "app-uploads" } + MountRow(MountType.Tmpfs, "", "/tmp", false, MountOrigin.New, null),
            extraNetworks = emptyList(),
            labels = before.labels.filterNot { it.first == "traefik.enable" } + ("tier" to "web"),
            memoryBytes = 1L shl 30,
            nanoCpus = null,
            command = emptyList(),
            env = before.env + "X=1",
            image = "ghcr.io/example/app:1.1",
        )
        assertEquals(
            listOf(
                "Command: app serve → (empty)",
                "Ports: removed 127.0.0.1:9090→9090/tcp",
                "Ports: added 8443→443/tcp",
                "Mounts: removed volume app-uploads → /uploads",
                "Mounts: added tmpfs → /tmp",
                "Networks: removed backend",
                "Labels: removed traefik.enable",
                "Labels: added tier=web",
                "Restart policy: unless-stopped → always",
                "Memory limit: 512 MiB → 1 GiB",
                "CPU limit: 1.5 CPUs → unlimited",
            ),
            describeChanges(before, after),
        )
        assertTrue(describeChanges(before, before).isEmpty())
        // The planner has no forms: the caller attaches the lines to the plan's summary.
        assertTrue(edit(full) { after }.plan().changes.otherChanges.isEmpty())
    }

    @Test
    fun describes_removing_every_network() {
        val before = FormParser.fromInspect(full)
        assertEquals(
            listOf("Networks: primary frontend → bridge (default)", "Networks: removed frontend", "Networks: removed backend"),
            describeChanges(before, before.copy(primaryNetwork = null, extraNetworks = emptyList())),
        )
        assertEquals(
            listOf("Networks: primary frontend → backend", "Networks: removed frontend"),
            describeChanges(before, before.copy(primaryNetwork = null)),
        )
    }

    // ---- fix round 1 ----

    @Test
    fun copy_does_not_share_anonymous_volumes_named_by_an_earlier_recreate() {
        // After a v1 Replace the anonymous volume is written by name into HostConfig.
        val afterReplace = full.withHost("Binds", strings("/srv/app/config:/etc/app:ro", "app-uploads:/uploads", "$fullAnon:/data:ro,z"))
        val p = edit(afterReplace) { it.copy(name = "app-web-copy") }.plan(mode = PlanMode.Copy)
        assertEquals(listOf("/srv/app/config:/etc/app:ro", "app-uploads:/uploads", "/data:ro,z"), p.createBody.binds())
        assertFalse(fullAnon in p.createBody.toString())

        val composeAfterReplace = compose.withHost(
            "Mounts",
            JsonArray(
                listOf(
                    obj("""{"Type": "volume", "Source": "shop_db", "Target": "/var/lib/data", "VolumeOptions": {}}"""),
                    obj("""{"Type": "volume", "Target": "/app/node_modules", "VolumeOptions": {}, "Source": "$composeAnon"}"""),
                ),
            ),
        )
        val c = edit(composeAfterReplace) { it.copy(name = "shop-node-1-copy") }.plan(null, PlanMode.Copy)
        assertEquals(
            JsonArray(
                listOf(
                    obj("""{"Type": "volume", "Source": "shop_db", "Target": "/var/lib/data", "VolumeOptions": {}}"""),
                    obj("""{"Type": "volume", "Target": "/app/node_modules", "VolumeOptions": {}}"""),
                ),
            ),
            c.createBody.hostConfig["Mounts"],
        )
        assertFalse(composeAnon in c.createBody.toString())
    }

    @Test
    fun copy_is_not_a_stack_member() {
        val p = edit(full) { it.copy(name = "app-web-copy") }.plan(mode = PlanMode.Copy)
        val labels = p.createBody.getValue("Labels").jsonObject
        assertTrue(labels.keys.none { it.startsWith("com.docker.compose.") })
        assertEquals(JsonPrimitive("true"), labels["traefik.enable"])
        assertTrue(p.warnings.none { it is RecreateWarning.StackMember })
        assertTrue(RecreateWarning.StackMember("shop") in edit(full) { it.copy(name = "x") }.plan().warnings, "Replace keeps them")
    }

    @Test
    fun copy_in_container_network_mode_warns_about_the_shared_namespace() {
        val p = edit(netmode) { it.copy(name = "sidecar-copy") }.plan(null, PlanMode.Copy)
        assertTrue(RecreateWarning.SharedNetworkNamespace("0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b") in p.warnings)
        assertTrue(edit(netmode) { it.copy(name = "x") }.plan(null).warnings.none { it is RecreateWarning.SharedNetworkNamespace })
    }

    @Test
    fun typed_env_equal_to_the_old_image_default_is_kept_across_an_image_change() {
        // The container overrode APP_VERSION; the user sets it back to the old image's value and moves to a new image.
        val overridden = full.withConfig("Env", strings("DB_HOST=db", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "APP_VERSION=0"))
        val e = edit(overridden) { f -> f.copy(image = "ghcr.io/example/app:1.1", env = f.env.map { if (it == "APP_VERSION=0") "APP_VERSION=1" else it }) }
        val p = e.plan()
        assertEquals(strings("DB_HOST=db", "APP_VERSION=1"), p.createBody["Env"], "PATH (untyped) still stripped")
        assertEquals(listOf("APP_VERSION=0" to "APP_VERSION=1"), p.changes.envChanged)
        assertEquals("ghcr.io/example/app:1.0" to "ghcr.io/example/app:1.1", p.changes.imageChange)
        assertTrue(p.pull, "image changed in the form")
        // v1 (no edited fields) strips it.
        val v1 = RecreatePlanner.plan(overridden, imageInspect, RecreateEdits("ghcr.io/example/app:1.1", e.after.env, false, null))
        assertEquals(strings("DB_HOST=db"), v1.createBody["Env"])
    }

    @Test
    fun blank_command_without_entrypoint_warns() {
        val p = edit(autoremove) { it.copy(command = emptyList()) }.plan(null)
        assertEquals(JsonArray(emptyList()), p.createBody["Cmd"])
        assertTrue(RecreateWarning.ImageDefaultCommand in p.warnings)
        assertFalse(RecreateWarning.ImageDefaultCommand in edit(full) { it.copy(command = emptyList()) }.plan().warnings, "entrypoint kept")
    }
}
