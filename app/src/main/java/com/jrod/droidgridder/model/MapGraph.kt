package com.jrod.droidgridder.model

import java.util.UUID

fun Direction.opposite(): Direction = when (this) {
    Direction.N -> Direction.S; Direction.S -> Direction.N
    Direction.E -> Direction.W; Direction.W -> Direction.E
    Direction.NE -> Direction.SW; Direction.NW -> Direction.SE
    Direction.SE -> Direction.NW; Direction.SW -> Direction.NE
    Direction.UP -> Direction.DOWN; Direction.DOWN -> Direction.UP
    Direction.IN -> Direction.OUT; Direction.OUT -> Direction.IN
}

fun directionOffset(d: Direction, stride: Float = GRID_STEP): Pos = when (d) {
    Direction.N -> Pos(0f, -stride); Direction.S -> Pos(0f, stride)
    Direction.E -> Pos(stride, 0f); Direction.W -> Pos(-stride, 0f)
    Direction.NE -> Pos(stride, -stride); Direction.NW -> Pos(-stride, -stride)
    Direction.SE -> Pos(stride, stride); Direction.SW -> Pos(-stride, stride)
    Direction.UP -> Pos(0f, -stride * 2f); Direction.DOWN -> Pos(0f, stride * 2f)
    // ponytail: containment has no compass — rest vector zero means "same
    // location"; freePosition's spiral below picks the nearest free slot.
    Direction.IN, Direction.OUT -> Pos(0f, 0f)
}

private fun Pos.isNear(o: Pos, stride: Float): Boolean =
    (x - o.x) * (x - o.x) + (y - o.y) * (y - o.y) < stride * stride

fun freePosition(from: Pos, direction: Direction, occupied: List<Pos>, stride: Float = GRID_STEP): Pos {
    if (direction == Direction.IN || direction == Direction.OUT) {
        return containmentPosition(from, occupied, stride)
    }
    val base = directionOffset(direction, stride)
    var k = 1
    while (true) {
        val c = Pos(from.x + base.x * k, from.y + base.y * k)
        if (occupied.none { it.isNear(c, stride) }) return c
        k++
    }
}

/**
 * Placement for IN/OUT ("contained") rooms: the nearest free cell around the
 * parent, in fixed spiral order (N, NE, E, SE, S, SW, W, NW, then ring 2…),
 * so an interior room hugs its outer room. Deterministic for a given map.
 */
private val CONTAINMENT_SLOTS = listOf(
    Pos(0f, -1f), Pos(1f, -1f), Pos(1f, 0f), Pos(1f, 1f),
    Pos(0f, 1f), Pos(-1f, 1f), Pos(-1f, 0f), Pos(-1f, -1f),
)

private fun containmentPosition(from: Pos, occupied: List<Pos>, stride: Float): Pos {
    var ring = 1
    while (true) {
        for (slot in CONTAINMENT_SLOTS) {
            val c = Pos(from.x + slot.x * stride * ring, from.y + slot.y * stride * ring)
            if (occupied.none { it.isNear(c, stride) }) return c
        }
        ring++
    }
}

fun placeNewRoom(direction: Direction, from: Room, rooms: List<Room>, stride: Float = GRID_STEP): Pos =
    freePosition(Pos(from.x, from.y), direction, rooms.map { Pos(it.x, it.y) }, stride)

fun go(direction: Direction, currentRoomId: String, map: MapFile, stride: Float = GRID_STEP): MapFile {
    val fromRoom = map.rooms.firstOrNull { it.id == currentRoomId }
    require(fromRoom != null) { "go: unknown room $currentRoomId" }
    if (map.exits.any { it.from == currentRoomId && it.direction == direction }) return map
    // A one-way passage blocks its reverse: you can't walk back up.
    if (map.exits.any { it.oneWay && it.to == currentRoomId && it.direction.opposite() == direction }) return map
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

/**
 * Toggle [oneWay] on the exit [exitId]. One-way means the reverse record is
 * removed (the return direction is blocked, there is nothing left to draw);
 * turning it back off recreates the reverse if it is missing. Other records
 * (including unrelated one-ways) are untouched.
 */
fun setExitOneWay(exitId: String, oneWay: Boolean, map: MapFile): MapFile {
    val exit = map.exits.firstOrNull { it.id == exitId } ?: return map
    val isReverseOf: (Exit) -> Boolean =
        { it.from == exit.to && it.to == exit.from && it.direction == exit.direction.opposite() }
    return if (oneWay) {
        map.copy(
            exits = map.exits
                .map { if (it.id == exitId) it.copy(oneWay = true) else it }
                .filterNot { it.id != exitId && isReverseOf(it) },
        )
    } else {
        val exits = map.exits.map { if (it.id == exitId) it.copy(oneWay = false) else it }
        val withReverse = if (exits.none { isReverseOf(it) }) {
            exits + Exit(UUID.randomUUID().toString(), exit.to, exit.direction.opposite(), exit.from)
        } else {
            exits
        }
        map.copy(exits = withReverse)
    }
}

/**
 * Delete the passage the user pointed at: the exit [exitId] plus its mirror
 * record if one exists. Rooms and every other connection survive.
 */
fun deletePassage(exitId: String, map: MapFile): MapFile {
    val exit = map.exits.firstOrNull { it.id == exitId } ?: return map
    val isReverseOf: (Exit) -> Boolean =
        { it.from == exit.to && it.to == exit.from && it.direction == exit.direction.opposite() }
    return map.copy(exits = map.exits.filterNot { it.id == exitId || isReverseOf(it) })
}
