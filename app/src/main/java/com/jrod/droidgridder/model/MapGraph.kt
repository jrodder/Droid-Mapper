package com.jrod.droidgridder.model

import java.util.UUID

fun Direction.opposite(): Direction = when (this) {
    Direction.N -> Direction.S; Direction.S -> Direction.N
    Direction.E -> Direction.W; Direction.W -> Direction.E
    Direction.NE -> Direction.SW; Direction.NW -> Direction.SE
    Direction.SE -> Direction.NW; Direction.SW -> Direction.NE
    Direction.UP -> Direction.DOWN; Direction.DOWN -> Direction.UP
}

fun directionOffset(d: Direction, stride: Float = GRID_STEP): Pos = when (d) {
    Direction.N -> Pos(0f, -stride); Direction.S -> Pos(0f, stride)
    Direction.E -> Pos(stride, 0f); Direction.W -> Pos(-stride, 0f)
    Direction.NE -> Pos(stride, -stride); Direction.NW -> Pos(-stride, -stride)
    Direction.SE -> Pos(stride, stride); Direction.SW -> Pos(-stride, stride)
    Direction.UP -> Pos(0f, -stride * 2f); Direction.DOWN -> Pos(0f, stride * 2f)
}

private fun Pos.isNear(o: Pos, stride: Float): Boolean =
    (x - o.x) * (x - o.x) + (y - o.y) * (y - o.y) < stride * stride

fun freePosition(from: Pos, direction: Direction, occupied: List<Pos>, stride: Float = GRID_STEP): Pos {
    val base = directionOffset(direction, stride)
    var k = 1
    while (true) {
        val c = Pos(from.x + base.x * k, from.y + base.y * k)
        if (occupied.none { it.isNear(c, stride) }) return c
        k++
    }
}

fun placeNewRoom(direction: Direction, from: Room, rooms: List<Room>, stride: Float = GRID_STEP): Pos =
    freePosition(Pos(from.x, from.y), direction, rooms.map { Pos(it.x, it.y) }, stride)

fun go(direction: Direction, currentRoomId: String, map: MapFile, stride: Float = GRID_STEP): MapFile {
    val fromRoom = map.rooms.firstOrNull { it.id == currentRoomId }
    require(fromRoom != null) { "go: unknown room $currentRoomId" }
    if (map.exits.any { it.from == currentRoomId && it.direction == direction }) return map
    val id = UUID.randomUUID().toString()
    val pos = placeNewRoom(direction, fromRoom, map.rooms, stride)
    val room = Room(id = id, x = pos.x, y = pos.y)
    val exit = Exit(UUID.randomUUID().toString(), currentRoomId, direction, id)
    val reverse = Exit(UUID.randomUUID().toString(), id, direction.opposite(), currentRoomId)
    return map.copy(rooms = map.rooms + room, exits = map.exits + exit + reverse)
}

fun linkToExisting(direction: Direction, fromRoomId: String, toRoomId: String, map: MapFile): MapFile {
    val existing = map.exits.firstOrNull { it.from == fromRoomId && it.direction == direction }
    val exit = Exit(existing?.id ?: UUID.randomUUID().toString(), fromRoomId, direction, toRoomId)
    val exits = if (existing == null) map.exits + exit
                else map.exits.map { if (it.id == existing.id) exit else it }
    return map.copy(exits = exits)
}

fun deleteRoom(roomId: String, map: MapFile): MapFile =
    map.copy(rooms = map.rooms.filterNot { it.id == roomId },
             exits = map.exits.filterNot { it.from == roomId || it.to == roomId })

fun deleteExit(exitId: String, map: MapFile): MapFile =
    map.copy(exits = map.exits.filterNot { it.id == exitId })

fun redirectExit(exitId: String, newToRoomId: String, map: MapFile): MapFile =
    map.copy(exits = map.exits.map { if (it.id == exitId) it.copy(to = newToRoomId) else it })

fun updateRoomText(roomId: String, name: String, description: String, notes: String, map: MapFile): MapFile =
    map.copy(rooms = map.rooms.map { if (it.id == roomId) it.copy(name = name, description = description, notes = notes) else it })
