package com.jrod.droidgridder.ui.editor

import androidx.lifecycle.ViewModel
import com.jrod.droidgridder.data.MapStore
import com.jrod.droidgridder.model.Direction
import com.jrod.droidgridder.model.MapFile
import com.jrod.droidgridder.model.deleteExit as removeExit
import com.jrod.droidgridder.model.deleteRoom as removeRoom
import com.jrod.droidgridder.model.go as goRoom
import com.jrod.droidgridder.model.autoTidy as tidyLayout
import com.jrod.droidgridder.model.linkToExisting
import com.jrod.droidgridder.model.redirectExit as repointExit
import com.jrod.droidgridder.model.updateRoomText as setRoomText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Armed redirect: an exit to be repointed at the next room tapped (link-mode pattern). */
data class RedirectMode(
    val exitId: String,
    val direction: Direction,
    val fromRoomId: String,
)

data class MapEditorUiState(
    val map: MapFile? = null,
    val currentRoomId: String? = null,
    val selectedRoomId: String? = null,
    val wheelForRoomId: String? = null,
    val linkMode: Direction? = null,
    val linkSourceRoomId: String? = null,
    val redirectMode: RedirectMode? = null,
    val canUndo: Boolean = false,
)

/**
 * Editor state machine. Task 4 adds the skeleton plus selection/wheel actions;
 * Task 5 adds the map-mutating go/link actions; Task 6 adds text/exit/room
 * mutation from the bottom sheet plus single-step undo.
 */
class MapEditorViewModel(mapId: String, private val store: MapStore) : ViewModel() {
    private val _uiState = MutableStateFlow(MapEditorUiState())
    val uiState: StateFlow<MapEditorUiState> = _uiState.asStateFlow()

    // ponytail: single-step undo — one previous map, held only in the VM, never persisted.
    private var previousMap: MapFile? = null

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
        previousMap = map
        store.save(newMap)
        // ponytail: pure go() appends the new room, so the destination is the last one.
        _uiState.value = s.copy(
            map = newMap,
            currentRoomId = newMap.rooms.last().id,
            wheelForRoomId = null,
            canUndo = true,
        )
    }

    /**
     * Re-lay out every room via the pure [tidyLayout] (root pinned at the origin,
     * the rest re-placed via BFS along direction offsets). It is a real map
     * replacement, so it pushes the single-step undo slot and persists. A no-op
     * tidy (already-tidy or empty map) returns without pushing undo or re-saving,
     * so it cannot swallow the user's previous mutation from the undo slot.
     */
    fun autoTidy() {
        val s = _uiState.value
        val map = s.map ?: return
        val newMap = tidyLayout(map)
        // ponytail: data-class equality is the no-op test; store.save would also burn updatedAt.
        if (newMap == map) return
        previousMap = map
        store.save(newMap)
        _uiState.value = s.copy(map = newMap, canUndo = true)
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
        previousMap = map
        store.save(newMap)
        _uiState.value = s.copy(
            map = newMap,
            linkMode = null,
            linkSourceRoomId = null,
            selectedRoomId = targetRoomId,
            currentRoomId = targetRoomId,
            canUndo = true,
        )
    }

    /**
     * Commit the sheet's text draft for [roomId] via the pure [setRoomText] and
     * persist. One undo step per commit; unchanged drafts are a no-op so an
     * untouched field losing focus does not burn the undo slot. The sheet stays
     * open on commit so multiple fields can be edited in one session (each commit
     * is its own undo step).
     */
    fun updateRoomText(roomId: String, name: String, description: String, notes: String) {
        val s = _uiState.value
        val map = s.map ?: return
        val room = map.rooms.firstOrNull { it.id == roomId } ?: return
        if (room.name == name && room.description == description && room.notes == notes) return
        previousMap = map
        val newMap = setRoomText(roomId, name, description, notes, map)
        store.save(newMap)
        _uiState.value = s.copy(map = newMap, canUndo = true)
    }

    /** Delete [roomId] and every exit touching it (pure [removeRoom]), persist. */
    fun deleteRoom(roomId: String) {
        val s = _uiState.value
        val map = s.map ?: return
        if (map.rooms.none { it.id == roomId }) return
        previousMap = map
        val newMap = removeRoom(roomId, map)
        store.save(newMap)
        _uiState.value = s.copy(
            map = newMap,
            canUndo = true,
            selectedRoomId = s.selectedRoomId?.takeIf { it != roomId },
            currentRoomId = s.currentRoomId?.takeIf { it != roomId },
            wheelForRoomId = s.wheelForRoomId?.takeIf { it != roomId },
        )
    }

    /** Delete one exit by id (pure [removeExit]), persist. Sheet stays open. */
    fun deleteExit(exitId: String) {
        val s = _uiState.value
        val map = s.map ?: return
        if (map.exits.none { it.id == exitId }) return
        previousMap = map
        val newMap = removeExit(exitId, map)
        store.save(newMap)
        _uiState.value = s.copy(map = newMap, canUndo = true)
    }

    /** Arm redirect mode from a sheet exit row: dismiss the sheet, remember the exit. */
    fun startRedirect(exitId: String) {
        val s = _uiState.value
        val exit = s.map?.exits?.firstOrNull { it.id == exitId } ?: return
        _uiState.value = s.copy(
            selectedRoomId = null,
            redirectMode = RedirectMode(exitId = exitId, direction = exit.direction, fromRoomId = exit.from),
        )
    }

    /**
     * Finish redirect mode. With a [targetRoomId], repoint the armed exit via the
     * pure [repointExit] (same exit id, new `to`) and persist; `null`, a
     * self-target, or the already-current target cancel without a save.
     */
    fun completeRedirect(targetRoomId: String?) {
        val s = _uiState.value
        val r = s.redirectMode ?: return
        val map = s.map
        val exit = map?.exits?.firstOrNull { it.id == r.exitId }
        if (targetRoomId == null || map == null || exit == null ||
            targetRoomId == r.fromRoomId || exit.to == targetRoomId
        ) {
            _uiState.value = s.copy(redirectMode = null)
            return
        }
        previousMap = map
        val newMap = repointExit(r.exitId, targetRoomId, map)
        store.save(newMap)
        _uiState.value = s.copy(
            map = newMap,
            canUndo = true,
            redirectMode = null,
            // Reopen the sheet on the source room so the repointed row is visible.
            selectedRoomId = r.fromRoomId,
        )
    }

    /**
     * Single-step undo: restore [previousMap] (persist it), drop any selection/
     * current/wheel/link/redirect references that no longer exist in the restored
     * map, and clear the slot. No-op when nothing is pending.
     */
    fun undo() {
        val s = _uiState.value
        val prev = previousMap ?: return
        store.save(prev)
        val roomIds = prev.rooms.mapTo(HashSet()) { it.id }
        val exitIds = prev.exits.mapTo(HashSet()) { it.id }
        _uiState.value = s.copy(
            map = prev,
            canUndo = false,
            selectedRoomId = s.selectedRoomId?.takeIf { it in roomIds },
            currentRoomId = s.currentRoomId?.takeIf { it in roomIds },
            wheelForRoomId = s.wheelForRoomId?.takeIf { it in roomIds },
            linkMode = s.linkMode?.takeIf { s.linkSourceRoomId in roomIds },
            linkSourceRoomId = s.linkSourceRoomId?.takeIf { it in roomIds },
            redirectMode = s.redirectMode?.takeIf { it.fromRoomId in roomIds && it.exitId in exitIds },
        )
        previousMap = null
    }
}
