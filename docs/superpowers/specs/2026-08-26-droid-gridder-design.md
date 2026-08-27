# Droid-Gridder — Design Spec (v1)

- **Date:** 2026-08-26
- **Status:** Approved (awaiting implementation)
- **Author:** jrod + agent

## Purpose

A manual mapping tool for text adventures (Infocom-style: Zork, Enchanter, etc.).
The user places rooms on an infinite canvas and connects them with the game's ten
compass directions, annotating each room. It is deliberately **manual** — there is
no screen-scraping or automapping; the player records every move themselves.

It is designed to later gain a second "grid" mode for Wizardry-style dungeon
crawlers. v1 ships only the Infocom room-graph mode, but the architecture leaves
room for grid mode.

## Play setup (context)

- **Infocom games** run on the *same Android device* (an interpreter / terminal
  emulator). The map is therefore used side-by-side via Android's native
  split-screen — so the app **must** be resizeable with no locked orientation.
  A floating overlay was explicitly rejected (insufficient screen real estate).
- **Wizardry-style crawlers** are played elsewhere (PC); the phone sits nearby as
  a dedicated, full-screen map.

## Goals

- Place, connect, and annotate rooms by tapping, with an emphasis on one-tap
  recording of the common (cardinal) moves.
- Handle Infocom's real quirks: mazes where exits lie, non-symmetric connections,
  and UP/DOWN with no spatial direction.
- Save maps automatically and locally; export/import as JSON.
- Feel alive: smooth pan/zoom and subtle animations.

## Non-goals (v1)

- No automapping / game integration / OCR.
- No grid mode (deferred; architected for).
- No cloud sync, collaboration, or sharing accounts.
- No multi-step undo (single-step only).
- No manual dragging of rooms (positions are auto-managed).

## Map model

The map is a **graph**, not a grid:

- A **room** is a node floating on an infinite 2D canvas. It has a name,
  description, notes, and a position.
- A **direction** is a **labeled directed edge** between two rooms. Directions are
  the ten compass/vertical words: `N, S, E, W, NE, NW, SE, SW, UP, DOWN`.
- Spatial position is **decoration**. The real data is "room A has a `W` exit to
  room B." This makes overlaps a non-issue: arriving at an already-mapped room is
  just *another edge into that room*, never a stacked duplicate.

## Direction set

`N, S, E, W, NE, NW, SE, SW, UP, DOWN` (10). Opposite pairs:

| Direction | Opposite |
|---|---|
| N / S | each other |
| E / W | each other |
| NE / SW | each other |
| NW / SE | each other |
| UP / DOWN | each other |

## Interaction model

| Gesture | Action |
|---|---|
| One-finger drag | Pan the canvas |
| Pinch | Zoom |
| Single-tap a room | Select it (opens bottom sheet, sets current-room highlight) |
| Double-tap a room | Open the direction wheel for that room |
| Single-tap empty canvas | Deselect |

Double-tap (not single-tap) opens the wheel so pan/zoom and selection never
conflict.

## Move verbs (direction wheel)

After double-tapping a room, the wheel shows the ten directions. Two gestures:

| Gesture | Verb | Behavior |
|---|---|---|
| **Tap** a direction | **go** | If that exit already exists → follow it (no change to map data). If not → create a new room, auto-place it in that direction, and draw the labeled connector. |
| **Long-press** a direction | **link to existing** | Then tap the room you actually ended up in. Draws the labeled connector to that existing room (used for loops and mazes). |

### Reverse exits

- On **go** (new room), automatically add the opposite exit back to where you came
  from (most text adventures are symmetric). Example: going `N` from A creates B
  and adds both `A -N-> B` and `B -S-> A`.
- On **link to existing**, do **not** auto-add a reverse exit — linking is a
  signal the geometry is special (maze-safe).

## Room data

Each room carries: **name**, **description**, **notes** (all free text, optional).
Displayed/edited in the bottom sheet. The name is drawn on the canvas under the
room box.

## Editing & corrections

Selecting a room opens a bottom sheet containing:

- Editable **name / description / notes** fields.
- An **exits list**: one row per exit (e.g. `N → North of House`), each with
  **delete** and **redirect** (point the exit at a different room) actions.
- A **Delete room** button (with confirmation dialog).

Correction flows:

- Wrong direction tapped → **Undo** (or delete that exit in the sheet).
- Room created by mistake → **Undo** or **Delete room**.
- Exit leads somewhere unexpected (the game lied) → **redirect** the exit.
- Typos / more detail → edit text fields.

### Delete semantics

Deleting a room **cascade-deletes** every exit that touches it (both its outgoing
exits and every incoming exit from neighbors).

### Undo

