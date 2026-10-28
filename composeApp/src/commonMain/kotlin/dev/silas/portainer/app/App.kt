package dev.silas.portainer.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.silas.portainer.app.about.AboutScreen
import dev.silas.portainer.app.cleanup.CleanupViewModel
import dev.silas.portainer.app.containers.ContainerListScreen
import dev.silas.portainer.app.containers.ContainerListViewModel
import dev.silas.portainer.app.detail.ContainerDetailScreen
import dev.silas.portainer.app.detail.ContainerDetailViewModel
import dev.silas.portainer.app.detail.LogsViewModel
import dev.silas.portainer.app.detail.QuickRecreateViewModel
import dev.silas.portainer.app.editor.EditorScreen
import dev.silas.portainer.app.editor.EditorViewModel
import dev.silas.portainer.app.environment.EnvTab
import dev.silas.portainer.app.environment.EnvironmentShell
import dev.silas.portainer.app.environments.EnvironmentsScreen
import dev.silas.portainer.app.environments.EnvironmentsViewModel
import dev.silas.portainer.app.images.ImageDetailScreen
import dev.silas.portainer.app.images.ImageDetailViewModel
import dev.silas.portainer.app.images.ImagesScreen
import dev.silas.portainer.app.images.ImagesViewModel
import dev.silas.portainer.app.networks.NetworkDetailScreen
import dev.silas.portainer.app.networks.NetworkDetailViewModel
import dev.silas.portainer.app.networks.NetworksScreen
import dev.silas.portainer.app.networks.NetworksViewModel
import dev.silas.portainer.app.stacks.CreateStackScreen
import dev.silas.portainer.app.stacks.CreateStackViewModel
import dev.silas.portainer.app.stacks.CreatedStack
import dev.silas.portainer.app.stacks.GitSettingsViewModel
import dev.silas.portainer.app.stacks.StackDetailScreen
import dev.silas.portainer.app.stacks.StackDetailViewModel
import dev.silas.portainer.app.stacks.StackEditorViewModel
import dev.silas.portainer.app.stacks.StacksScreen
import dev.silas.portainer.app.stacks.StacksViewModel
import dev.silas.portainer.app.volumes.VolumeDetailScreen
import dev.silas.portainer.app.volumes.VolumeDetailViewModel
import dev.silas.portainer.app.volumes.VolumesScreen
import dev.silas.portainer.app.volumes.VolumesViewModel
import dev.silas.portainer.app.registries.RegistriesScreen
import dev.silas.portainer.app.registries.RegistriesViewModel
import dev.silas.portainer.app.registries.RegistryEditScreen
import dev.silas.portainer.app.registries.RegistryEditViewModel
import dev.silas.portainer.app.recreate.RecreateScreen
import dev.silas.portainer.app.recreate.RecreateViewModel
import dev.silas.portainer.app.servers.ServerEditScreen
import dev.silas.portainer.app.servers.ServerEditViewModel
import dev.silas.portainer.app.servers.ServerListScreen
import dev.silas.portainer.app.servers.ServersViewModel
import dev.silas.portainer.app.theme.PortainerTheme
import dev.silas.portainer.core.cleanup.CleanupKind
import dev.silas.portainer.core.storage.ServerRepository
import dev.silas.portainer.core.storage.createSecretStore
import kotlinx.serialization.KSerializer

/** Process-wide dependencies; on Android, AndroidPlatform/AppContext must be initialised before first access. */
object AppGraph {
    val container: AppContainer by lazy {
        AppContainer(ServerRepository(createSettings(), createSecretStore()))
    }
}

private const val REGISTRIES_REFRESH = "registriesRefresh"

val LocalAppContainer = compositionLocalOf<AppContainer> { error("AppContainer not provided") }

