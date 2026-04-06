package com.codeodyssey.retrodriveaa

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeviceUploadActivity : ComponentActivity() {

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return@registerForActivityResult
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                importSelectedUri(uri)
            }

            Toast.makeText(
                this@DeviceUploadActivity,
                result.message,
                if (result.success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
            ).show()

            if (result.success) {
                sendBroadcast(
                    Intent(ACTION_LIBRARY_CHANGED).setPackage(packageName)
                )
            }

            setResult(
                if (result.success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Intent().putExtra(EXTRA_RESULT_MESSAGE, result.message)
            )
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            openDocumentLauncher.launch(
                arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/octet-stream"
                )
            )
        }
    }

    private fun importSelectedUri(uri: Uri): GameImportManager.ImportResult {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val filename = resolveDisplayName(uri) ?: "device_upload.zip"
        val stream = contentResolver.openInputStream(uri)
            ?: return GameImportManager.ImportResult(false, "Could not open selected file")

        stream.use {
            return GameImportManager.importUploadedStream(this, filename, it)
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (columnIndex != -1 && cursor.moveToFirst()) {
                cursor.getString(columnIndex)
            } else {
                null
            }
        }
    }

    companion object {
        const val ACTION_LIBRARY_CHANGED = "com.codeodyssey.retrodriveaa.action.LIBRARY_CHANGED"
        const val EXTRA_RESULT_MESSAGE = "com.codeodyssey.retrodriveaa.extra.RESULT_MESSAGE"

        fun createIntent(context: Context): Intent {
            return Intent(context, DeviceUploadActivity::class.java)
        }
    }
}