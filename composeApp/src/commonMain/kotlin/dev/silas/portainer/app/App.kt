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
                onQuickRecreated = { nav.navigate(Recreate(route.serverId, route.endpointId, route.containerId)) },
                onEditRecreate = { nav.navigate(Recreate(route.serverId, route.endpointId, route.containerId)) },
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
                onSucceeded = { newId ->
                    nav.navigate(ContainerDetail(route.serverId, route.endpointId, newId)) {
                        popUpTo(ContainerDetail(route.serverId, route.endpointId, route.containerId)) { inclusive = true }
                    }
                },
                onToContainers = { nav.popBackStack(Containers(route.serverId, route.endpointId), inclusive = false) },
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
    }
    navigate(route) {
        popUpTo(Containers(serverId, endpointId)) { inclusive = false }
        launchSingleTop = true
    }
}
