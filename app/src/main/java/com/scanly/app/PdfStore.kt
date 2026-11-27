package com.scanly.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PdfRecord(
    val id: String,
    val uri: String,
    val name: String,
    val location: String,
    val createdAt: Long,
    val pageCount: Int,
    val sizeBytes: Long
)

/** Remembers the PDFs Scanly has exported so they can be listed, viewed, renamed and deleted. */
class PdfStore(private val context: Context) {
    private val file = File(context.filesDir, "pdfs.json")
    private val thumbDir = File(context.filesDir, "pdfthumbs").apply { mkdirs() }
    private val _pdfs = MutableStateFlow(load())
    val pdfs: StateFlow<List<PdfRecord>> = _pdfs.asStateFlow()

    fun get(id: String): PdfRecord? = _pdfs.value.firstOrNull { it.id == id }

    fun thumbFile(id: String) = File(thumbDir, "$id.jpg")

    fun add(record: PdfRecord, thumbSource: File?) {
        thumbSource?.let { src -> runCatching { src.copyTo(thumbFile(record.id), overwrite = true) } }
        _pdfs.update { sorted(it + record) }
        save()
    }

    /** Drops entries whose file no longer exists and picks up renames or size changes made elsewhere. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val decisions = HashMap<String, PdfRecord?>()
        _pdfs.value.forEach { r ->
            decisions[r.id] = when (val p = probe(Uri.parse(r.uri))) {
                Probe.Missing -> null
                Probe.Unknown -> r
                is Probe.Found -> r.copy(name = p.name ?: r.name, sizeBytes = p.size ?: r.sizeBytes)
            }
        }
        var changed = false
        _pdfs.update { cur ->
            val next = cur.mapNotNull { if (decisions.containsKey(it.id)) decisions[it.id] else it }
            changed = next != cur
            next
        }
        decisions.filterValues { it == null }.keys.forEach { thumbFile(it).delete() }
        if (changed) save()
    }

    suspend fun rename(record: PdfRecord, newBaseName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = PdfExporter.sanitizeName(newBaseName) + ".pdf"
            if (fileName == record.name) return@runCatching
            val resolver = context.contentResolver
            val uri = Uri.parse(record.uri)
            val newUri = if (uri.authority == MediaStore.AUTHORITY) {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, fileName) }
                if (resolver.update(uri, values, null, null) < 1) error("Rename failed")
                uri
            } else {
                DocumentsContract.renameDocument(resolver, uri, fileName) ?: error("Rename failed")
            }
            val actual = (probe(newUri) as? Probe.Found)?.name ?: fileName
            _pdfs.update { list -> list.map { if (it.id == record.id) it.copy(uri = newUri.toString(), name = actual) else it } }
            save()
        }
    }

    suspend fun delete(record: PdfRecord): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val uri = Uri.parse(record.uri)
            try {
                if (uri.authority == MediaStore.AUTHORITY) resolver.delete(uri, null, null)
                else DocumentsContract.deleteDocument(resolver, uri)
            } catch (e: Exception) {
                // already gone is fine, anything else is a real failure
                if (probe(uri) != Probe.Missing) throw e
            }
            _pdfs.update { list -> list.filter { it.id != record.id } }
            thumbFile(record.id).delete()
            save()
        }
    }

    private sealed interface Probe {
        data object Missing : Probe
        data object Unknown : Probe
        data class Found(val name: String?, val size: Long?) : Probe
    }

    private fun probe(uri: Uri): Probe = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) Probe.Found(it.getString(0), it.getLong(1)) else Probe.Missing
        } ?: Probe.Missing
    } catch (e: SecurityException) {
        Probe.Unknown
    } catch (e: Exception) {
        Probe.Missing
    }

    private fun sorted(list: List<PdfRecord>) = list.sortedByDescending { it.createdAt }

    @Synchronized
    private fun save() {
        val array = JSONArray()
        _pdfs.value.forEach {
            array.put(JSONObject().apply {
                put("id", it.id)
                put("uri", it.uri)
                put("name", it.name)
                put("location", it.location)
                put("createdAt", it.createdAt)
                put("pages", it.pageCount)
                put("size", it.sizeBytes)
            })
        }
        val tmp = File(file.parentFile, "pdfs.json.tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
    }

    private fun load(): List<PdfRecord> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            sorted((0 until array.length()).map { i ->
                val j = array.getJSONObject(i)
                PdfRecord(
                    id = j.getString("id"),
                    uri = j.getString("uri"),
                    name = j.getString("name"),
                    location = j.getString("location"),
                    createdAt = j.getLong("createdAt"),
                    pageCount = j.getInt("pages"),
                    sizeBytes = j.getLong("size")
                )
            })
        }.getOrDefault(emptyList())
    }
}
