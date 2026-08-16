package com.wivy.dreamlog.transcription.model

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

internal data class LocalAsrModelFile(
    val localName: String,
    val remoteName: String,
    val bytes: Long,
    val sha256: String,
    val source: URI,
) {
    init {
        require(SAFE_FILE_NAME.matches(localName)) { "Invalid local model file name." }
        require(SAFE_FILE_NAME.matches(remoteName)) { "Invalid remote model file name." }
        require(bytes > 0L) { "Model files must be non-empty." }
        require(SHA_256.matches(sha256)) { "Invalid SHA-256 value." }
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

internal data class LocalAsrModelDefinition(
    val id: String,
    val revision: String,
    val directoryName: String,
    val files: List<LocalAsrModelFile>,
) {
    init {
        require(SAFE_IDENTIFIER.matches(id)) { "Invalid model identifier." }
        require(SHA_1.matches(revision)) { "The model revision must be a full commit hash." }
        require(SAFE_IDENTIFIER.matches(directoryName)) { "Invalid model directory name." }
        require(files.isNotEmpty()) { "The model manifest must contain files." }
        require(files.map(LocalAsrModelFile::localName).distinct().size == files.size) {
            "Model file names must be unique."
        }
        require(files.map(LocalAsrModelFile::source).distinct().size == files.size) {
            "Model source URLs must be unique."
        }
    }

    val totalBytes: Long = files.sumOf(LocalAsrModelFile::bytes)

    val identityText: String = buildString {
        appendLine("id=$id")
        appendLine("revision=$revision")
        files.forEach { file ->
            append("file=")
            append(file.localName)
            append('|')
            append(file.bytes)
            append('|')
            appendLine(file.sha256)
        }
    }

    val identitySha256: String = MessageDigest.getInstance("SHA-256")
        .digest(identityText.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    val installedManifestText: String = buildString {
        appendLine("schemaVersion=1")
        appendLine("id=$id")
        appendLine("revision=$revision")
        appendLine("totalModelBytes=$totalBytes")
        files.forEach { file ->
            append("file=")
            append(file.localName)
            append('|')
            append(file.remoteName)
            append('|')
            append(file.bytes)
            append('|')
            append(file.sha256)
            append('|')
            appendLine(file.source.toASCIIString())
        }
    }

    private companion object {
        val SAFE_IDENTIFIER = Regex("[a-z0-9][a-z0-9._-]*")
        val SHA_1 = Regex("[0-9a-f]{40}")
    }
}

/** The selected local ASR model. URLs cannot be supplied by app or user input. */
internal object LocalAsrModelManifest {
    const val ID = "sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming"
    const val REVISION = "8c3a10fb13408c7a7054f6898958bf1c64a8d6c7"
    const val DIRECTORY_NAME = "parakeet-unified-en-0.6b-int8-non-streaming"
    const val INSTALLED_MANIFEST_FILE = "model-manifest.txt"
    const val MODEL_SHA256 = "7f26c438ef27dbc07f392996edf1464017eb64c0c8dc2d7d7a7f410d17f1a496"

    private const val REPOSITORY =
        "https://huggingface.co/csukuangfj2/" +
            "sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming"

    val definition = LocalAsrModelDefinition(
        id = ID,
        revision = REVISION,
        directoryName = DIRECTORY_NAME,
        files = listOf(
            modelFile(
                localName = "encoder.int8.onnx",
                remoteName = "encoder.int8.onnx",
                bytes = 654_040_552L,
                sha256 = "6716910b7a0833997fec7a410494c995d70124001a0e9b66d6370d6aced577e0",
            ),
            modelFile(
                localName = "decoder.int8.onnx",
                remoteName = "decoder.int8.onnx",
                bytes = 7_257_753L,
                sha256 = "a5e223392c90e75f8144cdb5eb95af7625db389e39edef2bd1a9c872b3298fe6",
            ),
            modelFile(
                localName = "joiner.int8.onnx",
                remoteName = "joiner.int8.onnx",
                bytes = 1_735_860L,
                sha256 = "869f43f7d24595c55581ad3bf249a935fb8a71389fbdaa7504b9f46f93140f8a",
            ),
            modelFile(
                localName = "tokens.txt",
                remoteName = "tokens.txt",
                bytes = 8_952L,
                sha256 = "dc0b4584ab2e4ddbf888425c076c61b736e7356a015250db7d307e6f1a8188ff",
            ),
        ),
    ).also { manifest ->
        require(manifest.identitySha256 == MODEL_SHA256) {
            "The selected model identity does not match its pinned manifest."
        }
    }

    private fun modelFile(
        localName: String,
        remoteName: String,
        bytes: Long,
        sha256: String,
    ): LocalAsrModelFile = LocalAsrModelFile(
        localName = localName,
        remoteName = remoteName,
        bytes = bytes,
        sha256 = sha256,
        source = URI("$REPOSITORY/resolve/$REVISION/$remoteName?download=true"),
    )
}

internal data class InstalledLocalAsrModel(
    val directory: File,
    val revision: String,
    val modelSha256: String,
    val totalModelBytes: Long,
) {
    fun file(localName: String): File = File(directory, localName)

    val encoderFile: File = file("encoder.int8.onnx")
    val decoderFile: File = file("decoder.int8.onnx")
    val joinerFile: File = file("joiner.int8.onnx")
    val tokensFile: File = file("tokens.txt")
}

internal sealed interface LocalAsrModelStatus {
    data object NotInstalled : LocalAsrModelStatus

    data class Installed(val model: InstalledLocalAsrModel) : LocalAsrModelStatus

    data class Invalid(val reason: String) : LocalAsrModelStatus
}

internal data class LocalAsrInstallProgress(
    val currentFile: String,
    val completedBytes: Long,
    val totalBytes: Long,
)

internal class LocalAsrInstallCancelledException : IOException("Model installation cancelled.")

internal fun interface ModelArtifactDownloader {
    @Throws(IOException::class)
    fun download(
        file: LocalAsrModelFile,
        destination: File,
        isCancelled: () -> Boolean,
        onBytesWritten: (Long) -> Unit,
    )
}

internal class LocalAsrModelManager internal constructor(
    appFilesDirectory: File,
    private val definition: LocalAsrModelDefinition,
    private val downloader: ModelArtifactDownloader,
    private val availableStorageBytes: (File) -> Long,
) {
    constructor(appFilesDirectory: File) : this(
        appFilesDirectory = appFilesDirectory,
        definition = LocalAsrModelManifest.definition,
        downloader = HttpsModelArtifactDownloader(),
        availableStorageBytes = File::getUsableSpace,
    )

    private val modelsDirectory = File(appFilesDirectory, MODELS_DIRECTORY)
    private val installedDirectory = File(modelsDirectory, definition.directoryName)
    private val stagingDirectory = File(modelsDirectory, ".${definition.directoryName}.installing")
    private val legacyZipformerDirectory = File(modelsDirectory, LEGACY_ZIPFORMER_DIRECTORY)

    @Synchronized
    fun status(): LocalAsrModelStatus {
        if (stagingDirectory.exists()) {
            runCatching { deleteTree(stagingDirectory) }
                .getOrElse {
                    return LocalAsrModelStatus.Invalid(
                        "An incomplete model installation could not be removed.",
                    )
                }
        }
        if (!installedDirectory.exists()) return LocalAsrModelStatus.NotInstalled
        val problem = verifyDirectory(installedDirectory)
        return if (problem == null) {
            LocalAsrModelStatus.Installed(installedModel()).also {
                // A developer may provision the already-verified replacement directly into
                // app-private storage. Once that tree has passed the same full verification,
                // the superseded M04 model is no longer needed.
                runCatching { deleteTree(legacyZipformerDirectory) }
            }
        } else {
            LocalAsrModelStatus.Invalid(problem)
        }
    }

    /**
     * Installs the fixed selected model. Call from a background thread.
     *
     * Cancellation is cooperative and leaves no partial model or staging directory behind.
     */
    @Synchronized
    @Throws(IOException::class)
    fun install(
        isCancelled: () -> Boolean = { false },
        onProgress: (LocalAsrInstallProgress) -> Unit = {},
    ): InstalledLocalAsrModel {
        val existingStatus = status()
        if (existingStatus is LocalAsrModelStatus.Installed) {
            deleteTree(legacyZipformerDirectory)
            return existingStatus.model
        }

        ensureModelsDirectory()
        deleteTree(stagingDirectory)
        requireInstallStorageHeadroom()
        checkCancelled(isCancelled)
        if (!stagingDirectory.mkdir()) {
            throw IOException("Could not create the model staging directory.")
        }

        var completedBytes = 0L
        try {
            definition.files.forEach { file ->
                checkCancelled(isCancelled)
                val partial = File(stagingDirectory, "${file.localName}.part")
                val completed = File(stagingDirectory, file.localName)
                downloader.download(
                    file = file,
                    destination = partial,
                    isCancelled = isCancelled,
                    onBytesWritten = { written ->
                        if (written < 0L || completedBytes + written > definition.totalBytes) {
                            throw IOException("The model download reported an invalid byte count.")
                        }
                        completedBytes += written
                        onProgress(
                            LocalAsrInstallProgress(
                                currentFile = file.localName,
                                completedBytes = completedBytes,
                                totalBytes = definition.totalBytes,
                            ),
                        )
                    },
                )
                checkCancelled(isCancelled)
                verifyFile(partial, file)?.let { problem -> throw IOException(problem) }
                moveAtomically(partial, completed)
            }
            if (completedBytes != definition.totalBytes) {
                throw IOException("The model download reported an incomplete byte count.")
            }
            writeInstalledManifest(stagingDirectory)
            verifyDirectory(stagingDirectory)?.let { problem -> throw IOException(problem) }
            checkCancelled(isCancelled)

            // A complete verified staging tree becomes visible under the stable name in one move.
            deleteTree(installedDirectory)
            moveAtomically(stagingDirectory, installedDirectory)
            return installedModel().also {
                // The exact M04 model remains usable until the replacement is fully verified and
                // promoted. It is removed only after that successful side-by-side installation.
                deleteTree(legacyZipformerDirectory)
            }
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

    private fun installedModel(): InstalledLocalAsrModel = InstalledLocalAsrModel(
        directory = installedDirectory,
        revision = definition.revision,
        modelSha256 = definition.identitySha256,
        totalModelBytes = definition.totalBytes,
    )

    private fun ensureModelsDirectory() {
        if (modelsDirectory.isDirectory) return
        if (modelsDirectory.exists() || !modelsDirectory.mkdirs()) {
            throw IOException("Could not create the private model directory.")
        }
    }

    private fun requireInstallStorageHeadroom() {
        val requiredBytes = Math.addExact(
            definition.totalBytes,
            INSTALL_STORAGE_HEADROOM_BYTES,
        )
        val availableBytes = availableStorageBytes(modelsDirectory)
        if (availableBytes < requiredBytes) {
            throw IOException(
                "Not enough private storage to stage the local transcription model while " +
                    "preserving capture headroom.",
            )
        }
    }

    private fun writeInstalledManifest(directory: File) {
        val partial = File(directory, "${LocalAsrModelManifest.INSTALLED_MANIFEST_FILE}.part")
        FileOutputStream(partial).use { output ->
            output.write(definition.installedManifestText.toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        moveAtomically(partial, File(directory, LocalAsrModelManifest.INSTALLED_MANIFEST_FILE))
    }

    private fun verifyDirectory(directory: File): String? {
        if (!directory.isDirectory) return "The installed model path is not a directory."
        val expectedNames = definition.files.mapTo(mutableSetOf(), LocalAsrModelFile::localName)
            .apply { add(LocalAsrModelManifest.INSTALLED_MANIFEST_FILE) }
        val actualNames = directory.listFiles()?.mapTo(mutableSetOf(), File::getName)
            ?: return "The installed model directory could not be read."
        if (actualNames != expectedNames) return "The installed model directory has unexpected files."

        val manifest = File(directory, LocalAsrModelManifest.INSTALLED_MANIFEST_FILE)
        if (!manifest.isFile) return "The installed model manifest is missing."
        val manifestText = runCatching { manifest.readText(StandardCharsets.UTF_8) }
            .getOrElse { return "The installed model manifest could not be read." }
        if (manifestText != definition.installedManifestText) {
            return "The installed model manifest does not match the selected revision."
        }
        definition.files.forEach { file ->
            verifyFile(File(directory, file.localName), file)?.let { return it }
        }
        return null
    }

    private fun verifyFile(file: File, expected: LocalAsrModelFile): String? {
        if (!file.isFile) return "Model file is missing: ${expected.localName}."
        if (file.length() != expected.bytes) {
            return "Model file has the wrong size: ${expected.localName}."
        }
        val actualSha256 = runCatching { sha256(file) }
            .getOrElse { return "Model file could not be verified: ${expected.localName}." }
        if (actualSha256 != expected.sha256) {
            return "Model file failed verification: ${expected.localName}."
        }
        return null
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw LocalAsrInstallCancelledException()
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
        const val MODELS_DIRECTORY = "transcription-models"
        const val LEGACY_ZIPFORMER_DIRECTORY = "zipformer-gigaspeech"
        const val INSTALL_STORAGE_HEADROOM_BYTES = 1_073_741_824L
        const val BUFFER_BYTES = 1024 * 1024
    }
}

internal class HttpsModelArtifactDownloader : ModelArtifactDownloader {
    override fun download(
        file: LocalAsrModelFile,
        destination: File,
        isCancelled: () -> Boolean,
        onBytesWritten: (Long) -> Unit,
    ) {
        requireHttps(file.source)
        if (destination.exists() && !destination.delete()) {
            throw IOException("Could not clear a partial model download.")
        }
        destination.parentFile?.let { parent ->
            if (!parent.isDirectory) throw IOException("The model staging directory is missing.")
        }

        var current = file.source
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            if (isCancelled()) throw LocalAsrInstallCancelledException()
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
            if (advertisedBytes > file.bytes) {
                connection.disconnect()
                throw IOException("The model download is larger than its manifest entry.")
            }

            try {
                connection.inputStream.buffered(BUFFER_BYTES).use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var total = 0L
                        while (true) {
                            if (isCancelled()) throw LocalAsrInstallCancelledException()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            if (total > file.bytes) {
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
