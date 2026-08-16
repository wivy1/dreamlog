package com.wivy.dreamlog.enrichment.model

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class EnrichmentModelArtifact(
    val localName: String,
    val bytes: Long,
    val sha256: String,
    val source: URI,
) {
    init {
        require(SAFE_FILE_NAME.matches(localName)) { "Invalid local model file name." }
        require(bytes > 0L) { "The model artifact must be non-empty." }
        require(SHA_256.matches(sha256)) { "Invalid model artifact SHA-256 value." }
        require(source.scheme.equals("https", ignoreCase = true)) {
            "Model sources must use HTTPS."
        }
        require(source.userInfo == null) { "Model sources must not contain credentials." }
    }

    private companion object {
        val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
        val SHA_256 = Regex("[0-9a-f]{64}")
    }
}

internal data class EnrichmentModelLicense(
    val spdxIdentifier: String,
    val displayName: String,
    val source: URI,
) {
    init {
        require(SPDX_IDENTIFIER.matches(spdxIdentifier)) { "Invalid model license identifier." }
        require(displayName.isNotBlank()) { "The model license name must not be blank." }
        require(source.scheme.equals("https", ignoreCase = true)) {
            "The model license source must use HTTPS."
        }
        require(source.userInfo == null) { "The model license source must not contain credentials." }
    }

    private companion object {
        val SPDX_IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9.+-]*")
    }
}

internal data class EnrichmentModelDefinition(
    val id: String,
    val revision: String,
    val directoryName: String,
    val artifact: EnrichmentModelArtifact,
    val license: EnrichmentModelLicense,
) {
    init {
        require(SAFE_IDENTIFIER.matches(id)) { "Invalid model identifier." }
        require(SHA_1.matches(revision)) { "The model revision must be a full commit hash." }
        require(SAFE_IDENTIFIER.matches(directoryName)) { "Invalid model directory name." }
    }

    val installedManifestText: String = buildString {
        appendLine("schemaVersion=1")
        appendLine("id=$id")
        appendLine("revision=$revision")
        appendLine("licenseSpdx=${license.spdxIdentifier}")
        appendLine("licenseName=${license.displayName}")
        appendLine("licenseUrl=${license.source.toASCIIString()}")
        appendLine("modelFile=${artifact.localName}")
        appendLine("modelBytes=${artifact.bytes}")
        appendLine("modelSha256=${artifact.sha256}")
        appendLine("modelUrl=${artifact.source.toASCIIString()}")
    }

    private companion object {
        val SAFE_IDENTIFIER = Regex("[a-z0-9][a-z0-9._-]*")
        val SHA_1 = Regex("[0-9a-f]{40}")
    }
}

/** The fixed M05 selected candidate. No model URL can be supplied by app or user input. */
internal object EnrichmentModelManifest {
    const val ID = "qwen3-4b-instruct-2507-mixed-int4"
    const val REVISION = "a7385088ed97778d7cf91a0b541fa1f95735f768"
    const val DIRECTORY_NAME = "qwen3-4b-instruct-2507-mixed-int4"
    const val INSTALLED_MANIFEST_FILE = "model-manifest.txt"
    const val MODEL_FILE_NAME = "qwen3_4b_instruct_2507_mixed_int4.litertlm"
    const val MODEL_BYTES = 2_659_057_664L
    const val MODEL_SHA256 =
        "9e48b165836256f5344d9d044930607b9c47f6ef34e27f82e96881664f3ba2fd"
    const val LICENSE_SPDX = "Apache-2.0"
    const val LICENSE_NAME = "Apache License 2.0"

    private const val REPOSITORY =
        "https://huggingface.co/litert-community/Qwen3-4B-Instruct-2507"

