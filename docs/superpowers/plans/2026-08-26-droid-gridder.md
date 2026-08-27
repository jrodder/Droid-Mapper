# Droid-Gridder v1 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development
> (or superpowers:executing-plans). Execute task-by-task. Steps use `- [ ]` checkboxes.
> Spec: `docs/superpowers/specs/2026-08-26-droid-gridder-design.md`.

**Goal:** A working Android app to map Infocom-style text adventures — place, connect,
and annotate rooms; save/load maps; export/import JSON.

**Architecture:** Immutable `MapFile` model + pure `MapGraph`/`AutoLayout` functions
over it; a thin JSON file store; a Compose editor screen rendering the graph on a
zoomable/pannable canvas with a direction wheel and an editing bottom sheet.

**Tech Stack:** Kotlin 2.1.20, AGP 8.10.0, Gradle 8.14.3, JDK 17, Compose BOM
2025.01.00, Material 3, Navigation Compose, kotlinx.serialization, coroutines,
JUnit4 (JVM tests).

---

## Environment / Prerequisites (read first)

This machine already has the Android toolchain installed. Before any build or test:

```bash
source ~/Android/env.sh   # sets JAVA_HOME (JDK 17), ANDROID_HOME, PATH (adb/emulator/gradle)
```

- **JDK 17** at `~/.local/jdk/jdk-17.0.20.1+1` (AGP 8.x requires 17; do NOT use the
  system Java 25).
- **Emulator AVD** `dev` (Pixel 7, Android 16, x86_64, KVM-accelerated). It may
  already be running. Check with `adb devices` (expect `emulator-5554`). To boot
  headless if stopped:
  ```bash
  emulator -avd dev -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
  adb wait-for-device && adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done'
  ```
- **Build:** `./gradlew assembleDebug`
- **Unit tests:** `./gradlew testDebugUnitTest`
- **Install + launch:** `adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell am start -n com.jrod.droidgridder/.MainActivity`
- **Read the screen:** `adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml /tmp/ui.xml`
- **Screenshot:** `adb exec-out screencap -p > /tmp/screen.png` (view with the vision
  tool or scrcpy). Live view: `scrcpy -s emulator-5554` (a window may already be open).
- **Interact:** `adb shell input tap X Y`, `adb shell input swipe ...`, `adb shell input text "..."`.

## Global Constraints

- Package `com.jrod.droidgridder`; app label "Droid-Gridder"; `minSdk 24`,
  `compileSdk 36`, `targetSdk 36`.
- JDK toolchain 17; build with `./gradlew assembleDebug`.
- `android:resizeableActivity="true"`, no fixed orientation (split-screen support).
- All pure logic in `model/` must have **zero Android imports** (JVM-testable).
- Model is immutable; every mutation returns a new `MapFile`.
- Every logic task follows TDD: failing test → run (fails) → implement → run
  (passes) → commit.
- Version pins (bump only if the build fails): navigation-compose 2.8.5,
  lifecycle-viewmodel-compose 2.8.7, kotlinx-serialization-json 1.7.3,
  core-ktx 1.15.0, kotlinx-coroutines-android 1.9.0.

## File Structure

```
Droid-Gridder/
├── settings.gradle.kts, build.gradle.kts, gradle.properties, gradlew, gradle/wrapper/*
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/AndroidManifest.xml
        ├── main/java/com/jrod/droidgridder/
        │   ├── MainActivity.kt
        │   ├── model/MapModel.kt      (MapFile, Room, Exit, Direction, Pos, GRID_STEP)
        │   ├── model/MapGraph.kt      (pure mutations + placement)
        │   ├── model/AutoLayout.kt    (autoTidy BFS layout)
        │   ├── data/MapJson.kt        (encode/decode)
        │   ├── data/MapStore.kt       (file persistence, list, delete)
        │   ├── ui/navigation/AppNav.kt
        │   ├── ui/list/MapListScreen.kt, MapListViewModel.kt
        │   ├── ui/editor/MapEditorViewModel.kt
        │   ├── ui/editor/MapEditorScreen.kt
        │   ├── ui/editor/MapCanvas.kt (render + gestures + camera)
        │   ├── ui/editor/DirectionWheel.kt
        │   ├── ui/editor/RoomSheet.kt
        │   └── ui/theme/Theme.kt
        └── test/java/com/jrod/droidgridder/
            ├── model/MapGraphTest.kt
            ├── model/AutoLayoutTest.kt
            └── data/MapJsonTest.kt
```

