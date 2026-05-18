package com.u1.slicer.model

import org.junit.Assert.*
import org.junit.Test

class CopyArrangeCalculatorTest {

    @Test
    fun `single copy returns centered position on bed`() {
        val positions = CopyArrangeCalculator.calculate(20f, 20f, 1)
        assertEquals(2, positions.size)
        // (270 - 20) / 2 = 125
        assertEquals(125f, positions[0], 0.01f)
        assertEquals(125f, positions[1], 0.01f)
    }

    @Test
    fun `two copies are centered in the same row`() {
        val positions = CopyArrangeCalculator.calculate(20f, 20f, 2)
        assertEquals(4, positions.size)
        // gridWidth = 2*20 + 1*5 = 45, offsetX = (270-45)/2 = 112.5
        assertEquals(112.5f, positions[0], 0.01f)
        assertEquals(125f, positions[1], 0.01f)
        assertEquals(137.5f, positions[2], 0.01f)
        assertEquals(125f, positions[3], 0.01f)
    }

    @Test
    fun `grid wraps to next row centered`() {
        // 270mm bed, 20mm object, 5mm margin -> cols = floor(275/25) = 11
        // 12 copies: 11 in row 0, 1 in row 1 (2 rows)
        // gridWidth = 11*20 + 10*5 = 270, offsetX = 0
        // gridHeight = 2*20 + 1*5 = 45, offsetY = (270-45)/2 = 112.5
        val positions = CopyArrangeCalculator.calculate(20f, 20f, 12)
        val x = positions[11 * 2]
        val y = positions[11 * 2 + 1]
        assertEquals(0f, x, 0.01f)
        assertEquals(137.5f, y, 0.01f)
    }

    @Test
    fun `copies capped at bed capacity`() {
        val positions = CopyArrangeCalculator.calculate(250f, 250f, 10)
        assertEquals(2, positions.size)
    }

    @Test
    fun `maxCopies returns correct fit for 20mm object`() {
        val max = CopyArrangeCalculator.maxCopies(20f, 20f)
        assertEquals(121, max)
    }

    @Test
    fun `maxCopies for large object is 1`() {
        assertEquals(1, CopyArrangeCalculator.maxCopies(260f, 260f))
    }

    @Test
    fun `all positions are within bed bounds`() {
        val positions = CopyArrangeCalculator.calculate(30f, 30f, 50)
        for (i in positions.indices step 2) {
            val x = positions[i]
            val y = positions[i + 1]
            assertTrue("x=$x out of bounds", x >= 0f && x + 30f <= 270f)
            assertTrue("y=$y out of bounds", y >= 0f && y + 30f <= 270f)
        }
    }

    @Test
    fun `copy count 1 always succeeds even for oversized object`() {
        val positions = CopyArrangeCalculator.calculate(300f, 300f, 1)
        assertEquals(2, positions.size)
    }

    @Test
    fun `single copy of oversized object positions at origin not negative`() {
        val positions = CopyArrangeCalculator.calculate(300f, 300f, 1)
        assertEquals(0f, positions[0], 0.01f)
        assertEquals(0f, positions[1], 0.01f)
    }

    // --- computeWipeTowerPosition tests ---

    @Test
    fun `tower avoids centered model - picks corner with most clearance`() {
        // Model centered at (125, 125), size 20x20 → occupies (125,125)-(145,145)
        val objPos = floatArrayOf(125f, 125f)
        val (tx, ty) = CopyArrangeCalculator.computeWipeTowerPosition(objPos, 20f, 20f, 60f)
        // Tower should NOT overlap the model
        val tMaxX = tx + 60f; val tMaxY = ty + 60f
        val oMinX = 125f; val oMinY = 125f; val oMaxX = 145f; val oMaxY = 145f
        val overlapsX = tx < oMaxX && tMaxX > oMinX
        val overlapsY = ty < oMaxY && tMaxY > oMinY
        assertFalse("Tower at ($tx,$ty) overlaps model", overlapsX && overlapsY)
    }

