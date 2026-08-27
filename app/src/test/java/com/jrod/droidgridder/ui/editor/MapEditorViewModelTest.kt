package com.jrod.droidgridder.ui.editor

import com.jrod.droidgridder.data.MapStore
import com.jrod.droidgridder.model.Direction
import com.jrod.droidgridder.model.Exit
import com.jrod.droidgridder.model.MapFile
import com.jrod.droidgridder.model.Room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
    fun `select null clears selection but keeps current room`() {
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
        vm.select("a")
        vm.updateRoomText("a", "New", "a desc", "a note")

        val room = vm.uiState.value.map!!.rooms.single { it.id == "a" }
        assertEquals("New", room.name)
        assertEquals("a desc", room.description)
        assertEquals("a note", room.notes)
        assertTrue(vm.uiState.value.canUndo)
        assertNull(vm.uiState.value.selectedRoomId) // sheet closes on commit
        val saved = MapStore(tmp.root).load("m1")!!.rooms.single()
        assertEquals("New", saved.name)
        assertEquals("a note", saved.notes)
    }

    @Test
    fun `updateRoomText with unchanged values is a no-op with no undo push`() {
        val vm = MapEditorViewModel("m1", store(baseMap(Room(id = "a", name = "Old"))))
        vm.select("a")
        vm.updateRoomText("a", "Old", "", "")

        val s = vm.uiState.value
        assertFalse(s.canUndo)
        assertEquals("a", s.selectedRoomId) // sheet stays open
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
        vm.select("a")
        vm.deleteExit("e1")

        val s = vm.uiState.value
        assertEquals(listOf("e2"), s.map!!.exits.map { it.id })
        assertTrue(s.canUndo)
        assertEquals(listOf("e2"), MapStore(tmp.root).load("m1")!!.exits.map { it.id })
    }

    @Test
    fun `redirect arms from the sheet, repoints the same exit id, and saves`() {
        val m = baseMap(Room(id = "a"), Room(id = "b"), Room(id = "c"))
            .copy(exits = listOf(Exit("e1", "a", Direction.N, "b")))
        val vm = MapEditorViewModel("m1", store(m))
        vm.select("a")
        vm.startRedirect("e1")

        val s1 = vm.uiState.value
        assertEquals("e1", s1.redirectMode!!.exitId)
        assertEquals(Direction.N, s1.redirectMode!!.direction)
        assertEquals("a", s1.redirectMode!!.fromRoomId)
        assertNull(s1.selectedRoomId) // sheet dismissed while redirect is armed

        vm.completeRedirect("c")
        val s2 = vm.uiState.value
        val exit = s2.map!!.exits.single { it.id == "e1" }
        assertEquals("c", exit.to)
        assertNull(s2.redirectMode)
        assertTrue(s2.canUndo)
        assertEquals("a", s2.selectedRoomId) // sheet reopens on the source room
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
    fun `go on existing exit pushes no undo step`() {
        val m = baseMap(Room(id = "a"), Room(id = "b"))
            .copy(exits = listOf(Exit("e1", "a", Direction.N, "b")))
        val vm = MapEditorViewModel("m1", store(m))
        vm.openWheel("a")
        vm.go(Direction.N)

        assertFalse(vm.uiState.value.canUndo)
    }
}
