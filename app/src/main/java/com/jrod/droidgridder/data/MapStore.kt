package com.jrod.droidgridder.data

import com.jrod.droidgridder.model.MapFile
import java.io.File
import java.util.UUID

class MapStore(private val rootDir: File) {
    init { rootDir.mkdirs() }
    private fun fileFor(id: String) = File(rootDir, "$id.json")

    fun newMap(name: String): MapFile =
        MapFile(id = UUID.randomUUID().toString(), name = name, createdAt = 0L, updatedAt = 0L)

    fun save(map: MapFile) {
        val now = System.currentTimeMillis()
        fileFor(map.id).writeText(encodeMap(map.copy(
            createdAt = if (map.createdAt == 0L) now else map.createdAt, updatedAt = now)))
    }

    fun load(id: String): MapFile? =
        fileFor(id).takeIf { it.exists() }?.readText()?.let { runCatching { decodeMap(it) }.getOrNull() }

    fun delete(id: String) { fileFor(id).delete() }

    fun list(): List<MapFile> =
        rootDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { decodeMap(it.readText()) }.getOrNull() }
            ?.sortedByDescending { it.updatedAt } ?: emptyList()
}
