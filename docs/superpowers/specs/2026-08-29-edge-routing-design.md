# v1.6.1 Edge Routing Design

**Date:** 2026-08-29 · **Status:** approved by user ("let's try to fix all of that")
**Evidence:** emulator screenshots of *Zork I (Complete)* — Tidy:
`/tmp/zork_now.png`, Relax: `/tmp/zork_relax.png`. Both modes: straight
anchor-to-anchor connectors pierce unrelated room boxes and label strips;
long-distance edges (spiral-placed Foggy River rooms, the Maze cluster,
Relax-stretched edges) draw as long diagonals across the map.

## Diagnosis

Room *placement* is acceptable in both modes. The defect is *connector
drawing*: one straight segment per exit, blind to intermediate footprints.
This is the Trizbort separation — placement and edge routing are different
problems; routing must be a **drawing layer over any layout** (Tidy and
Relax both benefit), not a third layout mode.

Already in place (no work needed): fixed direction offsets
(`directionOffset`), grid-snapped Tidy placement, port anchoring
(v1.6 pin rule: cardinals at edge midpoints, diagonals at corners,
UP/DOWN at top/bottom midpoint, IN/OUT at center).

## 1. Asymmetric edges — arrows on every one-way passage

The model already stores directed records (`Exit.oneWay`), and the canvas
already draws an arrowhead for `oneWay == true`. The gap: an exit whose
**mirror record is missing** (A–E→B, but B's W points elsewhere — or the
mirror was deleted) draws as a plain undirected line. The data is the
source of truth:

- `fun hasMirror(exit: Exit, exits: Collection<Exit>): Boolean` — true iff
  a record `(exit.to, exit.from, exit.direction.opposite())` exists.
- Canvas draws the arrowhead iff `!hasMirror` (supersedes the
  `oneWay`-only test; the flag still drives model semantics, the arrow
  reflects topology).

## 2. Obstruction routing

`fun routeExit(exit: Exit, map: MapFile): ExitRoute?` — pure, deterministic,
layout-agnostic (works on whatever positions the map has). Null for
unknown room ids or self-exits (canvas already skips those).

```kotlin
sealed class ExitRoute {
    data class Straight(val from: Pos, val to: Pos) : ExitRoute()
    data class Bends(val points: List<Pos>) : ExitRoute() // axis-aligned,
        // first = source anchor, last = destination anchor
    data class Stub(val from: Pos, val tip: Pos, val direction: Direction,
                    val targetName: String) : ExitRoute()
}
```

Algorithm:

1. **Containment:** IN/OUT → `Straight(center(a), center(b))`. No routing.
2. **Anchors:** `a = anchorPos(fromRoom, dir)`,
   `b = anchorPos(toRoom, dir.opposite())` — the existing pin rule.
   **Obstacles:** every other room's box + label strip
   (`unnamed rooms: box only`), each inflated by `ROUTE_MARGIN = 2f`.
   The endpoint rooms' own footprints never obstruct their own connector.
3. **Straight first:** segment `a→b` clear of all obstacles →
   `Straight(a, b)`. (Common case; preserves today's look for clean edges.)
4. **Manhattan candidates**, fixed deterministic order, first fully clear
   wins → `Bends(points)`. All segments axis-aligned; zero-length segments
   collapsed:
   - `L1: a → (b.x, a.y) → b` — horizontal first
   - `L2: a → (a.x, b.y) → b` — vertical first
   - gutter channels, `g = GRID_STEP / 2f = 90f`, signs fixed `[-1, +1]`:
     - columns `m ∈ {a.x + s·g, b.x + s·g}`: `a → (m, a.y) → (m, b.y) → b`
     - rows `m ∈ {a.y + s·g, b.y + s·g}`: `a → (a.x, m) → (b.x, m) → b`
   For cardinal bearings, candidates whose first segment runs along the
   bearing come first (a line leaving an E port should leave east), via
   stable sort — deterministic. Gutter channels run in the half-stride
   channels between grid columns/rows, so on Tidy layouts routes track the
   grid gutters (the Trizbort dogleg); on Relax layouts the same channels
   still sit between the roughly-180-spaced rooms.
5. **Stub fallback:** no candidate clear →
   `Stub(a, a + unit(bearing)·STUB_LEN, dir, toRoom.name)`,
   `STUB_LEN = 40f`. The stub points outward in the declared bearing and
   names the destination — non-Euclidean passages (Zork's maze loops)
   render as labeled stubs instead of hairballs. Stubs are a *fallback*:
   gutter routing keeps connected clusters legible.

**Ceilings (ponytail):** candidate-list routing, not A* (upgrade path if
visible routing failures remain); no routed-line-vs-routed-line crossing
avoidance; no manual dogleg editing.

## 3. Rendering — `ui/editor/MapCanvas.kt`

- Replace the per-exit `drawLine` with a `drawPath` along the routed
  polyline (each world point → screen; camera transform is affine so
  intermediate points are exact). Same color/width rules as today
  (selected-room edges green 3dp, rest outline 2dp).
- Arrowhead at the destination anchor along the **final** segment's
  direction; drawn iff `!hasMirror` (spec §1).
- Stubs: short line + `"→ ${targetName}"` horizontal text near the tip
  (offset along the bearing's dominant axis), line colors.
- Long-press hit-testing (`exitAt`): point-to-polyline distance (min over
  the route's segments), same 16px tolerance.
- Routes computed per frame from the **animated** room positions so
  connectors follow room glides. `ponytail:` O(E·n) float ops per frame —
  cache per settled position only if on-device jank appears.
- IN/OUT: straight center-to-center, unchanged.

## 4. Out of scope

Proxy/duplicated nodes (dashed-border clones), A* pathfinding,
line-crossing minimization, manual bend editing, model semantics changes,
version bump/release (user-gated).

## 5. Verification

- Unit: the nine model-layer tests above (TDD, red→green per behavior).
- On-device: Zork I (Complete) in Tidy and Relax — no connector pierces a
  box or label; Maze cluster routes through gutters (stubs only where
  routing fails); one-way passages show arrows; the *testing* map stays
  clean. Full unit suite green.