---

### Task 1: Project scaffold

**Files:** create all root Gradle files + wrapper (copy `gradlew`, `gradle/wrapper/*`
from `~/Android/tmp/smoke`), `app/build.gradle.kts`, `AndroidManifest.xml`,
`MainActivity.kt`, `Theme.kt`.

**Produces:** app that builds and shows a placeholder screen.

- [ ] **Step 1: Write Gradle config**

`settings.gradle.kts`:
```kotlin
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "DroidGridder"
include(":app")
```

root `build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application") version "8.10.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20" apply false
}
```

`gradle.properties`:
```
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
android.useAndroidX=true
org.gradle.configuration-cache=false
```

- [ ] **Step 2: `app/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.jrod.droidgridder"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.jrod.droidgridder"
        minSdk = 24; targetSdk = 36; versionCode = 1; versionName = "1.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
```

- [ ] **Step 3: `AndroidManifest.xml`**

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <application android:label="Droid-Gridder"
      android:supportsRtl="true">
    <activity android:name=".MainActivity" android:exported="true"
        android:resizeableActivity="true">
      <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LAUNCHER"/>
      </intent-filter>
    </activity>
  </application>
</manifest>
```

Note: use a Material 3 theme defined in `Theme.kt` (or the `android:theme` omitted
above — apply `Theme.DroidGridder` via a `styles.xml` if preferred).

- [ ] **Step 4: `MainActivity.kt`** — single activity hosting `AppNav()`; for now a
  placeholder `Text("Droid-Gridder")` centered on screen.

- [ ] **Step 5: Build & verify**
  Run: `source ~/Android/env.sh && ./gradlew assembleDebug` → expect `BUILD SUCCESSFUL`.
  Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell am start -n com.jrod.droidgridder/.MainActivity`
  Verify via `adb shell uiautomator dump` that "Droid-Gridder" is on screen.

- [ ] **Step 6: Commit**
  `git add . && git commit -m "chore: scaffold Android project"`

---

### Task 2: Data model + pure graph logic (core)

**Files:** `model/MapModel.kt`, `model/MapGraph.kt`; test `model/MapGraphTest.kt`.

**Interfaces produced (used by all later tasks):**
- `enum class Direction { N, S, E, W, NE, NW, SE, SW, UP, DOWN }`
- `data class MapFile(id, name, createdAt, updatedAt, rooms, exits)`
- `data class Room(id, name, description, notes, x: Float, y: Float)`
- `data class Exit(id, from, direction, to)`
- `data class Pos(x: Float, y: Float)`
- `const val GRID_STEP = 180f`
- `fun Direction.opposite(): Direction`
- `fun directionOffset(d: Direction): Pos`
- `fun placeNewRoom(direction, from: Room, rooms: List<Room>): Pos`
- `fun go(direction, currentRoomId, map): MapFile`
- `fun linkToExisting(direction, fromRoomId, toRoomId, map): MapFile`
- `fun deleteRoom(roomId, map): MapFile`
- `fun deleteExit(exitId, map): MapFile`
- `fun redirectExit(exitId, newToRoomId, map): MapFile`
- `fun updateRoomText(roomId, name, description, notes, map): MapFile`

- [ ] **Step 1: Write failing tests** — `MapGraphTest.kt`

