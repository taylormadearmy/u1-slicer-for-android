package com.u1.slicer.native

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.SlicerViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HammerUiTest {

    private fun copyAsset(filename: String): String {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val targetContext = ApplicationProvider.getApplicationContext<Application>()
        val file = File(targetContext.cacheDir, filename)
        testContext.assets.open(filename).use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return file.absolutePath
    }

    @Test
    fun testHammerUiFlow() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = SlicerViewModel(app)
        val file = File(copyAsset("Hammer.3mf"))

        vm.loadModelFromFile(file)
        
        // Wait for load to finish or plate selector to show
        val loadedOrSelector = try {
            withTimeout(15000) {
                kotlinx.coroutines.flow.combine(vm.state, vm.showPlateSelector) { s, sel ->
                    if (sel) "selector"
                    else if (s is SlicerViewModel.SlicerState.ModelLoaded || s is SlicerViewModel.SlicerState.Error) "loaded"
                    else null
                }.first { it != null }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timeout waiting for model load or plate selector")
        }

        val nativeField = SlicerViewModel::class.java.getDeclaredField("native")
        nativeField.isAccessible = true
        val nativeLib = nativeField.get(vm) as com.u1.slicer.NativeLibrary

        var splittableIdx: Int? = null
        if (loadedOrSelector == "selector") {
            val plates = vm.multiPlatePlates.value
            for (plate in plates) {
                vm.selectPlate(plate.plateId)
                
                try {
                    withTimeout(15000) { vm.state.first { it is SlicerViewModel.SlicerState.ModelLoaded || it is SlicerViewModel.SlicerState.Error } }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    throw AssertionError("Timeout waiting for model load after plate selection ${plate.plateId}")
                }
                
                val objCount = nativeLib.nativeGetObjectCount()
                println("HAMMERUITEST Plate ${plate.plateId} objCount: $objCount")
                
                if (plate.plateId == 4) {
                    splittableIdx = (0 until objCount).firstOrNull {
                        val isSplit = nativeLib.nativeIsObjectSplittable(it)
                        println("HAMMERUITEST Plate ${plate.plateId} obj $it isSplittable: $isSplit")
                        isSplit
                    }
                    if (splittableIdx != null) break
                }
            }
        } else {
            val state = vm.state.value
            assertTrue("Model should be loaded successfully", state is SlicerViewModel.SlicerState.ModelLoaded)
            
            val objCount = nativeLib.nativeGetObjectCount()
            println("HAMMERUITEST objCount: $objCount")
            
            splittableIdx = (0 until objCount).firstOrNull {
                val isSplit = nativeLib.nativeIsObjectSplittable(it)
                println("HAMMERUITEST obj $it isSplittable: $isSplit")
                isSplit
            }
        }
        
        assertNotNull("Hammer.3mf should have a splittable object on some plate", splittableIdx)

        vm.selectObject(splittableIdx!!)
        val splitResult = vm.splitObject(splittableIdx)
        assertTrue("Split should succeed", splitResult)
        
        // Now force read all StateFlows that the UI reads to trigger any crashes
        val boxes = vm.objectBoundingBoxes.value
        val loadBoxes = vm.loadTimeObjectBoundingBoxes.value
        val poses = vm.perObjectPoses.value
        
        assertNotNull(boxes)
        assertNotNull(loadBoxes)

        // Scale down to 10% so it fits well within the 270mm volume and slices instantly
        vm.setModelScale(SlicerViewModel.ModelScale(0.1f, 0.1f, 0.1f))

        // Trigger slicing and wait for it
        vm.startSlicing()
        val sliceState = try {
            withTimeout(60000) { vm.state.first { it is SlicerViewModel.SlicerState.SliceComplete || it is SlicerViewModel.SlicerState.Error } }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timeout waiting for slice complete")
        }
        println("SLICE STATE: $sliceState")
        assertTrue(sliceState is SlicerViewModel.SlicerState.SliceComplete)
        assertNotNull(poses)
    }
}
