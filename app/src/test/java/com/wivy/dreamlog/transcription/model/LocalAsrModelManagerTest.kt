package com.wivy.dreamlog.transcription.model

import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalAsrModelManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun selectedManifestHasStableAggregateIdentityAndImmutableSources() {
        val definition = LocalAsrModelManifest.definition

        assertEquals(LocalAsrModelManifest.MODEL_SHA256, definition.identitySha256)
        assertEquals(663_043_117L, definition.totalBytes)
        assertTrue(definition.files.all { file ->
            file.source.scheme == "https" &&
                file.source.path.contains("/${LocalAsrModelManifest.REVISION}/")
        })
    }

    @Test
    fun installStagesVerifiesAndPromotesCompleteModel() {
        val fixture = fixture()
        fixture.legacyDirectory.mkdirs()
        File(fixture.legacyDirectory, "legacy-sentinel").writeText("legacy")
        val downloads = mutableListOf<URI>()
        val progress = mutableListOf<LocalAsrInstallProgress>()
        val manager = manager(fixture) { file, destination, _, onBytesWritten ->
            assertFalse(fixture.installedDirectory.isDirectory)
            assertTrue(fixture.legacyDirectory.isDirectory)
            downloads += file.source
            destination.writeBytes(fixture.content.getValue(file.localName))
            onBytesWritten(file.bytes)
        }

        val installed = manager.install(onProgress = progress::add)

        assertEquals(fixture.definition.revision, installed.revision)
        assertEquals(fixture.definition.identitySha256, installed.modelSha256)
        assertEquals(fixture.definition.totalBytes, installed.totalModelBytes)
        assertTrue(installed.encoderFile.isFile)
        assertTrue(installed.decoderFile.isFile)
        assertTrue(installed.joinerFile.isFile)
        assertTrue(installed.tokensFile.isFile)
        assertEquals(fixture.definition.files.map { it.source }, downloads)
        assertEquals(fixture.definition.totalBytes, progress.last().completedBytes)
        assertEquals(
            fixture.definition.installedManifestText,
            File(installed.directory, LocalAsrModelManifest.INSTALLED_MANIFEST_FILE).readText(),
        )
        assertTrue(manager.status() is LocalAsrModelStatus.Installed)
        assertFalse(fixture.stagingDirectory.exists())
        assertFalse(fixture.legacyDirectory.exists())
    }

    @Test
    fun installRejectsCorruptFileAndCleansAllPartialState() {
        val fixture = fixture()
        fixture.legacyDirectory.mkdirs()
        File(fixture.legacyDirectory, "legacy-sentinel").writeText("legacy")
        val manager = manager(fixture) { file, destination, _, onBytesWritten ->
            val corrupt = ByteArray(file.bytes.toInt()) { 0x7f }
            destination.writeBytes(corrupt)
            onBytesWritten(corrupt.size.toLong())
        }

        val failure = runCatching { manager.install() }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(LocalAsrModelStatus.NotInstalled, manager.status())
        assertFalse(fixture.installedDirectory.exists())
        assertFalse(fixture.stagingDirectory.exists())
        assertTrue(fixture.legacyDirectory.isDirectory)
    }

    @Test
    fun downloaderFailureAfterPartialWriteCleansStaging() {
        val fixture = fixture()
        val manager = manager(fixture) { _, destination, _, onBytesWritten ->
            destination.writeBytes(byteArrayOf(1, 2, 3))
            onBytesWritten(3L)
            throw IOException("connection lost")
        }

        val failure = runCatching { manager.install() }.exceptionOrNull()

        assertEquals("connection lost", failure?.message)
        assertEquals(LocalAsrModelStatus.NotInstalled, manager.status())
        assertFalse(fixture.installedDirectory.exists())
        assertFalse(fixture.stagingDirectory.exists())
    }

    @Test
    fun cancellationCleansCompletedAndPartialStagingFiles() {
        val fixture = fixture()
        var cancelled = false
        val manager = manager(fixture) { file, destination, _, onBytesWritten ->
            val bytes = fixture.content.getValue(file.localName)
            destination.writeBytes(bytes)
            onBytesWritten(bytes.size.toLong())
            cancelled = true
        }

        val failure = runCatching { manager.install(isCancelled = { cancelled }) }.exceptionOrNull()

        assertTrue(failure is LocalAsrInstallCancelledException)
        assertEquals(LocalAsrModelStatus.NotInstalled, manager.status())
        assertFalse(fixture.stagingDirectory.exists())
    }

    @Test
    fun readyModelIsReusedWithoutNetworkAndRemoveIsExplicit() {
        val fixture = fixture()
        var downloadCount = 0
        val manager = manager(fixture) { file, destination, _, onBytesWritten ->
            downloadCount += 1
            val bytes = fixture.content.getValue(file.localName)
            destination.writeBytes(bytes)
            onBytesWritten(bytes.size.toLong())
        }
        manager.install()

        manager.install()

        assertEquals(fixture.definition.files.size, downloadCount)
        assertTrue(manager.remove())
        assertEquals(LocalAsrModelStatus.NotInstalled, manager.status())
        assertFalse(manager.remove())
    }

    @Test
    fun statusRejectsChangedInstalledManifestOrModelBytes() {
        val fixture = fixture()
        val manager = manager(fixture) { file, destination, _, onBytesWritten ->
            val bytes = fixture.content.getValue(file.localName)
            destination.writeBytes(bytes)
            onBytesWritten(bytes.size.toLong())
        }
        val installed = manager.install()
        File(installed.directory, fixture.definition.files.first().localName).appendText("changed")

        val status = manager.status()

        assertTrue(status is LocalAsrModelStatus.Invalid)
    }

    @Test
    fun statusCleansAnInstallStagingTreeLeftByProcessDeath() {
        val fixture = fixture()
        fixture.stagingDirectory.mkdirs()
        File(fixture.stagingDirectory, "encoder.int8.onnx.part").writeText("partial")
        val manager = manager(fixture) { _, _, _, _ ->
            error("Status cleanup must not start a download.")
        }

        assertEquals(LocalAsrModelStatus.NotInstalled, manager.status())
        assertFalse(fixture.stagingDirectory.exists())
    }

    @Test
    fun statusRemovesLegacyModelAfterRevalidatingPreprovisionedReplacement() {
        val fixture = fixture()
        fixture.writeInstalledModel()
        fixture.legacyDirectory.mkdirs()
        File(fixture.legacyDirectory, "legacy-sentinel").writeText("legacy")
        val manager = manager(fixture) { _, _, _, _ ->
            error("Status verification must not start a download.")
        }

        assertTrue(manager.status() is LocalAsrModelStatus.Installed)
        assertFalse(fixture.legacyDirectory.exists())
    }

    @Test
    fun insufficientPrivateStorageDoesNotStartOrDisturbEitherModel() {
        val fixture = fixture()
        fixture.legacyDirectory.mkdirs()
        File(fixture.legacyDirectory, "legacy-sentinel").writeText("legacy")
        var downloadCount = 0
        val requiredBytes = fixture.definition.totalBytes + INSTALL_HEADROOM_BYTES
        val manager = manager(
            fixture = fixture,
            availableStorageBytes = requiredBytes - 1L,
        ) { _, _, _, _ ->
            downloadCount += 1
        }

        val failure = runCatching { manager.install() }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(0, downloadCount)
        assertEquals(LocalAsrModelStatus.NotInstalled, manager.status())
        assertFalse(fixture.installedDirectory.exists())
        assertFalse(fixture.stagingDirectory.exists())
        assertTrue(fixture.legacyDirectory.isDirectory)
    }

    private fun manager(
        fixture: Fixture,
        availableStorageBytes: Long = Long.MAX_VALUE,
        download: (LocalAsrModelFile, File, () -> Boolean, (Long) -> Unit) -> Unit,
    ): LocalAsrModelManager = LocalAsrModelManager(
        appFilesDirectory = fixture.appFilesDirectory,
        definition = fixture.definition,
        downloader = ModelArtifactDownloader(download),
        availableStorageBytes = { availableStorageBytes },
    )

    private fun fixture(): Fixture {
        val appFilesDirectory = temporaryFolder.newFolder("app-files")
        val content = linkedMapOf(
            "encoder.int8.onnx" to "small encoder".toByteArray(),
            "decoder.int8.onnx" to "small decoder".toByteArray(),
            "joiner.int8.onnx" to "small joiner".toByteArray(),
            "tokens.txt" to "<blk> 0\nA 1\n".toByteArray(),
        )
        val revision = "1234567890abcdef1234567890abcdef12345678"
        val definition = LocalAsrModelDefinition(
            id = "test-model",
            revision = revision,
            directoryName = "test-model",
            files = content.map { (name, bytes) ->
                LocalAsrModelFile(
                    localName = name,
                    remoteName = "remote-$name",
                    bytes = bytes.size.toLong(),
                    sha256 = sha256(bytes),
                    source = URI("https://example.test/models/$revision/$name"),
                )
            },
        )
        return Fixture(appFilesDirectory, definition, content)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private data class Fixture(
        val appFilesDirectory: File,
        val definition: LocalAsrModelDefinition,
        val content: Map<String, ByteArray>,
    ) {
        val installedDirectory = File(
            File(appFilesDirectory, "transcription-models"),
            definition.directoryName,
        )
        val stagingDirectory = File(
            File(appFilesDirectory, "transcription-models"),
            ".${definition.directoryName}.installing",
        )
        val legacyDirectory = File(
            File(appFilesDirectory, "transcription-models"),
            "zipformer-gigaspeech",
        )

        fun writeInstalledModel() {
            check(installedDirectory.mkdirs())
            content.forEach { (name, bytes) ->
                File(installedDirectory, name).writeBytes(bytes)
            }
            File(installedDirectory, LocalAsrModelManifest.INSTALLED_MANIFEST_FILE)
                .writeText(definition.installedManifestText)
        }
    }

    private companion object {
        const val INSTALL_HEADROOM_BYTES = 1_073_741_824L
    }
}