    @Test
    fun `tower avoids large centered model - picks edge midpoint`() {
        // 130x120 model at (70, 80) → occupies (70,80)-(200,200)
        // 60mm tower with 10mm edge margin: bottom-center at (105, 10) → (105,10)-(165,70)
        // Fully below model at y=80.
        val objPos = floatArrayOf(70f, 80f)
        val (tx, ty) = CopyArrangeCalculator.computeWipeTowerPosition(objPos, 130f, 120f, 60f)
        val tMaxX = tx + 60f; val tMaxY = ty + 60f
        val overlapsX = tx < 200f && tMaxX > 70f
        val overlapsY = ty < 200f && tMaxY > 80f
        assertFalse("Tower at ($tx,$ty) overlaps 130x120mm model", overlapsX && overlapsY)
    }

    @Test
    fun `tower position is within bed bounds`() {
        val objPos = floatArrayOf(125f, 125f)
        val (tx, ty) = CopyArrangeCalculator.computeWipeTowerPosition(objPos, 20f, 20f, 60f)
        assertTrue("tx=$tx out of bounds", tx >= 0f && tx + 60f <= 270f)
        assertTrue("ty=$ty out of bounds", ty >= 0f && ty + 60f <= 270f)
    }

    @Test
    fun `tower avoids multiple grid copies`() {
        // 4 copies of 50x50 in grid: (5,5), (60,5), (5,60), (60,60)
        val objPos = CopyArrangeCalculator.calculate(50f, 50f, 4)
        val (tx, ty) = CopyArrangeCalculator.computeWipeTowerPosition(objPos, 50f, 50f, 60f)
        // Verify no overlap with any copy
        for (i in 0 until objPos.size / 2) {
            val ox = objPos[i * 2]; val oy = objPos[i * 2 + 1]
            val overlapsX = tx < ox + 50f && tx + 60f > ox
            val overlapsY = ty < oy + 50f && ty + 60f > oy
            assertFalse("Tower at ($tx,$ty) overlaps copy $i at ($ox,$oy)", overlapsX && overlapsY)
        }
    }

    @Test
    fun `grid is centered on bed for small objects`() {
        // 4 copies of 50mm objects with 5mm margin
        // cols = floor(275/55) = 5, 4 copies fit in 1 row
        // gridWidth = 4*50 + 3*5 = 215, offsetX = (270-215)/2 = 27.5
        // gridHeight = 1*50 = 50, offsetY = (270-50)/2 = 110
        val positions = CopyArrangeCalculator.calculate(50f, 50f, 4)
        assertEquals(8, positions.size)
        assertEquals(27.5f, positions[0], 0.01f) // first copy X
        assertEquals(110f, positions[1], 0.01f) // first copy Y
        assertEquals(82.5f, positions[2], 0.01f) // second copy X
        assertEquals(110f, positions[3], 0.01f) // second copy Y
    }

    @Test
    fun `grid copies all within bed bounds`() {
        val positions = CopyArrangeCalculator.calculate(50f, 50f, 9)
        for (i in positions.indices step 2) {
            assertTrue("x=${positions[i]} < 0", positions[i] >= 0f)
            assertTrue("y=${positions[i+1]} < 0", positions[i+1] >= 0f)
            assertTrue("x+size=${positions[i]+50f} > 270", positions[i] + 50f <= 270f)
            assertTrue("y+size=${positions[i+1]+50f} > 270", positions[i+1] + 50f <= 270f)
        }
    }

    @Test
    fun `three copies in a row are centered`() {
        // 3 copies of 80mm with 5mm margin → 1 row
        // gridWidth = 3*80 + 2*5 = 250, offsetX = (270-250)/2 = 10
        val positions = CopyArrangeCalculator.calculate(80f, 80f, 3)
        assertEquals(6, positions.size)
        assertEquals(10f, positions[0], 0.01f)
        assertEquals(95f, positions[1], 0.01f) // (270-80)/2 = 95
    }

    @Test
    fun `tower picks bottom-left when model is in top-right`() {
        // Model at (200, 200), size 50x50
        val objPos = floatArrayOf(200f, 200f)
        val (tx, ty) = CopyArrangeCalculator.computeWipeTowerPosition(objPos, 50f, 50f, 60f)
        // Should pick bottom-left corner with 10mm edge margin for skirt+brim clearance
        assertEquals(10f, tx, 0.01f)
        assertEquals(10f, ty, 0.01f)
    }

