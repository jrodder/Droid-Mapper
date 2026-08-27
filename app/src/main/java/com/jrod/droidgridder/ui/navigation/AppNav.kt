package com.jrod.droidgridder.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.jrod.droidgridder.data.MapStore
import com.jrod.droidgridder.ui.editor.MapEditorScreen
import com.jrod.droidgridder.ui.list.MapListScreen

const val ROUTE_LIST = "list"
const val ROUTE_EDITOR = "editor/{mapId}"

/**
 * App nav graph: map list (start route) and the per-map editor. The [store] is
 * created once in MainActivity and passed down (manual DI, no framework).
 */
@Composable
fun AppNav(navController: NavHostController, store: MapStore) {
    NavHost(navController = navController, startDestination = ROUTE_LIST) {
        composable(ROUTE_LIST) {
            MapListScreen(store = store, onOpenMap = { navController.navigate(ROUTE_EDITOR.replace("{mapId}", it)) })
        }
        composable(ROUTE_EDITOR) { entry ->
            val mapId = entry.arguments?.getString("mapId").orEmpty()
            MapEditorScreen(store = store, mapId = mapId, onBack = { navController.popBackStack() })
        }
    }
}
