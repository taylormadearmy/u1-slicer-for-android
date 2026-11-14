package com.u1.slicer.ui.printer

import java.net.URI

/** Keep the default web port explicit: the shared Moonraker normalizer otherwise inserts 7125. */
internal fun cosmosPrinterUrl(raw: String): String {
    val value = raw.trim().let { if (it.isBlank() || it.contains("://")) it else "http://$it" }
    return runCatching {
        val uri = URI(value)
        if (uri.host != null && uri.port == -1 && uri.scheme in setOf("http", "https")) {
            URI(uri.scheme, uri.userInfo, uri.host, if (uri.scheme == "https") 443 else 80,
                uri.path, uri.query, uri.fragment).toString().trimEnd('/')
        } else value.trimEnd('/')
    }.getOrDefault(value)
}