    @Test
    fun `tower position leaves skirt clearance from bed edges for all model positions`() {
        // Regression: tower at (0, 210) caused skirt to extend beyond bed boundary.
        // Clearance needed: prime_tower_brim (3mm) + skirt_distance (6mm) + 1 loop (~0.5mm) ≈ 9.5mm.
        val skirtClearance = 9.5f
        val towerWidth = 60f
        val bedSize = 270f

        // Test with models at various positions across the bed
        val testCases = listOf(
            floatArrayOf(105f, 105f) to (20f to 20f),   // centered small model
            floatArrayOf(200f, 200f) to (50f to 50f),   // top-right
            floatArrayOf(10f, 10f) to (50f to 50f),     // bottom-left
            floatArrayOf(105f, 10f) to (60f to 60f),    // bottom-center large
            floatArrayOf(85f, 91f) to (100f to 88f),    // Shashibo plate 5 approx
        )

        for ((objPos, size) in testCases) {
            val (tx, ty) = CopyArrangeCalculator.computeWipeTowerPosition(
                objectPositions = objPos,
                objectSizeX = size.first,
                objectSizeY = size.second,
                towerWidth = towerWidth,
                bedSizeX = bedSize,
                bedSizeY = bedSize
            )
            assertTrue(
                "Tower at ($tx,$ty): left edge too close to bed (need ${skirtClearance}mm for skirt)",
                tx >= skirtClearance
            )
            assertTrue(
                "Tower at ($tx,$ty): bottom edge too close to bed (need ${skirtClearance}mm for skirt)",
                ty >= skirtClearance
            )
            assertTrue(
                "Tower at ($tx,$ty): right edge too close to bed (need ${skirtClearance}mm for skirt)",
                tx + towerWidth <= bedSize - skirtClearance
            )
            assertTrue(
                "Tower at ($tx,$ty): top edge too close to bed (need ${skirtClearance}mm for skirt)",
                ty + towerWidth <= bedSize - skirtClearance
            )
        }
    }

    @Test fun `auto-placement uses towerDepth for Y footprint not towerWidth`() {
        // A model that exactly fits the left half of the bed (135mm wide, 270mm tall)
        // The tower should be placed on the right side; with the correct 20mm depth
        // it should fit without Y-overlap issues.
        val positions = floatArrayOf(0f, 0f)  // model at origin
        val result = CopyArrangeCalculator.computeWipeTowerPosition(
            objectPositions = positions,
            objectSizeX = 135f,
            objectSizeY = 270f,
            towerWidth = 60f,
            towerDepth = 20f
        )
        // Tower placed to the right: x should be >= 135 + some margin
        assertTrue("Tower X should be right of model: ${result.first}", result.first >= 140f)
    }

    // --- F75: Prime tower defaults to back of plate (GitHub #90) ---

    @Test fun `f75 tower defaults to back half for centered small model`() {
        // Centered 20x20 model: all 4 corners tie on clearance (110), all 4
        // edge midpoints tie (55). Before F75, bottom-left (front) won by
        // virtue of being the first candidate. After F75, back-of-plate
        // candidates are listed first so a back position wins the tie.
        val objPos = floatArrayOf(125f, 125f)
        val (_, ty) = CopyArrangeCalculator.computeWipeTowerPosition(
            objectPositions = objPos,
            objectSizeX = 20f,
            objectSizeY = 20f,
            towerWidth = 60f,
            towerDepth = 60f
        )
        // Back of plate = Y in upper half; tower minY should be >= bed center (135).
        assertTrue("F75: tower must default to back of plate, got ty=$ty", ty >= 135f)
    }

