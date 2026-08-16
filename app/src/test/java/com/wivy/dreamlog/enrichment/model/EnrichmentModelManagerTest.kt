package com.wivy.dreamlog.enrichment.model

import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EnrichmentModelManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun selectedManifestPinsExactArtifactProvenanceAndLicense() {
        val definition = EnrichmentModelManifest.definition
        val expectedSource = URI(
            "https://huggingface.co/litert-community/Qwen3-4B-Instruct-2507/resolve/" +
                "a7385088ed97778d7cf91a0b541fa1f95735f768/" +
                "qwen3_4b_instruct_2507_mixed_int4.litertlm?download=true",
        )

        assertEquals("qwen3-4b-instruct-2507-mixed-int4", definition.id)
        assertEquals("a7385088ed97778d7cf91a0b541fa1f95735f768", definition.revision)
        assertEquals("qwen3_4b_instruct_2507_mixed_int4.litertlm", definition.artifact.localName)
        assertEquals(2_659_057_664L, definition.artifact.bytes)
        assertEquals(
            "9e48b165836256f5344d9d044930607b9c47f6ef34e27f82e96881664f3ba2fd",
            definition.artifact.sha256,
        )
        assertEquals(expectedSource, definition.artifact.source)
        assertEquals("Apache-2.0", definition.license.spdxIdentifier)
        assertEquals(URI("https://www.apache.org/licenses/LICENSE-2.0"), definition.license.source)
        assertTrue(definition.artifact.source.scheme == "https")
        assertNull(definition.artifact.source.userInfo)
        assertTrue(definition.installedManifestText.contains("licenseSpdx=Apache-2.0\n"))
        assertTrue(definition.installedManifestText.contains("modelBytes=2659057664\n"))
    }

    @Test
    fun installStagesVerifiesAndAtomicallyPromotesArtifact() {
        val fixture = fixture()
        val downloads = mutableListOf<URI>()
        val progress = mutableListOf<EnrichmentInstallProgress>()
        val manager = manager(fixture) { artifact, destination, _, onBytesWritten ->
            assertFalse(fixture.installedDirectory.isDirectory)
            downloads += artifact.source
            destination.writeBytes(fixture.content)
            onBytesWritten(fixture.content.size.toLong())
        }

        val installed = manager.install(onProgress = progress::add)

        assertEquals(fixture.definition.id, installed.id)
        assertEquals(fixture.definition.revision, installed.revision)
        assertEquals(fixture.definition.artifact.sha256, installed.artifactSha256)
        assertEquals(fixture.definition.artifact.bytes, installed.artifactBytes)
        assertEquals(fixture.definition.license.spdxIdentifier, installed.licenseSpdxIdentifier)
        assertEquals(fixture.definition.license.source, installed.licenseSource)
        assertTrue(installed.modelFile.isFile)
        assertArrayEquals(fixture.content, installed.modelFile.readBytes())
        assertEquals(listOf(fixture.definition.artifact.source), downloads)
        assertEquals(fixture.definition.artifact.localName, progress.last().artifactName)
        assertEquals(fixture.definition.artifact.bytes, progress.last().completedBytes)
        assertEquals(fixture.definition.artifact.bytes, progress.last().totalBytes)
        assertEquals(
            fixture.definition.installedManifestText,
            File(installed.modelFile.parentFile, EnrichmentModelManifest.INSTALLED_MANIFEST_FILE)
                .readText(),
        )
        assertTrue(manager.status() is EnrichmentModelStatus.Installed)
        assertFalse(fixture.stagingDirectory.exists())
    }

    @Test
    fun newManagerVerifiesAndReusesCachedArtifactWithoutDownloading() {
        val fixture = fixture()
        var downloadCount = 0
        manager(fixture) { _, destination, _, onBytesWritten ->
            downloadCount += 1
            destination.writeBytes(fixture.content)
            onBytesWritten(fixture.content.size.toLong())
        }.install()
        val verifier = manager(fixture) { _, _, _, _ ->
            error("A verified cached artifact must not be downloaded again.")
        }

        val status = verifier.status()
        val installedAgain = verifier.install()

        assertTrue(status is EnrichmentModelStatus.Installed)
        assertEquals(1, downloadCount)
        assertTrue(installedAgain.modelFile.isFile)
    }

    @Test
    fun installRejectsCorruptArtifactAndCleansPartialState() {
        val fixture = fixture()
        val manager = manager(fixture) { artifact, destination, _, onBytesWritten ->
            val corrupt = ByteArray(artifact.bytes.toInt()) { 0x7f }
            destination.writeBytes(corrupt)
            onBytesWritten(corrupt.size.toLong())
        }

        val failure = runCatching { manager.install() }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(EnrichmentModelStatus.NotInstalled, manager.status())
        assertFalse(fixture.installedDirectory.exists())
        assertFalse(fixture.stagingDirectory.exists())
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
        assertEquals(EnrichmentModelStatus.NotInstalled, manager.status())
        assertFalse(fixture.stagingDirectory.exists())
    }

    @Test
    fun cancellationCleansCompletedAndPartialStagingFiles() {
        val fixture = fixture()
        var cancelled = false
        val manager = manager(fixture) { _, destination, _, onBytesWritten ->
            destination.writeBytes(fixture.content)
            onBytesWritten(fixture.content.size.toLong())
            cancelled = true
        }

        val failure = runCatching {
            manager.install(isCancelled = { cancelled })
        }.exceptionOrNull()

        assertTrue(failure is EnrichmentInstallCancelledException)
        assertEquals(EnrichmentModelStatus.NotInstalled, manager.status())
        assertFalse(fixture.stagingDirectory.exists())
    }

    @Test
    fun invalidProgressCannotPromoteArtifact() {
        val fixture = fixture()
        val manager = manager(fixture) { artifact, destination, _, onBytesWritten ->
            destination.writeBytes(fixture.content)
            onBytesWritten(artifact.bytes + 1L)
        }

        val failure = runCatching { manager.install() }.exceptionOrNull()

        assertEquals("The model download reported an invalid byte count.", failure?.message)
        assertEquals(EnrichmentModelStatus.NotInstalled, manager.status())
        assertFalse(fixture.stagingDirectory.exists())
    }

    @Test
    fun changedCachedBytesAreRejectedAndRemoveIsExplicit() {
        val fixture = fixture()
        val manager = manager(fixture) { _, destination, _, onBytesWritten ->
            destination.writeBytes(fixture.content)
            onBytesWritten(fixture.content.size.toLong())
        }
        val installed = manager.install()
        val changed = installed.modelFile.readBytes().also { bytes -> bytes[0] = (bytes[0] + 1).toByte() }
        installed.modelFile.writeBytes(changed)

        val status = manager.status()

        assertTrue(status is EnrichmentModelStatus.Invalid)
        assertTrue(manager.remove())
        assertEquals(EnrichmentModelStatus.NotInstalled, manager.status())
        assertFalse(manager.remove())
    }

    @Test
    fun changedCachedManifestIsRejected() {
        val fixture = fixture()
        val manager = manager(fixture) { _, destination, _, onBytesWritten ->
            destination.writeBytes(fixture.content)
            onBytesWritten(fixture.content.size.toLong())
        }
        val installed = manager.install()
        File(installed.modelFile.parentFile, EnrichmentModelManifest.INSTALLED_MANIFEST_FILE)
            .appendText("changed=true\n")

        assertTrue(manager.status() is EnrichmentModelStatus.Invalid)
    }

    @Test
    fun statusCleansStagingTreeLeftByProcessDeath() {
        val fixture = fixture()
        fixture.stagingDirectory.mkdirs()
        File(fixture.stagingDirectory, "${fixture.definition.artifact.localName}.part")
            .writeText("partial")
        val manager = manager(fixture) { _, _, _, _ ->
            error("Status cleanup must not start a download.")
        }

        assertEquals(EnrichmentModelStatus.NotInstalled, manager.status())
        assertFalse(fixture.stagingDirectory.exists())
    }

    private fun manager(
        fixture: Fixture,
        download: (EnrichmentModelArtifact, File, () -> Boolean, (Long) -> Unit) -> Unit,
    ): EnrichmentModelManager = EnrichmentModelManager(
        appFilesDirectory = fixture.appFilesDirectory,
        definition = fixture.definition,
        downloader = EnrichmentArtifactDownloader(download),
    )

    private fun fixture(): Fixture {
        val appFilesDirectory = temporaryFolder.newFolder("app-files")
        val content = "small model artifact".toByteArray()
        val revision = "1234567890abcdef1234567890abcdef12345678"
        val artifact = EnrichmentModelArtifact(
            localName = "test-model.litertlm",
            bytes = content.size.toLong(),
            sha256 = sha256(content),
            source = URI("https://example.test/models/$revision/test-model.litertlm"),
        )
        val definition = EnrichmentModelDefinition(
            id = "test-enrichment-model",
            revision = revision,
            directoryName = "test-enrichment-model",
            artifact = artifact,
            license = EnrichmentModelLicense(
                spdxIdentifier = "Apache-2.0",
                displayName = "Apache License 2.0",
                source = URI("https://www.apache.org/licenses/LICENSE-2.0"),
            ),
        )
        return Fixture(appFilesDirectory, definition, content)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private data class Fixture(
        val appFilesDirectory: File,
        val definition: EnrichmentModelDefinition,
        val content: ByteArray,
    ) {
        val installedDirectory = File(
            File(appFilesDirectory, "enrichment-models"),
            definition.directoryName,
        )
        val stagingDirectory = File(
            File(appFilesDirectory, "enrichment-models"),
            ".${definition.directoryName}.installing",
        )
    }
}
