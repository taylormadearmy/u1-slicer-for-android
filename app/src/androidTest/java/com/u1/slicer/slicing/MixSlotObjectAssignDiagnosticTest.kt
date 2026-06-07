package com.u1.slicer.slicing

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.data.MixedFilamentManager
import com.u1.slicer.data.MixedFilamentRow
import com.u1.slicer.data.SliceConfig
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ISSUE #3 DIAGNOSTIC (NOT A GATE).
 *
 * Question: when a whole OBJECT is assigned to a mix slot via the colour-mapping /
 * object-extruder path (the [com.u1.slicer.ui.FilamentMappingDialog] /
 * [com.u1.slicer.SlicerViewModel.applyMultiColorAssignments] flow — slot id =
 * numPhysical + mixIndex), does the mix-assigned object's region BLEND its two
 * component tools in the sliced G-code, or does it print as a SINGLE physical tool?
 *
 * The Smart Paint blend (proven by [MixSlotRealLoadPathBlendTest]) works because the
 * painted triangles carry a PER-TRIANGLE paint-state byte (= numPhysical + 1 + k)
 * baked into the 3MF `paint_color` attribute. The Snapmaker fork's MixedFilament path
 * resolves that virtual paint state to the recipe and alternates the two component
 * tools per layer.
 *
 * The OBJECT-mix path is DIFFERENT: it routes a whole object to a filament index via
 * `model_settings.config`'s per-object `extruder` metadata (the same channel
 * `applyMultiColorAssignments`/`objectExtruderMap`/`_colorMapping` ultimately feeds —
 * see ProfileEmbedder.convertToModelSettings). It produces NO per-triangle paint
 * bytes. This test reproduces exactly that: a 2-object 3MF with object 1 on physical
 * extruder 1 and object 2 assigned to `extruder="5"` (1-based; 0-based slot 4 =
 * numPhysical + 0 = mix slot 0), with the mix recipe embedded and the same
 * full_spectrum_physical_count=4 marker the paint path uses.
 *
 * We then slice and report the per-object tool usage. If object 2 blends, its layers
 * alternate two tools (T0<->T1). If it clamps/collapses, it prints a single tool.
 *
 * IMPORTANT: this is a DIAGNOSTIC. It does NOT fail the build on the "wrong" outcome —
 * it LOGS the result (tool counts, transitions, the object→tool attribution) under the
 * "MixObjAssign" tag so the controller learns the truth. The only hard assertions are
 * that the slice succeeds and produces G-code (so the diagnostic is meaningful).
 */
@RunWith(AndroidJUnit4::class)
class MixSlotObjectAssignDiagnosticTest {

    private lateinit var lib: NativeLibrary
    private lateinit var out3mf: File

