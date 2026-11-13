package com.u1.slicer.data

/** Installed nozzle diameters in physical E1–E4 order, independent of loaded filament. */
class U1NozzleConfiguration(diametersMm: List<Float> = List(4) { 0.4f }) {
    val diametersMm: List<Float> = diametersMm.toList()

    init {
        require(this.diametersMm.size == 4) { "U1 requires four nozzle diameters" }
        require(this.diametersMm.all { it in SUPPORTED_DIAMETERS_MM }) {
            "Supported U1 nozzle diameters are 0.2, 0.4, 0.6 and 0.8 mm"
        }
    }

    fun diameterMm(slot: Int): Float = diametersMm[slot]

    fun withDiameter(slot: Int, diameterMm: Float): U1NozzleConfiguration {
        require(slot in 0..3) { "U1 nozzle slot must be between 0 and 3" }
        return U1NozzleConfiguration(diametersMm.mapIndexed { index, value ->
            if (index == slot) diameterMm else value
        })
    }

    override fun equals(other: Any?): Boolean =
        other is U1NozzleConfiguration && diametersMm == other.diametersMm

    override fun hashCode(): Int = diametersMm.hashCode()

    override fun toString(): String = "U1NozzleConfiguration($diametersMm)"

    companion object {
        val SUPPORTED_DIAMETERS_MM: List<Float> = listOf(0.2f, 0.4f, 0.6f, 0.8f)
    }
}
