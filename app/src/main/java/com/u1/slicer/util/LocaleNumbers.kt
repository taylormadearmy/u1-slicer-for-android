package com.u1.slicer.util

/**
 * B139: parse a user-entered decimal that may use either '.' or ',' as the decimal separator.
 *
 * Android numeric keyboards emit the locale decimal separator (',' across much of Europe), but
 * Kotlin's [String.toFloatOrNull] is locale-independent and only accepts '.', so European input
 * like "0,16" would otherwise return null and be silently dropped.
 *
 * Use this for any HUMAN-typed decimal field. Do NOT use it for machine-format values (G-code,
 * 3MF/XML, JSON, profile config) — those always use '.' and must stay locale-independent.
 */
fun String.toFloatLenient(): Float? = trim().replace(',', '.').toFloatOrNull()