    @Test fun `f75 tower defaults to back of plate when no objects`() {
        // Empty objectPositions edge case: tower should land at back-center.
        val (tx, ty) = CopyArrangeCalculator.computeWipeTowerPosition(
            objectPositions = floatArrayOf(),
            objectSizeX = 0f,
            objectSizeY = 0f,
            towerWidth = 60f,
            towerDepth = 60f
        )
        // Expect back-center: bedCenterX - towerWidth/2 = 135-30 = 105;
        // back Y = 270 - 60 - 10 (edgeMargin) = 200.
        assertEquals(105f, tx, 0.01f)
        assertEquals(200f, ty, 0.01f)
    }

    @Test fun `f75 model at back forces tower to front - file-position-style intent preserved`() {
        // When the model occupies the back of the bed, F75 must NOT force a
        // back tower — front candidates have more clearance, so they still
        // win on merit. This proves the default applies only as a tie-breaker.
        val objPos = floatArrayOf(110f, 200f)  // model at back-center
        val (_, ty) = CopyArrangeCalculator.computeWipeTowerPosition(
            objectPositions = objPos,
            objectSizeX = 50f,
            objectSizeY = 50f,
            towerWidth = 60f,
            towerDepth = 60f
        )
        // Tower should land in the front half (Y < 135) because back is occupied.
        assertTrue("Tower should prefer front when back is occupied, got ty=$ty", ty < 135f)
    }

    @Test fun `computeWipeTowerPosition accepts separate towerDepth`() {
        val positions = floatArrayOf(105f, 105f)  // centered model
        val resultSquare = CopyArrangeCalculator.computeWipeTowerPosition(
            objectPositions = positions,
            objectSizeX = 60f,
            objectSizeY = 60f,
            towerWidth = 60f,
            towerDepth = 60f  // square: same as old behavior
        )
        val resultRect = CopyArrangeCalculator.computeWipeTowerPosition(
            objectPositions = positions,
            objectSizeX = 60f,
            objectSizeY = 60f,
            towerWidth = 60f,
            towerDepth = 20f  // rectangular: narrower depth
        )
        // Both should produce valid positions within bed bounds
        assertTrue(resultSquare.first >= 0f && resultSquare.first <= 210f)
        assertTrue(resultSquare.second >= 0f && resultSquare.second <= 210f)
        assertTrue(resultRect.first >= 0f && resultRect.first <= 210f)
        assertTrue(resultRect.second >= 0f && resultRect.second <= 240f)  // 270-20-10 (edgeMargin)
    }

    @Test
    fun `single copy position is min-corner not center - 10x10 model`() {
        // Regression: buildExpectedModelFootprint was treating positions as centers,
        // which shifted the expected footprint by half the object size in each axis.
        // Positions are min-corner: object occupies [x, x+sizeX) x [y, y+sizeY).
        val positions = CopyArrangeCalculator.calculate(10f, 10f, 1)
        // Min-corner: (270-10)/2 = 130
        assertEquals(130f, positions[0], 0.01f)
        assertEquals(130f, positions[1], 0.01f)
        // Object occupies [130, 140] x [130, 140] — NOT centered at 130.
        val expectedMinX = 130f
        val expectedMaxX = 140f
        val expectedMinY = 130f
        val expectedMaxY = 140f
        // Verify treating as min-corner gives correct bounds:
        val minX = positions[0]
        val maxX = positions[0] + 10f
        val minY = positions[1]
        val maxY = positions[1] + 10f
        assertEquals(expectedMinX, minX, 0.01f)
        assertEquals(expectedMaxX, maxX, 0.01f)
        assertEquals(expectedMinY, minY, 0.01f)
        assertEquals(expectedMaxY, maxY, 0.01f)
    }

    // --- B109: computeRotatedFootprint tests ---

    @Test fun `b109 zero rotation returns original size`() {
        val (w, h) = CopyArrangeCalculator.computeRotatedFootprint(200f, 100f, 50f, 0f, 0f, 0f)
        assertEquals(200f, w, 0.01f)
        assertEquals(100f, h, 0.01f)
    }

    @Test fun `b109 90deg z rotation swaps xy footprint`() {
        // A 200x100mm model rotated 90° on bed becomes ~100x200mm footprint
        val (w, h) = CopyArrangeCalculator.computeRotatedFootprint(200f, 100f, 50f, 0f, 0f, 90f)
        assertEquals(100f, w, 0.5f)
        assertEquals(200f, h, 0.5f)
    }