```kotlin
package com.jrod.droidgridder.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapGraphTest {
    private fun room(id: String, x: Float = 0f, y: Float = 0f) = Room(id = id, x = x, y = y)
    private fun map(vararg rs: Room) = MapFile(id = "m", name = "m", createdAt = 0L, updatedAt = 0L, rooms = rs.toList())

    @Test fun `opposite is symmetric for all directions`() {
        for (d in Direction.entries) assertEquals(d, d.opposite().opposite())
    }

    @Test fun `go north creates room and reverse exit`() {
        val out = go(Direction.N, "a", map(room("a")))
        assertEquals(2, out.rooms.size)
        val n = out.exits.single { it.from == "a" && it.direction == Direction.N }
        assertEquals(2, out.exits.size)
        val back = out.exits.single { it.from == n.to && it.direction == Direction.S }
        assertEquals("a", back.to)
    }

    @Test fun `go on existing exit does not create a room`() {
        val e = Exit(id = "e", from = "a", direction = Direction.E, to = "b")
        val m = map(room("a"), room("b")).copy(exits = listOf(e))
        assertEquals(m, go(Direction.E, "a", m))
    }

    @Test fun `placeNewRoom nudges when a spot is occupied`() {
        val occupied = listOf(room("a"), room("b", 0f, -GRID_STEP))
        val pos = placeNewRoom(Direction.N, room("a"), occupied)
        assertEquals(-2 * GRID_STEP, pos.y, 0.001f)
        assertEquals(0f, pos.x, 0.001f)
    }

    @Test fun `linkToExisting adds exit without reverse`() {
        val m = map(room("a"), room("b"))
        val out = linkToExisting(Direction.W, "a", "b", m)
        assertEquals(1, out.exits.size)
        assertEquals("b", out.exits.single().to)
    }

    @Test fun `deleteRoom cascades incoming and outgoing exits`() {
        val m = MapFile(id = "m", name = "m", createdAt = 0L, updatedAt = 0L,
            rooms = listOf(room("a"), room("b"), room("c")),
            exits = listOf(Exit("1", "a", Direction.N, "b"), Exit("2", "c", Direction.S, "b")))
        val out = deleteRoom("b", m)
        assertEquals(listOf("a", "c"), out.rooms.map { it.id }.sorted())
        assertTrue(out.exits.isEmpty())
    }

    @Test fun `redirectExit repoints and deleteExit removes`() {
        val e = Exit("1", "a", Direction.N, "b")
        val m = map(room("a"), room("b"), room("c")).copy(exits = listOf(e))
        assertEquals("c", redirectExit("1", "c", m).exits.single().to)
        assertTrue(deleteExit("1", m).exits.isEmpty())
    }

    @Test fun `updateRoomText sets all three fields`() {
        val m = map(room("a"))
        val out = updateRoomText("a", "n", "d", "t", m)
        assertEquals(Room("a", "n", "d", "t", 0f, 0f), out.rooms.single())
    }
}
```

- [ ] **Step 2: Run tests, expect failures**
  Run: `source ~/Android/env.sh && ./gradlew testDebugUnitTest --tests "com.jrod.droidgridder.model.MapGraphTest"`
  Expected: FAIL (unresolved references).

- [ ] **Step 3: Implement `MapModel.kt`**

```kotlin
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

enum class Direction { N, S, E, W, NE, NW, SE, SW, UP, DOWN }

data class Pos(val x: Float, val y: Float)

const val GRID_STEP = 180f
```

- [ ] **Step 4: Implement `MapGraph.kt`**

