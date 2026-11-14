package com.u1.slicer.bambu

/** Rewrites TriangleSelector leaf states while retaining its split-tree topology. */
object PaintColorRemapper {
    fun remap(hex: String, stateMap: Map<Int, Int>): String? {
        if (hex.isEmpty()) return hex
        val nibbles = hex.reversed().map { it.digitToIntOrNull(16) ?: return null }
        var index = 0
        val out = mutableListOf<Int>()

        fun writeState(state: Int) {
            require(state >= 0)
            if (state <= 2) out += state shl 2
            else {
                out += 0xC
                var n = state - 3
                while (n >= 15) { out += 0xF; n -= 15 }
                out += n
            }
        }
        fun readNode(): Boolean {
            if (index >= nibbles.size) return false
            val code = nibbles[index++]
            val children = code and 3
            if (children == 0) {
                val state = if ((code and 0xC) == 0xC) {
                    var extension = 0
                    var next: Int
                    do {
                        if (index >= nibbles.size) return false
                        next = nibbles[index++]
                        if (next == 15) extension += 15
                    } while (next == 15)
                    extension + next + 3
                } else code shr 2
                writeState(stateMap[state] ?: state)
                return true
            }
            out += code
            repeat(children + 1) { if (!readNode()) return false }
            return true
        }
        if (!readNode() || index != nibbles.size) return null
        return out.reversed().joinToString("") { it.toString(16).uppercase() }
    }
}
