package com.u1.slicer.data

import org.json.JSONObject

/** One filament from the bundled OpenPrintTag snapshot (FFF only). */
data class FilamentLibraryEntry(
    val slug: String,
    val brand: String,
    val name: String,
    /** Canonical material where mappable (e.g. PA6→PA); raw type otherwise. */
    val material: String,
    /** Original database type when it differs from [material]. */
    val materialRaw: String? = null,
    /** "#RRGGBB", or null for entries without a primary colour (no swatch). */
    val hex: String? = null,
    /** HueForge transmission distance — carried for future translucency work, NOT used in slicing. */
    val td: Double? = null,
    /** Refractive index — carried for future translucency work, NOT used in slicing. */
    val ri: Double? = null,
    val density: Double? = null,
    val minNozzle: Int? = null,
    val maxNozzle: Int? = null,
    val minBed: Int? = null,
    val maxBed: Int? = null,
) {
    val displayName: String get() = "$brand $name"
}

data class LibrarySnapshotInfo(val commit: String, val date: String, val count: Int)

/**
 * In-memory filament library parsed from assets/filament_library.json.
 * Pure Kotlin — hosts load the asset text and call [parse]; search inputs
 * (favourites/recents) are passed in so this class stays state-free.
 */
class FilamentLibrary(
    val entries: List<FilamentLibraryEntry>,
    val snapshot: LibrarySnapshotInfo,
) {
    private val bySlug = entries.associateBy { it.slug }

    fun entry(slug: String): FilamentLibraryEntry? = bySlug[slug]

    fun search(
        query: String,
        material: String? = null,
        favourites: Set<String> = emptySet(),
        recents: List<String> = emptyList(),
        limit: Int = DEFAULT_LIMIT,
    ): List<FilamentLibraryEntry> {
        val pool = if (material == null) entries
        else entries.filter { it.material.equals(material, ignoreCase = true) }

        val q = query.trim()
        if (q.isEmpty()) {
            val favs = pool.filter { it.slug in favourites }
                .sortedBy { it.displayName.lowercase() }
            val recs = recents.mapNotNull { slug ->
                pool.firstOrNull { it.slug == slug && slug !in favourites }
            }
            val head = (favs + recs)
            val headSlugs = head.map { it.slug }.toSet()
            val rest = pool.filter { it.slug !in headSlugs }
                .sortedBy { it.displayName.lowercase() }
            return (head + rest).take(limit)
        }

        val tokens = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val matched = pool.mapNotNull { e ->
            val haystack = "${e.brand} ${e.name} ${e.material} ${e.materialRaw ?: ""}".lowercase()
            if (tokens.all { haystack.contains(it) }) {
                val quality = when {
                    e.displayName.lowercase().startsWith(q.lowercase()) -> 0
                    haystack.split(' ').any { w -> w.startsWith(tokens.first()) } -> 1
                    else -> 2
                }
                val favRank = if (e.slug in favourites) 0 else 1
                Triple(e, favRank, quality)
            } else null
        }
        return matched
            .sortedWith(compareBy({ it.second }, { it.third }, { it.first.displayName.lowercase() }))
            .map { it.first }
            .take(limit)
    }

    companion object {
        const val DEFAULT_LIMIT = 200

        /** Throws on malformed input — callers map exceptions to a Failed state. */
        fun parse(json: String): FilamentLibrary {
            val root = JSONObject(json)
            val arr = root.getJSONArray("entries")
            val entries = ArrayList<FilamentLibraryEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                entries.add(
                    FilamentLibraryEntry(
                        slug = o.getString("s"),
                        brand = o.getString("b"),
                        name = o.getString("n"),
                        material = o.optString("m", ""),
                        materialRaw = if (o.has("mr")) o.getString("mr") else null,
                        hex = if (o.has("h")) o.getString("h") else null,
                        td = if (o.has("td")) o.getDouble("td") else null,
                        ri = if (o.has("ri")) o.getDouble("ri") else null,
                        density = if (o.has("d")) o.getDouble("d") else null,
                        minNozzle = if (o.has("nl")) o.getInt("nl") else null,
                        maxNozzle = if (o.has("nh")) o.getInt("nh") else null,
                        minBed = if (o.has("bl")) o.getInt("bl") else null,
                        maxBed = if (o.has("bh")) o.getInt("bh") else null,
                    )
                )
            }
            return FilamentLibrary(
                entries = entries,
                snapshot = LibrarySnapshotInfo(
                    commit = root.optString("commit", "?"),
                    date = root.optString("date", "?"),
                    count = root.optInt("count", entries.size),
                ),
            )
        }
    }
}
