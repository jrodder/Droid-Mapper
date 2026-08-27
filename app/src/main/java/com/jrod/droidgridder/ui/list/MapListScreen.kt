package com.jrod.droidgridder.ui.list

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jrod.droidgridder.data.MapStore
import com.jrod.droidgridder.data.encodeMap
import com.jrod.droidgridder.model.MapFile
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val updatedFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy, HH:mm", Locale.getDefault())

/** "Updated" line for a list row. */
private fun formatUpdated(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(updatedFormat)

/**
 * Share [map] as JSON: write it under filesDir/exports (sanitized name) and fire
 * ACTION_SEND with a FileProvider URI (authority "<package>.fileprovider").
 */
private fun exportMap(context: Context, map: MapFile) {
    val dir = File(context.filesDir, "exports")
    dir.mkdirs()
    val safe = map.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { map.id }
    val file = File(dir, "$safe.json")
    file.writeText(encodeMap(map))
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share map",
        ),
    )
}

/** Map list: name + updated per row. Tap opens the editor; long-press opens the row menu. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MapListScreen(store: MapStore, onOpenMap: (String) -> Unit) {
    val vm: MapListViewModel = viewModel { MapListViewModel(store) }
    val maps by vm.maps.collectAsState()
    val context = LocalContext.current

    // Re-list on (re)entry: edits made in the editor refresh the rows here.
    LaunchedEffect(Unit) { vm.refresh() }

    var creating by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<MapFile?>(null) }
    var menuTarget by remember { mutableStateOf<MapFile?>(null) }
    var deleteTarget by remember { mutableStateOf<MapFile?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val text = uri?.let {
            runCatching { context.contentResolver.openInputStream(it)!!.bufferedReader().readText() }
                .getOrNull()
        }
        if (text == null || !vm.importMap(text)) {
            Toast.makeText(context, "Import failed: not a valid map JSON", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Maps") },
                actions = {
                    TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Text("Import")
                    }
                    TextButton(onClick = { creating = true }) {
                        Text("New map")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (maps.isEmpty()) {
                Text(
                    text = "No maps yet — tap “New map”.",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(maps, key = { it.id }) { map ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onOpenMap(map.id) },
                                    onLongClick = { menuTarget = map },
                                )
                                .padding(vertical = 12.dp),
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                Text(
                                    text = map.name.ifBlank { "(untitled)" },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = "Updated ${formatUpdated(map.updatedAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "New map",
            initial = "",
            onConfirm = { name ->
                creating = false
                onOpenMap(vm.create(name).id)
            },
            onDismiss = { creating = false },
        )
    }
    renameTarget?.let { map ->
        NameDialog(
            title = "Rename map",
            initial = map.name,
            onConfirm = { name ->
                renameTarget = null
                vm.rename(map.id, name)
            },
            onDismiss = { renameTarget = null },
        )
    }
    menuTarget?.let { map ->
        AlertDialog(
            onDismissRequest = { menuTarget = null },
            title = { Text(map.name.ifBlank { "(untitled)" }) },
            text = {
                Column {
                    TextButton(onClick = { menuTarget = null; renameTarget = map }) { Text("Rename") }
                    TextButton(onClick = { menuTarget = null; deleteTarget = map }) { Text("Delete") }
                    TextButton(onClick = { menuTarget = null; exportMap(context, map) }) { Text("Export") }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { menuTarget = null }) { Text("Cancel") }
            },
        )
    }
    deleteTarget?.let { map ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete map?") },
            text = { Text("“${map.name.ifBlank { "(untitled)" }}” will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { deleteTarget = null; vm.delete(map.id) }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

/** Shared name entry dialog for create/rename; confirm is disabled while blank. */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Map name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}