@Composable
fun App() {
    val container = AppGraph.container
    PortainerTheme {
        CompositionLocalProvider(LocalAppContainer provides container) {
            AppNavHost(container)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION") // BackHandler: the NavigationEventHandler replacement is not worth another dependency here
@Composable
private fun AppNavHost(container: AppContainer) {
    val nav = rememberNavController()

    // Reopen the last environment when there is exactly one server; back leads to Environments.
    // Once per cold start: the saved flag survives Activity recreation, which also restores the back stack.
    var autoOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (autoOpened) return@LaunchedEffect
        autoOpened = true
        val only = container.servers.servers.value.singleOrNull()
        val endpointId = only?.lastEndpointId
        if (only != null && endpointId != null) {
            nav.navigate(Environments(only.id))
            nav.navigate(Containers(only.id, endpointId))
        }
    }

    NavHost(nav, startDestination = ServerList) {
        composable<ServerList> {
            val vm = viewModel { ServersViewModel(container) }
            ServerListScreen(
                viewModel = vm,
                onOpen = { nav.navigate(Environments(it.id)) },
                onAdd = { nav.navigate(ServerEdit(null)) },
                onEdit = { nav.navigate(ServerEdit(it.id)) },
                onAbout = { nav.navigate(About) },
            )
        }
        composable<About> { AboutScreen(onBack = { nav.popBackStack() }) }
        composable<ServerEdit> { entry ->
            val route = entry.toRoute<ServerEdit>()
            val vm = viewModel(key = "edit-${route.serverId}") { ServerEditViewModel(container, route.serverId) }
            ServerEditScreen(vm, onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() })
        }
        composable<Environments> { entry ->
            val route = entry.toRoute<Environments>()
            val vm = viewModel(key = "env-${route.serverId}") { EnvironmentsViewModel(container, route.serverId) }
            EnvironmentsScreen(
                viewModel = vm,
                onSelect = { nav.navigate(Containers(route.serverId, it)) },
                onRegistries = { nav.navigate(Registries(route.serverId)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable<Containers> { entry ->
            val route = entry.toRoute<Containers>()
            val vm = viewModel(key = "containers-${route.serverId}-${route.endpointId}") {
                ContainerListViewModel(container, route.serverId, route.endpointId)
            }
            val cleanup = viewModel(key = "cleanup-containers-${route.serverId}-${route.endpointId}") {
                CleanupViewModel(container, route.serverId, route.endpointId, CleanupKind.Containers, onFinished = vm::refresh)
            }
            EnvironmentShell(EnvTab.Containers, { nav.switchTab(route.serverId, route.endpointId, it) }) {
                ContainerListScreen(
                    viewModel = vm,
                    cleanup = cleanup,
                    onOpen = { nav.navigate(ContainerDetail(route.serverId, route.endpointId, it)) },
                    onNew = { nav.navigate(Editor(route.serverId, route.endpointId, null)) },
                    onRegistries = { nav.navigate(Registries(route.serverId)) },
                    onBack = { nav.popBackStack() },
                )
            }
        }
        composable<Images> { entry ->
            val route = entry.toRoute<Images>()
            val vm = viewModel(key = "images-${route.serverId}-${route.endpointId}") {
                ImagesViewModel(container, route.serverId, route.endpointId)
            }
            val cleanup = viewModel(key = "cleanup-images-${route.serverId}-${route.endpointId}") {
                CleanupViewModel(container, route.serverId, route.endpointId, CleanupKind.Images, onFinished = vm::refresh)
            }
            val toEnvironments = { nav.popBackStack(Environments(route.serverId), inclusive = false); Unit }
            BackHandler(onBack = toEnvironments)
            EnvironmentShell(EnvTab.Images, { nav.switchTab(route.serverId, route.endpointId, it) }) {
                ImagesScreen(
                    viewModel = vm,
                    cleanup = cleanup,
                    nowEpochSeconds = container.nowEpochSeconds,
                    onOpen = { nav.navigate(ImageDetail(route.serverId, route.endpointId, it)) },
                    onBack = toEnvironments,
                )
            }
        }
        composable<ImageDetail> { entry ->
            val route = entry.toRoute<ImageDetail>()
            val vm = viewModel(key = "image-${route.serverId}-${route.endpointId}-${route.imageId}") {
                ImageDetailViewModel(container, route.serverId, route.endpointId, route.imageId)
            }
            ImageDetailScreen(
                viewModel = vm,
                onOpenContainer = { nav.navigate(ContainerDetail(route.serverId, route.endpointId, it)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable<Volumes> { entry ->
            val route = entry.toRoute<Volumes>()
            val vm = viewModel(key = "volumes-${route.serverId}-${route.endpointId}") {
                VolumesViewModel(container, route.serverId, route.endpointId)
            }
            val cleanup = viewModel(key = "cleanup-volumes-${route.serverId}-${route.endpointId}") {
                CleanupViewModel(container, route.serverId, route.endpointId, CleanupKind.Volumes, onFinished = vm::refresh)
            }
            val toEnvironments = { nav.popBackStack(Environments(route.serverId), inclusive = false); Unit }
            BackHandler(onBack = toEnvironments)
            EnvironmentShell(EnvTab.Volumes, { nav.switchTab(route.serverId, route.endpointId, it) }) {
                VolumesScreen(
                    viewModel = vm,
                    cleanup = cleanup,
                    nowEpochSeconds = container.nowEpochSeconds,
                    onOpen = { nav.navigate(VolumeDetail(route.serverId, route.endpointId, it)) },
                    onBack = toEnvironments,
                )
            }
        }
        composable<VolumeDetail> { entry ->
            val route = entry.toRoute<VolumeDetail>()
            val vm = viewModel(key = "volume-${route.serverId}-${route.endpointId}-${route.name}") {
                VolumeDetailViewModel(container, route.serverId, route.endpointId, route.name)
            }
            VolumeDetailScreen(
                viewModel = vm,
                onOpenContainer = { nav.navigate(ContainerDetail(route.serverId, route.endpointId, it)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable<Networks> { entry ->
            val route = entry.toRoute<Networks>()
            val vm = viewModel(key = "networks-${route.serverId}-${route.endpointId}") {
                NetworksViewModel(container, route.serverId, route.endpointId)
            }
            val cleanup = viewModel(key = "cleanup-networks-${route.serverId}-${route.endpointId}") {
                CleanupViewModel(container, route.serverId, route.endpointId, CleanupKind.Networks, onFinished = vm::refresh)
            }
            val toEnvironments = { nav.popBackStack(Environments(route.serverId), inclusive = false); Unit }
            BackHandler(onBack = toEnvironments)
            EnvironmentShell(EnvTab.Networks, { nav.switchTab(route.serverId, route.endpointId, it) }) {
                NetworksScreen(
                    viewModel = vm,
                    cleanup = cleanup,
                    onOpen = { nav.navigate(NetworkDetail(route.serverId, route.endpointId, it)) },
                    onBack = toEnvironments,
                )
            }
        }
        composable<NetworkDetail> { entry ->
            val route = entry.toRoute<NetworkDetail>()
            val vm = viewModel(key = "network-${route.serverId}-${route.endpointId}-${route.networkId}") {
                NetworkDetailViewModel(container, route.serverId, route.endpointId, route.networkId)
            }
            NetworkDetailScreen(
                viewModel = vm,
                onOpenContainer = { nav.navigate(ContainerDetail(route.serverId, route.endpointId, it)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable<Stacks> { entry ->
            val route = entry.toRoute<Stacks>()
            val vm = viewModel(key = "stacks-${route.serverId}-${route.endpointId}") {
                StacksViewModel(container, route.serverId, route.endpointId)
            }
            val toEnvironments = { nav.popBackStack(Environments(route.serverId), inclusive = false); Unit }
            BackHandler(onBack = toEnvironments)
            EnvironmentShell(EnvTab.Stacks, { nav.switchTab(route.serverId, route.endpointId, it) }) {
                StacksScreen(
                    viewModel = vm,
                    onOpen = { nav.navigate(StackDetail(route.serverId, route.endpointId, it.id, it.name)) { launchSingleTop = true } },
                    onCreate = { nav.navigate(CreateStack(route.serverId, route.endpointId)) },
                    onBack = toEnvironments,
                )
            }
        }
        composable<CreateStack> { entry ->
            val route = entry.toRoute<CreateStack>()
            val vm = viewModel(key = "create-stack-${route.serverId}-${route.endpointId}") {
                CreateStackViewModel(container, route.serverId, route.endpointId)
            }
            val open = { created: CreatedStack ->
                // The create screen is done: Back from the new stack leads to the list.
                nav.navigate(StackDetail(route.serverId, route.endpointId, created.id, created.name)) { popUpTo(route) { inclusive = true } }
            }
            CreateStackScreen(viewModel = vm, onCreated = open, onOpenStack = open, onBack = { nav.popBackStack() })
        }
        composable<StackDetail> { entry ->
            val route = entry.toRoute<StackDetail>()
            val vm = viewModel(key = "stack-${route.serverId}-${route.endpointId}-${route.stackId}-${route.name}") {
                StackDetailViewModel(container, route.serverId, route.endpointId, route.stackId, route.name)
            }
            val editor = viewModel(key = "stack-editor-${route.serverId}-${route.endpointId}-${route.stackId}-${route.name}") {
                StackEditorViewModel(container, vm, route.serverId, route.endpointId, route.stackId, route.name)
            }
            val git = viewModel(key = "stack-git-${route.serverId}-${route.endpointId}-${route.stackId}-${route.name}") {
                GitSettingsViewModel(container, vm, route.serverId, route.endpointId, route.stackId, route.name)
            }
            StackDetailScreen(
                viewModel = vm,
                editor = editor,
                git = git,
                onOpenContainer = { id, unsavedEdits ->
                    val target = ContainerDetail(route.serverId, route.endpointId, id)
                    if (canPopTo(target, nav.currentBackStack.value.map(::routeOf), unsavedEdits)) nav.popBackStack(target, inclusive = false)
                    else nav.navigate(target) { launchSingleTop = true }
                },
                onGone = {
                    val list = Stacks(route.serverId, route.endpointId)
                    when (afterStackGone(list, nav.currentBackStack.value.map(::routeOf))) {
                        AfterStackGone.PopToList -> nav.popBackStack(list, inclusive = false)
                        AfterStackGone.SwitchToListTab -> nav.switchTab(route.serverId, route.endpointId, EnvTab.Stacks)
                        AfterStackGone.ReplaceWithList -> nav.navigate(list) {
                            popUpTo(route) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable<ContainerDetail> { entry ->
            val route = entry.toRoute<ContainerDetail>()
            val key = "detail-${route.serverId}-${route.endpointId}-${route.containerId}"
            val vm = viewModel(key = key) {
                ContainerDetailViewModel(container, route.serverId, route.endpointId, route.containerId)
            }
            ContainerDetailScreen(
                viewModel = vm,
                logsViewModel = { tty ->
                    viewModel(key = "logs-$key") {
                        LogsViewModel(container, route.serverId, route.endpointId, route.containerId, tty)
                    }
                },
                quickRecreate = viewModel(key = "quick-$key") {
                    QuickRecreateViewModel(container, route.serverId, route.endpointId, route.containerId)
                },
                onQuickRecreated = { nav.navigate(Recreate(route.serverId, route.endpointId, route.containerId, quick = true)) },
                onEdit = { nav.navigate(Editor(route.serverId, route.endpointId, route.containerId)) },
                // A plain push: Back from the stack returns to this container.
                onOpenStack = { nav.navigate(it) { launchSingleTop = true } },
                onBack = { nav.popBackStack() },
            )
        }
        composable<Recreate> { entry ->
            val route = entry.toRoute<Recreate>()
            val vm = viewModel(key = "recreate-${route.serverId}-${route.endpointId}-${route.containerId}") {
                RecreateViewModel(container, route.serverId, route.endpointId, route.containerId)
            }
            RecreateScreen(
                viewModel = vm,
                quick = route.quick,
                onSucceeded = { newId ->
                    nav.navigate(ContainerDetail(route.serverId, route.endpointId, newId)) {
                        popUpTo(ContainerDetail(route.serverId, route.endpointId, route.containerId)) { inclusive = true }
                    }
                },
                onToContainers = { nav.popBackStack(Containers(route.serverId, route.endpointId), inclusive = false) },
                onBack = { nav.popBackStack() },
            )
        }
        composable<Editor> { entry ->
            val route = entry.toRoute<Editor>()
            val vm = viewModel(key = "editor-${route.serverId}-${route.endpointId}-${route.containerId}") {
                EditorViewModel(container, route.serverId, route.endpointId, route.containerId)
            }
            EditorScreen(
                viewModel = vm,
                // The editor stays below the progress: Back from a failed Replace returns to the form with the edits.
                // A successful one pops past it (Recreate's onSucceeded pops up to the old container's detail).
                onReplaceStarted = {
                    val id = checkNotNull(route.containerId)
                    nav.navigate(Recreate(route.serverId, route.endpointId, id))
                },
                onOpenContainer = { id ->
                    nav.navigate(ContainerDetail(route.serverId, route.endpointId, id)) { popUpTo(route) { inclusive = true } }
                },
                // A plain push: Back from the stack returns to the review with the edits.
                onOpenStack = { nav.navigate(it) { launchSingleTop = true } },
                onBack = { nav.popBackStack() },
            )
        }
        composable<Registries> { entry ->
            val route = entry.toRoute<Registries>()
            val vm = viewModel(key = "registries-${route.serverId}") { RegistriesViewModel(container, route.serverId) }
            val refresh by entry.savedStateHandle.getStateFlow(REGISTRIES_REFRESH, 0).collectAsState()
            RegistriesScreen(
                viewModel = vm,
                refreshSignal = refresh,
                onAdd = { nav.navigate(RegistryEdit(route.serverId, null)) },
                onEdit = { nav.navigate(RegistryEdit(route.serverId, it.id)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable<RegistryEdit> { entry ->
            val route = entry.toRoute<RegistryEdit>()
            val vm = viewModel(key = "registry-edit-${route.serverId}-${route.registryId}") {
                RegistryEditViewModel(container, route.serverId, route.registryId)
            }
            RegistryEditScreen(
                viewModel = vm,
                onDone = {
                    nav.previousBackStackEntry?.savedStateHandle?.let { it[REGISTRIES_REFRESH] = (it[REGISTRIES_REFRESH] ?: 0) + 1 }
                    nav.popBackStack()
                },
                onBack = { nav.popBackStack() },
            )
        }
    }
}

/** Switches environment tab: tabs sit on top of Containers, so Back from any tab leads to Environments. */
private fun NavController.switchTab(serverId: String, endpointId: Int, tab: EnvTab) {
    val route: Any = when (tab) {
        EnvTab.Containers -> Containers(serverId, endpointId)
        EnvTab.Images -> Images(serverId, endpointId)
        EnvTab.Volumes -> Volumes(serverId, endpointId)
        EnvTab.Networks -> Networks(serverId, endpointId)
        EnvTab.Stacks -> Stacks(serverId, endpointId)
    }
    navigate(route) {
        popUpTo(Containers(serverId, endpointId)) { inclusive = false }
        launchSingleTop = true
    }
}

/** The route object of a back-stack entry when [canPopTo] or [afterStackGone] care about it; null for every other destination. */
private fun routeOf(entry: NavBackStackEntry): Any? {
    val route = entry.destination.route ?: return null
    fun <T> matches(serializer: KSerializer<T>) = route.startsWith(serializer.descriptor.serialName)
    return when {
        matches(ContainerDetail.serializer()) -> entry.toRoute<ContainerDetail>()
        matches(Editor.serializer()) -> entry.toRoute<Editor>()
        matches(Recreate.serializer()) -> entry.toRoute<Recreate>()
        matches(StackDetail.serializer()) -> entry.toRoute<StackDetail>()
        // By exact name: a prefix match would also take routes whose names start the same.
        route.substringBefore('/').substringBefore('?') == Stacks.serializer().descriptor.serialName -> entry.toRoute<Stacks>()
        route.substringBefore('/').substringBefore('?') == Containers.serializer().descriptor.serialName -> entry.toRoute<Containers>()
        else -> null
    }
}

/**
 * Whether opening [target] from a stack may pop back to the [target] already on the back stack ([stack], bottom to top,
 * the stack screen last). Popping discards everything above it, so it is only done when nothing there can hold work:
 * an Editor or Recreate entry in between holds unsaved edits or a running recreate's progress, another stack screen in
 * between may hold editor or git settings edits, and [unsavedEdits] says the current stack screen does. Back must still
 * return to them, so the container is pushed instead (single-top).
 */
internal fun canPopTo(target: ContainerDetail, stack: List<Any?>, unsavedEdits: Boolean = false): Boolean {
    if (unsavedEdits) return false
    val at = stack.indexOfLast { it == target }
    if (at < 0) return false
    val above = stack.drop(at + 1)
    return above.none { it is Editor || it is Recreate } && above.dropLast(1).none { it is StackDetail }
}

/** Where a stack screen goes once its stack was deleted or migrated away. */
internal enum class AfterStackGone {
    /** Back to the stack list below. */
    PopToList,

    /** Opened from elsewhere in the environment (e.g. a container): the Stacks tab, on top of Containers. */
    SwitchToListTab,

    /** Neither is on the back stack: the list replaces the stack screen. */
    ReplaceWithList,
}

/** [list] is the stack list of the stack's environment; [stack] the back stack as [routeOf] gives it, bottom to top. */
internal fun afterStackGone(list: Stacks, stack: List<Any?>): AfterStackGone = when {
    list in stack -> AfterStackGone.PopToList
    Containers(list.serverId, list.endpointId) in stack -> AfterStackGone.SwitchToListTab
    else -> AfterStackGone.ReplaceWithList
}
