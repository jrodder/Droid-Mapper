package com.jrod.droidgridder.ui.editor

import com.jrod.droidgridder.data.MapStore
import com.jrod.droidgridder.model.Direction
import com.jrod.droidgridder.model.Exit
import com.jrod.droidgridder.model.GRID_STEP
import com.jrod.droidgridder.model.MapFile
import com.jrod.droidgridder.model.Room
import com.jrod.droidgridder.model.autoTidy as tidyLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MapEditorViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(map: MapFile): MapStore {
        val store = MapStore(tmp.root)
        store.save(map)
        return store
    }

    private fun baseMap(vararg rooms: Room) =
        MapFile(id = "m1", name = "m", createdAt = 0L, updatedAt = 0L, rooms = rooms.toList())

    @Test
    fun `go on missing exit creates room, saves it, and makes it current`() {
        val vm = MapEditorViewModel("m1", store(baseMap(Room(id = "a", x = 0f, y = 0f))))
        vm.select("a")
        vm.openWheel("a")
        vm.go(Direction.N)

        val s = vm.uiState.value
        assertEquals(2, s.map!!.rooms.size)
        val newRoom = s.map!!.rooms.last()
        assertEquals(newRoom.id, s.currentRoomId)
        assertNull(s.wheelForRoomId)
        assertTrue(s.canUndo) // go-new pushes an undo step
        val exits = s.map!!.exits
        assertTrue(exits.any { it.from == "a" && it.direction == Direction.N && it.to == newRoom.id })
        assertTrue(exits.any { it.from == newRoom.id && it.direction == Direction.S && it.to == "a" })
        // persisted
        val saved = MapStore(tmp.root).load("m1")!!
        assertEquals(2, saved.rooms.size)
    }

    @Test
    fun `go on existing exit follows it without mutating the map`() {
        val m = baseMap(Room(id = "a"), Room(id = "b"))
            .copy(exits = listOf(Exit("e1", "a", Direction.N, "b")))
        val vm = MapEditorViewModel("m1", store(m))
        vm.openWheel("a")
        vm.go(Direction.N)

        val s = vm.uiState.value
        assertEquals("b", s.currentRoomId)
        assertEquals(2, s.map!!.rooms.size)
        assertEquals(1, s.map!!.exits.size)
        assertNull(s.wheelForRoomId)
    }

    @Test
    fun `link mode adds labeled exit without reverse and saves`() {
        val vm = MapEditorViewModel("m1", store(baseMap(Room(id = "a"), Room(id = "b"))))
        vm.openWheel("a")
        vm.startLink(Direction.E)

        val s1 = vm.uiState.value
        assertEquals(Direction.E, s1.linkMode)
        assertEquals("a", s1.linkSourceRoomId)
        assertNull(s1.wheelForRoomId)

        vm.completeLink("b")
        val s2 = vm.uiState.value
        assertNull(s2.linkMode)
        assertNull(s2.linkSourceRoomId)
        assertEquals(1, s2.map!!.exits.size)
        val exit = s2.map!!.exits.single()
        assertEquals(Direction.E, exit.direction)
        assertEquals("a", exit.from)
        assertEquals("b", exit.to)
        assertEquals("b", s2.currentRoomId)
        assertEquals("b", s2.selectedRoomId)
        assertTrue(s2.canUndo) // link pushes an undo step
        val saved = MapStore(tmp.root).load("m1")!!
        assertTrue(saved.exits.none { it.from == "b" && it.direction == Direction.W })
    }

    @Test
    fun `completeLink with null target cancels without mutation`() {
        val vm = MapEditorViewModel("m1", store(baseMap(Room(id = "a"), Room(id = "b"))))
        vm.openWheel("a")
        vm.startLink(Direction.N)
        vm.completeLink(null)

        val s = vm.uiState.value
        assertNull(s.linkMode)
        assertNull(s.linkSourceRoomId)
        assertTrue(s.map!!.exits.isEmpty())
    }

    @Test
    fun `completeLink on the source room cancels without mutation or save`() {
        val vm = MapEditorViewModel("m1", store(baseMap(Room(id = "a"), Room(id = "b"))))
        vm.openWheel("a")
        vm.startLink(Direction.N)
        vm.completeLink("a") // self-link target == source

        val s = vm.uiState.value
        assertNull(s.linkMode)
        assertNull(s.linkSourceRoomId)
        assertTrue(s.map!!.exits.isEmpty())
        // nothing persisted
        val saved = MapStore(tmp.root).load("m1")!!
        assertTrue(saved.exits.isEmpty())
    }

    @Test
    fun `selecting a different room closes the wheel and go fires from the new current room`() {
        val vm = MapEditorViewModel("m1", store(baseMap(Room(id = "a"), Room(id = "b"))))
        vm.openWheel("a")
        assertEquals("a", vm.uiState.value.wheelForRoomId)

        vm.select("b") // single-tap room B while the wheel is open over A
        val s1 = vm.uiState.value
        assertNull(s1.wheelForRoomId) // the wheel closes — it is modal for room taps
        assertEquals("b", s1.currentRoomId)
        assertEquals("b", s1.selectedRoomId)

        // with the wheel gone it can no longer fire from stale A; go() uses current=b
        vm.go(Direction.N)
        val s2 = vm.uiState.value
        assertEquals(3, s2.map!!.rooms.size)
        val newRoom = s2.map!!.rooms.last()
        assertEquals(newRoom.id, s2.currentRoomId)
        val toB = s2.map!!.exits.single { it.from == "b" && it.direction == Direction.N }
        assertEquals(newRoom.id, toB.to) // the new room is north of B, not A
        val saved = MapStore(tmp.root).load("m1")!!
        assertEquals(newRoom.id, saved.exits.single { it.from == "b" && it.direction == Direction.N }.to)
    }

    @Test
    fun `select null clears selection but keeps current room and any open wheel`() {
        val vm = MapEditorViewModel("m1", store(baseMap(Room(id = "a"), Room(id = "b"))))
        vm.select("a")
        vm.select("b")
        vm.select(null)

        val s = vm.uiState.value
        assertNull(s.selectedRoomId)
        assertEquals("b", s.currentRoomId)
    }

    @Test
    fun `updateRoomText commit updates the room, saves it, and pushes one undo step`() {
        val vm = MapEditorViewModel("m1", store(baseMap(Room(id = "a", name = "Old"))))
        vm.openRoomEdit("a")
        vm.updateRoomText("a", "New", "a desc", "a note")

        val room = vm.uiState.value.map!!.rooms.single { it.id == "a" }
        assertEquals("New", room.name)
        assertEquals("a desc", room.description)
        assertEquals("a note", room.notes)
        assertTrue(vm.uiState.value.canUndo)
        assertEquals("a", vm.uiState.value.selectedRoomId) // edit sheet stays open after a text commit
        assertEquals(RoomMode.Edit, vm.uiState.value.roomMode)
        val saved = MapStore(tmp.root).load("m1")!!.rooms.single()
        assertEquals("New", saved.name)
        assertEquals("a note", saved.notes)
    }

    @Test
    fun `updateRoomText with unchanged values is a no-op with no undo push`() {
        val vm = MapEditorViewModel("m1", store(baseMap(Room(id = "a", name = "Old"))))
        vm.openRoomEdit("a")
        vm.updateRoomText("a", "Old", "", "")

        val s = vm.uiState.value
        assertFalse(s.canUndo)
        assertEquals("a", s.selectedRoomId) // edit sheet stays open
        assertEquals(RoomMode.Edit, s.roomMode)
    }

    @Test
    fun `deleteRoom cascades touching exits, clears selection and current, and saves`() {
        val m = baseMap(Room(id = "a"), Room(id = "b"))
            .copy(exits = listOf(Exit("e1", "a", Direction.N, "b"), Exit("e2", "b", Direction.S, "a")))
        val vm = MapEditorViewModel("m1", store(m))
        vm.select("a") // selection and current both point at the doomed room
        vm.deleteRoom("a")

        val s = vm.uiState.value
        assertEquals(listOf("b"), s.map!!.rooms.map { it.id })
        assertTrue(s.map!!.exits.isEmpty())
        assertNull(s.selectedRoomId)
        assertNull(s.currentRoomId)
        assertNull(s.roomMode) // the window/sheet clears along with the selection
        assertTrue(s.canUndo)
        val saved = MapStore(tmp.root).load("m1")!!
        assertEquals(listOf("b"), saved.rooms.map { it.id })
        assertTrue(saved.exits.isEmpty())
    }

    @Test
    fun `deleteExit removes only that exit and saves`() {
        val m = baseMap(Room(id = "a"), Room(id = "b"))
            .copy(exits = listOf(Exit("e1", "a", Direction.N, "b"), Exit("e2", "b", Direction.S, "a")))
        val vm = MapEditorViewModel("m1", store(m))
        vm.openRoomEdit("a")
        vm.deleteExit("e1")

        val s = vm.uiState.value
        assertEquals(listOf("e2"), s.map!!.exits.map { it.id })
        assertTrue(s.canUndo)
        assertEquals("a", s.selectedRoomId) // edit sheet stays open after deleting an exit row
        assertEquals(RoomMode.Edit, s.roomMode)
        assertEquals(listOf("e2"), MapStore(tmp.root).load("m1")!!.exits.map { it.id })
    }

    @Test
    fun `redirect arms from the sheet, repoints the same exit id, and saves`() {
        val m = baseMap(Room(id = "a"), Room(id = "b"), Room(id = "c"))
            .copy(exits = listOf(Exit("e1", "a", Direction.N, "b")))
        val vm = MapEditorViewModel("m1", store(m))
        vm.openRoomEdit("a")
        vm.startRedirect("e1")

        val s1 = vm.uiState.value
        assertEquals("e1", s1.redirectMode!!.exitId)
        assertEquals(Direction.N, s1.redirectMode!!.direction)
        assertEquals("a", s1.redirectMode!!.fromRoomId)
        assertNull(s1.selectedRoomId) // sheet dismissed while redirect is armed
        assertNull(s1.roomMode) // the edit mode is dismissed with the sheet

        vm.completeRedirect("c")
        val s2 = vm.uiState.value
        val exit = s2.map!!.exits.single { it.id == "e1" }
        assertEquals("c", exit.to)
        assertNull(s2.redirectMode)
        assertTrue(s2.canUndo)
        assertEquals("a", s2.selectedRoomId) // sheet reopens on the source room
        assertEquals(RoomMode.Edit, s2.roomMode) // ...in Edit mode
        assertEquals("c", MapStore(tmp.root).load("m1")!!.exits.single { it.id == "e1" }.to)
    }

    @Test
    fun `completeRedirect with null, self, or unchanged target cancels without save`() {
        val m = baseMap(Room(id = "a"), Room(id = "b"), Room(id = "c"))
            .copy(exits = listOf(Exit("e1", "a", Direction.N, "b")))

        val vm1 = MapEditorViewModel("m1", store(m))
        vm1.select("a")
        vm1.startRedirect("e1")
        vm1.completeRedirect(null)
        assertFalse(vm1.uiState.value.canUndo)

        val vm2 = MapEditorViewModel("m1", store(m))
        vm2.select("a")
        vm2.startRedirect("e1")
        vm2.completeRedirect("a") // self-target
        assertFalse(vm2.uiState.value.canUndo)

        val vm3 = MapEditorViewModel("m1", store(m))
        vm3.select("a")
        vm3.startRedirect("e1")
        vm3.completeRedirect("b") // already the target
        assertFalse(vm3.uiState.value.canUndo)

        val saved = MapStore(tmp.root).load("m1")!!
        assertEquals("b", saved.exits.single { it.id == "e1" }.to)
        assertTrue(saved.exits.size == 1)
    }

    @Test
    fun `single-step undo reverts only the latest mutation`() {
        val m = baseMap(Room(id = "a", name = "A0"), Room(id = "b"))
            .copy(exits = listOf(Exit("e1", "a", Direction.N, "b"), Exit("e2", "b", Direction.S, "a")))
        val vm = MapEditorViewModel("m1", store(m))
        vm.select("a")
        vm.updateRoomText("a", "A1", "", "") // mutation 1
        vm.select("a")
        vm.deleteExit("e2") // mutation 2
        assertTrue(vm.uiState.value.canUndo)

        vm.undo()
        val s = vm.uiState.value
        assertFalse(s.canUndo)
        assertEquals("A1", s.map!!.rooms.single { it.id == "a" }.name) // mutation 1 kept
        assertEquals(listOf("e1", "e2"), s.map!!.exits.map { it.id }) // mutation 2 reverted: e2 restored
        val saved = MapStore(tmp.root).load("m1")!!
        assertEquals("A1", saved.rooms.single { it.id == "a" }.name)
        assertEquals(listOf("e1", "e2"), saved.exits.map { it.id })
    }

    @Test
    fun `undo clears selection and current that no longer exist in the restored map`() {
        val vm = MapEditorViewModel("m1", store(baseMap(Room(id = "a"))))
        vm.select("a")
        vm.openWheel("a")
        vm.go(Direction.N) // creates a new current room
        val newId = vm.uiState.value.currentRoomId!!
        vm.select(newId)

        vm.undo()
        val s = vm.uiState.value
        assertEquals(1, s.map!!.rooms.size)
        assertEquals("a", s.map!!.rooms.single().id)
        assertNull(s.selectedRoomId)
        assertNull(s.currentRoomId)
        assertFalse(s.canUndo)
    }

    @Test
    fun `undo clears armed link and redirect modes whose state is stale`() {
        // link: go-new pushes an undo step, then a link is armed on top of it
        val vm1 = MapEditorViewModel("m1", store(baseMap(Room(id = "a"))))
        vm1.openWheel("a")
        vm1.go(Direction.N)
        assertTrue(vm1.uiState.value.canUndo)
        vm1.openWheel(vm1.uiState.value.currentRoomId!!)
        vm1.startLink(Direction.E)
        vm1.undo()
        val s1 = vm1.uiState.value
        assertNull(s1.linkMode)
        assertNull(s1.linkSourceRoomId)
        assertFalse(s1.canUndo)

        // redirect: the armed exit is created after the undo point, so it is stale in the restored map
        val vm2 = MapEditorViewModel("m1", store(baseMap(Room(id = "a"), Room(id = "b"))))
        vm2.openWheel("a")
        vm2.go(Direction.N) // pushes the undo step and creates the exits
        val freshExit = vm2.uiState.value.map!!.exits.first { it.from == "a" }
        vm2.select("a")
        vm2.startRedirect(freshExit.id)
        assertTrue(vm2.uiState.value.redirectMode != null)
        vm2.undo()
        val s2 = vm2.uiState.value
        assertNull(s2.redirectMode)
        assertFalse(s2.canUndo)
    }

    @Test
    fun `go on existing exit pushes no undo step`() {
        val m = baseMap(Room(id = "a"), Room(id = "b"))
            .copy(exits = listOf(Exit("e1", "a", Direction.N, "b")))
        val vm = MapEditorViewModel("m1", store(m))
        vm.openWheel("a")
        vm.go(Direction.N)

        assertFalse(vm.uiState.value.canUndo)
    }

    @Test
    fun `autoTidy on an already-tidy map pushes no undo step and re-saves nothing`() {
        val m = baseMap(Room(id = "a", x = 5f, y = 6f), Room(id = "b", x = 100f, y = 200f))
            .copy(exits = listOf(Exit("e1", "a", Direction.E, "b"), Exit("e2", "b", Direction.W, "a")))
        val vm = MapEditorViewModel("m1", store(tidyLayout(m))) // already at the tidy fixpoint
        val file = File(tmp.root, "m1.json")
        val before = file.readText()

        vm.autoTidy()

        val s = vm.uiState.value
        assertFalse(s.canUndo) // no undo step pushed, so the user's previous mutation survives
        assertEquals(before, file.readText()) // nothing re-saved (no updatedAt burn)

        // and a real mutation afterwards is still undoable in one step
        vm.select("a")
        vm.updateRoomText("a", "Renamed", "", "")
        vm.undo()
        assertFalse(vm.uiState.value.canUndo)
        assertEquals("", vm.uiState.value.map!!.rooms.single { it.id == "a" }.name)
    }

    @Test
    fun `autoTidy re-lays-out rooms, saves it, and pushes one undo step`() {
        val m = baseMap(Room(id = "a", x = 5f, y = 6f), Room(id = "b", x = 100f, y = 200f))
            .copy(exits = listOf(Exit("e1", "a", Direction.E, "b"), Exit("e2", "b", Direction.W, "a")))
        val vm = MapEditorViewModel("m1", store(m))
        vm.autoTidy()

        val s = vm.uiState.value
        val byId = s.map!!.rooms.associateBy { it.id }
        assertEquals(0f, byId["a"]!!.x, 0.001f) // root pinned at the origin
        assertEquals(0f, byId["a"]!!.y, 0.001f)
        assertEquals(GRID_STEP, byId["b"]!!.x, 0.001f) // re-placed along the E exit
        assertTrue(s.canUndo) // tidy is a real map replacement -> undo step
        assertEquals(GRID_STEP, MapStore(tmp.root).load("m1")!!.rooms.single { it.id == "b" }.x, 0.001f)

        vm.undo()
        val reverted = vm.uiState.value.map!!.rooms.associateBy { it.id }
        assertEquals(5f, reverted["a"]!!.x, 0.001f) // undo reverts the positions
        assertEquals(200f, reverted["b"]!!.y, 0.001f)
        assertFalse(vm.uiState.value.canUndo)
    }

    // --- v1.1 room-mode interaction: detail window (single-tap) vs edit sheet (double-tap) ---

    @Test
    fun `select sets roomMode Detail and closes any open wheel`() {
        val vm = MapEditorViewModel("m1", store(baseMap(Room(id = "a"), Room(id = "b"))))
        vm.openWheel("a")
        vm.select("a")

        val s = vm.uiState.value
        assertEquals(RoomMode.Detail, s.roomMode)
        assertEquals("a", s.selectedRoomId)
        assertEquals("a", s.currentRoomId)
        assertNull(s.wheelForRoomId)
    }

    @Test
    fun `select null clears roomMode`() {
        val vm = MapEditorViewModel("m1", store(baseMap(Room(id = "a"))))
        vm.select("a")
        assertEquals(RoomMode.Detail, vm.uiState.value.roomMode)

        vm.select(null)
        val s = vm.uiState.value
        assertNull(s.roomMode)
        assertNull(s.selectedRoomId)
        assertEquals("a", s.currentRoomId) // current is still kept, as before
    }

    @Test
    fun `openRoomEdit sets roomMode Edit with selected and current on the room`() {
        val vm = MapEditorViewModel("m1", store(baseMap(Room(id = "a"), Room(id = "b"))))
        vm.select("a") // start with the detail window open
        vm.openRoomEdit("b")

        val s = vm.uiState.value
        assertEquals(RoomMode.Edit, s.roomMode)
        assertEquals("b", s.selectedRoomId)
        assertEquals("b", s.currentRoomId)
        assertNull(s.wheelForRoomId)
    }

    @Test
    fun `closeDetail clears roomMode but keeps selection and current`() {
        val vm = MapEditorViewModel("m1", store(baseMap(Room(id = "a"), Room(id = "b"))))
        vm.select("a")
        vm.closeDetail()

        val s = vm.uiState.value
        assertNull(s.roomMode)
        assertEquals("a", s.selectedRoomId)
        assertEquals("a", s.currentRoomId)
    }

    @Test
    fun `manage exits from the edit sheet opens the wheel, and closing it reopens the edit sheet`() {
        val vm = MapEditorViewModel("m1", store(baseMap(Room(id = "a"), Room(id = "b"))))
        vm.openRoomEdit("a")

        vm.openWheelFromEdit()
        val s1 = vm.uiState.value
        assertEquals("a", s1.wheelForRoomId)
        assertNull(s1.roomMode) // the sheet closed
        assertTrue(s1.wheelReturnToEdit) // the return flag is set

        vm.closeWheel()
        val s2 = vm.uiState.value
        assertEquals(RoomMode.Edit, s2.roomMode) // the edit sheet reopened
        assertEquals("a", s2.selectedRoomId)
        assertNull(s2.wheelForRoomId)
        assertFalse(s2.wheelReturnToEdit) // the flag is consumed
    }

    @Test
    fun `go from a sheet-opened wheel reopens the edit sheet for the source room`() {
        val m = baseMap(Room(id = "a"), Room(id = "b"))
            .copy(exits = listOf(Exit("e1", "a", Direction.N, "b")))
        val vm = MapEditorViewModel("m1", store(m))
        vm.openRoomEdit("a")
        vm.openWheelFromEdit()

        vm.go(Direction.N) // follows the existing exit to b
        val s = vm.uiState.value
        assertNull(s.wheelForRoomId)
        assertEquals("b", s.currentRoomId)
        assertEquals(RoomMode.Edit, s.roomMode) // back to the edit sheet for the source room
        assertEquals("a", s.selectedRoomId)
        assertFalse(s.wheelReturnToEdit)
    }

    @Test
    fun `link from a sheet-opened wheel ends in Edit mode on the target room`() {
        val vm = MapEditorViewModel("m1", store(baseMap(Room(id = "a"), Room(id = "b"))))
        vm.openRoomEdit("a")
        vm.openWheelFromEdit()
        vm.startLink(Direction.E)

        val s0 = vm.uiState.value
        assertNull(s0.wheelForRoomId)
        assertEquals(Direction.E, s0.linkMode)
        assertNull(s0.roomMode) // the sheet is closed while the link is armed

        vm.completeLink("b")
        val s = vm.uiState.value
        assertEquals(RoomMode.Edit, s.roomMode) // the sheet reopens in Edit mode
        assertEquals("b", s.selectedRoomId)
        assertEquals("b", s.currentRoomId)
        val exit = s.map!!.exits.single()
        assertEquals("a", exit.from)
        assertEquals("b", exit.to)
    }

    @Test
    fun `undo clears roomMode when the selected room is gone from the restored map`() {
        val vm = MapEditorViewModel("m1", store(baseMap(Room(id = "a"))))
        vm.select("a")
        vm.openWheel("a")
        vm.go(Direction.N) // creates a new current room; undo slot = {a}
        val newId = vm.uiState.value.currentRoomId!!
        vm.select(newId) // detail window on the freshly created room
        assertEquals(RoomMode.Detail, vm.uiState.value.roomMode)

        vm.undo()
        val s = vm.uiState.value
        assertEquals(1, s.map!!.rooms.size)
        assertNull(s.selectedRoomId)
        assertNull(s.roomMode) // the window cannot stay open without its room
    }

    @Test
    fun `undo keeps roomMode when the selected room still exists in the restored map`() {
        val vm = MapEditorViewModel("m1", store(baseMap(Room(id = "a", name = "A0"))))
        vm.openRoomEdit("a")
        vm.updateRoomText("a", "A1", "", "")
        vm.updateRoomText("a", "A2", "", "")

        vm.undo()
        val s = vm.uiState.value
        assertEquals("A1", s.map!!.rooms.single().name)
        assertEquals("a", s.selectedRoomId)
        assertEquals(RoomMode.Edit, s.roomMode) // the edit sheet stays open
    }
}