    @Test fun `b109 45deg z rotation produces larger footprint`() {
        // |cos(45°)| * 200 + |sin(45°)| * 100 = 0.707 * 300 ≈ 212.1
        val (w, h) = CopyArrangeCalculator.computeRotatedFootprint(200f, 100f, 50f, 0f, 0f, 45f)
        val expected = ((200f + 100f) * Math.sqrt(2.0) / 2.0).toFloat()
        assertEquals(expected, w, 1.0f)
        assertEquals(expected, h, 1.0f)
    }

    @Test fun `b109 symmetry plus and minus rotation same footprint`() {
        val (w1, h1) = CopyArrangeCalculator.computeRotatedFootprint(200f, 100f, 50f, 0f, 0f, 45f)
        val (w2, h2) = CopyArrangeCalculator.computeRotatedFootprint(200f, 100f, 50f, 0f, 0f, -45f)
        assertEquals(w1, w2, 0.01f)
        assertEquals(h1, h2, 0.01f)
    }

    @Test fun `b109 180deg rotation returns original size`() {
        val (w, h) = CopyArrangeCalculator.computeRotatedFootprint(200f, 100f, 50f, 0f, 0f, 180f)
        assertEquals(200f, w, 0.5f)
        assertEquals(100f, h, 0.5f)
    }

    // --- B65: Copy bed warning tests ---

    @Test
    fun `copyBedWarning returns null when copies fit on bed`() {
        // 20mm object, 9 copies → 3x3 grid fits easily
        assertNull(CopyArrangeCalculator.copyBedWarning(20f, 20f, 9))
    }

    @Test
    fun `copyBedWarning returns warning when copies exceed bed capacity`() {
        // 260mm object, max 1 copy — requesting 2 should warn
        val warning = CopyArrangeCalculator.copyBedWarning(260f, 260f, 2)
        assertNotNull("Should warn for 2 copies of 260mm object", warning)
    }

    @Test
    fun `copyBedWarning returns null for single copy of any size`() {
        assertNull(CopyArrangeCalculator.copyBedWarning(300f, 300f, 1))
    }

    @Test
    fun `copyBedWarning returns warning at boundary`() {
        // 80mm object: maxCopies = int(275/85) * int(275/85) = 3*3 = 9
        // 10 copies should warn
        val warning = CopyArrangeCalculator.copyBedWarning(80f, 80f, 10)
        assertNotNull("Should warn for 10 copies of 80mm object", warning)
        // 9 copies should not warn
        assertNull(CopyArrangeCalculator.copyBedWarning(80f, 80f, 9))
    }

    // --- B109 v2.2.6 review: `effectivePlacementFootprint` unit tests ---
    // Replaces the brittle structural-grep tests that asserted MainActivity
    // closure shape. The helper is now the single source of truth for the
    // rotated+scaled footprint used by drag-clamp, auto-center, and
    // bed-warning callers.

    @Test
    fun `effectivePlacementFootprint prefers mesh AABB when supplied`() {
        // 100x50 load-time box; mesh AABB reports actual rotated 80x60 (e.g. a real
        // organic mesh that doesn't fill the box-rotation envelope). Helper must
        // pick the mesh AABB scaled — the box-rotation envelope is irrelevant.
        val (w, h) = CopyArrangeCalculator.effectivePlacementFootprint(
            rotatedMeshSizeXY = 80f to 60f,
            loadTimeSizeX = 100f, loadTimeSizeY = 50f, loadTimeSizeZ = 30f,
            scaleX = 1f, scaleY = 1f,
            rotationXDeg = 0f, rotationYDeg = 0f, rotationZDeg = 45f,
        )
        assertEquals(80f, w, 0.01f)
        assertEquals(60f, h, 0.01f)
    }

