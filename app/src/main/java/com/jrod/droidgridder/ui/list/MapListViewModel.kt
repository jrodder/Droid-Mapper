package com.jrod.droidgridder.ui.list

import androidx.lifecycle.ViewModel
import com.jrod.droidgridder.data.MapStore
import com.jrod.droidgridder.data.decodeMap
import com.jrod.droidgridder.model.MapFile
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Map list state machine: the persisted maps plus create/rename/delete/import.
 * The list state is refreshed from [MapStore.list] when the screen enters and
 * after each mutation here. All store I/O stays synchronous (deferred ruling).
 */
class MapListViewModel(private val store: MapStore) : ViewModel() {
    private val _maps = MutableStateFlow(store.list())
    val maps: StateFlow<List<MapFile>> = _maps.asStateFlow()

    fun refresh() {
        _maps.value = store.list()
    }

    /** Create a new map, persist it, refresh, and return it so the screen can navigate into it. */
    fun create(name: String): MapFile {
        val map = store.newMap(name)
        store.save(map)
        refresh()
        return map
    }

    /** Rename a persisted map; [MapStore.save] stamps the fresh updatedAt. */
    fun rename(id: String, name: String) {
        val map = store.load(id) ?: return
        store.save(map.copy(name = name))
        refresh()
    }

    fun delete(id: String) {
        store.delete(id)
        refresh()
    }

    /**
     * Import a map from JSON text. Returns false (so the caller can toast) when
     * the text does not decode. A collision on the decoded id never overwrites
     * the existing map: the imported copy gets a fresh UUID id (and a fresh
     * updatedAt from [MapStore.save]).
     */
    fun importMap(json: String): Boolean {
        val decoded = runCatching { decodeMap(json) }.getOrNull() ?: return false
        val map = if (store.load(decoded.id) != null) decoded.copy(id = UUID.randomUUID().toString()) else decoded
        store.save(map)
        refresh()
        return true
    }
}