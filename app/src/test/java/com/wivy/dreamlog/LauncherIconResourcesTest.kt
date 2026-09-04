package com.wivy.dreamlog

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconResourcesTest {
    @Test
    fun canonicalLauncherResourcesUseTheCloudArtwork() {
        val art = projectFile("src/main/res/drawable-nodpi/m01_launcher_art.png")
        val foreground = projectFile("src/main/res/drawable/m01_launcher_foreground.xml")
        val colors = projectFile("src/main/res/values/colors.xml")
        val manifest = projectFile("src/main/AndroidManifest.xml")
        val theme = projectFile("src/main/res/values/themes.xml")
        val adaptiveIcons = listOf(
            projectFile("src/main/res/mipmap-anydpi-v26/ic_launcher.xml"),
            projectFile("src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml"),
        )

        assertTrue("The full-color cloud artwork must ship in main resources", art.isFile)
        assertEquals(EXPECTED_CLOUD_SHA256, art.sha256())
        assertTrue(
            foreground.readText().contains("@drawable/m01_launcher_art"),
        )
        assertTrue(foreground.readText().contains("android:inset=\"4dp\""))
        assertTrue(colors.readText().contains("<color name=\"m01_launcher_background\">#000000"))
        adaptiveIcons.forEach { icon ->
            val text = icon.readText()
            assertTrue(text.contains("<adaptive-icon"))
            assertTrue(text.contains("@color/m01_launcher_background"))
            assertTrue(text.contains("@drawable/m01_launcher_foreground"))
        }
        assertTrue(manifest.readText().contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(manifest.readText().contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))
        assertTrue(theme.readText().contains("@mipmap/ic_launcher"))

        val nonMainOverrides = projectFile("src")
            .listFiles()
            .orEmpty()
            .filterNot { it.name == "main" }
            .flatMap { sourceSet ->
                sourceSet.walkTopDown()
                    .filter { file ->
                        file.isFile &&
                            (
                                file.name.startsWith("ic_launcher") ||
                                    file.name.startsWith("m01_launcher")
                                )
                    }
                    .toList()
            }
        assertTrue(
            "Launcher resources must not be overridden outside main: $nonMainOverrides",
            nonMainOverrides.isEmpty(),
        )
    }

    private fun projectFile(relativePath: String): File =
        File(relativePath).takeIf { it.exists() } ?: File("app/$relativePath")

    private fun File.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(readBytes())
        .joinToString(separator = "") { byte -> "%02X".format(byte) }

    private companion object {
        const val EXPECTED_CLOUD_SHA256 =
            "FC2D9CF1DBFE0A3866A33144BF0A8CE0A88C98CD2A603FC8453626203CF2EA91"
    }
}
