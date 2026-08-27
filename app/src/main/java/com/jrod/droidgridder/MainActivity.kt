package com.jrod.droidgridder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.compose.rememberNavController
import com.jrod.droidgridder.data.MapStore
import com.jrod.droidgridder.ui.navigation.AppNav
import com.jrod.droidgridder.ui.theme.DroidGridderTheme
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ponytail: single manual DI seam — one store for the whole process, passed to AppNav.
        val store = MapStore(File(filesDir, "maps"))
        setContent {
            DroidGridderTheme {
                AppNav(navController = rememberNavController(), store = store)
            }
        }
    }
}