    val definition = EnrichmentModelDefinition(
        id = ID,
        revision = REVISION,
        directoryName = DIRECTORY_NAME,
        artifact = EnrichmentModelArtifact(
            localName = MODEL_FILE_NAME,
            bytes = MODEL_BYTES,
            sha256 = MODEL_SHA256,
            source = URI("$REPOSITORY/resolve/$REVISION/$MODEL_FILE_NAME?download=true"),
        ),
        license = EnrichmentModelLicense(
            spdxIdentifier = LICENSE_SPDX,
            displayName = LICENSE_NAME,
            source = URI("https://www.apache.org/licenses/LICENSE-2.0"),
        ),
    )
}

internal data class InstalledEnrichmentModel(
    val modelFile: File,
    val id: String,
    val revision: String,
    val artifactSha256: String,
    val artifactBytes: Long,
    val licenseSpdxIdentifier: String,
    val licenseSource: URI,
)

internal sealed interface EnrichmentModelStatus {
    data object NotInstalled : EnrichmentModelStatus

    data class Installed(val model: InstalledEnrichmentModel) : EnrichmentModelStatus

    data class Invalid(val reason: String) : EnrichmentModelStatus
}

internal data class EnrichmentInstallProgress(
    val artifactName: String,
    val completedBytes: Long,
    val totalBytes: Long,
)

internal class EnrichmentInstallCancelledException : IOException("Model installation cancelled.")

internal fun interface EnrichmentArtifactDownloader {
    @Throws(IOException::class)
    fun download(
        artifact: EnrichmentModelArtifact,
        destination: File,
        isCancelled: () -> Boolean,
        onBytesWritten: (Long) -> Unit,
    )
}

