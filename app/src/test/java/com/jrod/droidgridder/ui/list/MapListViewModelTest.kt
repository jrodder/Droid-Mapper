package com.jrod.droidgridder.ui.list

import com.jrod.droidgridder.data.MapStore
import com.jrod.droidgridder.data.encodeMap
import com.jrod.droidgridder.model.Direction
import com.jrod.droidgridder.model.Exit
import com.jrod.droidgridder.model.MapFile
import com.jrod.droidgridder.model.Room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MapListViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun sampleMap(id: String = "m1", name: String = "Sample") = MapFile(
        id = id, name = name, createdAt = 1L, updatedAt = 2L,
        rooms = listOf(Room(id = "a", name = "Room A"), Room(id = "b", name = "Room B")),
        exits = listOf(Exit("e1", "a", Direction.E, "b")),
    )

    private fun vmWith(vararg maps: MapFile): MapListViewModel {
        val store = MapStore(tmp.root)
        maps.forEach(store::save)
        return MapListViewModel(store)
    }

    private fun storeOf() = MapStore(tmp.root)

    @Test
    fun `create persists the new map and it appears in the list state`() {
        val vm = vmWith()

        val created = vm.create("Zork")

        assertEquals("Zork", created.name)
        assertNotNull(storeOf().load(created.id))
        assertEquals("Zork", storeOf().load(created.id)!!.name)
        assertEquals(listOf(created.id), vm.maps.value.map { it.id })
        assertEquals("Zork", vm.maps.value.single().name)
    }

    @Test
    fun `rename updates the stored name and the list state`() {
        val m = sampleMap()
        val vm = vmWith(m)

        vm.rename(m.id, "Renamed")

        assertEquals("Renamed", storeOf().load(m.id)!!.name)
        assertEquals("Renamed", vm.maps.value.single().name)
    }

    @Test
    fun `delete removes the map from the store and the list state`() {
        val m = sampleMap()
        val vm = vmWith(m)

        vm.delete(m.id)

        assertNull(storeOf().load(m.id))
        assertTrue(vm.maps.value.isEmpty())
    }

    @Test
    fun `importMap round-trips a clean map with rooms and exits intact`() {
        val original = sampleMap(id = "imported", name = "Imported Map")
        val vm = vmWith()

        assertTrue(vm.importMap(encodeMap(original)))

        val imported = vm.maps.value.single()
        assertEquals("imported", imported.id)
        assertEquals("Imported Map", imported.name)
        assertEquals(original.rooms, imported.rooms)
        assertEquals(original.exits, imported.exits)
        assertEquals(imported, storeOf().load("imported"))
    }

    @Test
    fun `importMap of the same file twice keeps the original and gives both imports fresh distinct ids`() {
        val original = sampleMap(id = "m1", name = "Original")
        val vm = vmWith(original)
        val json = encodeMap(original)

        assertTrue(vm.importMap(json))
        assertTrue(vm.importMap(json))

        val maps = vm.maps.value
        assertEquals(3, maps.size)
        // original data untouched (same id, name, rooms, exits — only updatedAt may differ)
        val kept = maps.single { it.id == "m1" }
        assertEquals(original.name, kept.name)
        assertEquals(original.rooms, kept.rooms)
        assertEquals(original.exits, kept.exits)
        val importedIds = maps.filter { it.id != "m1" }.map { it.id }
        assertEquals(2, importedIds.toSet().size) // both imports exist, distinct fresh ids
        importedIds.forEach { id ->
            val imported = maps.single { it.id == id }
            assertEquals(original.rooms, imported.rooms)
            assertEquals(original.exits, imported.exits)
        }
        assertEquals(3, storeOf().list().size)
    }

    @Test
    fun `importMap of invalid JSON fails without saving anything`() {
        val vm = vmWith()

        assertFalse(vm.importMap("{ not a valid map"))

        assertTrue(vm.maps.value.isEmpty())
        assertTrue(storeOf().list().isEmpty())
    }
}