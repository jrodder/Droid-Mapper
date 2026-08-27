package com.jrod.droidgridder.data

import com.jrod.droidgridder.model.MapFile
import com.jrod.droidgridder.model.Room
import java.io.File
import java.util.UUID

class MapStore(private val rootDir: File) {
    init { rootDir.mkdirs() }
    private fun fileFor(id: String) = File(rootDir, "$id.json")

    fun newMap(name: String): MapFile =
        MapFile(id = UUID.randomUUID().toString(), name = name, createdAt = 0L, updatedAt = 0L,
            // ponytail: new maps seed one root room — go() needs a current room and the UI has no
            // "add first room" affordance (Task 9 E2E found fresh maps were an empty dead-end).
            rooms = listOf(Room(id = UUID.randomUUID().toString())))

    fun save(map: MapFile) {
        val now = System.currentTimeMillis()
        val target = fileFor(map.id)
        // ponytail: write a temp file in the same dir then rename — atomic on the same
        // filesystem, so a mid-write kill leaves the previous map intact instead of a
        // truncated file load()/list() would silently skip.
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(encodeMap(map.copy(
            createdAt = if (map.createdAt == 0L) now else map.createdAt, updatedAt = now)))
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    fun load(id: String): MapFile? =
        fileFor(id).takeIf { it.exists() }?.readText()?.let { runCatching { decodeMap(it) }.getOrNull() }

    fun delete(id: String) { fileFor(id).delete() }

    fun list(): List<MapFile> =
        rootDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { decodeMap(it.readText()) }.getOrNull() }
            ?.sortedByDescending { it.updatedAt } ?: emptyList()
}