```kotlin
package com.jrod.droidgridder.model

import java.util.UUID

fun Direction.opposite(): Direction = when (this) {
    Direction.N -> Direction.S; Direction.S -> Direction.N
    Direction.E -> Direction.W; Direction.W -> Direction.E
    Direction.NE -> Direction.SW; Direction.NW -> Direction.SE
    Direction.SE -> Direction.NW; Direction.SW -> Direction.NE
    Direction.UP -> Direction.DOWN; Direction.DOWN -> Direction.UP
}

fun directionOffset(d: Direction): Pos = when (d) {
    Direction.N -> Pos(0f, -GRID_STEP); Direction.S -> Pos(0f, GRID_STEP)
    Direction.E -> Pos(GRID_STEP, 0f); Direction.W -> Pos(-GRID_STEP, 0f)
    Direction.NE -> Pos(GRID_STEP, -GRID_STEP); Direction.NW -> Pos(-GRID_STEP, -GRID_STEP)
    Direction.SE -> Pos(GRID_STEP, GRID_STEP); Direction.SW -> Pos(-GRID_STEP, GRID_STEP)
    Direction.UP -> Pos(0f, -GRID_STEP * 2f); Direction.DOWN -> Pos(0f, GRID_STEP * 2f)
}

private fun Pos.isNear(o: Pos): Boolean =
    (x - o.x) * (x - o.x) + (y - o.y) * (y - o.y) < GRID_STEP * GRID_STEP

fun freePosition(from: Pos, direction: Direction, occupied: List<Pos>): Pos {
    val base = directionOffset(direction)
    var k = 1
    while (true) {
        val c = Pos(from.x + base.x * k, from.y + base.y * k)
        if (occupied.none { it.isNear(c) }) return c
        k++
    }
}

fun placeNewRoom(direction: Direction, from: Room, rooms: List<Room>): Pos =
    freePosition(Pos(from.x, from.y), direction, rooms.map { Pos(it.x, it.y) })

fun go(direction: Direction, currentRoomId: String, map: MapFile): MapFile {
    val fromRoom = map.rooms.first { it.id == currentRoomId }
    if (map.exits.any { it.from == currentRoomId && it.direction == direction }) return map
    val id = UUID.randomUUID().toString()
    val pos = placeNewRoom(direction, fromRoom, map.rooms)
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
```

- [ ] **Step 5: Run tests, expect pass**
  Run: same command as Step 2 → PASS (8 tests).

- [ ] **Step 6: Commit**
  `git commit -am "feat: map model and pure graph logic"`

### Task 3: JSON serialization + file store

**Files:** `data/MapJson.kt`, `data/MapStore.kt`; test `data/MapJsonTest.kt`.

**Interfaces produced:**
- `fun encodeMap(map: MapFile): String`
- `fun decodeMap(text: String): MapFile`
- `class MapStore(rootDir: File)` with `newMap(name): MapFile`, `save(map)`,
  `load(id): MapFile?`, `delete(id)`, `list(): List<MapFile>`

- [ ] **Step 1: Write failing tests** — `MapJsonTest.kt`

```kotlin
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

    @Test fun `store list save load delete`() {
        val dir = File.createTempFile("maps", "").let { it.delete(); File(it.absolutePath) }
        val store = MapStore(dir)
        val map = store.newMap("Zork")
        store.save(map.copy(rooms = listOf(Room("a"))))
        assertEquals(1, store.list().size)
        assertEquals("Zork", store.load(map.id)?.name)
        store.delete(map.id)
        assertTrue(store.list().isEmpty())
        assertNull(store.load(map.id))
        dir.deleteRecursively()
    }
}
```

- [ ] **Step 2: Run tests, expect failures**
  Run: `./gradlew testDebugUnitTest --tests "com.jrod.droidgridder.data.MapJsonTest"` → FAIL.

- [ ] **Step 3: Implement `MapJson.kt`**

```kotlin
package com.jrod.droidgridder.data

import com.jrod.droidgridder.model.MapFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

fun encodeMap(map: MapFile): String = json.encodeToString(map)
fun decodeMap(text: String): MapFile = json.decodeFromString(text)
```

- [ ] **Step 4: Implement `MapStore.kt`**

```kotlin
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
    fun load(id: String): MapFile? = fileFor(id).takeIf { it.exists() }?.readText()?.let(::decodeMap)
    fun delete(id: String) { fileFor(id).delete() }
    fun list(): List<MapFile> =
        rootDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { decodeMap(it.readText()) }.getOrNull() }
            ?.sortedByDescending { it.updatedAt } ?: emptyList()
}
```