    @Before
    fun setup() {
        assertTrue("Native library must be loaded on device (arm64 required)", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        lib.clearModel()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        out3mf = File(ctx.cacheDir, "mix_obj_assign_${System.currentTimeMillis()}.3mf")
    }

    @After
    fun teardown() {
        lib.clearModel()
        out3mf.delete()
    }

    /** Closed cuboid spanning [ox, ox+w] × [0, d] × [0, h], 12 outward triangles. */
    private fun box(ox: Float, w: Float, d: Float, h: Float): FloatArray {
        val x0 = ox; val x1 = ox + w
        val y0 = 0f; val y1 = d
        val z0 = 0f; val z1 = h
        return floatArrayOf(
            x0,y0,z0,  x1,y1,z0,  x1,y0,z0,
            x0,y0,z0,  x0,y1,z0,  x1,y1,z0,
            x0,y0,z1,  x1,y0,z1,  x1,y1,z1,
            x0,y0,z1,  x1,y1,z1,  x0,y1,z1,
            x0,y0,z0,  x1,y0,z0,  x1,y0,z1,
            x0,y0,z0,  x1,y0,z1,  x0,y0,z1,
            x0,y1,z0,  x1,y1,z1,  x1,y1,z0,
            x0,y1,z0,  x0,y1,z1,  x1,y1,z1,
            x0,y0,z0,  x0,y0,z1,  x0,y1,z1,
            x0,y0,z0,  x0,y1,z1,  x0,y1,z0,
            x1,y0,z0,  x1,y1,z1,  x1,y0,z1,
            x1,y0,z0,  x1,y1,z0,  x1,y1,z1,
        )
    }

    /**
     * Writes a 2-object Bambu-flavoured 3MF. Object [objId] carries a per-object
     * `extruder` metadata value [ext] (1-based). NO paint_color attributes — this is
     * the object-extruder channel, not the paint channel.
     */
    private fun writeTwoObjectThreeMf(
        positionsA: FloatArray, extA: Int,
        positionsB: FloatArray, extB: Int,
        recipe: String,
    ) {
        ZipOutputStream(out3mf.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
</Relationships>""".toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("3D/3dmodel.model"))
            zip.write(buildModelXml(positionsA, positionsB).toByteArray())
            zip.closeEntry()

            // Per-object extruder assignment — the channel applyMultiColorAssignments /
            // objectExtruderMap ultimately feeds via ProfileEmbedder. Object 2 → mix id.
            zip.putNextEntry(ZipEntry("Metadata/model_settings.config"))
            val modelSettings =
                """<?xml version="1.0" encoding="UTF-8"?><config>""" +
                    """<object id="1"><metadata type="object" key="extruder" value="$extA"/></object>""" +
                    """<object id="2"><metadata type="object" key="extruder" value="$extB"/></object>""" +
                    """</config>"""
            zip.write(modelSettings.toByteArray())
            zip.closeEntry()

            // project_settings.config: 4 physical filaments + the recipe + the
            // full_spectrum marker (same as PaintedMeshWriter for a mix). The recipe
            // is ALSO injected via SliceConfig.mixedFilamentDefinitions at slice time
            // (matching production), but embedding it here mirrors the painted 3MF.
            zip.putNextEntry(ZipEntry("Metadata/project_settings.config"))
            zip.write(
                """{
  "filament_colour": ["#FF0000", "#00FF00", "#0000FF", "#FFFF00"],
  "filament_type": ["PLA", "PLA", "PLA", "PLA"],
  "filament_settings_id": ["Generic PLA", "Generic PLA", "Generic PLA", "Generic PLA"],
  "filament_count": "4",
  "mixed_filament_definitions": "$recipe",
  "full_spectrum_physical_count": "4"
}""".toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>
</Types>""".toByteArray()
            )
            zip.closeEntry()
        }
    }

