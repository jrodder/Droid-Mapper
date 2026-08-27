package com.jrod.droidgridder.ui.editor

import com.jrod.droidgridder.data.MapStore
import com.jrod.droidgridder.model.Direction
import com.jrod.droidgridder.model.Exit
import com.jrod.droidgridder.model.MapFile
import com.jrod.droidgridder.model.Room
import org.junit.Assert.assertEquals
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
    fun `select null clears selection but keeps current room`() {
        val vm = MapEditorViewModel("m1", store(baseMap(Room(id = "a"), Room(id = "b"))))
        vm.select("a")
        vm.select("b")
        vm.select(null)

        val s = vm.uiState.value
        assertNull(s.selectedRoomId)
        assertEquals("b", s.currentRoomId)
    }
}
