package com.u1.slicer.aipaint

/**
 * Tags every region with the cascade branch that produced it. Surfaced in diagnostics and
 * (later) UI hints — also used by tests to assert which branch fired on which fixture.
 */
enum class SegmentationSource(val displayLabel: String) {
    PAINT_STATE("Painted"),
    VOLUME("Per-volume"),
    OBJECT("Per-object"),
    TRIANGLE_INDEX("Triangle indices"),
    TOPOLOGY("Topology"),
    TOPOLOGY_RECURSIVE("Topology + sub-regions"),
    Z_BAND("Height bands"),
    BRUSH("Brush stroke"),
}