**Single-step undo**: a global Undo button reverts only the most recent mutating
action (no full history).

## Positioning

- **No dragging.** Room positions are always auto-managed.
- **Auto-placement:** a new room is placed at a fixed step (`GRID_STEP`) from its
  source in the chosen direction. If that spot is already occupied, the app nudges
  outward along the same direction until a free spot is found. UP/DOWN are offset
  vertically with a larger gap (they have no spatial direction).
- **Auto-tidy:** a button re-lays-out the whole graph from the first room via a
  breadth-first walk, nudging to resolve collisions. Rooms become draggable only
  through this re-layout, not by hand.

## Current room

A **soft highlight** (not a hard game-state tracker): the last room acted on is
outlined as "current" and moves when you `go`. Session-only, not persisted.

## Persistence

- Maps persist **locally** and **auto-save** on every mutation.
- Multiple maps, each with a name, stored as JSON files in app-private storage
  (one file per map) plus an in-memory index.
- **Export** a map as a JSON file (share intent). **Import** a JSON file (open
  document). The on-disk format **is** the export format.

## Screens

1. **Map list** — create / open / rename / delete maps; export (share) / import
   (open document).
2. **Map editor** — full-screen canvas; top bar with Undo and Auto-tidy; direction
   wheel; room bottom sheet.

## Visual & animation

- Rooms render as rounded rectangles with their name; exits as lines with a
  direction label at the midpoint.
- Current room outlined; selected room highlighted.
- Animations: new rooms pop in, connectors draw in, smooth pan/zoom, and auto-tidy
  glides rooms into place.

## Architecture

- **Stack:** Kotlin + Jetpack Compose (Material 3), single Activity, Compose
  Navigation (2 screens), MVVM with `StateFlow`, `kotlinx.serialization`, custom
  `Canvas` drawing.
- **Core logic is pure** (zero Android imports) and JVM-unit-tested.
- **Model is immutable**; every mutation returns a new `MapFile`.

## Data model (types)

```kotlin
enum class Direction { N, S, E, W, NE, NW, SE, SW, UP, DOWN }

data class Pos(val x: Float, val y: Float)          // canvas position (not persisted)

data class MapFile(
    val id: String,
    val name: String,
    val createdAt: Long,                             // epoch millis (0 until first save)
    val updatedAt: Long,
    val rooms: List<Room> = emptyList(),
    val exits: List<Exit> = emptyList(),
)

data class Room(
    val id: String,
    val name: String = "",
    val description: String = "",
    val notes: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
)

data class Exit(
    val id: String,
    val from: String,                                // room id
    val direction: Direction,
    val to: String,                                  // room id
)
```

## Pure logic (functions)

```kotlin
const val GRID_STEP = 180f

fun Direction.opposite(): Direction
fun directionOffset(d: Direction): Pos

fun placeNewRoom(direction: Direction, from: Room, rooms: List<Room>): Pos
fun go(direction: Direction, currentRoomId: String, map: MapFile): MapFile
fun linkToExisting(direction: Direction, fromRoomId: String, toRoomId: String, map: MapFile): MapFile
fun deleteRoom(roomId: String, map: MapFile): MapFile
fun deleteExit(exitId: String, map: MapFile): MapFile
fun redirectExit(exitId: String, newToRoomId: String, map: MapFile): MapFile
fun updateRoomText(roomId: String, name: String, description: String, notes: String, map: MapFile): MapFile
fun autoTidy(map: MapFile): MapFile
```

## Error handling / edge cases

- `go` with an unknown room id → treated as caller error (guarded in the ViewModel;
  the pure function uses `first {}` and throws a clear message).
- `linkToExisting` to a room that doesn't exist → guarded in UI (target is picked
  from the rendered canvas, so it always exists).
- JSON decode of a corrupt/unknown file → `runCatching` in the store; bad files are
  skipped in the list rather than crashing.
- Export/import file access is via the Storage Access Framework (no storage
  permissions required).

## Testing strategy

- **JVM unit tests** cover all pure logic (placement, reverse, go/link, delete
  cascade, redirect, auto-tidy, JSON round-trip). These are the correctness
  guarantee.
- **Agentic UI verification** on the headless emulator: build → install → launch →
  drive with `adb input`, read the screen with `uiautomator dump`, and confirm with
  screenshots (live-viewable via scrcpy).
- Compose UI tests are a stretch goal, not required for v1.

## Future work (out of scope for v1)

- **Grid mode** for Wizardry-style crawlers (bounded grid, walls/doors/spinners/
  pits/stairs, multi-floor). The model should treat this as a new "map kind"
  alongside the room graph.
- Multi-step undo history.
- Custom room icons / colors / categories.
- Sharing maps via a hosted service.