    @Test
    fun `effectivePlacementFootprint scales mesh AABB`() {
        // Scale must multiply the mesh AABB, since the renderer applies scale after
        // centering the mesh. 80x60 * (2,1.5) → 160x90.
        val (w, h) = CopyArrangeCalculator.effectivePlacementFootprint(
            rotatedMeshSizeXY = 80f to 60f,
            loadTimeSizeX = 100f, loadTimeSizeY = 50f, loadTimeSizeZ = 30f,
            scaleX = 2f, scaleY = 1.5f,
            rotationXDeg = 0f, rotationYDeg = 0f, rotationZDeg = 45f,
        )
        assertEquals(160f, w, 0.01f)
        assertEquals(90f, h, 0.01f)
    }

    @Test
    fun `effectivePlacementFootprint falls back to box rotation when mesh null`() {
        // No mesh yet (cold load before native preview returns) — must use the
        // box-rotation approximation. 100x50 box rotated 90°Z → 50x100 (axis swap).
        val (w, h) = CopyArrangeCalculator.effectivePlacementFootprint(
            rotatedMeshSizeXY = null,
            loadTimeSizeX = 100f, loadTimeSizeY = 50f, loadTimeSizeZ = 30f,
            scaleX = 1f, scaleY = 1f,
            rotationXDeg = 0f, rotationYDeg = 0f, rotationZDeg = 90f,
        )
        assertEquals(50f, w, 0.01f)
        assertEquals(100f, h, 0.01f)
    }

    @Test
    fun `effectivePlacementFootprint fallback applies scale after rotation`() {
        // 100x50 box rotated 90°Z → 50x100, then scaled (2, 1) → 100x100.
        val (w, h) = CopyArrangeCalculator.effectivePlacementFootprint(
            rotatedMeshSizeXY = null,
            loadTimeSizeX = 100f, loadTimeSizeY = 50f, loadTimeSizeZ = 30f,
            scaleX = 2f, scaleY = 1f,
            rotationXDeg = 0f, rotationYDeg = 0f, rotationZDeg = 90f,
        )
        assertEquals(100f, w, 0.01f)
        assertEquals(100f, h, 0.01f)
    }

    @Test
    fun `effectivePlacementFootprint with mesh null and zero rotation returns load time size`() {
        // Initial-load shape: no mesh yet, rotation=0,0,0 — must return load-time
        // size (× scale). Asserts the no-rotation shortcut path.
        val (w, h) = CopyArrangeCalculator.effectivePlacementFootprint(
            rotatedMeshSizeXY = null,
            loadTimeSizeX = 100f, loadTimeSizeY = 50f, loadTimeSizeZ = 30f,
            scaleX = 1f, scaleY = 1f,
            rotationXDeg = 0f, rotationYDeg = 0f, rotationZDeg = 0f,
        )
        assertEquals(100f, w, 0.01f)
        assertEquals(50f, h, 0.01f)
    }

    @Test
    fun `effectivePlacementFootprint dragonScale45deg matches mesh AABB not box envelope`() {
        // DC15's Dragon Scale repro — 200x80x20mm visible mesh rotated 45°Z.
        // Box-rotation envelope: ~(200+80)/√2 = ~198 on each axis (over-estimate).
        // True rotated mesh AABB (organic, doesn't fill the corners): say 160x140.
        // Helper must report 160x140 so the drag clamp matches what the renderer draws.
        val boxRotated = CopyArrangeCalculator.computeRotatedFootprint(
            200f, 80f, 20f, 0f, 0f, 45f
        )
        val meshAabb = 160f to 140f
        val (w, h) = CopyArrangeCalculator.effectivePlacementFootprint(
            rotatedMeshSizeXY = meshAabb,
            loadTimeSizeX = 200f, loadTimeSizeY = 80f, loadTimeSizeZ = 20f,
            scaleX = 1f, scaleY = 1f,
            rotationXDeg = 0f, rotationYDeg = 0f, rotationZDeg = 45f,
        )
        assertEquals(meshAabb.first, w, 0.01f)
        assertEquals(meshAabb.second, h, 0.01f)
        // Sanity-check that the box envelope would have been bigger — proves
        // the over-estimation that this helper avoids.
        assertTrue(
            "box-rotation envelope (${boxRotated.first}) must exceed true mesh " +
                "AABB (${meshAabb.first}) — that's the whole point of preferring mesh AABB",
            boxRotated.first > meshAabb.first
        )
    }