- [ ] **Step 5: Run tests, expect pass** → PASS (3 tests).
- [ ] **Step 6: Commit** — `git commit -am "feat: json serialization and map store"`

---

### Task 4: Editor canvas — camera, render rooms/exits, pan/zoom

**Files:** `ui/editor/MapEditorViewModel.kt`, `ui/editor/MapEditorScreen.kt`,
`ui/editor/MapCanvas.kt`.

**Interfaces produced:**
```kotlin
data class MapEditorUiState(
    val map: MapFile? = null,
    val currentRoomId: String? = null,
    val selectedRoomId: String? = null,
    val wheelForRoomId: String? = null,
)
```
`MapEditorViewModel(mapId: String, store: MapStore)` exposes
`val uiState: StateFlow<MapEditorUiState>` and actions (added incrementally across
Tasks 4-6): `openWheel(roomId)`, `closeWheel()`, `select(roomId)`, `go(direction)`,
`link(direction, toRoomId)`, `autoTidy()`, `updateRoomText(...)`, `deleteRoom(id)`,
`deleteExit(id)`, `redirectExit(id, toId)`, `undo()`.

`MapCanvas(state, camera, onTapRoom, onDoubleTapRoom, onTapEmpty)` — renders the
graph and handles gestures. `CameraState(scale, offset)` supports `screenToWorld`
and `worldToScreen`.

- [ ] **Step 1: VM skeleton** — holds `MutableStateFlow(MapEditorUiState())`; `init`
  loads `store.load(mapId)`; `select` sets `selectedRoomId` and `currentRoomId`;
  `openWheel`/`closeWheel` toggle `wheelForRoomId`; every mutating action (Tasks 5-6)
  applies the pure `MapGraph` function, sets `currentRoomId` accordingly, then
  `store.save(newMap)`.

- [ ] **Step 2: `MapCanvas` rendering** — a `Canvas` that, given the `MapFile`, draws:
  - exits first (lines from source room center to target room center, with a small
    direction label `N`/`NE`/`UP`… at the midpoint);
  - rooms as `drawRoundRect` boxes sized ~`GRID_STEP * 0.7`, with the room `name`
    centered below/inside; current room outlined; selected room with a highlight ring.
  Use `worldToScreen` to map room `(x, y)` to canvas coordinates.

- [ ] **Step 3: `CameraState` + gestures** — pan/zoom via `detectTransformGestures`
  in a `pointerInput` block, updating `scale` (clamped ~0.25..4.0) and `offset`.
  Implement `screenToWorld(screenOffset) = (screen - offset) / scale`.

- [ ] **Step 4: Verify on emulator** — temporarily seed a map with a few rooms and
  exits (or add a hidden debug button), install, screenshot, confirm rooms + labels
  render (vision or `uiautomator dump`).

- [ ] **Step 5: Commit** — `git commit -am "feat: editor canvas with camera and graph rendering"`

---

### Task 5: Gestures + direction wheel (go / link)

**Files:** `ui/editor/DirectionWheel.kt`; wire gestures in `MapCanvas`.

- [ ] **Step 1: Gesture routing** — a `pointerInput` block using `detectTapGestures(
  onTap = { onTapEmpty or onTapRoom via hit-test }, onDoubleTap = { onDoubleTapRoom })`
  in a **separate** `pointerInput` from the transform gestures. Hit-test: nearest room
  whose center is within `GRID_STEP / 2` of `screenToWorld(tap)`.

- [ ] **Step 2: `DirectionWheel`** — an overlay centered on the wheel room showing
  eight compass buttons arranged in a ring plus UP/DOWN buttons; each calls
  `onDirection(direction)`. Long-press on a direction enters "link mode": the wheel
  dismisses and the next room tap calls `link(direction, toRoomId)`.

- [ ] **Step 3: Wiring** — single-tap empty → `select(null)`; single-tap room →
  `select(roomId)`; double-tap room → `openWheel(roomId)`; wheel tap → `go(direction)`;
  wheel long-press → link mode → tap target → `link(direction, targetId)`.

