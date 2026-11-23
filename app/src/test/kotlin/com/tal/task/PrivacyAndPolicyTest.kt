package com.tal.task

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PrivacyAndPolicyTest {

    @Test
    fun `D4 and D7 AndroidManifest declares zero INTERNET permission`() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        assertTrue("Manifest file must exist at ${manifestFile.absolutePath}", manifestFile.exists())
        val content = manifestFile.readText()
        assertFalse(
            "Manifest must NEVER request android.permission.INTERNET",
            content.contains("android.permission.INTERNET")
        )
        assertTrue(
            "Manifest must declare TaskInputMethodService",
            content.contains("TaskInputMethodService")
        )
    }

    @Test
    fun `T_A_L_ palette forbids retired Signal Lime C7FF32 across app sources`() {
        val srcDir = File("src/main")
        val offending = srcDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { it.readText().contains("C7FF32", ignoreCase = true) }
            .map { it.path }
            .toList()
        assertTrue("Retired #C7FF32 found in: $offending", offending.isEmpty())
    }
}
