package com.u1.slicer.machine

import com.u1.slicer.data.Printer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MachineProfileRepositoryTest {
    private val u1Json = requireNotNull(
        javaClass.classLoader?.getResource("machine/snapmaker_u1_resolved.json")
    ).readText()

    private val assets = AssetProvider { path ->
        when (path) {
            MachineProfileRepository.U1_MACHINE_ASSET -> u1Json
            else -> error("unexpected asset path: $path")
        }
    }

    @Test
    fun `built in U1 is parsed from the bundled machine profile`() {
        val repository = MachineProfileRepository(assets, tempCache())

        val profile = repository.builtInU1()

        assertEquals("snapmaker-u1-0.4", profile.id)
        assertEquals(270f, profile.bedSizeX)
        assertEquals(270f, profile.bedSizeY)
        assertEquals(4, profile.extruderCount)
        assertEquals(GcodeFlavor.KLIPPER, profile.gcodeFlavor)
        assertEquals(MachineProfileRepository.U1_BED_STL_ASSET, profile.bedStlAsset)
    }

    @Test
    fun `unknown profile id returns null`() {
        val repository = MachineProfileRepository(assets, tempCache())

        assertNull(repository.byId("does-not-exist"))
    }

    @Test
    fun `cached machine profile is resolved by stable id`() {
        val cache = tempCache()
        val cachedJson = requireNotNull(
            javaClass.classLoader?.getResource("machine/generic_klipper_300.json")
        ).readText()
        val cached = requireNotNull(
            MachineProfile.fromResolvedOrcaJson(cachedJson, MachineFamily.GENERIC)
        )
        File(cache, "${cached.id}.json").writeText(cachedJson)
        val repository = MachineProfileRepository(assets, cache)

        assertEquals(cached, repository.byId(cached.id))
        assertNull(cached.bedStlAsset)
    }

    @Test
    fun `printer without a profile id uses built in U1`() {
        val repository = MachineProfileRepository(assets, tempCache())
        val printer = Printer(id = "p1", nickname = "U1", moonrakerUrl = "http://printer")

        assertEquals(repository.builtInU1(), repository.activeFor(printer))
    }

    private fun tempCache(): File = createTempDir(prefix = "machine-profiles-").also {
        it.deleteOnExit()
    }
}
