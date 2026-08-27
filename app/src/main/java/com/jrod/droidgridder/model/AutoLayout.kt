package com.jrod.droidgridder.model

/**
 * Pure auto-tidy layout: re-places every room reachable from the first room
 * (the root, pinned at the origin) via BFS along exit direction offsets, using
 * [freePosition] to dodge already-occupied grid cells. Returns a new [MapFile]
 * with the re-laid-out rooms; unreachable rooms keep their position.
 */
fun autoTidy(map: MapFile, stride: Float = GRID_STEP): MapFile {
    if (map.rooms.isEmpty()) return map
    val pos = HashMap<String, Pos>()
    val occupied = ArrayList<Pos>()
    val root = map.rooms.first().id
    pos[root] = Pos(0f, 0f); occupied += Pos(0f, 0f)
    val queue = ArrayDeque<String>(); queue.add(root)
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        for (e in map.exits.filter { it.from == id && it.to != id }) {
            if (!pos.containsKey(e.to)) {
                val p = freePosition(pos[id]!!, e.direction, occupied, stride)
                pos[e.to] = p; occupied += p; queue.add(e.to)
            }
        }
    }
    return map.copy(rooms = map.rooms.map { r -> pos[r.id]?.let { r.copy(x = it.x, y = it.y) } ?: r })
}