internal class EnrichmentModelManager internal constructor(
    appFilesDirectory: File,
    private val definition: EnrichmentModelDefinition,
    private val downloader: EnrichmentArtifactDownloader,
) {
    constructor(appFilesDirectory: File) : this(
        appFilesDirectory = appFilesDirectory,
        definition = EnrichmentModelManifest.definition,
        downloader = HttpsEnrichmentArtifactDownloader(),
    )

    private val modelsDirectory = File(appFilesDirectory, MODELS_DIRECTORY)
    private val installedDirectory = File(modelsDirectory, definition.directoryName)
    private val stagingDirectory = File(modelsDirectory, ".${definition.directoryName}.installing")

    /** Verifies the complete cached artifact. Call from a background thread. */
    @Synchronized
    fun status(): EnrichmentModelStatus = status { false }

    private fun status(isCancelled: () -> Boolean): EnrichmentModelStatus {
        checkCancelled(isCancelled)
        if (stagingDirectory.exists()) {
            runCatching { deleteTree(stagingDirectory) }
                .getOrElse {
                    return EnrichmentModelStatus.Invalid(
                        "An incomplete model installation could not be removed.",
                    )
                }
        }
        if (!installedDirectory.exists()) return EnrichmentModelStatus.NotInstalled
        val problem = verifyDirectory(installedDirectory, isCancelled = isCancelled)
        return if (problem == null) {
            EnrichmentModelStatus.Installed(installedModel())
        } else {
            EnrichmentModelStatus.Invalid(problem)
        }
    }

    /**
     * Installs the fixed selected artifact. Call from a background thread.
     *
     * Cancellation is cooperative and leaves no partial artifact or staging directory behind.
     */
    @Synchronized
    @Throws(IOException::class)
    fun install(
        isCancelled: () -> Boolean = { false },
        onProgress: (EnrichmentInstallProgress) -> Unit = {},
    ): InstalledEnrichmentModel {
        val existingStatus = status(isCancelled)
        if (existingStatus is EnrichmentModelStatus.Installed) return existingStatus.model

        ensureModelsDirectory()
        deleteTree(stagingDirectory)
        checkCancelled(isCancelled)
        if (!stagingDirectory.mkdir()) {
            throw IOException("Could not create the model staging directory.")
        }

        var completedBytes = 0L
        try {
            val artifact = definition.artifact
            val partial = File(stagingDirectory, "${artifact.localName}.part")
            val completed = File(stagingDirectory, artifact.localName)
            downloader.download(
                artifact = artifact,
                destination = partial,
                isCancelled = isCancelled,
                onBytesWritten = { written ->
                    if (written < 0L || completedBytes + written > artifact.bytes) {
                        throw IOException("The model download reported an invalid byte count.")
                    }
                    completedBytes += written
                    onProgress(
                        EnrichmentInstallProgress(
                            artifactName = artifact.localName,
                            completedBytes = completedBytes,
                            totalBytes = artifact.bytes,
                        ),
                    )
                },
            )
            checkCancelled(isCancelled)
            verifyFile(partial, artifact, isCancelled)?.let { problem -> throw IOException(problem) }
            moveAtomically(partial, completed)
            if (completedBytes != artifact.bytes) {
                throw IOException("The model download reported an incomplete byte count.")
            }
            writeInstalledManifest(stagingDirectory)
            verifyDirectory(
                directory = stagingDirectory,
                isCancelled = isCancelled,
                artifactShaAlreadyVerified = true,
            )?.let { problem -> throw IOException(problem) }
            checkCancelled(isCancelled)

            // Only a complete, verified staging directory becomes visible under the stable name.
            deleteTree(installedDirectory)
            moveAtomically(stagingDirectory, installedDirectory)
            return installedModel()
        } catch (failure: Throwable) {
            runCatching { deleteTree(stagingDirectory) }
                .onFailure(failure::addSuppressed)
            throw failure
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun remove(): Boolean {
        val existed = installedDirectory.exists() || stagingDirectory.exists()
        deleteTree(stagingDirectory)
        deleteTree(installedDirectory)
        return existed
    }

    private fun installedModel(): InstalledEnrichmentModel = InstalledEnrichmentModel(
        modelFile = File(installedDirectory, definition.artifact.localName),
        id = definition.id,
        revision = definition.revision,
        artifactSha256 = definition.artifact.sha256,
        artifactBytes = definition.artifact.bytes,
        licenseSpdxIdentifier = definition.license.spdxIdentifier,
        licenseSource = definition.license.source,
    )

    private fun ensureModelsDirectory() {
        if (modelsDirectory.isDirectory) return
        if (modelsDirectory.exists() || !modelsDirectory.mkdirs()) {
            throw IOException("Could not create the private model directory.")
        }
    }

    private fun writeInstalledManifest(directory: File) {
        val partial = File(directory, "${EnrichmentModelManifest.INSTALLED_MANIFEST_FILE}.part")
        FileOutputStream(partial).use { output ->
            output.write(definition.installedManifestText.toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        moveAtomically(partial, File(directory, EnrichmentModelManifest.INSTALLED_MANIFEST_FILE))
    }

    private fun verifyDirectory(
        directory: File,
        isCancelled: () -> Boolean = { false },
        artifactShaAlreadyVerified: Boolean = false,
    ): String? {
        checkCancelled(isCancelled)
        if (!directory.isDirectory) return "The installed model path is not a directory."
        val expectedNames = setOf(
            definition.artifact.localName,
            EnrichmentModelManifest.INSTALLED_MANIFEST_FILE,
        )
        val actualNames = directory.listFiles()?.mapTo(mutableSetOf(), File::getName)
            ?: return "The installed model directory could not be read."
        if (actualNames != expectedNames) return "The installed model directory has unexpected files."

        val manifest = File(directory, EnrichmentModelManifest.INSTALLED_MANIFEST_FILE)
        if (!manifest.isFile) return "The installed model manifest is missing."
        val manifestText = runCatching { manifest.readText(StandardCharsets.UTF_8) }
            .getOrElse { return "The installed model manifest could not be read." }
        if (manifestText != definition.installedManifestText) {
            return "The installed model manifest does not match the selected revision."
        }
        val artifactFile = File(directory, definition.artifact.localName)
        if (!artifactFile.isFile) return "The model artifact is missing."
        if (artifactFile.length() != definition.artifact.bytes) {
            return "The model artifact has the wrong size."
        }
        return if (artifactShaAlreadyVerified) {
            null
        } else {
            verifyFile(artifactFile, definition.artifact, isCancelled)
        }
    }

    private fun verifyFile(
        file: File,
        expected: EnrichmentModelArtifact,
        isCancelled: () -> Boolean = { false },
    ): String? {
        if (!file.isFile) return "The model artifact is missing."
        if (file.length() != expected.bytes) return "The model artifact has the wrong size."
        val actualSha256 = try {
            sha256(file, isCancelled)
        } catch (cancelled: EnrichmentInstallCancelledException) {
            throw cancelled
        } catch (_: Throwable) {
            return "The model artifact could not be verified."
        }
        if (actualSha256 != expected.sha256) return "The model artifact failed verification."
        return null
    }

    private fun sha256(
        file: File,
        isCancelled: () -> Boolean,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                checkCancelled(isCancelled)
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        checkCancelled(isCancelled)
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw EnrichmentInstallCancelledException()
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            throw IOException("The private model directory does not support atomic installation.", unsupported)
        }
    }

    private fun deleteTree(target: File) {
        if (!target.exists()) return
        if (!target.deleteRecursively() && target.exists()) {
            throw IOException("Could not clean the private model staging area.")
        }
    }

    private companion object {
        const val MODELS_DIRECTORY = "enrichment-models"
        const val BUFFER_BYTES = 1024 * 1024
    }
}

internal class HttpsEnrichmentArtifactDownloader : EnrichmentArtifactDownloader {
    override fun download(
        artifact: EnrichmentModelArtifact,
        destination: File,
        isCancelled: () -> Boolean,
        onBytesWritten: (Long) -> Unit,
    ) {
        requireHttps(artifact.source)
        if (destination.exists() && !destination.delete()) {
            throw IOException("Could not clear a partial model download.")
        }
        destination.parentFile?.let { parent ->
            if (!parent.isDirectory) throw IOException("The model staging directory is missing.")
        }

        var current = artifact.source
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            if (isCancelled()) throw EnrichmentInstallCancelledException()
            requireHttps(current)
            val connection = current.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("User-Agent", "DreamLog local model installer")
            val responseCode = try {
                connection.responseCode
            } catch (failure: IOException) {
                connection.disconnect()
                throw failure
            }

            if (responseCode in REDIRECT_CODES) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (redirectCount == MAX_REDIRECTS || location.isNullOrBlank()) {
                    throw IOException("The model download returned too many redirects.")
                }
                current = current.resolve(location)
                return@repeat
            }
            if (responseCode !in 200..299) {
                connection.disconnect()
                throw IOException("The model download failed with HTTP $responseCode.")
            }
            val advertisedBytes = connection.contentLengthLong
            if (advertisedBytes > artifact.bytes) {
                connection.disconnect()
                throw IOException("The model download is larger than its manifest entry.")
            }

            try {
                connection.inputStream.buffered(BUFFER_BYTES).use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var total = 0L
                        while (true) {
                            if (isCancelled()) throw EnrichmentInstallCancelledException()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            if (total > artifact.bytes) {
                                throw IOException("The model download exceeded its manifest size.")
                            }
                            output.write(buffer, 0, count)
                            onBytesWritten(count.toLong())
                        }
                        output.fd.sync()
                    }
                }
            } finally {
                connection.disconnect()
            }
            return
        }
        throw IOException("The model download did not reach a file response.")
    }

    private fun requireHttps(uri: URI) {
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) {
            throw IOException("The model download was redirected to an unsafe location.")
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 8
        const val CONNECT_TIMEOUT_MILLIS = 20_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val BUFFER_BYTES = 1024 * 1024
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
