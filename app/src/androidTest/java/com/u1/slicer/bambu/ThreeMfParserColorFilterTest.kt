package com.u1.slicer.bambu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ThreeMfParserColorFilterTest {

    @Test
    fun parse_filters_unused_h2c_metadata_colors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "h2c_color_filter_test.3mf")

        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            writeEntry(
                zip,
                "[Content_Types].xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>
                  <Default Extension="config" ContentType="application/xml"/>
                  <Default Extension="json" ContentType="application/json"/>
                </Types>
                """.trimIndent()
            )
            writeEntry(
                zip,
                "3D/3dmodel.model",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
                <resources>
                <object id="1" type="model">
                <mesh>
                <vertices>
                <vertex x="0" y="0" z="0"/>
                <vertex x="1" y="0" z="0"/>
                <vertex x="0" y="1" z="0"/>
                <vertex x="1" y="1" z="0"/>
                <vertex x="2" y="0" z="0"/>
                <vertex x="2" y="1" z="0"/>
                <vertex x="3" y="0" z="0"/>
                </vertices>
                <triangles>
                <triangle v1="0" v2="1" v3="2" paint_color="1C"/>
                <triangle v1="1" v2="3" v3="2" paint_color="2C"/>
                <triangle v1="1" v2="4" v3="3" paint_color="3C"/>
                <triangle v1="4" v2="5" v3="3" paint_color="4C"/>
                <triangle v1="4" v2="6" v3="5" paint_color="8C"/>
                </triangles>
                </mesh>
                </object>
                </resources>
                <build>
                <item objectid="1"/>
                </build>
                </model>
                """.trimIndent()
            )
            writeEntry(
                zip,
                "Metadata/model_settings.config",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <config>
                  <object id="1">
                    <metadata key="name" value="H2C Test"/>
                    <metadata key="extruder" value="1"/>
                  </object>
                </config>
                """.trimIndent()
            )
            writeEntry(
                zip,
                "Metadata/project_settings.config",
                """
                {
                  "printer_model": "Bambu Lab H2C",
                  "filament_colour": [
                    "#0086D6",
                    "#FFFF00",
                    "#FFFFFF",
                    "#6A00D5",
                    "#FF0000",
                    "#00AE42",
                    "#FF8000"
                  ]
                }
                """.trimIndent()
            )
            writeEntry(
                zip,
                "Metadata/filament_sequence.json",
                """{"plate_1":{"sequence":[]}}"""
            )
        }

        val info = ThreeMfParser.parse(file)

        assertEquals(
            listOf("#0086D6", "#FFFF00", "#FFFFFF", "#6A00D5", "#FF8000"),
            info.detectedColors
        )
        assertEquals(5, info.detectedExtruderCount)
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray())
        zip.closeEntry()
    }
}
