package com.u1.slicer.model

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * F77 (GitHub #109): combine multiple STL files into one binary STL with each
 * part translated to a centred grid position on the U1 build plate. The native
 * loader still sees a single model object — slicing treats the parts as one
 * scene, which is the issue's main goal ("treat the merged result as one
 * Prepare/Preview/slice cycle").
 *
 * Trade-off: users can't tweak individual parts after combine. Each part lives
 * at a fixed grid cell; resizing or rotating one part isn't supported. If the
 * caller needs per-object control they should load files one at a time.
 *
 * Per-part error handling per the issue body:
 *  - A part that fails to parse → skipped, recorded in [Result.failed]
 *  - A part with footprint > 270 mm → skipped, recorded in [Result.oversize]
 *  - Triangle-budget overflow → still combined; the slicer / preview decimator
 *    handles ceiling logic downstream
 */
object MultiStlCombiner {

    private const val BED_MM: Float = 270f
    private const val GRID_MARGIN_MM: Float = 5f

    data class PartGeometry(
        val sourceFile: File,
        val sizeX: Float,
        val sizeY: Float,
        val sizeZ: Float,
        val minX: Float,
        val minY: Float,
        val minZ: Float,
        val triangleCount: Int,
    )

    data class Result(
        val combinedFile: File,
        val placedParts: List<PartGeometry>,
        val failed: List<File>,
        val oversize: List<File>,
        val totalTriangles: Int,
    )

    /**
     * Reads each STL into the combined output. The grid is sized to the
     * largest part footprint so every cell holds any part. Returns null when
     * no part could be placed (all parsed but all oversize, or all failed).
     */
    fun combine(inputs: List<File>, output: File): Result? {
        require(inputs.isNotEmpty()) { "No input STL files provided" }

        val geometry = mutableListOf<PartGeometry>()
        val failed = mutableListOf<File>()
        val oversize = mutableListOf<File>()

        for (file in inputs) {
            val geo = readGeometry(file)
            if (geo == null) {
                failed += file
                continue
            }
            if (geo.sizeX > BED_MM || geo.sizeY > BED_MM) {
                oversize += file
                continue
            }
            geometry += geo
        }

        if (geometry.isEmpty()) return null

        // Use the largest part footprint as the cell size so every part fits.
        val cellW = geometry.maxOf { it.sizeX }
        val cellH = geometry.maxOf { it.sizeY }
        val positions = CopyArrangeCalculator.calculate(
            objectSizeX = cellW,
            objectSizeY = cellH,
            copyCount = geometry.size,
            bedSizeX = BED_MM,
            bedSizeY = BED_MM,
            margin = GRID_MARGIN_MM,
        )
        // Cap to as many parts as the bed can hold without overlap. Beyond that
        // calculate() truncates — the leftover parts are dropped from the
        // combined file and recorded as oversize so the caller can warn.
        val placeable = positions.size / 2
        val dropped = geometry.drop(placeable)
        oversize += dropped.map { it.sourceFile }
        val placed = geometry.take(placeable)

        val totalTriangles = placed.sumOf { it.triangleCount }
        writeCombinedBinaryStl(output, placed, positions, cellW, cellH, totalTriangles)
        return Result(
            combinedFile = output,
            placedParts = placed,
            failed = failed,
            oversize = oversize,
            totalTriangles = totalTriangles,
        )
    }

    /** Reads vertex bounds + triangle count without retaining the full triangle data. */
    internal fun readGeometry(file: File): PartGeometry? {
        if (!file.exists() || file.length() < 84) return null
        return try {
            val bytes = file.readBytes()
            val triCount = ByteBuffer.wrap(bytes, 80, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int
            val expected = 84L + triCount.toLong() * 50L
            if (expected.toLong() != bytes.size.toLong()) {
                // ASCII or malformed binary — not supported yet by the combiner.
                return null
            }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(84)
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            for (t in 0 until triCount) {
                // Skip normal (12 bytes)
                buf.position(buf.position() + 12)
                for (v in 0 until 3) {
                    val x = buf.float; val y = buf.float; val z = buf.float
                    if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
                    if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
                }
                buf.position(buf.position() + 2) // attribute count
            }
            PartGeometry(
                sourceFile = file,
                sizeX = maxX - minX,
                sizeY = maxY - minY,
                sizeZ = maxZ - minZ,
                minX = minX, minY = minY, minZ = minZ,
                triangleCount = triCount,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Streams each part's triangles into [output], translating XY so the part's
     * AABB centre lands at the grid cell centre, and clamping Z so the part
     * rests on z=0. Writes a single 80-byte header + uint32 triangle count +
     * per-triangle [12 float, uint16 attr] records — standard binary STL.
     */
    private fun writeCombinedBinaryStl(
        output: File,
        parts: List<PartGeometry>,
        positions: FloatArray,
        cellW: Float,
        cellH: Float,
        totalTriangles: Int,
    ) {
        RandomAccessFile(output, "rw").use { raf ->
            raf.setLength(0L)
            // 80-byte header. STL spec ignores its content but tools sniff it
            // so use a printable identifier.
            val header = ByteArray(80)
            "U1Slicer multi-STL combine".toByteArray(Charsets.US_ASCII)
                .copyInto(header, 0, 0, minOf(80, "U1Slicer multi-STL combine".length))
            raf.write(header)
            // Triangle count (uint32 LE)
            val countBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            countBuf.putInt(totalTriangles)
            raf.write(countBuf.array())

            val triBuf = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN)
            for ((i, geo) in parts.withIndex()) {
                val cellMinX = positions[i * 2]
                val cellMinY = positions[i * 2 + 1]
                val cellCenterX = cellMinX + cellW / 2f
                val cellCenterY = cellMinY + cellH / 2f
                val partCenterX = geo.minX + geo.sizeX / 2f
                val partCenterY = geo.minY + geo.sizeY / 2f
                val txX = cellCenterX - partCenterX
                val txY = cellCenterY - partCenterY
                // Snap part to bed plane: drop the load-time minZ.
                val txZ = -geo.minZ

                val bytes = geo.sourceFile.readBytes()
                val src = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                src.position(84)
                for (t in 0 until geo.triangleCount) {
                    triBuf.clear()
                    // Normal (unchanged — pure translation)
                    val nx = src.float; val ny = src.float; val nz = src.float
                    triBuf.putFloat(nx); triBuf.putFloat(ny); triBuf.putFloat(nz)
                    for (v in 0 until 3) {
                        val x = src.float + txX
                        val y = src.float + txY
                        val z = src.float + txZ
                        triBuf.putFloat(x); triBuf.putFloat(y); triBuf.putFloat(z)
                    }
                    // Preserve the source's attribute count (typically 0).
                    val attr = src.short
                    triBuf.putShort(attr)
                    raf.write(triBuf.array())
                }
            }
        }
    }
}
