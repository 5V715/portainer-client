package dev.silas.portainer.core.editor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewContainerBuilderTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private val minimal = FormParser.empty().copy(image = "nginx:1.27", name = "web")

    @Test
    fun minimal_form_gives_minimal_skeleton() {
        val (name, body) = NewContainerBuilder.build(minimal)
        assertEquals("web", name)
        assertEquals(
            obj(
                """{"Image": "nginx:1.27", "Env": [], "Labels": {},
                   "HostConfig": {"RestartPolicy": {"Name": "no", "MaximumRetryCount": 0}}}""",
            ),
            body,
        )
        assertTrue(NewContainerBuilder.extraNetworks(minimal).isEmpty())
    }

    @Test
    fun full_form() {
        val form = minimal.copy(
            command = listOf("nginx", "-g", "daemon off;"),
            entrypoint = emptyList(),
            workingDir = "/srv",
            user = "101",
            ports = listOf(PortRow("", "8080", 80, "tcp"), PortRow("127.0.0.1", "", 53, "udp"), PortRow("::", "8080", 80, "tcp")),
            mounts = listOf(
                MountRow(MountType.Bind, "/srv/site", "/usr/share/nginx/html", true, MountOrigin.New, null),
                MountRow(MountType.Volume, "web-cache", "/var/cache/nginx", false, MountOrigin.New, null),
                MountRow(MountType.Volume, "", "/scratch", false, MountOrigin.New, null),
                MountRow(MountType.Tmpfs, "", "/tmp", false, MountOrigin.New, null),
            ),
            primaryNetwork = NetworkRow("frontend", listOf("web"), "172.21.0.9", "", "02:42:ac:15:00:09"),
            extraNetworks = listOf(NetworkRow("backend", emptyList(), "", "", "")),
            env = listOf("TZ=UTC"),
            labels = listOf("tier" to "web"),
            restart = RestartPolicyForm("on-failure", 3),
            memoryBytes = 268435456,
            memoryReservationBytes = 134217728,
            nanoCpus = 500000000,
            cpuShares = 512,
        )
        val (_, body) = NewContainerBuilder.build(form)
        assertEquals(
            obj(
                """{
                  "Image": "nginx:1.27",
                  "Env": ["TZ=UTC"],
                  "Labels": {"tier": "web"},
                  "Cmd": ["nginx", "-g", "daemon off;"],
                  "Entrypoint": [],
                  "WorkingDir": "/srv",
                  "User": "101",
                  "ExposedPorts": {"80/tcp": {}, "53/udp": {}},
                  "HostConfig": {
                    "RestartPolicy": {"Name": "on-failure", "MaximumRetryCount": 3},
                    "PortBindings": {
                      "80/tcp": [{"HostIp": "", "HostPort": "8080"}, {"HostIp": "::", "HostPort": "8080"}],
                      "53/udp": [{"HostIp": "127.0.0.1", "HostPort": ""}]
                    },
                    "Binds": ["/srv/site:/usr/share/nginx/html:ro", "web-cache:/var/cache/nginx", "/scratch"],
                    "Mounts": [{"Type": "tmpfs", "Target": "/tmp"}],
                    "Memory": 268435456,
                    "MemoryReservation": 134217728,
                    "NanoCpus": 500000000,
                    "CpuShares": 512,
                    "NetworkMode": "frontend"
                  },
                  "NetworkingConfig": {"EndpointsConfig": {"frontend": {
                    "Aliases": ["web"], "IPAMConfig": {"IPv4Address": "172.21.0.9"}, "MacAddress": "02:42:ac:15:00:09"
                  }}}
                }""",
            ),
            body,
        )
        assertEquals(mapOf("backend" to JsonObject(emptyMap())), NewContainerBuilder.extraNetworks(form))
    }

    @Test
    fun publish_all_and_read_only_network_modes() {
        val (_, host) = NewContainerBuilder.build(minimal.copy(publishAll = true, networkModeReadOnly = "host"))
        assertEquals(
            obj("""{"Image": "nginx:1.27", "Env": [], "Labels": {}, "HostConfig": {"RestartPolicy": {"Name": "no", "MaximumRetryCount": 0}, "PublishAllPorts": true, "NetworkMode": "host"}}"""),
            host,
        )
        val readOnlyTmpfs = MountRow(MountType.Tmpfs, "", "/run", true, MountOrigin.New, null)
        val (_, body) = NewContainerBuilder.build(minimal.copy(mounts = listOf(readOnlyTmpfs)))
        assertEquals(obj("""[{"Type": "tmpfs", "Target": "/run", "ReadOnly": true}]""".let { "{\"m\": $it}" })["m"], body["HostConfig"]!!.jsonObject["Mounts"])
    }
}
