package com.jrod.droidgridder.data

import com.jrod.droidgridder.model.Direction
import com.jrod.droidgridder.model.Exit
import com.jrod.droidgridder.model.MapFile
import com.jrod.droidgridder.model.Room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MapJsonTest {
    private val sample = MapFile(
        id = "m1", name = "Zork", createdAt = 1L, updatedAt = 2L,
        rooms = listOf(Room("a", "West of House", "desc", "note", 0f, 0f), Room("b", "North of House", "", "", 0f, -180f)),
        exits = listOf(Exit("e1", "a", Direction.N, "b"), Exit("e2", "b", Direction.S, "a")),
    )

    @Test fun `round trip preserves all data`() {
        assertEquals(sample, decodeMap(encodeMap(sample)))
    }

    @Test fun `round trip preserves all ten directions`() {
        val m = MapFile("m", "m", 0L, 0L, rooms = listOf(Room("a"), Room("b")),
            exits = Direction.entries.mapIndexed { i, d -> Exit("e$i", "a", d, "b") })
        assertEquals(m, decodeMap(encodeMap(m)))
    }

    @Test fun `save twice overwrites the file in place and load round-trips`() {
        // ponytail: JVM-level pin for the atomic write — a mid-write process kill is not
        // simulatable on the JVM, so this only proves the temp-file-then-rename overwrite
        // path leaves a valid, loadable file with no stray .tmp behind.
        val dir = File.createTempFile("maps", "").let { it.delete(); File(it.absolutePath) }
        try {
            val store = MapStore(dir)
            val m = store.newMap("Zork")
            store.save(m)
            val root = m.rooms.single().copy(name = "Renamed")
            store.save(m.copy(rooms = listOf(root))) // second save: overwrite path

            val loaded = store.load(m.id)!!
            assertEquals("Renamed", loaded.rooms.single().name)
            assertEquals(m.id, loaded.id)
            assertEquals(listOf("${m.id}.json"), dir.list()?.toList()) // overwritten in place, no .tmp left
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `store list save load delete`() {
        val dir = File.createTempFile("maps", "").let { it.delete(); File(it.absolutePath) }
        val store = MapStore(dir)
        val map = store.newMap("Zork")
        assertEquals(1, map.rooms.size) // new maps seed a single root room (go() needs a current room)
        store.save(map.copy(rooms = listOf(Room("a"))))
        assertEquals(1, store.list().size)
        assertEquals("Zork", store.load(map.id)?.name)
        store.delete(map.id)
        assertTrue(store.list().isEmpty())
        assertNull(store.load(map.id))
        dir.deleteRecursively()
    }
}
