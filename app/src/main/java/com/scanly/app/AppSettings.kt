package com.scanly.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _saveTree = MutableStateFlow(loadTree())

    /** Folder chosen by the user for exported PDFs, or null for the default Downloads/Scanly. */
    val saveTree: StateFlow<Uri?> = _saveTree.asStateFlow()

    private fun loadTree(): Uri? {
        val uri = prefs.getString(KEY_TREE, null)?.let(Uri::parse) ?: return null
        val stillGranted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (!stillGranted) {
            prefs.edit().remove(KEY_TREE).apply()
            return null
        }
        return uri
    }

    /** Returns false if the folder permission could not be kept. Pass null to go back to the default. */
    fun setSaveTree(uri: Uri?): Boolean {
        val old = _saveTree.value
        if (uri != null) {
            val ok = runCatching { context.contentResolver.takePersistableUriPermission(uri, FLAGS) }.isSuccess
            if (!ok) return false
        }
        if (old != null && old != uri) {
            runCatching { context.contentResolver.releasePersistableUriPermission(old, FLAGS) }
        }
        prefs.edit().apply { if (uri == null) remove(KEY_TREE) else putString(KEY_TREE, uri.toString()) }.apply()
        _saveTree.value = uri
        return true
    }

    fun locationLabel(tree: Uri? = _saveTree.value): String {
        if (tree == null) return DEFAULT_LABEL
        val docId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
        if (docId != null && tree.authority == "com.android.externalstorage.documents") {
            val volume = docId.substringBefore(':')
            val path = docId.substringAfter(':', "")
            val root = if (volume == "primary") "Internal storage" else "SD card"
            return if (path.isEmpty()) root else "$root/$path"
        }
        return runCatching {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId!!)
            context.contentResolver.query(doc, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: "Selected folder"
    }

    companion object {
        const val DEFAULT_LABEL = "Downloads/Scanly"
        private const val KEY_TREE = "save_tree_uri"
        private const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
