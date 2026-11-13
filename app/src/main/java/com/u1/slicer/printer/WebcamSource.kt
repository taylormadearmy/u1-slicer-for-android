package com.u1.slicer.printer

/**
 * One snapshot-capable Moonraker webcam. URL failover belongs to this source so a
 * failing source never silently switches the user to another camera.
 */
data class WebcamSource(
    val uid: String,
    val name: String,
    val location: String,
    val service: String,
    val snapshotUrls: List<String>,
    val isLegacyFallback: Boolean = false,
) {
    init {
        require(uid.isNotBlank()) { "Webcam source uid must not be blank" }
        require(snapshotUrls.isNotEmpty()) { "Webcam source requires a snapshot URL" }
    }

    val label: String
        get() = listOf(name, location)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" \u00b7 ")
}

/** The current source choice after reconciling discovered cameras with a saved UID. */
data class WebcamSelection(
    val sources: List<WebcamSource> = emptyList(),
    val selected: WebcamSource? = null,
    val preferredSourceUnavailable: Boolean = false,
) {
    companion object {
        fun resolve(sources: List<WebcamSource>, preferredUid: String?): WebcamSelection {
            val selected = sources.firstOrNull { it.uid == preferredUid } ?: sources.firstOrNull()
            return WebcamSelection(
                sources = sources,
                selected = selected,
                preferredSourceUnavailable = preferredUid != null && selected?.uid != preferredUid,
            )
        }
    }
}
