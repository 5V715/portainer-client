package dev.silas.portainer.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.silas.portainer.app.environments.EnvironmentsScreen
import dev.silas.portainer.app.environments.EnvironmentsViewModel
import dev.silas.portainer.app.placeholder.ComingSoonScreen
import dev.silas.portainer.app.servers.ServerEditScreen
import dev.silas.portainer.app.servers.ServerEditViewModel
import dev.silas.portainer.app.servers.ServerListScreen
import dev.silas.portainer.app.servers.ServersViewModel
import dev.silas.portainer.app.theme.PortainerTheme
import dev.silas.portainer.core.storage.ServerRepository
import dev.silas.portainer.core.storage.createSecretStore

val LocalAppContainer = compositionLocalOf<AppContainer> { error("AppContainer not provided") }

@Composable
fun App() {
    val container = remember { AppContainer(ServerRepository(createSettings(), createSecretStore())) }
    PortainerTheme {
        CompositionLocalProvider(LocalAppContainer provides container) {
            AppNavHost(container)
        }
    }
}

@Composable
private fun AppNavHost(container: AppContainer) {
    val nav = rememberNavController()

    // Reopen the last environment when there is exactly one server; back leads to Environments.
    LaunchedEffect(Unit) {
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
            )
        }
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
        composable<Containers> { ComingSoonScreen() }
        composable<ContainerDetail> { ComingSoonScreen() }
        composable<Recreate> { ComingSoonScreen() }
        composable<Registries> { ComingSoonScreen() }
        composable<RegistryEdit> { ComingSoonScreen() }
    }
}
