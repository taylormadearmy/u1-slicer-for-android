package com.u1.slicer

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativeTbbSerialShimTest {

    @Test
    fun `both TBB include spellings are intercepted by Android serial shim`() {
        val nativeRoot = locateNativeRoot()
        val cmake = File(nativeRoot, "CMakeLists.txt").readText()
        val legacy = File(nativeRoot, "extern/tbb_serial/tbb/parallel_for.h")
        val oneApi = File(nativeRoot, "extern/tbb_serial/oneapi/tbb/parallel_for.h")
        val printObject = File(nativeRoot, "orcaslicer/src/libslic3r/PrintObject.cpp").readText()

        assertTrue(legacy.isFile)
        assertTrue(oneApi.isFile)
        assertTrue(oneApi.readText().contains("#include <tbb/parallel_for.h>"))
        assertTrue(cmake.contains("include_directories(BEFORE \"\${EXTERN_DIR}/tbb_serial\")"))
        assertTrue(
            printObject.indexOf("#include <tbb/parallel_for.h>") <
                printObject.indexOf("#include <oneapi/tbb/parallel_for.h>"),
        )
    }

    private fun locateNativeRoot(): File {
        var cursor = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            File(cursor, "src/main/cpp/CMakeLists.txt").takeIf(File::isFile)?.let {
                return it.parentFile
            }
            File(cursor, "app/src/main/cpp/CMakeLists.txt").takeIf(File::isFile)?.let {
                return it.parentFile
            }
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate app/src/main/cpp")
    }
}
