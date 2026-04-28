package com.u1.slicer.gcode

import java.io.File

/**
 * Phase 2 (2026-04-28, B.1 type-safety pivot) — compile-time
 * distinction between "G-code in canonical-fileIndex space" and
 * "G-code in physical-slot space".
 *
 * The 3rd-party adversarial review found 3 P1 leaks where canonical
 * G-code reached the printer-facing path (Send-race, Save/Share with
 * narrowed mapping, Jobs-tab share). Each leak was the SAME bug class
 * — a `String` G-code path passed to a function expecting physical
 * slots when it actually carried canonical fileIndex T-values.
 *
 * The runtime fix in commits `436f6cc` + `09b2daf` plugged the three
 * known instances. This pivot makes the bug class structurally
 * impossible: functions that send G-code to the printer take
 * [PhysicalGcodePath], functions that receive raw slice output
 * produce [CanonicalGcodePath], and the only way to convert is
 * through [com.u1.slicer.gcode.applyPrintTimeRemap].
 *
 * Inline `value class` so there's zero runtime overhead — at
 * bytecode level both types erase to `String`. The Kotlin compiler
 * enforces the distinction at every call site.
 *
 * Migration note: at the Room database boundary [SliceJob.gcodePath]
 * stays a raw `String` because Phase-1-era jobs carry physical-slot
 * G-code (no remap was applied at slice time) and Phase-2-era jobs
 * carry canonical (remap is applied at Send/Save/Share time).
 * `shareJobGcode` decides the wrap based on
 * [SliceJob.canonicalListSize] — null → wrap as `PhysicalGcodePath`
 * (legacy), non-null → wrap as `CanonicalGcodePath`.
 */
@JvmInline
value class CanonicalGcodePath(val absolutePath: String) {
    fun toFile(): File = File(absolutePath)
    companion object {
        fun of(file: File): CanonicalGcodePath = CanonicalGcodePath(file.absolutePath)
    }
}

@JvmInline
value class PhysicalGcodePath(val absolutePath: String) {
    fun toFile(): File = File(absolutePath)
    companion object {
        fun of(file: File): PhysicalGcodePath = PhysicalGcodePath(file.absolutePath)
    }
}