- [ ] **Step 4: Verify agentically** — launch, double-tap a room to open the wheel,
  tap `N`, confirm a new room appears connected north (screenshot + `uiautomator dump`).

- [ ] **Step 5: Commit** — `git commit -am "feat: direction wheel and go/link interactions"`

---

### Task 6: Room bottom sheet (edit/delete/redirect) + single-step undo

**Files:** `ui/editor/RoomSheet.kt`; extend `MapEditorViewModel` with undo + exit ops.

- [ ] **Step 1: VM additions** — `updateRoomText`, `deleteRoom`, `deleteExit`,
  `redirectExit` (all delegate to `MapGraph` + `store.save`). Add `previousMap:
  MapFile?`; every mutating action stores `previousMap = currentMap` before applying;
  `undo()` restores `previousMap` (and clears it → single-step).

- [ ] **Step 2: `RoomSheet`** — `ModalBottomSheet` (shown when `selectedRoomId !=
  null` and `wheelForRoomId == null`) with:
  - name/description/notes `TextField`s wired to `updateRoomText`;
  - an exits list (each row `N → <destination name or (unmapped)>`) with a delete
    action (`deleteExit`) and a redirect action (enters redirect mode → tap target →
    `redirectExit`);
  - a "Delete room" button with a confirmation dialog (`deleteRoom`).

- [ ] **Step 3: Undo button** — in the editor top bar, enabled when `previousMap !=
  null`; calls `undo()`.

- [ ] **Step 4: Verify** — full edit/delete/redirect/undo flow via `adb input` +
  `uiautomator dump`; confirm cascade delete and undo behavior.

- [ ] **Step 5: Commit** — `git commit -am "feat: room editing sheet and single-step undo"`

---

### Task 7: Auto-tidy + animations

**Files:** `model/AutoLayout.kt` (pure `autoTidy(map): MapFile`), test
`model/AutoLayoutTest.kt`; animate in `MapCanvas`.

- [ ] **Step 1: Failing tests** — `AutoLayoutTest.kt`

```kotlin
package com.jrod.droidgridder.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoLayoutTest {
    @Test fun `linear chain lays out in a straight line`() {
        val m = MapFile("m", "m", 0L, 0L,
            rooms = listOf(Room("a"), Room("b"), Room("c")),
            exits = listOf(Exit("1", "a", Direction.E, "b"), Exit("2", "b", Direction.E, "c")))
        val out = autoTidy(m)
        val byId = out.rooms.associateBy { it.id }
        assertEquals(0f, byId["a"]!!.x, 0.001f)
        assertEquals(GRID_STEP, byId["b"]!!.x, 0.001f)
        assertEquals(2 * GRID_STEP, byId["c"]!!.x, 0.001f)
    }

    @Test fun `cycle does not duplicate positions`() {
        val m = MapFile("m", "m", 0L, 0L,
            rooms = listOf(Room("a"), Room("b"), Room("c")),
            exits = listOf(Exit("1", "a", Direction.E, "b"),
                           Exit("2", "b", Direction.E, "c"),
                           Exit("3", "c", Direction.W, "a")))
        val out = autoTidy(m)
        val positions = out.rooms.map { Pos(it.x, it.y) }
        assertEquals(positions.size, positions.distinct().size)
    }
}
```

- [ ] **Step 2: Implement `AutoLayout.kt`**

```kotlin
package com.jrod.droidgridder.model

fun autoTidy(map: MapFile): MapFile {
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
                val p = freePosition(pos[id]!!, e.direction, occupied)
                pos[e.to] = p; occupied += p; queue.add(e.to)
            }
        }
    }
    return map.copy(rooms = map.rooms.map { r -> pos[r.id]?.let { r.copy(x = it.x, y = it.y) } ?: r })
}
```

- [ ] **Step 3: Run tests → PASS** (2 tests).
- [ ] **Step 4: Animations in `MapCanvas`** — animate room `(x, y)` with
  `animateFloatAsState` (or an `Animatable` per room) so auto-tidy glides rooms;
  `AnimatedVisibility` for new-room pop-in; animate connector stroke on first draw.
