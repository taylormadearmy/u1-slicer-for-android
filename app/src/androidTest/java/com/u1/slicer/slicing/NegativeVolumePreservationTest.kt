package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.bambu.BambuSanitizer
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * Regression guard for the negative/modifier-volume bug (2026-06-04, fixture
 * `negative-volume-articulated.3mf`, original `2.3mf`).
 *
 * The fixture is a single-file compound Bambu 3MF: object 4 = one `normal_part`
 * plus two `negative_part` articulation-joint cutters, all inlined into ONE
 * ~119MB `3D/3dmodel.model`. Because that main model exceeds
 * `BambuSanitizer.MAX_IN_MEMORY_MAIN_MODEL_BYTES` (64MB), `process()` takes the
 * streaming sanitize path, which skipped the compound-restructuring block. That
 * meant `attachCompoundComponentIds` never ran, `isCompound` was false, and the
 * fallback `buildSlic3rModelConfig` emitted `<volume firstid/lastid>` entries
 * with NO `subtype` — so the native BBS importer treated the negative volumes as
 * solid `normal_part` objects and printed them instead of subtracting them.
 *
 * The native engine derives volume type SOLELY from the model_settings.config
 * `<part subtype>` entries, so the regression is fully observable at the embedded
 * file's config level: the embedded model_settings.config must still carry both
 * `subtype="negative_part"` entries.
 */
@RunWith(AndroidJUnit4::class)
class NegativeVolumePreservationTest {

    private lateinit var lib: NativeLibrary
    private lateinit var cacheDir: File
    private lateinit var outDir: File
    private lateinit var embedder: ProfileEmbedder

    @Before
    fun setup() {
        assertTrue("Native library required", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        lib.clearModel()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        cacheDir = ctx.cacheDir
        outDir = File(cacheDir, "neg_vol_test_out").also { it.mkdirs() }
        embedder = ProfileEmbedder(ctx)
    }

    @After
    fun teardown() {
        lib.clearModel()
        outDir.deleteRecursively()
    }

    private fun asset(name: String): File {
        val file = File(cacheDir, name.replace("/", "_"))
        InstrumentationRegistry.getInstrumentation().context
            .assets.open(name).use { out -> file.outputStream().use { out.copyTo(it) } }
        return file
    }

    private fun readModelSettings(zipFile: File): String {
        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry("Metadata/model_settings.config")
                ?: zip.getEntry("Metadata/Slic3r_PE_model.config")
                ?: error("no model_settings.config / Slic3r_PE_model.config in ${zipFile.name}")
            return zip.getInputStream(entry).bufferedReader().readText()
        }
    }

    private fun negativePartCount(configXml: String): Int =
        Regex("""subtype="negative_part"""").findAll(configXml).count()

    /**
     * End-to-end: the two `negative_part` cutters must survive `process()` +
     * `embed()` so the native slicer subtracts them. Mirrors the production
     * pipeline in [SlicerViewModel.prepareImportedModelArtifacts] (process →
     * mergeThreeMfInfo → embed with the first plate's id).
     */
    @Test
    fun largeCompoundModel_negativeVolumesSurviveProcessAndEmbed() {
        val input = asset("negative-volume-articulated.3mf")
        val origInfo = ThreeMfParser.parse(input)
        assertTrue("fixture should be detected as Bambu", origInfo.isBambu)

        // Sanity: the source genuinely has two negative parts (the bug is about
        // losing them downstream, not a mis-authored fixture).
        assertEquals(
            "source fixture must contain exactly 2 negative_part volumes",
            2, negativePartCount(readModelSettings(input))
        )

        val processed = BambuSanitizer.process(input, outDir, isBambu = origInfo.isBambu)
        val mergedInfo = SlicerViewModel.mergeThreeMfInfo(
            ThreeMfParser.parse(processed, skipPaintDetection = true), origInfo
        )
        val firstPlateId = mergedInfo.plates.firstOrNull()?.plateId
        val config = embedder.buildConfig(mergedInfo)
        val embedded = embedder.embed(processed, config, outDir, mergedInfo, plateId = firstPlateId)

        // The regression: the embedded file the native slicer actually loads must
        // still mark both cutters as negative_part. Pre-fix this is 0.
        val embeddedConfig = readModelSettings(embedded)
        assertEquals(
            "embedded model_settings.config must preserve both negative_part subtypes " +
                "(got config:\n$embeddedConfig\n)",
            2, negativePartCount(embeddedConfig)
        )

        // The embedded file must remain loadable by the native importer.
        assertTrue("loadModel should succeed for the embedded file",
            lib.loadModel(embedded.absolutePath))
    }
}
