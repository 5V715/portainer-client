package dev.silas.portainer.app.editor

import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.settle
import dev.silas.portainer.core.editor.FormField
import dev.silas.portainer.core.editor.FormParser
import dev.silas.portainer.core.editor.MountOrigin
import dev.silas.portainer.core.editor.MountType
import dev.silas.portainer.core.editor.NetworkRow
import dev.silas.portainer.core.recreate.PROTECTED_IMAGE_MESSAGE
import dev.silas.portainer.core.storage.ServerConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requestLog = MutableStateFlow(emptyList<String>())
    private val requests: List<String> get() = requestLog.value
    private var image = "ghcr.io/acme/app:1"
    private var inspectGone = false
    private var volumesFail = false
    private var hostMounts = """[{"Type":"tmpfs","Target":"/tmp"}]"""
    private var env = """["A=1","B=2"]"""

    private val anonName = "a".repeat(64)

    private fun inspect() = """{
      "Id":"abcdef1234567890aaaa","Name":"/web","Image":"sha256:old",
      "State":{"Status":"running","Running":true},
      "Config":{
        "Image":"$image","Cmd":["sh","-c","echo hi there"],"Entrypoint":["/entry"],"WorkingDir":"/srv","User":"1000",
        "Env":$env,"Labels":{"tier":"web"},"ExposedPorts":{"80/tcp":{}},
        "Healthcheck":{"Test":["CMD-SHELL","curl -f localhost"]}
      },
      "HostConfig":{
        "NetworkMode":"front","PortBindings":{"80/tcp":[{"HostIp":"","HostPort":"8080"}]},"PublishAllPorts":false,
        "Binds":["/srv/data:/data:ro","cache:/cache","/a:/b:"],
        "Mounts":$hostMounts,
        "RestartPolicy":{"Name":"on-failure","MaximumRetryCount":3},
        "Memory":536870912,"MemoryReservation":0,"NanoCpus":1500000000,"CpuShares":512,
        "Privileged":true,"CapAdd":["NET_ADMIN"],
        "Devices":[{"PathOnHost":"/dev/snd","PathInContainer":"/dev/snd","CgroupPermissions":"rwm"}],
        "LogConfig":{"Type":"json-file","Config":{}},"Dns":["1.1.1.1"],"ExtraHosts":["db:10.0.0.5"]
      },
      "Mounts":[
        {"Type":"bind","Source":"/srv/data","Destination":"/data"},
        {"Type":"volume","Name":"cache","Destination":"/cache"},
        {"Type":"bind","Source":"/a","Destination":"/b"},
        {"Type":"volume","Name":"$anonName","Destination":"/var/lib/anon"},
        {"Type":"tmpfs","Destination":"/tmp"}
      ],
      "NetworkSettings":{"Networks":{
        "front":{"NetworkID":"n2","Aliases":["web","abcdef123456"],"IPAMConfig":{"IPv4Address":"172.20.0.5"},"MacAddress":"02:42:ac:14:00:05"},
        "back":{"NetworkID":"n3","Aliases":null,"MacAddress":"02:42:ac:15:00:02"}
      }}
    }"""

    private val registries = """[
      {"Id":7,"Name":"GitHub","Type":8,"URL":"ghcr.io","Authentication":true,"Username":"me"},
      {"Id":3,"Name":"Hub","Type":6,"URL":"docker.io","Authentication":false}
    ]"""

    private val volumes = """{"Volumes":[{"Name":"cache","Driver":"local"},{"Name":"$anonName","Driver":"local"},{"Name":"db","Driver":"local"}]}"""

    private val networks = """[
      {"Id":"n1","Name":"bridge","Driver":"bridge"},{"Id":"n2","Name":"front","Driver":"bridge"},
      {"Id":"n3","Name":"back","Driver":"bridge"},{"Id":"n4","Name":"host","Driver":"host"}
    ]"""

    private suspend fun MockRequestHandleScope.handle(r: HttpRequestData): HttpResponseData {
        val path = r.url.encodedPath
        requestLog.update { it + "${r.method.value} ${path.substringAfter("/docker", path)}" }
        return when {
            path.endsWith("/containers/abc/json") ->
                if (inspectGone) respond("""{"message":"No such container: abc"}""", HttpStatusCode.NotFound, json)
                else respond(inspect(), HttpStatusCode.OK, json)
            path.endsWith("/api/registries") -> respond(registries, HttpStatusCode.OK, json)
            path.endsWith("/volumes") ->
                if (volumesFail) respond("""{"message":"denied"}""", HttpStatusCode.Forbidden, json)
                else respond(volumes, HttpStatusCode.OK, json)
            path.endsWith("/networks") -> respond(networks, HttpStatusCode.OK, json)
            else -> respond("""{"message":"unexpected"}""", HttpStatusCode.NotFound, json)
        }
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun env(): TestEnv {
        val env = TestEnv { handle(it) }
        env.repo.save(ServerConfig("s1", "A", "https://a.example.com"), "k")
        return env
    }

    private suspend fun TestScope.loaded(containerId: String? = "abc"): EditorViewModel {
        val vm = EditorViewModel(env().container, "s1", 1, containerId)
        settle { vm.state.value.phase != EditorPhase.Loading }
        return vm
    }

    private fun EditorViewModel.mountIndex(target: String) = state.value.draft.mounts.indexOfFirst { it.target == target }

    @Test
    fun loadFromInspectPopulatesAllSections() = runTest {
        val vm = loaded()
        val s = vm.state.value
        assertEquals(EditorPhase.Ready, s.phase)
        assertEquals(EditorMode.Edit, s.mode)
        val parsed = FormParser.fromInspect(Json.parseToJsonElement(inspect()) as JsonObject)
        assertEquals(parsed, s.before)
        assertEquals(parsed, s.form)
        assertEquals(emptySet(), s.dirty)
        assertEquals(emptyMap(), s.errors)

        // Image
        assertEquals("ghcr.io/acme/app:1", s.draft.image)
        assertEquals(7, s.registryId)
        assertFalse(s.pull, "pull defaults off in Edit while the image is unchanged")
        // General
        assertEquals("web", s.draft.name)
        assertEquals("sh -c 'echo hi there'", s.draft.command)
        assertEquals("/entry", s.draft.entrypoint)
        assertEquals("/srv", s.draft.workingDir)
        assertEquals("1000", s.draft.user)
        // Ports
        assertEquals(listOf(PortDraft("", "8080", "80", "tcp")), s.draft.ports)
        // Volumes
        assertEquals(listOf("/data", "/cache", "/b", "/tmp"), s.draft.mounts.map { it.target })
        assertEquals(listOf("cache", "db"), s.volumes, "anonymous volumes are not offered in the picker")
        assertEquals(listOf("/var/lib/anon"), s.anonymousVolumes)
        assertTrue(s.isLocked(s.draft.mounts[vm.mountIndex("/b")]))
        assertFalse(s.isLocked(s.draft.mounts[vm.mountIndex("/data")]))
        // Networks
        assertEquals(listOf("front", "back"), s.draft.networks.map { it.name })
        assertEquals(NetworkDraft("front", "web", "172.20.0.5", "", "02:42:ac:14:00:05"), s.draft.networks[0])
        assertEquals(listOf("bridge", "front", "back", "host"), s.networks)
        // Env, labels
        assertEquals(listOf("A=1", "B=2"), s.draft.env)
        assertEquals(listOf("tier" to "web"), s.draft.labels)
        // Restart, resources
        assertEquals("on-failure", s.draft.restartName)
        assertEquals("3", s.draft.maxRetries)
        assertEquals("512m", s.draft.memory)
        assertEquals("", s.draft.memoryReservation)
        assertEquals("1.5", s.draft.cpus)
        assertEquals("512", s.draft.cpuShares)
        // Advanced
        val advanced = s.advanced.toMap()
        assertEquals("Yes", advanced["Privileged"])
        assertEquals("NET_ADMIN", advanced["Capabilities added"])
        assertEquals("/dev/snd → /dev/snd", advanced["Devices"])
        assertEquals("curl -f localhost", advanced["Healthcheck"])
        assertEquals("1.1.1.1", advanced["DNS servers"])
        assertEquals("db:10.0.0.5", advanced["Extra hosts"])
        assertEquals("json-file", advanced["Log driver"])
        // Actions
        assertNull(s.readOnlyMessage)
        assertTrue(s.canReplace)
        assertTrue(s.canCopy)
        assertFalse(s.canCreate)
    }

    @Test
    fun editingAPortMarksPortsDirtyOnly() = runTest {
        val vm = loaded()
        vm.updatePort(0, vm.state.value.draft.ports[0].copy(hostPort = "9090"))
        val s = vm.state.value
        assertEquals(setOf(FormField.Ports), s.dirty)
        assertEquals("9090", s.form.ports.single().hostPort)
        assertEquals(s.before.copy(ports = s.form.ports), s.form)
    }

    @Test
    fun validationMessages() = runTest {
        val vm = loaded()
        vm.setImage(" ")
        vm.addPort()
        vm.updatePort(1, PortDraft("1.2.3", "70000", "0", "tcp"))
        vm.addMount()
        val m = vm.state.value.draft.mounts.lastIndex
        vm.updateMount(m, vm.state.value.draft.mounts[m].copy(type = MountType.Bind, source = "rel/path", target = "data"))
        vm.updateNetwork(1, vm.state.value.draft.networks[1].copy(ipv4 = "10.0.0", ipv6 = "zz::1"))
        vm.addLabel()
        vm.setLabel(1, "tier", "x")
        vm.setMemory("12q")
        vm.setMemoryReservation("-1g")
        vm.setCpus("0")
        val e = vm.state.value.errors
        assertEquals(listOf("Image is required"), e[EditorSection.Image])
        assertEquals(
            listOf(
                "Port 2: container port must be 1–65535",
                "Port 2: host port must be empty or 1–65535",
                "Port 2: host IP is not a valid IP address",
            ),
            e[EditorSection.Ports],
        )
        assertEquals(
            listOf("Mount 5: container path must be an absolute path", "Mount 5: host path must be an absolute path"),
            e[EditorSection.Volumes],
        )
        assertEquals(
            listOf("Network 2: IPv4 address is not valid", "Network 2: IPv6 address is not valid"),
            e[EditorSection.Networks],
        )
        assertEquals(listOf("Duplicate label: tier"), e[EditorSection.Labels])
        assertEquals(
            listOf(
                "Memory limit: use bytes or a size like 512m or 1g",
                "Memory reservation: use bytes or a size like 512m or 1g",
                "CPU limit must be a number of cores greater than 0",
            ),
            e[EditorSection.Resources],
        )
        assertFalse(vm.review(FinishAction.Replace))
        assertTrue(vm.state.value.showErrors)
    }

    @Test
    fun validValuesPassAndParse() = runTest {
        val vm = loaded()
        vm.setMemory("1g")
        assertEquals(1L shl 30, vm.state.value.form.memoryBytes)
        assertEquals(setOf(FormField.Resources), vm.state.value.dirty)
        vm.setMemory("1048576")
        assertEquals(1_048_576L, vm.state.value.form.memoryBytes)
        vm.setMemory("")
        assertNull(vm.state.value.form.memoryBytes)
        vm.setMemoryReservation("512M")
        assertEquals(512L shl 20, vm.state.value.form.memoryReservationBytes)
        vm.setCpus("0.25")
        assertEquals(250_000_000L, vm.state.value.form.nanoCpus)
        vm.updateNetwork(1, vm.state.value.draft.networks[1].copy(ipv4 = "10.0.0.9", ipv6 = "fd00::9"))
        vm.updatePort(0, PortDraft("::", "", "443", "udp"))
        assertEquals(emptyMap(), vm.state.value.errors)
        assertTrue(vm.review(FinishAction.Replace))
    }

    @Test
    fun newStartsFromTheEmptyFormWithPullAndStartOn() = runTest {
        val vm = loaded(containerId = null)
        val s = vm.state.value
        assertEquals(EditorPhase.Ready, s.phase)
        assertEquals(EditorMode.New, s.mode)
        val expected = FormParser.empty().copy(primaryNetwork = NetworkRow("bridge", emptyList(), "", "", ""))
        assertEquals(expected, s.form)
        assertEquals(expected, s.before)
        assertTrue(s.pull)
        assertTrue(s.startAfterCreate)
        assertTrue(requests.none { "/containers/" in it }, "New mode inspects no container")
        assertTrue(s.canCreate)
        assertFalse(s.canReplace)
        assertFalse(s.canCopy)
        assertEquals(listOf("Image is required"), s.errors[EditorSection.Image])
        assertFalse(s.showErrors)
        assertFalse(vm.review(FinishAction.Create))
        assertTrue(vm.state.value.showErrors)
        vm.setImage("nginx:1")
        vm.setStartAfterCreate(false)
        assertFalse(vm.state.value.startAfterCreate)
        assertTrue(vm.review(FinishAction.Create))
    }

    @Test
    fun protectedImageOpensReadOnlyWithOnlyCopy() = runTest {
        image = "portainer/portainer-ce:latest"
        val vm = loaded()
        val s = vm.state.value
        assertEquals(PROTECTED_IMAGE_MESSAGE, s.readOnlyMessage)
        assertFalse(s.canReplace)
        assertTrue(s.canCopy)
        vm.setName("other")
        vm.updatePort(0, s.draft.ports[0].copy(hostPort = "1"))
        assertEquals(s.form, vm.state.value.form, "a read-only form ignores edits")
        assertFalse(vm.review(FinishAction.Replace))
        assertTrue(vm.review(FinishAction.Copy))
    }

    @Test
    fun lockedMountIgnoresEditsButCanBeRemoved() = runTest {
        val vm = loaded()
        val i = vm.mountIndex("/b")
        val row = vm.state.value.draft.mounts[i]
        vm.updateMount(i, row.copy(readOnly = true))
        assertEquals(emptySet(), vm.state.value.dirty)
        vm.removeMount(i)
        assertEquals(setOf(FormField.Mounts), vm.state.value.dirty)
        assertTrue(vm.state.value.form.mounts.none { it.target == "/b" })
    }

    @Test
    fun bindsRowTypeFollowsSource() = runTest {
        val vm = loaded()
        val i = vm.mountIndex("/cache")
        val row = vm.state.value.draft.mounts[i]
        assertEquals(MountOrigin.Binds, row.origin)
        vm.updateMount(i, row.copy(source = "/host/cache"))
        assertEquals(MountType.Bind, vm.state.value.form.mounts[i].type)
        vm.updateMount(i, vm.state.value.draft.mounts[i].copy(source = "other"))
        assertEquals(MountType.Volume, vm.state.value.form.mounts[i].type)
        vm.updateMount(i, vm.state.value.draft.mounts[i].copy(type = MountType.Tmpfs))
        assertEquals(MountType.Volume, vm.state.value.form.mounts[i].type, "no type switch a Binds string can't hold")
    }

    @Test
    fun typedMacsAndAliasesAreOnlyTheChangedOnes() = runTest {
        val vm = loaded()
        assertEquals(emptySet(), vm.typedMacs())
        assertEquals(emptySet(), vm.typedAliases())
        vm.updateNetwork(0, vm.state.value.draft.networks[0].copy(aliases = "web, api", mac = "02:00:00:00:00:01"))
        assertEquals(setOf("api"), vm.typedAliases())
        assertEquals(setOf("02:00:00:00:00:01"), vm.typedMacs())
        assertEquals(listOf("web", "api"), vm.state.value.form.primaryNetwork?.aliases)
        assertEquals(setOf(FormField.Networks), vm.state.value.dirty)
    }

    @Test
    fun pullFollowsImageChangeUntilTouched() = runTest {
        val vm = loaded()
        vm.setImage("ghcr.io/acme/app:2")
        assertTrue(vm.state.value.pull)
        vm.setImage("ghcr.io/acme/app:1")
        assertFalse(vm.state.value.pull)
        vm.setImage("ghcr.io/acme/app:3")
        vm.setPull(false)
        vm.setImage("ghcr.io/acme/app:4")
        assertFalse(vm.state.value.pull)
        vm.setImage("docker.io/library/nginx")
        assertEquals(3, vm.state.value.registryId)
    }

    @Test
    fun commandTextKeepsOriginalUntilChangedAndBlankMeansImageDefault() = runTest {
        val vm = loaded()
        vm.setCommand("sh -c 'echo hi there' ")
        assertEquals(listOf("sh", "-c", "echo hi there"), vm.state.value.form.command)
        assertEquals(emptySet(), vm.state.value.dirty)
        vm.setCommand("")
        assertNull(vm.state.value.form.command)
        assertEquals(setOf(FormField.Command), vm.state.value.dirty)
    }

    @Test
    fun loadFailureShowsAnErrorAndOptionalListsMayFail() = runTest {
        inspectGone = true
        val failed = loaded()
        val phase = failed.state.value.phase
        assertIs<EditorPhase.Error>(phase)
        assertTrue("No such container" in phase.message)

        inspectGone = false
        volumesFail = true
        val vm = loaded()
        assertEquals(EditorPhase.Ready, vm.state.value.phase)
        assertEquals(emptyList(), vm.state.value.volumes)
    }

    @Test
    fun typedMacsAndAliasesIgnoreLoadedValuesMovedToAnotherNetwork() = runTest {
        val vm = loaded()
        val front = vm.state.value.draft.networks[0]
        vm.updateNetwork(0, front.copy(name = "other", aliases = "web, abcdef123456"))
        assertEquals(emptySet(), vm.typedMacs(), "the loaded MAC on a renamed row isn't typed")
        assertEquals(emptySet(), vm.typedAliases(), "loaded aliases and the short id aren't typed")
        vm.updateNetwork(1, vm.state.value.draft.networks[1].copy(aliases = "db", mac = "02:00:00:00:00:09"))
        assertEquals(setOf("db"), vm.typedAliases())
        assertEquals(setOf("02:00:00:00:00:09"), vm.typedMacs())
    }

    @Test
    fun hostAndNoneDropEndpointSettings() = runTest {
        val vm = loaded(containerId = null)
        vm.updateNetwork(0, NetworkDraft("host", "web", "bad-ip", "zz", "nope"))
        val s = vm.state.value
        assertEquals(NetworkRow("host", emptyList(), "", "", ""), s.form.primaryNetwork)
        assertNull(s.errors[EditorSection.Networks])
    }

    @Test
    fun envKeyWithoutValueRestoresItsKeyOnlyForm() = runTest {
        env = """["A=1","FLAG"]"""
        val vm = loaded()
        vm.setEnvRow(1, "FLAG", "x")
        assertEquals(setOf(FormField.Env), vm.state.value.dirty)
        vm.setEnvRow(1, "FLAG", "")
        assertEquals(listOf("A=1", "FLAG"), vm.state.value.form.env)
        assertEquals(emptySet(), vm.state.value.dirty)
    }

    @Test
    fun loadedDuplicateEnvKeysDontBlockAnUntouchedReplace() = runTest {
        env = """["A=1","A=2"]"""
        val vm = loaded()
        vm.setName("web2")
        assertNull(vm.state.value.errors[EditorSection.Env])
        assertTrue(vm.review(FinishAction.Replace))
        vm.addEnvRow()
        assertEquals(listOf("Duplicate variable: A"), vm.state.value.errors[EditorSection.Env])
    }

    @Test
    fun typeThenRevertLeavesNothingDirty() = runTest {
        hostMounts = """[{"Type":"volume","Source":"logs","Target":"/logs"}]"""
        val vm = loaded()
        val port = vm.state.value.draft.ports[0]
        vm.updatePort(0, port.copy(hostPort = "80801"))
        vm.updatePort(0, port)
        vm.setEnvRow(0, "A", "12")
        vm.setEnvRow(0, "A", "1")
        val i = vm.mountIndex("/logs")
        val row = vm.state.value.draft.mounts[i]
        vm.updateMount(i, row.copy(readOnly = true))
        vm.updateMount(i, vm.state.value.draft.mounts[i].copy(type = MountType.Tmpfs))
        assertEquals("", vm.state.value.form.mounts[i].source)
        vm.updateMount(i, vm.state.value.draft.mounts[i].copy(type = MountType.Volume, readOnly = false))
        assertEquals(row, vm.state.value.form.mounts[i], "switching back from tmpfs restores the source")
        vm.setWorkingDir(" /srv ")
        assertEquals(emptySet(), vm.state.value.dirty)
        assertFalse(vm.state.value.hasUnsavedChanges)
        vm.setPull(true)
        assertTrue(vm.state.value.hasUnsavedChanges, "a changed pull switch counts as unsaved")
    }

    @Test
    fun resourceAndMountLimits() = runTest {
        val vm = loaded()
        vm.setMemory("4m")
        vm.setMemoryReservation("1g")
        vm.setCpus("0.1234567891")
        assertEquals(
            listOf(
                "Memory limit must be at least 6m",
                "Memory reservation can't be more than the memory limit",
                "CPU limit: use at most 9 decimal places",
            ),
            vm.state.value.errors[EditorSection.Resources],
        )
        vm.addMount()
        val m = vm.state.value.draft.mounts.lastIndex
        vm.updateMount(m, vm.state.value.draft.mounts[m].copy(source = "x", target = "/data"))
        assertEquals(listOf("Mount 5: /data is already mounted"), vm.state.value.errors[EditorSection.Volumes])
    }

    @Test
    fun parseEdgeCases() {
        assertNull(parseMemory("9999999999t"), "overflow is rejected")
        assertEquals(1_610_612_736L, parseMemory("1.5g"))
        assertEquals(250_000_000L, parseCpus(".25"))
        assertNull(parseCpus("0.1234567891"))
        assertNull(parseCpus("."))
        assertEquals("1.5", formatCpus(1_500_000_000L))
        assertEquals("1536m", formatMemory(1_610_612_736L))
    }

    @Test
    fun editedRowBeforeALoadedRowWithTheSameTargetIsFlagged() = runTest {
        val vm = loaded()
        val first = vm.state.value.draft.mounts[0]
        vm.updateMount(0, first.copy(target = "/cache"))
        assertEquals(listOf("Mount 1: /cache is already mounted"), vm.state.value.errors[EditorSection.Volumes])
    }
}
