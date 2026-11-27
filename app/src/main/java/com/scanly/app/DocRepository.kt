package com.scanly.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class DocRepository(context: Context) {
    private val root = File(context.filesDir, "docs").apply { mkdirs() }
    private val lock = Any()
    private val addMutex = Mutex()

    private val _docs = MutableStateFlow<List<ScanDoc>>(emptyList())
    val docs: StateFlow<List<ScanDoc>> = _docs.asStateFlow()

    init {
        _docs.value = loadAll()
    }

    fun doc(id: String): ScanDoc? = _docs.value.firstOrNull { it.id == id }

    fun pageFile(docId: String, pageId: String) = File(root, "$docId/$pageId.jpg")

    fun thumbFile(docId: String, page: ScanPage) = File(root, "$docId/${page.id}_t${page.version}.jpg")

    fun createDoc(): ScanDoc {
        val now = System.currentTimeMillis()
        val name = "Scan " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date(now))
        val doc = ScanDoc(UUID.randomUUID().toString(), name, now, now, emptyList())
        File(root, doc.id).mkdirs()
        synchronized(lock) {
            persist(doc)
            _docs.value = sorted(_docs.value + doc)
        }
        return doc
    }

    /** Imports one image into the document: normalizes, auto-detects the edges and creates the thumbnail. */
    suspend fun addPage(docId: String, open: () -> InputStream): Boolean = addMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (doc(docId) == null) return@withContext false
                val bmp = ImageProcessor.decode(open, ImageProcessor.STORE_MAX) ?: return@withContext false
                val id = UUID.randomUUID().toString()
                ImageProcessor.saveJpeg(bmp, pageFile(docId, id), 95)
                val corners = ImageProcessor.detectCorners(bmp) ?: ImageProcessor.DEFAULT_CORNERS
                bmp.recycle()
                val page = ScanPage(id, corners, PageFilter.CROPPED, 1)
                makeThumb(docId, page)
                mutate(docId) { it.copy(pages = it.pages + page, updatedAt = System.currentTimeMillis()) }
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    suspend fun editPage(docId: String, pageId: String, change: (ScanPage) -> ScanPage) = withContext(Dispatchers.IO) {
        val cur = doc(docId)?.pages?.firstOrNull { it.id == pageId } ?: return@withContext
        val updated = change(cur).copy(version = cur.version + 1)
        makeThumb(docId, updated)
        val applied = mutate(docId) { d ->
            if (d.pages.none { it.id == pageId }) d
            else d.copy(pages = d.pages.map { if (it.id == pageId) updated else it }, updatedAt = System.currentTimeMillis())
        }
        if (applied) thumbFile(docId, cur).delete() else thumbFile(docId, updated).delete()
    }

    suspend fun setFilter(docId: String, pageIds: Collection<String>, filter: PageFilter) {
        val ids = doc(docId)?.pages?.filter { it.id in pageIds && it.filter != filter }?.map { it.id } ?: return
        ids.forEach { id -> editPage(docId, id) { it.copy(filter = filter) } }
    }

    /** Removes several pages at once; deleting every page removes the document itself. */
    fun deletePages(docId: String, pageIds: Set<String>) {
        val d = doc(docId) ?: return
        val doomed = d.pages.filter { it.id in pageIds }
        if (doomed.isEmpty()) return
        if (doomed.size == d.pages.size) {
            deleteDoc(docId)
            return
        }
        mutate(docId) { it.copy(pages = it.pages.filter { p -> p.id !in pageIds }, updatedAt = System.currentTimeMillis()) }
        doomed.forEach {
            pageFile(docId, it.id).delete()
            thumbFile(docId, it).delete()
        }
    }

    suspend fun rotatePage(docId: String, pageId: String, clockwise: Boolean) = withContext(Dispatchers.IO) {
        val file = pageFile(docId, pageId)
        val bmp = ImageProcessor.decodeFile(file, ImageProcessor.STORE_MAX) ?: return@withContext
        val rotated = ImageProcessor.rotate(bmp, clockwise)
        ImageProcessor.saveJpeg(rotated, file, 95)
        bmp.recycle(); rotated.recycle()
        editPage(docId, pageId) { it.copy(corners = ImageProcessor.rotateCorners(it.corners, clockwise)) }
    }

    fun setOrder(docId: String, orderedIds: List<String>) {
        mutate(docId) { d ->
            val byId = d.pages.associateBy { it.id }
            val reordered = orderedIds.mapNotNull { byId[it] }
            if (reordered.size != d.pages.size) d else d.copy(pages = reordered, updatedAt = System.currentTimeMillis())
        }
    }

    fun movePage(docId: String, pageId: String, delta: Int) {
        val pages = doc(docId)?.pages ?: return
        val from = pages.indexOfFirst { it.id == pageId }
        val to = from + delta
        if (from < 0 || to !in pages.indices) return
        val ids = pages.map { it.id }.toMutableList()
        ids.add(to, ids.removeAt(from))
        setOrder(docId, ids)
    }

    fun deletePage(docId: String, pageId: String) {
        val d = doc(docId) ?: return
        val page = d.pages.firstOrNull { it.id == pageId } ?: return
        if (d.pages.size == 1) {
            deleteDoc(docId)
            return
        }
        mutate(docId) { it.copy(pages = it.pages.filter { p -> p.id != pageId }, updatedAt = System.currentTimeMillis()) }
        pageFile(docId, pageId).delete()
        thumbFile(docId, page).delete()
    }

    fun rename(docId: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        mutate(docId) { it.copy(name = clean, updatedAt = System.currentTimeMillis()) }
    }

    fun deleteDoc(docId: String) {
        synchronized(lock) {
            _docs.value = _docs.value.filter { it.id != docId }
        }
        File(root, docId).deleteRecursively()
    }

    private fun makeThumb(docId: String, page: ScanPage) {
        val bmp = ImageProcessor.decodeFile(pageFile(docId, page.id), 900) ?: return
        val rendered = ImageProcessor.render(bmp, page, 480)
        ImageProcessor.saveJpeg(rendered, thumbFile(docId, page), 85)
        if (rendered !== bmp) bmp.recycle()
        rendered.recycle()
    }

    private fun mutate(id: String, f: (ScanDoc) -> ScanDoc): Boolean = synchronized(lock) {
        val current = _docs.value.firstOrNull { it.id == id } ?: return false
        val updated = f(current)
        persist(updated)
        _docs.value = sorted(_docs.value.map { if (it.id == id) updated else it })
        true
    }

    private fun sorted(list: List<ScanDoc>) = list.sortedByDescending { it.updatedAt }

    private fun persist(doc: ScanDoc) {
        val json = JSONObject().apply {
            put("id", doc.id)
            put("name", doc.name)
            put("createdAt", doc.createdAt)
            put("updatedAt", doc.updatedAt)
            put("pages", JSONArray().apply {
                doc.pages.forEach { p ->
                    put(JSONObject().apply {
                        put("id", p.id)
                        put("c", JSONArray(p.corners.map { it.toDouble() }))
                        put("f", p.filter.name)
                        put("v", p.version)
                    })
                }
            })
        }
        val dir = File(root, doc.id).apply { mkdirs() }
        val tmp = File(dir, "doc.json.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(File(dir, "doc.json"))
    }

    private fun loadAll(): List<ScanDoc> {
        val result = ArrayList<ScanDoc>()
        root.listFiles()?.forEach { dir ->
            val f = File(dir, "doc.json")
            if (!f.exists()) return@forEach
            runCatching {
                val j = JSONObject(f.readText())
                val pagesJson = j.getJSONArray("pages")
                val pages = (0 until pagesJson.length()).map { i ->
                    val p = pagesJson.getJSONObject(i)
                    val c = p.getJSONArray("c")
                    ScanPage(
                        id = p.getString("id"),
                        corners = (0 until c.length()).map { c.getDouble(it).toFloat() },
                        filter = runCatching { PageFilter.valueOf(p.getString("f")) }.getOrDefault(PageFilter.CROPPED),
                        version = p.getInt("v")
                    )
                }
                result += ScanDoc(j.getString("id"), j.getString("name"), j.getLong("createdAt"), j.getLong("updatedAt"), pages)
            }
        }
        return sorted(result)
    }
}
