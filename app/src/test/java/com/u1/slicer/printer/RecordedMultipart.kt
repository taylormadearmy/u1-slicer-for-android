package com.u1.slicer.printer

import okhttp3.mockwebserver.RecordedRequest

internal data class RecordedMultipartPart(val filename: String?, val value: String)

/** Test fixtures contain ASCII file bytes, so the parsed string length is also the byte length. */
internal fun RecordedRequest.asciiMultipartParts(): Map<String, RecordedMultipartPart> {
    val contentType = requireNotNull(getHeader("Content-Type"))
    val boundary = contentType.substringAfter("boundary=", "").trim().trim('"')
    require(boundary.isNotEmpty()) { "Missing multipart boundary: $contentType" }
    val parts = body.readUtf8().split("--$boundary").drop(1).dropLast(1).map { raw ->
        require(raw.startsWith("\r\n") && raw.endsWith("\r\n")) { "Malformed multipart framing" }
        val part = raw.removePrefix("\r\n").removeSuffix("\r\n")
        val separator = part.indexOf("\r\n\r\n")
        require(separator >= 0) { "Missing multipart header separator" }
        val headers = part.substring(0, separator)
        val value = part.substring(separator + 4)
        val name = requireNotNull(Regex("\\bname=\"([^\"]+)\"").find(headers)?.groupValues?.get(1))
        val filename = Regex("\\bfilename=\"([^\"]+)\"").find(headers)?.groupValues?.get(1)
        val length = Regex("(?im)^Content-Length: ([0-9]+)$").find(headers)?.groupValues?.get(1)?.toInt()
        require(length == null || length == value.toByteArray(Charsets.UTF_8).size) { "Incorrect multipart Content-Length for $name" }
        name to RecordedMultipartPart(filename, value)
    }
    require(parts.map { it.first }.distinct().size == parts.size) { "Duplicate multipart fields" }
    return parts.toMap()
}