    // --- buildMultiObjectPositions: additive multi-file bed packing ---

    @Test fun `buildMultiObjectPositions empty input returns empty`() {
        val positions = CopyArrangeCalculator.buildMultiObjectPositions(floatArrayOf())
        assertEquals(0, positions.size)
    }

    @Test fun `buildMultiObjectPositions single object placed at margin`() {
        // One 50x40mm object: x=5 (margin), y=5 (margin)
        val boxes = floatArrayOf(50f, 40f, 10f)
        val positions = CopyArrangeCalculator.buildMultiObjectPositions(boxes)
        assertEquals(2, positions.size)
        assertEquals(5f, positions[0], 0.01f)
        assertEquals(5f, positions[1], 0.01f)
    }

    @Test fun `buildMultiObjectPositions two objects in same row`() {
        // Two 60x50mm objects: first at (5,5), second at (5+60+5=70, 5)
        val boxes = floatArrayOf(60f, 50f, 10f, 60f, 50f, 10f)
        val positions = CopyArrangeCalculator.buildMultiObjectPositions(boxes)
        assertEquals(4, positions.size)
        assertEquals(5f, positions[0], 0.01f)   // x0
        assertEquals(5f, positions[1], 0.01f)   // y0
        assertEquals(70f, positions[2], 0.01f)  // x1 = 5 + 60 + 5
        assertEquals(5f, positions[3], 0.01f)   // y1 same row
    }

    @Test fun `buildMultiObjectPositions wraps to next row when exceeding bed`() {
        // 5mm margin, 270mm bed. Two 200mm objects can't fit in one row (5+200+5+200 = 410 > 265).
        // First at (5,5), second wraps to next row: x=5, y=5+50+5=60 (rowMaxY = sizeY of first).
        val boxes = floatArrayOf(200f, 50f, 10f, 200f, 50f, 10f)
        val positions = CopyArrangeCalculator.buildMultiObjectPositions(boxes)
        assertEquals(4, positions.size)
        assertEquals(5f, positions[0], 0.01f)   // x0
        assertEquals(5f, positions[1], 0.01f)   // y0
        assertEquals(5f, positions[2], 0.01f)   // x1 wrapped to start
        assertEquals(60f, positions[3], 0.01f)  // y1 = 5 + 50 + 5
    }

    @Test fun `buildMultiObjectPositions row height tracks tallest object`() {
        // Row has a short (20mm deep) and a tall (80mm deep) object; the next row
        // should start at margin + tallest row height + margin = 5 + 80 + 5 = 90.
        // Objects are narrow enough to fit in one row but we add a third to trigger wrap.
        val boxes = floatArrayOf(
            100f, 20f, 10f,   // short object
            100f, 80f, 10f,   // tall object  (rowMaxY becomes 80)
            250f, 30f, 10f,   // wide: forces wrap; this is > 265f at curX=210
        )
        val positions = CopyArrangeCalculator.buildMultiObjectPositions(boxes)
        assertEquals(6, positions.size)
        assertEquals(90f, positions[5], 0.01f)  // y2 = 5 + 80 + 5
    }

    // --- placeAdditionalObject: incremental add without disturbing existing objects ---

    @Test fun `placeAdditionalObject single existing object places new one to the right`() {
        // Existing: 60x50mm object at (5, 5). New: 40x30mm.
        // maxRight = 5 + 60 = 65; tryX = 65 + 5 = 70; fits (70 + 40 = 110 <= 270).
        val currentPos = floatArrayOf(5f, 5f)
        val boxes = floatArrayOf(60f, 50f, 10f, 40f, 30f, 10f)
        val result = CopyArrangeCalculator.placeAdditionalObject(currentPos, boxes)
        assertEquals(4, result.size)
        assertEquals(5f, result[0], 0.01f)   // existing object unchanged
        assertEquals(5f, result[1], 0.01f)
        assertEquals(70f, result[2], 0.01f)  // 5 + 60 + 5
        assertEquals(5f, result[3], 0.01f)   // aligned to rightmost Y
    }

