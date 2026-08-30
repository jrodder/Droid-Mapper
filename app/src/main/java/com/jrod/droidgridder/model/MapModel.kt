package com.jrod.droidgridder.model

import kotlinx.serialization.Serializable

@Serializable
data class MapFile(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val rooms: List<Room> = emptyList(),
    val exits: List<Exit> = emptyList(),
)

@Serializable
data class Room(
    val id: String,
    val name: String = "",
    val description: String = "",
    val notes: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    /** Unlit room ("total darkness") — the canvas draws it dimmed. */
    val isDark: Boolean = false,
)

@Serializable
data class Exit(
    val id: String,
    val from: String,
    val direction: Direction,
    val to: String,
    /** Deliberate one-way passage (no return): the reverse record is absent. */
    val oneWay: Boolean = false,
    /** Contextual command to traverse ("climb rope", "slide"); drawn on the line. */
    val traversalAction: String = "",
)

enum class Direction { N, S, E, W, NE, NW, SE, SW, UP, DOWN, IN, OUT }

data class Pos(val x: Float, val y: Float)

/**
 * ZUG-style display names: rooms sharing a name are numbered by discovery
 * order (append order of [rooms]) — "Maze (1)", "Maze (2)"… Unique and blank
 * names pass through. Display-only: stored names never change.
 * ponytail: deleting a mid-list room renumbers its successors (a human map
 * wouldn't); persist the number if that ever bites.
 */
fun displayNames(rooms: List<Room>): Map<String, String> {
    val shared = rooms.groupBy { it.name }.filterValues { it.size > 1 }.keys.toSet()
    val seen = mutableMapOf<String, Int>()
    return rooms.associate { r ->
        if (r.name.isNotBlank() && r.name in shared) {
            val n = (seen[r.name] ?: 0) + 1
            seen[r.name] = n
            r.id to "${r.name} ($n)"
        } else {
            r.id to r.name
        }
    }
}

/**
 * ZUG canon: containment passages get an In / Out label at their midpoint;
 * UP and DOWN passages carry no label — they are drawn DASHED (see
 * MapCanvas) so the stair is readable without text. Compass passages label
 * themselves by their bearing.
 */
fun edgeLabel(d: Direction): String? = when (d) {
    Direction.IN -> "In"
    Direction.OUT -> "Out"
    else -> null
}

const val GRID_STEP = 180f

/** Room box side in world units (~GRID_STEP * 0.7); public so the VM can compute label-aware strides. */
const val ROOM_BOX_SIZE = GRID_STEP * 0.7f
