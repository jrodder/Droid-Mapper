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
)

@Serializable
data class Exit(
    val id: String,
    val from: String,
    val direction: Direction,
    val to: String,
)

enum class Direction { N, S, E, W, NE, NW, SE, SW, UP, DOWN, IN, OUT }

data class Pos(val x: Float, val y: Float)

const val GRID_STEP = 180f

/** Room box side in world units (~GRID_STEP * 0.7); public so the VM can compute label-aware strides. */
const val ROOM_BOX_SIZE = GRID_STEP * 0.7f
