package com.wivy.dreamlog.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Stages owner-requested exports in a narrow cache directory and writes SAF destinations. */
class AndroidExportStore(context: Context) {
    private val appContext = context.applicationContext
    private val exportRoot = File(appContext.cacheDir, EXPORT_DIRECTORY)

    fun createShareChooser(
        document: DreamLogExportDocument,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Intent {
        val uri = stageShareDocument(document, nowEpochMillis)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = document.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(
                appContext.contentResolver,
                "DreamLog export",
                uri,
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, "Share DreamLog export")
    }

    fun writeToUri(document: DreamLogExportDocument, destination: Uri) {
        require(destination.scheme == "content") {
            "DreamLog exports must use a system-selected content destination."
        }
        val output = requireNotNull(
            appContext.contentResolver.openOutputStream(destination, "wt"),
        ) { "The selected destination could not be opened." }
        output.use { stream ->
            stream.write(document.utf8Bytes)
            stream.flush()
        }
    }

    private fun stageShareDocument(
        document: DreamLogExportDocument,
        nowEpochMillis: Long,
    ): Uri {
        require(SAFE_EXPORT_FILE_NAME.matches(document.fileName)) {
            "The export filename is invalid."
        }
        require(document.mimeType in ALLOWED_MIME_TYPES) {
            "The export type is unsupported."
        }
        val canonicalRoot = prepareCanonicalExportRoot()
        pruneExpiredStagedFiles(canonicalRoot, nowEpochMillis)

        val identity = UUID.randomUUID().toString()
        val stagedFile = File(canonicalRoot, "$identity-${document.fileName}").canonicalFile
        require(stagedFile.parentFile == canonicalRoot) {
            "The staged export escaped its private cache directory."
        }
        val partialFile = File(canonicalRoot, ".$identity.part").canonicalFile
        require(partialFile.parentFile == canonicalRoot) {
            "The staged export partial escaped its private cache directory."
        }

        try {
            FileOutputStream(partialFile).use { output ->
                output.write(document.utf8Bytes)
                output.fd.sync()
            }
            Files.move(
                partialFile.toPath(),
                stagedFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            if (partialFile.exists()) runCatching { partialFile.delete() }
        }
        return FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.export-files",
            stagedFile,
            document.fileName,
        )
    }

    private fun prepareCanonicalExportRoot(): File {
        val canonicalCache = appContext.cacheDir.canonicalFile
        val canonicalRoot = exportRoot.canonicalFile
        require(canonicalRoot.parentFile == canonicalCache) {
            "The private export cache escaped the app cache directory."
        }
        check(canonicalRoot.isDirectory || canonicalRoot.mkdirs()) {
            "The private export cache could not be prepared."
        }
        return canonicalRoot
    }

    private fun pruneExpiredStagedFiles(
        canonicalRoot: File,
        nowEpochMillis: Long,
    ) {
        if (nowEpochMillis < 0L) return
        val cutoff = (nowEpochMillis - STAGED_EXPORT_RETENTION_MILLIS).coerceAtLeast(0L)
        canonicalRoot.listFiles().orEmpty()
            .asSequence()
            .filter(File::isFile)
            .filter { file -> file.lastModified() in 1L..cutoff }
            .forEach { file ->
                runCatching {
                    val canonicalFile = file.canonicalFile
                    if (canonicalFile.parentFile == canonicalRoot) canonicalFile.delete()
                }
            }
    }

    private companion object {
        const val EXPORT_DIRECTORY = "exports"
        const val STAGED_EXPORT_RETENTION_MILLIS = 24L * 60L * 60L * 1_000L

        val SAFE_EXPORT_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val ALLOWED_MIME_TYPES = DreamLogExportFormat.entries
            .mapTo(mutableSetOf(), DreamLogExportFormat::mimeType)
    }
}