    private fun buildModelXml(posA: FloatArray, posB: FloatArray): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" xmlns:BambuStudio="http://schemas.bambulab.com/package/2021">""")
        sb.append("""<metadata name="Application">BambuStudio-2.2.4</metadata>""").append('\n')
        sb.append("<resources>")
        sb.append(objectXml(1, posA))
        sb.append(objectXml(2, posB))
        sb.append("</resources>")
        sb.append("""<build><item objectid="1"/><item objectid="2"/></build></model>""")
        return sb.toString()
    }

    private fun objectXml(id: Int, pos: FloatArray): String {
        val nTri = pos.size / 9
        // Dedup verts (simple — small meshes).
        val verts = LinkedHashMap<String, Int>()
        val tri = Array(nTri) { IntArray(3) }
        fun idx(x: Float, y: Float, z: Float): Int {
            val k = "%.4f,%.4f,%.4f".format(x, y, z)
            return verts.getOrPut(k) { verts.size }
        }
        for (i in 0 until nTri) {
            val b = i * 9
            tri[i][0] = idx(pos[b], pos[b + 1], pos[b + 2])
            tri[i][1] = idx(pos[b + 3], pos[b + 4], pos[b + 5])
            tri[i][2] = idx(pos[b + 6], pos[b + 7], pos[b + 8])
        }
        val sb = StringBuilder()
        sb.append("""<object id="$id" type="model"><mesh><vertices>""")
        val ordered = verts.keys.toList()
        for (k in ordered) {
            val (x, y, z) = k.split(",")
            sb.append("""<vertex x="$x" y="$y" z="$z"/>""")
        }
        sb.append("</vertices><triangles>")
        for (i in 0 until nTri) {
            sb.append("""<triangle v1="${tri[i][0]}" v2="${tri[i][1]}" v3="${tri[i][2]}"/>""")
        }
        sb.append("</triangles></mesh></object>")
        return sb.toString()
    }

    private fun makeConfig(recipe: String) = SliceConfig(
        layerHeight = 0.2f,
        firstLayerHeight = 0.2f,
        perimeters = 2,
        topSolidLayers = 3,
        bottomSolidLayers = 3,
        fillDensity = 0.15f,
        fillPattern = "gyroid",
        printSpeed = 150f,
        travelSpeed = 200f,
        firstLayerSpeed = 50f,
        nozzleTemp = 220,
        bedTemp = 65,
        nozzleDiameter = 0.4f,
        filamentDiameter = 1.75f,
        retractLength = 0.8f,
        retractSpeed = 45f,
        extruderCount = 4,
        extruderTemps = IntArray(4) { 220 },
        wipeTowerEnabled = false,
        mixedFilamentDefinitions = recipe,
    )

    private fun buildRecipe(): String {
        val mgr = MixedFilamentManager(
            loadProject = { emptyList() },
            loadLibrary = { emptyList() },
            saveProject = {},
            saveLibrary = {},
        )
        mgr.add(
            componentA = 1,
            componentB = 2,
            mixBPercent = 50,
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
        )
        return mgr.serialize(numPhysicalFilaments = 4)
    }

    /** Slices the current loaded model and returns (toolCounts, t0t1Transitions, sawExtrude). */
    private fun sliceAndCount(recipe: String, tag: String): Triple<IntArray, Int, Boolean> {
        val result = lib.slice(makeConfig(recipe))
        assertNotNull("slice() must not return null", result)
        result!!
        assertTrue(
            "Slice must succeed for the diagnostic to be meaningful. Engine error: '${result.errorMessage}'",
            result.success,
        )
        val gcode = File(result.gcodePath).readText()
        assertTrue("G-code must be non-empty", gcode.isNotEmpty())

        gcode.lineSequence().filter {
            it.contains("mixed_filament_definitions") ||
                it.trimStart(';', ' ').startsWith("filament_colour") ||
                it.trimStart(';', ' ').startsWith("filament_diameter")
        }.forEach { Log.i("MixObjAssign", "[$tag] CFGDUMP: ${it.trim()}") }

        val toolRegex = Regex("""^T(\d+)\b""")
        val totalToolCounts = IntArray(16)
        var t0t1Transitions = 0
        var lastTool = -1
        var sawExtrude = false
        var activeTool = -1
        for (raw in gcode.lineSequence()) {
            val line = raw.trim()
            val tm = toolRegex.find(line)
            if (tm != null) {
                val t = tm.groupValues[1].toIntOrNull() ?: continue
                if (t in 0..15) {
                    if ((t == 0 || t == 1) && (lastTool == 0 || lastTool == 1) && t != lastTool)
                        t0t1Transitions++
                    activeTool = t
                    lastTool = t
                    totalToolCounts[t]++
                }
                continue
            }
            if (activeTool >= 0 && (line.startsWith("G1 ") || line.startsWith("G0 ")) && line.contains(" E")) {
                sawExtrude = true
            }
        }
        val toolSummary = (0..15).filter { totalToolCounts[it] > 0 }
            .joinToString(", ") { "T$it=${totalToolCounts[it]}" }
        Log.i("MixObjAssign", "[$tag] tool counts: [$toolSummary]; T0<->T1 transitions=$t0t1Transitions; sawExtrude=$sawExtrude")
        return Triple(totalToolCounts, t0t1Transitions, sawExtrude)
    }

    @Test
    fun objectAssignedToMixSlot_blendsOrClamps_DIAGNOSTIC() {
        // Object A: physical E1. Object B: assigned to the mix slot (1-based extruder 5
        // = 0-based slot 4 = numPhysical(4) + mixIndex 0). The recipe defines mix 0 as
        // E1+E2 @ 50% LAYER_CYCLE — the SAME mix MixSlotRealLoadPathBlendTest proved
        // blends through the PAINT path.
        val boxA = box(ox = 0f, w = 12f, d = 12f, h = 8f)
        val boxB = box(ox = 20f, w = 12f, d = 12f, h = 8f)
        val recipe = buildRecipe()
        assertTrue("Manager must produce a non-empty recipe", recipe.isNotEmpty())
        Log.i("MixObjAssign", "recipe='$recipe'")

        // Object B → extruder 5 (1-based) = mix slot id 4 (0-based) = numPhysical + 0.
        writeTwoObjectThreeMf(
            positionsA = boxA, extA = 1,
            positionsB = boxB, extB = 5,
            recipe = recipe,
        )
        assertTrue("Two-object 3MF must be written", out3mf.length() > 0)
        assertTrue("loadModel must succeed for the object-mix 3MF", lib.loadModel(out3mf.absolutePath))
        val (counts, transitions, sawExtrude) = sliceAndCount(recipe, "MIX")

        val usesT1 = counts[1] > 0
        val usesHighTool = (4..15).any { counts[it] > 0 }
        val verdict = when {
            usesHighTool ->
                "CLAMP/INVALID: a literal high tool index (T>=4) was emitted — the mix id was " +
                    "treated as a physical filament index, NOT resolved to a blend. " +
                    "highTools=${(4..15).filter { counts[it] > 0 }.map { "T$it=${counts[it]}" }}"
            usesT1 && transitions >= 8 ->
                "BLEND: object-mix region alternates its two component tools (T0<->T1=$transitions) — " +
                    "the object-extruder path DID resolve the mix to a real blend."
            else ->
                "CLAMP/COLLAPSE: object-mix region printed a SINGLE physical tool (no repeated " +
                    "T0<->T1 alternation: $transitions). The mix did NOT blend through the " +
                    "object-extruder path; the object was routed to one physical filament."
        }
        Log.i("MixObjAssign", "ISSUE#3 VERDICT: $verdict")
        Log.i("MixObjAssign", "ISSUE#3 DATA: extruderCount=4 toolCounts=[${(0..15).filter { counts[it] > 0 }.joinToString { "T$it=${counts[it]}" }}] T0<->T1=$transitions recipe='$recipe'")
        assertTrue("Sanity: G-code must contain extrusion moves", sawExtrude)
    }

    /**
     * Writes a SINGLE-object Bambu 3MF whose only object carries per-object
     * `extruder` metadata [ext] (1-based). No paint_color. This is the decisive
     * shape: a single object means ANY T0<->T1 alternation in the body MUST come
     * from that object's filament resolving to a per-layer blend — it cannot be an
     * inter-object interleave artefact (there is only one object).
     */
    private fun writeSingleObjectThreeMf(positions: FloatArray, ext: Int, recipe: String) {
        ZipOutputStream(out3mf.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
</Relationships>""".toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("3D/3dmodel.model"))
            val sb = StringBuilder()
            sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
            sb.append("""<model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" xmlns:BambuStudio="http://schemas.bambulab.com/package/2021">""")
            sb.append("""<metadata name="Application">BambuStudio-2.2.4</metadata>""").append('\n')
            sb.append("<resources>")
            sb.append(objectXml(1, positions))
            sb.append("</resources>")
            sb.append("""<build><item objectid="1"/></build></model>""")
            zip.write(sb.toString().toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("Metadata/model_settings.config"))
            zip.write(
                ("""<?xml version="1.0" encoding="UTF-8"?><config>""" +
                    """<object id="1"><metadata type="object" key="extruder" value="$ext"/></object>""" +
                    """</config>""").toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("Metadata/project_settings.config"))
            zip.write(
                """{
  "filament_colour": ["#FF0000", "#00FF00", "#0000FF", "#FFFF00"],
  "filament_type": ["PLA", "PLA", "PLA", "PLA"],
  "filament_settings_id": ["Generic PLA", "Generic PLA", "Generic PLA", "Generic PLA"],
  "filament_count": "4",
  "mixed_filament_definitions": "$recipe",
  "full_spectrum_physical_count": "4"
}""".toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>
</Types>""".toByteArray()
            )
            zip.closeEntry()
        }
    }

    /**
     * DECISIVE: a SINGLE object assigned to the mix slot (extruder=5, 1-based = mix
     * slot 0). With only one object on the bed there is no inter-object interleave, so
     * any repeated T0<->T1 alternation in the body proves the object's filament
     * resolved to a per-layer mix blend. A single tool (no alternation) proves it
     * clamped/collapsed to one physical filament. This is the metric the 2-object test
     * could NOT isolate (see controlObjectOnPlainPhysicalE2 — two solid objects also
     * interleave T0/T1 per layer, mimicking a blend).
     */
    @Test
    fun singleObjectAssignedToMixSlot_DECISIVE_DIAGNOSTIC() {
        val theBox = box(ox = 0f, w = 12f, d = 12f, h = 8f)
        val recipe = buildRecipe()
        // extruder 5 (1-based) = 0-based slot 4 = numPhysical(4) + mixIndex 0.
        writeSingleObjectThreeMf(theBox, ext = 5, recipe = recipe)
        assertTrue("loadModel must succeed for the single-object mix 3MF", lib.loadModel(out3mf.absolutePath))
        val (counts, transitions, sawExtrude) = sliceAndCount(recipe, "SINGLE-MIX")

        val usesT0 = counts[0] > 0
        val usesT1 = counts[1] > 0
        val usesHighTool = (4..15).any { counts[it] > 0 }
        val verdict = when {
            usesHighTool ->
                "CLAMP/INVALID: literal high tool index (T>=4) emitted — the mix id was treated as " +
                    "a physical filament index, never resolved to a blend. " +
                    "highTools=${(4..15).filter { counts[it] > 0 }.map { "T$it=${counts[it]}" }}"
            usesT0 && usesT1 && transitions >= 8 ->
                "BLEND: the single mix-assigned object alternates BOTH component tools per layer " +
                    "(T0=${counts[0]} T1=${counts[1]} transitions=$transitions). The object-extruder " +
                    "path DOES resolve a mix id to a real per-layer blend."
            else ->
                "CLAMP/COLLAPSE: the single mix-assigned object printed essentially ONE tool " +
                    "(T0=${counts[0]} T1=${counts[1]} transitions=$transitions). The mix did NOT blend; " +
                    "the object was routed to a single physical filament."
        }
        Log.i("MixObjAssign", "ISSUE#3 DECISIVE VERDICT: $verdict")
        assertTrue("Sanity: G-code must contain extrusion moves", sawExtrude)
    }

    /**
     * DECISIVE CONTROL: a SINGLE object on PLAIN PHYSICAL E1 (extruder=1), no mix.
     * Validates the single-object metric: with one solid physical object we expect a
     * single tool (T0) and ~0 T0<->T1 transitions. If THIS shows alternation, the
     * metric is broken and the SINGLE-MIX "blend" reading is invalid.
     */
    @Test
    fun singleObjectOnPlainPhysicalE1_DECISIVE_CONTROL() {
        val theBox = box(ox = 0f, w = 12f, d = 12f, h = 8f)
        val recipe = buildRecipe()
        writeSingleObjectThreeMf(theBox, ext = 1, recipe = recipe)
        assertTrue("loadModel must succeed", lib.loadModel(out3mf.absolutePath))
        val (counts, transitions, sawExtrude) = sliceAndCount(recipe, "SINGLE-PHYS-E1")
        Log.i(
            "MixObjAssign",
            "SINGLE-PHYS-E1 CONTROL: T0=${counts[0]} T1=${counts[1]} transitions=$transitions " +
                "(expect single tool, ~0 transitions — validates the single-object blend metric)."
        )
        assertTrue("Sanity: G-code must contain extrusion moves", sawExtrude)
    }

    /**
     * CONTROL: same 2-object 3MF, but object B on PLAIN PHYSICAL E2 (extruder=2), NOT
     * the mix. Proves the alternation in the MIX case is attributable to the mix
     * resolution and not to two solid objects on different tools. Here we EXPECT both
     * T0 and T1 present (object A=T0, object B=T1) but essentially NO per-layer
     * alternation (each object is a solid block) — far fewer T0<->T1 transitions than
     * a LAYER_CYCLE blend.
     */
    @Test
    fun controlObjectOnPlainPhysicalE2_noBlend_DIAGNOSTIC() {
        val boxA = box(ox = 0f, w = 12f, d = 12f, h = 8f)
        val boxB = box(ox = 20f, w = 12f, d = 12f, h = 8f)
        val recipe = buildRecipe()

        // Object B → extruder 2 (1-based) = plain physical E2. NOT a mix.
        writeTwoObjectThreeMf(
            positionsA = boxA, extA = 1,
            positionsB = boxB, extB = 2,
            recipe = recipe,
        )
        assertTrue("loadModel must succeed", lib.loadModel(out3mf.absolutePath))
        val (counts, transitions, sawExtrude) = sliceAndCount(recipe, "CONTROL")
        Log.i(
            "MixObjAssign",
            "CONTROL VERDICT: object B on plain E2 → T0=${counts[0]} T1=${counts[1]} " +
                "T0<->T1 transitions=$transitions (expect LOW: two solid blocks, not a per-layer blend). " +
                "If transitions here are ALSO high, the alternation is an interleaving artefact, " +
                "not proof of mix blending in the MIX case."
        )
        assertTrue("Sanity: G-code must contain extrusion moves", sawExtrude)
    }
}
