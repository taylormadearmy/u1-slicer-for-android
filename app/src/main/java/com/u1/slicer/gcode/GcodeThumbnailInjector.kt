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
 * Format: `; thumbnail begin WxH LEN\n; <base64 lines>\n; thumbnail end`
 * Sizes: 48x48 and 300x300 (matches Klipper's expected sizes).
 *
 * Ported from u1-slicer-bridge's gcode_thumbnails.py.
 */
object GcodeThumbnailInjector {

    private const val TAG = "ThumbnailInjector"

    // Keywords to score preview images in 3MF metadata/ folder
    private val SCORE_KEYWORDS = listOf("thumbnail", "preview", "cover", "top", "plate", "pick")
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

        val bitmap = extractPreviewImage(sourceFile) ?: return false
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
    internal fun extractPreviewImage(threeMfFile: File): Bitmap? {
        return try {
            ZipFile(threeMfFile).use { zip ->
                val candidates = zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .filter { it.name.endsWith(".png", true) || it.name.endsWith(".jpg", true) || it.name.endsWith(".jpeg", true) }
                    .toList()

                if (candidates.isEmpty()) return null

                // Score each candidate by keyword matches in the filename
                val scored = candidates.map { entry ->
                    val name = entry.name.lowercase()
                    val score = SCORE_KEYWORDS.sumOf { kw -> if (name.contains(kw)) 1L else 0L }
                    entry to score
                }.sortedByDescending { it.second }

                val bestEntry = scored.first().first
                Log.d(TAG, "Using preview image: ${bestEntry.name} (score=${scored.first().second})")

                val bytes = zip.getInputStream(bestEntry).use { it.readBytes() }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract preview from 3MF: ${e.message}")
            null
        }
    }

    /**
     * Build thumbnail comment blocks for all target sizes.
     */
    internal fun buildThumbnailBlocks(source: Bitmap): String {
        val sb = StringBuilder()
        for ((w, h) in THUMBNAIL_SIZES) {
            val scaled = Bitmap.createScaledBitmap(source, w, h, true)
            val pngBytes = ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
            if (scaled !== source) scaled.recycle()

            val b64 = Base64.encodeToString(pngBytes, Base64.NO_WRAP)
            sb.append("; thumbnail begin ${w}x${h} ${b64.length}\n")
            // Split into 76-char lines
            var i = 0
            while (i < b64.length) {
                val end = minOf(i + 76, b64.length)
                sb.append("; ${b64.substring(i, end)}\n")
                i = end
            }
            sb.append("; thumbnail end\n")
        }
        return sb.toString()
    }

    /**
     * Inject thumbnail blocks into the G-code file.
     * Inserts after HEADER_BLOCK_END if found, otherwise prepends.
     */
    private fun injectIntoGcode(gcodePath: String, blocks: String): Boolean {
        return try {
            val file = File(gcodePath)
            val content = file.readText()

            val headerEnd = content.indexOf("; HEADER_BLOCK_END")
            val newContent = if (headerEnd >= 0) {
                val insertPos = content.indexOf('\n', headerEnd)
                if (insertPos >= 0) {
                    content.substring(0, insertPos + 1) + blocks + content.substring(insertPos + 1)
                } else {
                    content + "\n" + blocks
                }
            } else {
                blocks + content
            }

            file.writeText(newContent)
            Log.i(TAG, "Injected thumbnails into G-code (${blocks.length} bytes)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inject thumbnails: ${e.message}")
            false
        }
    }
}
