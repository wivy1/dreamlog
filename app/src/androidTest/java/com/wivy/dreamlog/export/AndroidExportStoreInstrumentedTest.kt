package com.wivy.dreamlog.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidExportStoreInstrumentedTest {
    @Test
    fun sharedAttachmentUsesTheRequestedDisplayName() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val document = DreamLogExportDocument(
            fileName = "dreamlog-export.txt",
            mimeType = "text/plain",
            content = "2 nights\n2026-08-11 - 2026-08-12\n",
        )

        val chooser = AndroidExportStore(context).createShareChooser(
            document = document,
            nowEpochMillis = 1_800_000_000_000L,
        )
        val sendIntent = requireNotNull(
            chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java),
        )
        val uri = requireNotNull(
            sendIntent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java),
        )
        val displayName = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }

        assertEquals(document.fileName, displayName)
    }
}
