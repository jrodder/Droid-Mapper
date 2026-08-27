package com.jrod.droidgridder.ui.editor

import androidx.lifecycle.ViewModel
import com.jrod.droidgridder.data.MapStore
import com.jrod.droidgridder.model.Direction
import com.jrod.droidgridder.model.MapFile
import com.jrod.droidgridder.model.go as goRoom
import com.jrod.droidgridder.model.linkToExisting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MapEditorUiState(
    val map: MapFile? = null,
    val currentRoomId: String? = null,
    val selectedRoomId: String? = null,
    val wheelForRoomId: String? = null,
    val linkMode: Direction? = null,
    val linkSourceRoomId: String? = null,
)

/**
 * Editor state machine. Task 4 adds the skeleton plus selection/wheel actions;
 * Task 5 adds the map-mutating go/link actions. Undo/bottom sheet land in Task 6.
 */
class MapEditorViewModel(mapId: String, private val store: MapStore) : ViewModel() {
    private val _uiState = MutableStateFlow(MapEditorUiState())
    val uiState: StateFlow<MapEditorUiState> = _uiState.asStateFlow()

    init {
        val map = store.load(mapId)
        _uiState.value = MapEditorUiState(
            map = map,
            currentRoomId = map?.rooms?.firstOrNull()?.id,
        )
    }

    /** Select a room (also makes it current); `null` clears the selection. */
    fun select(roomId: String?) {
        _uiState.update {
            it.copy(selectedRoomId = roomId, currentRoomId = roomId ?: it.currentRoomId)
        }
    }

    fun openWheel(roomId: String) {
        _uiState.update { it.copy(wheelForRoomId = roomId, currentRoomId = roomId) }
    }

    fun closeWheel() {
        _uiState.update { it.copy(wheelForRoomId = null) }
    }

    /**
     * Walk [direction] from the current room. If the exit already exists, follow it
     * (no map mutation, no save); otherwise create the connected room via the pure
     * [goRoom], persist it, and make the new room current. Either way the
     * destination becomes the current room and the wheel closes.
     */
    fun go(direction: Direction) {
        val s = _uiState.value
        val map = s.map ?: return
        val fromId = s.currentRoomId ?: return
        val existing = map.exits.firstOrNull { it.from == fromId && it.direction == direction }
        if (existing != null) {
            _uiState.value = s.copy(currentRoomId = existing.to, wheelForRoomId = null)
            return
        }
        val newMap = goRoom(direction, fromId, map)
        store.save(newMap)
        // ponytail: pure go() appends the new room, so the destination is the last one.
        _uiState.value = s.copy(
            map = newMap,
            currentRoomId = newMap.rooms.last().id,
            wheelForRoomId = null,
        )
    }

    /** Enter link mode from the wheel: remember direction and the wheel's source room. */
    fun startLink(direction: Direction) {
        val s = _uiState.value
        _uiState.value = s.copy(
            wheelForRoomId = null,
            linkMode = direction,
            linkSourceRoomId = s.currentRoomId,
        )
    }

    /**
     * Leave link mode. With a [targetRoomId], connect the source room to it via the
     * pure [linkToExisting] (no reverse exit) and persist; `null` cancels the link,
     * and linking a room to itself is treated as cancel (would be an invisible
     * self-loop the canvas does not draw). No save on either cancel path.
     */
    fun completeLink(targetRoomId: String?) {
        val s = _uiState.value
        val direction = s.linkMode ?: return
        val fromId = s.linkSourceRoomId
        val map = s.map
        if (targetRoomId == null || fromId == null || map == null || targetRoomId == fromId) {
            _uiState.value = s.copy(linkMode = null, linkSourceRoomId = null)
            return
        }
        val newMap = linkToExisting(direction, fromId, targetRoomId, map)
        store.save(newMap)
        _uiState.value = s.copy(
            map = newMap,
            linkMode = null,
            linkSourceRoomId = null,
            selectedRoomId = targetRoomId,
            currentRoomId = targetRoomId,
        )
    }
}