- [ ] **Step 5: Verify visually** — tap Auto-tidy, watch the graph reflow smoothly.
- [ ] **Step 6: Commit** — `git commit -am "feat: auto-tidy layout and canvas animations"`

---

### Task 8: Map list screen + navigation + export/import

**Files:** `ui/navigation/AppNav.kt`, `ui/list/MapListScreen.kt`,
`ui/list/MapListViewModel.kt`; SAF intents for export/import.

- [ ] **Step 1: `AppNav`** — `NavHost` with routes `"list"` and `"editor/{mapId}"`;
  `MainActivity` hosts it. `MapStore` is created once (with `context.filesDir/maps`)
  and passed down (a simple manual dependency container in `MainActivity` is fine).

- [ ] **Step 2: `MapListViewModel`** — exposes `StateFlow<List<MapFile>>` refreshed
  from `store.list()`; actions `create(name)`, `rename(id, name)`, `delete(id)`,
  `import(json: String)`.

- [ ] **Step 3: `MapListScreen`** — list of maps (name + updated time) with:
  - "New map" → name dialog → `create` → navigate to editor;
  - tap a row → open editor; long-press (or overflow) → rename / delete (confirm) /
    export;
  - **Export:** write the map JSON to a temp file and fire `ACTION_SEND` (via
    `FileProvider`); **Import:** `ACTION_OPEN_DOCUMENT` → read text → `decodeMap` →
    `store.save`.

- [ ] **Step 4: Verify** — create/rename/delete/export/import round-trip on the
  emulator (import by pushing a JSON file via `adb` if the file picker is awkward to
  drive; prefer verifying the JSON round-trip at the unit level and the UI flow by
  screenshot).

- [ ] **Step 5: Commit** — `git commit -am "feat: map list, navigation, export/import"`

---

### Task 9: Polish + full agentic verification

- [ ] **Step 1: End-to-end scenario on the emulator** — new map → build a 6-room graph
  (cardinals + one diagonal + UP) → edit text → undo → delete a room (confirm cascade)
  → redirect an exit → auto-tidy → export → import into a fresh map → reopen after app
  restart (persistence). Confirm each step via `uiautomator dump` + screenshots.
- [ ] **Step 2: Full unit suite** — `./gradlew testDebugUnitTest` → all green.
- [ ] **Step 3: Fix issues; commit** — `git commit -am "chore: polish and verify"`

---

## Risks / notes

- `navigation-compose` / `kotlinx-serialization-json` version pins may need bumping at
  first build — adjust and note in the commit message.
- Diagonal spacing uses `GRID_STEP` on both axes (slightly farther than cardinals);
  acceptable for v1.
- `detectTapGestures` double-tap adds ~300 ms delay to single-tap selection — standard.
- Single-step undo is deliberately minimal (`previousMap` only); full history is a
  later enhancement.
- The emulator is a `google_apis` image and boots to the launcher unlocked; if the
  screen locks, run `adb shell input keyevent 82` / `adb shell wm dismiss-keyguard`.

## Self-review

- **Spec coverage:** every confirmed spec decision maps to a task — model (T2),
  placement/no-drag (T2/T7), 10 directions (T2/T5), gestures (T4/T5), go/link + reverse
  (T2/T5), current-room highlight (T4), room text sheet (T6), cascade-delete (T2/T6),
  single-step undo (T6), persistence + export/import (T3/T8), split-screen resizeable
  (T1 manifest). ✅
- **Placeholder scan:** no TBD/TODO; core logic fully coded; UI tasks have exact paths,
  signatures, and verification steps. ✅
- **Type consistency:** `go`/`linkToExisting`/`deleteRoom`/`deleteExit`/`redirectExit`/
  `updateRoomText`/`autoTidy`/`freePosition`/`directionOffset` names match across tasks;
  `MapFile`/`Room`/`Exit`/`Direction`/`Pos` fields match the spec. ✅
