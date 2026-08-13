package com.u1.slicer.native

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.u1.slicer.NativeLibrary
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.SlicerViewModel.SlicerState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ViewModelSplitTest {

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
    fun testViewModelSplit() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        
        // Force initialize NativeLibrary by accessing isLoaded
        val loaded = NativeLibrary.isLoaded
        val vm = SlicerViewModel(app)
        val file = File(copyAsset("Hammer.3mf"))
        
        val nativeField = SlicerViewModel::class.java.getDeclaredField("native")
        nativeField.isAccessible = true
        val nativeLib = nativeField.get(vm) as NativeLibrary

        // Load directly so we don't get stuck on SlicerViewModel multi-plate dialogs
        val success = nativeLib.loadModel(file.absolutePath)
        assertTrue("Native load should succeed", success)
        
        val objCount = nativeLib.nativeGetObjectCount()
        
        println("OBJECT COUNT: $objCount")
        for (i in 0 until objCount) {
            println("OBJ $i: volumes=${nativeLib.nativeGetVolumeCount(i)}")
        }
        val splittableIdx = (0 until objCount).firstOrNull {
            nativeLib.nativeIsObjectSplittable(it) 
        }
            
        assertNotNull("Hammer.3mf should have a splittable object", splittableIdx)
        val splitResult = vm.splitObject(splittableIdx!!)
        
        assertTrue("Split should succeed", splitResult)
        
    }
}
