package com.scanly.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Locale

enum class PdfPageSize(val label: String) {
    A4("A4"),
    FIT("Fit to image")
}

enum class PdfQuality(val label: String, val maxSide: Int, val jpegQuality: Int) {
    HIGH("High", 2480, 90),
    BALANCED("Balanced", 1754, 85),
    SMALL("Small", 1240, 70)
}

data class SavedPdf(val uri: Uri, val fileName: String, val location: String)

object PdfExporter {

    fun sanitizeName(raw: String): String {
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimEnd('.')
        return cleaned.ifEmpty { "Scan" }
    }

    /** Writes the document as a PDF into the user's chosen folder, or Downloads/Scanly by default. */
    suspend fun export(
        context: Context,
        doc: ScanDoc,
        name: String,
        size: PdfPageSize,
        quality: PdfQuality,
        onProgress: (Int, Int) -> Unit
    ): SavedPdf = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val settings = context.appSettings
        val tree = settings.saveTree.value
        val safeName = sanitizeName(name)

        val uri: Uri = if (tree != null) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            DocumentsContract.createDocument(resolver, parent, "application/pdf", "$safeName.pdf")
                ?: error("Could not create file")
        } else {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "$safeName.pdf")
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Scanly")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Could not create file")
        }

        try {
            val stream = resolver.openOutputStream(uri) ?: error("Could not open file")
            stream.buffered().use { write(context, doc, safeName, size, quality, it, onProgress) }
            if (tree == null) {
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
        } catch (e: Throwable) {
            if (tree != null) runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            else resolver.delete(uri, null, null)
            throw e
        }

        // the system may have renamed the file to avoid a clash, so report the real name
        val savedName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "$safeName.pdf"
        SavedPdf(uri, savedName, settings.locationLabel(tree))
    }

    private fun write(
        context: Context,
        doc: ScanDoc,
        title: String,
        size: PdfPageSize,
        quality: PdfQuality,
        out: OutputStream,
        onProgress: (Int, Int) -> Unit
    ) {
        val repo = context.repo
        val w = PdfWriter(out)
        val n = doc.pages.size
        w.header()
        w.obj(1) { w.raw("<< /Type /Catalog /Pages 2 0 R >>") }
        w.obj(2) {
            val kids = (0 until n).joinToString(" ") { "${6 + 3 * it} 0 R" }
            w.raw("<< /Type /Pages /Count $n /Kids [$kids] >>")
        }
        w.obj(3) { w.raw("<< /Title ${pdfString(title)} /Producer (Scanly) >>") }

        doc.pages.forEachIndexed { i, page ->
            onProgress(i, n)
            val original = ImageProcessor.decodeFile(repo.pageFile(doc.id, page.id), ImageProcessor.STORE_MAX)
                ?: error("Missing page image")
            val bmp = ImageProcessor.render(original, page, quality.maxSide)
            if (bmp !== original) original.recycle()
            val jpeg = ByteArrayOutputStream().also {
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality.jpegQuality, it)
            }.toByteArray()
            val iw = bmp.width
            val ih = bmp.height
            bmp.recycle()

            val (pw, ph, dw, dh) = layout(size, iw, ih)
            val dx = (pw - dw) / 2
            val dy = (ph - dh) / 2
            val imgObj = 4 + 3 * i
            val contentObj = 5 + 3 * i
            val pageObj = 6 + 3 * i

            w.obj(imgObj) {
                w.raw(
                    "<< /Type /XObject /Subtype /Image /Width $iw /Height $ih /ColorSpace /DeviceRGB " +
                        "/BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n"
                )
                w.bytes(jpeg)
                w.raw("\nendstream")
            }
            val content = "q ${f(dw)} 0 0 ${f(dh)} ${f(dx)} ${f(dy)} cm /Im0 Do Q".toByteArray(Charsets.ISO_8859_1)
            w.obj(contentObj) {
                w.raw("<< /Length ${content.size} >>\nstream\n")
                w.bytes(content)
                w.raw("\nendstream")
            }
            w.obj(pageObj) {
                w.raw(
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(pw)} ${f(ph)}] " +
                        "/Resources << /XObject << /Im0 $imgObj 0 R >> >> /Contents $contentObj 0 R >>"
                )
            }
        }
        onProgress(n, n)
        w.finish(3 + 3 * n)
    }

    private data class Layout(val pageW: Double, val pageH: Double, val drawW: Double, val drawH: Double)

    private fun layout(size: PdfPageSize, iw: Int, ih: Int): Layout {
        val landscape = iw > ih
        return when (size) {
            PdfPageSize.A4 -> {
                val pw = if (landscape) 841.89 else 595.28
                val ph = if (landscape) 595.28 else 841.89
                val s = minOf(pw / iw, ph / ih)
                Layout(pw, ph, iw * s, ih * s)
            }
            PdfPageSize.FIT -> {
                val s = 841.89 / maxOf(iw, ih)
                Layout(iw * s, ih * s, iw * s, ih * s)
            }
        }
    }

    private fun f(d: Double) = String.format(Locale.US, "%.2f", d)

    private fun pdfString(s: String): String {
        val sb = StringBuilder("<FEFF")
        s.toByteArray(Charsets.UTF_16BE).forEach { sb.append(String.format("%02X", it)) }
        return sb.append(">").toString()
    }

    private class PdfWriter(private val out: OutputStream) {
        private var pos = 0L
        private val offsets = HashMap<Int, Long>()

        fun raw(s: String) = bytes(s.toByteArray(Charsets.ISO_8859_1))

        fun bytes(b: ByteArray) {
            out.write(b)
            pos += b.size
        }

        fun header() {
            raw("%PDF-1.4\n")
            bytes(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
        }

        fun obj(num: Int, body: () -> Unit) {
            offsets[num] = pos
            raw("$num 0 obj\n")
            body()
            raw("\nendobj\n")
        }

        fun finish(objectCount: Int) {
            val xrefPos = pos
            raw("xref\n0 ${objectCount + 1}\n")
            raw("0000000000 65535 f \n")
            for (i in 1..objectCount) raw(String.format(Locale.US, "%010d 00000 n \n", offsets[i] ?: 0L))
            raw("trailer\n<< /Size ${objectCount + 1} /Root 1 0 R /Info 3 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")
        }
    }
}
