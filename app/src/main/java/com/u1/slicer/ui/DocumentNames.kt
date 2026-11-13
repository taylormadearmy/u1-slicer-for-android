package com.u1.slicer.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

internal fun Context.documentDisplayName(uri: Uri): String? {
    val fromProvider = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return fromProvider?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
}
