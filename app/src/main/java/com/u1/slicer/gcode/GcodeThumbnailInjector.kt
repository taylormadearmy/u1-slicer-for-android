package com.u1.slicer.gcode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * Extracts preview images from 3MF files and injects them as base64 thumbnail
 * blocks into G-code files. Moonraker/Klipper reads these blocks and serves
 * them via /server/files/thumbnails.
 *
 * Format: `; thumbnail begin WxH LEN\n; <base64 lines, 76 chars>\n; thumbnail end\n;`
 * Sizes: 48x48 and 300x300 (matches Klipper's expected sizes).
 *
 * Ported from u1-slicer-bridge's gcode_thumbnails.py.
 */
object GcodeThumbnailInjector {

    private const val TAG = "ThumbnailInjector"

    // Keywords to score preview images in 3MF metadata/ folder
    private val SCORE_KEYWORDS = listOf("thumbnail", "preview", "cover", "top", "plate", "pick")
    private val PLATE_HINT_REGEX = Regex("""(?i)plate[_-]?(\d+)""")
    private val THUMBNAIL_SIZES = listOf(48 to 48, 300 to 300)

    /**
     * Extract a preview image from a 3MF file and inject thumbnail blocks into G-code.
     * Returns true if thumbnails were injected.
     */
    fun inject(gcodePath: String, sourcePath: String): Boolean {
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists() || !sourcePath.endsWith(".3mf", ignoreCase = true)) {
            return false
        }

        val bitmap = extractPreviewImage(sourceFile, inferPlateHint(sourceFile.name)) ?: return false
        val blocks = buildThumbnailBlocks(bitmap)
        if (blocks.isEmpty()) return false

        return injectIntoGcode(gcodePath, blocks)
    }

    /**
     * Inject thumbnail blocks from a pre-existing Bitmap (e.g. OpenGL screenshot for STL).
     */
    fun injectFromBitmap(gcodePath: String, bitmap: Bitmap): Boolean {
        val blocks = buildThumbnailBlocks(bitmap)
        if (blocks.isEmpty()) return false
        return injectIntoGcode(gcodePath, blocks)
    }

    /**
     * Extract the best preview image from a 3MF ZIP file's metadata/ folder.
     */
    internal fun extractPreviewImage(threeMfFile: File, plateHint: Int? = null): Bitmap? {
        return try {
            ZipFile(threeMfFile).use { zip ->
                val candidates = zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .filter { it.name.endsWith(".png", true) || it.name.endsWith(".jpg", true) || it.name.endsWith(".jpeg", true) }
                    .toList()

                if (candidates.isEmpty()) return null

                val bestEntry = pickBestPreviewEntry(candidates, plateHint)
                Log.d(TAG, "Using preview image: ${bestEntry.name}")

                val bytes = zip.getInputStream(bestEntry).use { it.readBytes() }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract preview from 3MF: ${e.message}")
            null
        }
    }

    internal fun pickBestPreviewEntry(candidates: List<java.util.zip.ZipEntry>, plateHint: Int? = null): java.util.zip.ZipEntry {
        return candidates
            .map { entry -> entry to scorePreviewEntry(entry.name, plateHint) }
            .sortedWith(compareByDescending<Pair<java.util.zip.ZipEntry, Long>> { it.second }
                .thenBy { candidates.indexOf(it.first) })
            .first()
            .first
    }

    internal fun scorePreviewEntry(entryName: String, plateHint: Int? = null): Long {
        val name = entryName.lowercase()
        var score = SCORE_KEYWORDS.sumOf { kw -> if (name.contains(kw)) 1L else 0L }

        if (plateHint != null) {
            val exactHints = listOf(
                "plate_no_light_${plateHint}.png" to 120L,
                "plate_no_light_${plateHint}.jpg" to 120L,
                "plate_no_light_${plateHint}.jpeg" to 120L,
                "plate_${plateHint}.png" to 110L,
                "plate_${plateHint}.jpg" to 110L,
                "plate_${plateHint}.jpeg" to 110L,
                "top_${plateHint}.png" to 100L,
                "top_${plateHint}.jpg" to 100L,
                "top_${plateHint}.jpeg" to 100L,
                "pick_${plateHint}.png" to 90L,
                "pick_${plateHint}.jpg" to 90L,
                "pick_${plateHint}.jpeg" to 90L
            )
            for ((suffix, bonus) in exactHints) {
                if (name.endsWith(suffix)) {
                    score += bonus
                    break
                }
            }
        }

        return score
    }

    internal fun inferPlateHint(sourceName: String): Int? {
        return PLATE_HINT_REGEX.find(sourceName)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    /**
     * Build thumbnail comment blocks for all target sizes.
     */
    internal fun buildThumbnailBlocks(source: Bitmap): String {
        val sb = StringBuilder()
        for ((w, h) in THUMBNAIL_SIZES) {
            val scaled = Bitmap.createScaledBitmap(source, w, h, true)
            // Convert to RGB (strip alpha) — matches u1-slicer-bridge's PIL .convert("RGB").
            // Some Klipper/Moonraker firmware doesn't handle RGBA PNGs correctly.
            val rgb = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            val canvas = android.graphics.Canvas(rgb)
            canvas.drawColor(android.graphics.Color.WHITE)
            canvas.drawBitmap(scaled, 0f, 0f, null)
            if (scaled !== source) scaled.recycle()
            val pngBytes = ByteArrayOutputStream().use { out ->
                rgb.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
            rgb.recycle()

            val b64 = Base64.encodeToString(pngBytes, Base64.NO_WRAP)
            sb.append("; thumbnail begin ${w}x${h} ${b64.length}\n")
            // Split into 76-char lines — matches the vanilla Klipper/Moonraker format used by
            // u1-slicer-bridge and supported by all Moonraker versions (incl. older firmware).
            var i = 0
            while (i < b64.length) {
                val end = minOf(i + 76, b64.length)
                sb.append("; ${b64.substring(i, end)}\n")
                i = end
            }
            sb.append("; thumbnail end\n")
            sb.append(";\n")
        }
        return sb.toString()
    }

    /**
     * Inject thumbnail blocks into the G-code file.
     * Inserts after HEADER_BLOCK_END if found, otherwise prepends.
     *
     * Streams line-by-line via a temp file to avoid loading the entire G-code
     * into memory (large files can be 100MB+, causing OOM if read as a String).
     */
    private fun injectIntoGcode(gcodePath: String, blocks: String): Boolean {
        val tmpFile = File("$gcodePath.tmp")
        val prependFile = File("$gcodePath.pre")
        return try {
            val file = File(gcodePath)
            var injected = false

            // First pass: stream the file to tmpFile, injecting after HEADER_BLOCK_END
            tmpFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                file.bufferedReader(Charsets.UTF_8).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        writer.write(line!!)
                        writer.newLine()
                        if (!injected && line!!.contains("; HEADER_BLOCK_END")) {
                            writer.write(blocks)
                            injected = true
                        }
                    }
                }
            }

            if (!injected) {
                // No header found — prepend blocks by writing them first, then streaming tmpFile
                prependFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(blocks)
                    tmpFile.bufferedReader(Charsets.UTF_8).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            writer.write(line!!)
                            writer.newLine()
                        }
                    }
                }
                tmpFile.delete()
                if (!prependFile.renameTo(file)) {
                    file.delete()
                    prependFile.renameTo(file)
                }
            } else {
                if (!tmpFile.renameTo(file)) {
                    file.delete()
                    tmpFile.renameTo(file)
                }
            }

            Log.i(TAG, "Injected thumbnails into G-code (${blocks.length} bytes of thumbnail data)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inject thumbnails: ${e.message}")
            tmpFile.delete()
            prependFile.delete()
            false
        }
    }
}
