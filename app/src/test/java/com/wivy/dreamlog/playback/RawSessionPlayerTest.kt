package com.wivy.dreamlog.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RawSessionPlayerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `resolver confines opaque session audio to its night directory`() {
        val filesDirectory = temporaryFolder.newFolder("files")
        val nightId = "11111111-2222-3333-4444-555555555555"
        val audioFileName = "a_0123456789abcdef0123456789abcdef.wav"

        assertEquals(
            File(filesDirectory, "capture/audio/$nightId/$audioFileName").canonicalFile,
            resolveCanonicalRawSessionFile(filesDirectory, nightId, audioFileName),
        )
    }

    @Test
    fun `resolver rejects path-like identifiers and non-opaque audio names`() {
        val filesDirectory = temporaryFolder.newFolder("files")

        assertThrows(IllegalArgumentException::class.java) {
            resolveCanonicalRawSessionFile(
                filesDirectory,
                "../another-night",
                "a_0123456789abcdef0123456789abcdef.wav",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveCanonicalRawSessionFile(
                filesDirectory,
                "11111111-2222-3333-4444-555555555555",
                "../session.wav",
            )
        }
    }

    @Test
    fun `timestamp targets distinguish play from here from whole session playback`() {
        val wholeSession = RawSessionPlaybackTarget(
            nightId = "night",
            audioFileName = "a_0123456789abcdef0123456789abcdef.wav",
        )

        assertEquals(0L, wholeSession.startPositionMillis)
        assertNotEquals(
            wholeSession,
            wholeSession.copy(startPositionMillis = 4_250L),
        )
    }
}
