package com.jrod.droidgridder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.jrod.droidgridder.data.MapStore
import com.jrod.droidgridder.ui.editor.MapEditorScreen
import com.jrod.droidgridder.ui.theme.DroidGridderTheme
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DroidGridderTheme {
                // ponytail: hosts the editor directly; real map-list navigation is Task 8.
                val context = LocalContext.current
                val store = remember { MapStore(File(context.filesDir, "maps")) }
                val mapId = remember { store.list().firstOrNull()?.id }
                if (mapId == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Droid-Gridder")
                    }
                } else {
                    MapEditorScreen(store = store, mapId = mapId)
                }
            }
        }
    }
}