    @Test fun `placeAdditionalObject falls below when no room to the right`() {
        // Existing: 220x50mm object at (5, 5). New: 80x40mm.
        // maxRight = 5 + 220 = 225; tryX = 225 + 5 = 230; 230 + 80 = 310 > 270 → fall below.
        // maxBottom = 5 + 50 = 55; result y = 55 + 5 = 60.
        val currentPos = floatArrayOf(5f, 5f)
        val boxes = floatArrayOf(220f, 50f, 10f, 80f, 40f, 10f)
        val result = CopyArrangeCalculator.placeAdditionalObject(currentPos, boxes)
        assertEquals(4, result.size)
        assertEquals(5f, result[0], 0.01f)   // existing unchanged
        assertEquals(5f, result[1], 0.01f)
        assertEquals(5f, result[2], 0.01f)   // leftX = existing x
        assertEquals(60f, result[3], 0.01f)  // 5 + 50 + 5
    }

    @Test fun `placeAdditionalObject preserves all existing positions`() {
        // Two existing objects; adding a third preserves first two.
        val currentPos = floatArrayOf(5f, 5f, 70f, 5f)
        val boxes = floatArrayOf(60f, 50f, 10f, 60f, 50f, 10f, 40f, 30f, 10f)
        val result = CopyArrangeCalculator.placeAdditionalObject(currentPos, boxes)
        assertEquals(6, result.size)
        assertEquals(5f, result[0], 0.01f)    // object 0 x
        assertEquals(5f, result[1], 0.01f)    // object 0 y
        assertEquals(70f, result[2], 0.01f)   // object 1 x
        assertEquals(5f, result[3], 0.01f)    // object 1 y
    }

    @Test fun `placeAdditionalObject new object stays within bed bounds`() {
        // Single existing object placed to the right such that all results are within 0..270.
        val currentPos = floatArrayOf(5f, 5f)
        val boxes = floatArrayOf(60f, 50f, 10f, 40f, 30f, 10f)
        val result = CopyArrangeCalculator.placeAdditionalObject(currentPos, boxes)
        val newX = result[2]; val newY = result[3]
        val newSizeX = boxes[3]; val newSizeY = boxes[4]
        assertTrue("new object right edge must be within bed: ${newX + newSizeX}", newX + newSizeX <= 270f)
        assertTrue("new object bottom edge must be within bed: ${newY + newSizeY}", newY + newSizeY <= 270f)
    }

    @Test fun `placeAdditionalObject with one object uses buildMultiObjectPositions`() {
        // When objectCount == 1 (no existing objects yet), falls back to placing first object.
        val currentPos = floatArrayOf()
        val boxes = floatArrayOf(60f, 50f, 10f)
        val result = CopyArrangeCalculator.placeAdditionalObject(currentPos, boxes)
        // With one object total, center on bed or at margin — just verify not off-bed.
        assertEquals(2, result.size)
        assertTrue("x within bed: ${result[0]}", result[0] in 0f..270f)
        assertTrue("y within bed: ${result[1]}", result[1] in 0f..270f)
    }

    // --- computeWipeTowerPositionForObjects: per-object sizes ---

    @Test fun `computeWipeTowerPositionForObjects avoids all objects`() {
        // Two objects: one at (5,5) 100x80, one at (115,5) 100x80.
        // Best tower candidate should not overlap either.
        val positions = floatArrayOf(5f, 5f, 115f, 5f)
        val boxes = floatArrayOf(100f, 80f, 10f, 100f, 80f, 10f)
        val (tx, ty) = CopyArrangeCalculator.computeWipeTowerPositionForObjects(positions, boxes)
        // Tower default size 60x60. Verify it doesn't overlap either object.
        val tMaxX = tx + 60f; val tMaxY = ty + 60f
        val obj0OverlapX = tx < 105f && tMaxX > 5f
        val obj0OverlapY = ty < 85f && tMaxY > 5f
        assertFalse("Tower must not overlap object 0", obj0OverlapX && obj0OverlapY)
        val obj1OverlapX = tx < 215f && tMaxX > 115f
        val obj1OverlapY = ty < 85f && tMaxY > 5f
        assertFalse("Tower must not overlap object 1", obj1OverlapX && obj1OverlapY)
    }
}
