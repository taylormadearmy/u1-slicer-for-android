package com.u1.slicer.bambu

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * Machine templates pinned to the bundled OrcaSlicer 2.2.4 profile.
 *
 * Newer Bambu Studio templates use variables that this embedded engine does
 * not expose. Update the engine and these templates together; using whichever
 * upstream template is newest is not safe for generated G-code.
 */
internal object BambuA1MiniMachineGcode {
    private const val END_DIVIDER = "\n__U1_SLICER_A1_END__\n"
    private const val CHANGE_DIVIDER = "\n__U1_SLICER_A1_CHANGE__\n"

    private data class Templates(
        val start: String,
        val end: String,
        val changeFilament: String,
    )

    private val templates: Templates by lazy {
        val compressed = Base64.getDecoder().decode(ENCODED_TEMPLATES)
        val text = GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
            val output = ByteArrayOutputStream()
            input.copyTo(output)
            output.toString(Charsets.UTF_8.name())
        }
        require(text.contains(END_DIVIDER) && text.contains(CHANGE_DIVIDER)) {
            "A1 Mini template payload is incomplete"
        }
        require(text.indexOf(END_DIVIDER) == text.lastIndexOf(END_DIVIDER)) {
            "A1 Mini template payload must contain one end divider"
        }
        require(text.indexOf(CHANGE_DIVIDER) == text.lastIndexOf(CHANGE_DIVIDER)) {
            "A1 Mini template payload must contain one change divider"
        }
        val start = text.substringBefore(END_DIVIDER)
        val remainder = text.substringAfter(END_DIVIDER)
        val end = remainder.substringBefore(CHANGE_DIVIDER)
        val changeFilament = remainder.substringAfter(CHANGE_DIVIDER)
        require(start.isNotBlank() && end.isNotBlank() && changeFilament.isNotBlank()) {
            "A1 Mini templates must not be blank"
        }
        Templates(
            start = start,
            end = end,
            changeFilament = changeFilament,
        )
    }

    val start: String get() = templates.start
    val end: String get() = templates.end
    val changeFilament: String get() = templates.changeFilament

    private const val ENCODED_TEMPLATES = "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"
}
