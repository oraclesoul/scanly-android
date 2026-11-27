package com.scanly.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageProcessor {
    const val STORE_MAX = 3200

    val DEFAULT_CORNERS = listOf(0.06f, 0.06f, 0.94f, 0.06f, 0.94f, 0.94f, 0.06f, 0.94f)

    /** Decodes an image, applies EXIF rotation and downsizes so the longest side is at most [maxSide]. */
    fun decode(open: () -> InputStream, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = open().use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val degrees = runCatching {
            open().use { ExifInterface(it).rotationDegrees }
        }.getOrDefault(0)

        val scale = min(1f, maxSide.toFloat() / max(raw.width, raw.height))
        if (degrees == 0 && scale >= 1f) return raw
        val m = Matrix().apply {
            if (degrees != 0) postRotate(degrees.toFloat())
            if (scale < 1f) postScale(scale, scale)
        }
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (out !== raw) raw.recycle()
        return out
    }

    fun decodeFile(file: File, maxSide: Int): Bitmap? = decode({ file.inputStream() }, maxSide)

    fun saveJpeg(bmp: Bitmap, file: File, quality: Int) {
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    fun rotate(bmp: Bitmap, clockwise: Boolean): Bitmap {
        val m = Matrix().apply { postRotate(if (clockwise) 90f else -90f) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    fun rotateCorners(c: List<Float>, clockwise: Boolean): List<Float> {
        val pts = (0..3).map { i ->
            val x = c[i * 2]
            val y = c[i * 2 + 1]
            if (clockwise) floatArrayOf(1f - y, x) else floatArrayOf(y, 1f - x)
        }
        return orderPoints(pts)
    }

    /** Returns points as flat list ordered TL, TR, BR, BL. */
    fun orderPoints(pts: List<FloatArray>): List<Float> {
        val tl = pts.minBy { it[0] + it[1] }
        val br = pts.maxBy { it[0] + it[1] }
        val tr = pts.minBy { it[1] - it[0] }
        val bl = pts.maxBy { it[1] - it[0] }
        return listOf(tl[0], tl[1], tr[0], tr[1], br[0], br[1], bl[0], bl[1])
    }

    /** Finds the largest document-like quadrilateral. Returns null if nothing convincing is found. */
    fun detectCorners(src: Bitmap): List<Float>? {
        val scale = 500.0 / max(src.width, src.height)
        val small = if (scale < 1.0) {
            Bitmap.createScaledBitmap(
                src,
                (src.width * scale).roundToInt().coerceAtLeast(1),
                (src.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
        } else src

        val rgba = Mat()
        Utils.bitmapToMat(small, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        val imgArea = gray.rows().toDouble() * gray.cols()
        val masks = ArrayList<Mat>()

        val edges = Mat()
        Imgproc.Canny(gray, edges, 40.0, 120.0)
        Imgproc.dilate(edges, edges, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)), Point(-1.0, -1.0), 2)
        masks += edges

        val th = Mat()
        Imgproc.threshold(gray, th, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        Imgproc.morphologyEx(th, th, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)))
        Imgproc.rectangle(th, Point(0.0, 0.0), Point(th.cols() - 1.0, th.rows() - 1.0), Scalar(0.0), 3)
        masks += th

        var best: Array<Point>? = null
        var bestArea = 0.0
        for (mask in masks) {
            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(mask.clone(), contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val sorted = contours.sortedByDescending { Imgproc.contourArea(it) }.take(8)
            for (c in sorted) {
                val area = Imgproc.contourArea(c)
                if (area < imgArea * 0.2) break
                if (area > imgArea * 0.97) continue
                val c2f = MatOfPoint2f(*c.toArray())
                val peri = Imgproc.arcLength(c2f, true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
                if (approx.total() == 4L && Imgproc.isContourConvex(MatOfPoint(*approx.toArray())) && area > bestArea) {
                    best = approx.toArray()
                    bestArea = area
                }
            }
        }

        if (small !== src) small.recycle()
        val quad = best ?: return null
        val w = gray.cols().toFloat()
        val h = gray.rows().toFloat()
        val cx = quad.map { it.x }.average()
        val cy = quad.map { it.y }.average()
        // pull the corners slightly inward so the desk behind the paper doesn't leak into the crop
        val inset = 0.012
        return orderPoints(quad.map {
            val x = it.x + (cx - it.x) * inset
            val y = it.y + (cy - it.y) * inset
            floatArrayOf((x / w).toFloat().coerceIn(0f, 1f), (y / h).toFloat().coerceIn(0f, 1f))
        })
    }

    /** Applies crop and filter. [maxSide] > 0 limits the longest side of the result. */
    fun render(original: Bitmap, page: ScanPage, maxSide: Int = 0): Bitmap {
        if (page.filter == PageFilter.ORIGINAL) return scaleDown(original, maxSide)

        val w = original.width.toDouble()
        val h = original.height.toDouble()
        val c = page.corners
        val p = (0..3).map { Point(c[it * 2] * w, c[it * 2 + 1] * h) }
        val outW = max(dist(p[0], p[1]), dist(p[3], p[2])).roundToInt().coerceAtLeast(1)
        val outH = max(dist(p[0], p[3]), dist(p[1], p[2])).roundToInt().coerceAtLeast(1)

        val src = Mat()
        Utils.bitmapToMat(original, src)
        val srcPts = MatOfPoint2f(*p.toTypedArray())
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0), Point(outW - 1.0, 0.0), Point(outW - 1.0, outH - 1.0), Point(0.0, outH - 1.0)
        )
        val transform = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        var cur = Mat()
        Imgproc.warpPerspective(src, cur, transform, Size(outW.toDouble(), outH.toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
        src.release()

        val longest = max(outW, outH)
        if (maxSide > 0 && longest > maxSide) {
            val s = maxSide.toDouble() / longest
            val resized = Mat()
            Imgproc.resize(cur, resized, Size(), s, s, Imgproc.INTER_AREA)
            cur.release()
            cur = resized
        }

        val result = when (page.filter) {
            PageFilter.ENHANCED -> enhance(cur)
            PageFilter.XEROX -> xerox(cur)
            else -> cur
        }
        val out = Bitmap.createBitmap(result.cols(), result.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(result, out)
        result.release()
        return out
    }

    private fun scaleDown(bmp: Bitmap, maxSide: Int): Bitmap {
        val longest = max(bmp.width, bmp.height)
        if (maxSide <= 0 || longest <= maxSide) return bmp
        val s = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt(), (bmp.height * s).roundToInt(), true)
    }

    private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    /** Estimates the paper/lighting layer by erasing dark text (grayscale dilation) and blurring. */
    private fun background(gray: Mat): Mat {
        val s = min(1.0, 400.0 / max(gray.cols(), gray.rows()))
        val small = Mat()
        Imgproc.resize(gray, small, Size(), s, s, Imgproc.INTER_AREA)
        Imgproc.dilate(small, small, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(11.0, 11.0)))
        Imgproc.GaussianBlur(small, small, Size(11.0, 11.0), 0.0)
        val bg = Mat()
        Imgproc.resize(small, bg, gray.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        small.release()
        return bg
    }

    /** Flattens lighting and boosts contrast to a black & white photocopy look. */
    private fun xerox(rgba: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        val bg = background(gray)
        val norm = Mat()
        Core.divide(gray, bg, norm, 255.0)
        val lo = 115.0
        val hi = 215.0
        val alpha = 255.0 / (hi - lo)
        val levels = Mat()
        norm.convertTo(levels, -1, alpha, -lo * alpha)
        val out = Mat()
        Imgproc.cvtColor(levels, out, Imgproc.COLOR_GRAY2RGBA)
        gray.release(); bg.release(); norm.release(); levels.release()
        return out
    }

    /** Evens out lighting and whitens the paper while keeping colours. */
    private fun enhance(rgba: Mat): Mat {
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val bg = background(gray)

        val bgF = Mat()
        bg.convertTo(bgF, CvType.CV_32F)
        Core.max(bgF, Scalar(1.0), bgF)
        val gain = Mat()
        Core.divide(250.0, bgF, gain)
        val gain3 = Mat()
        Core.merge(listOf(gain, gain, gain), gain3)

        val rgbF = Mat()
        rgb.convertTo(rgbF, CvType.CV_32FC3)
        val scaled = Mat()
        Core.multiply(rgbF, gain3, scaled)
        val out8 = Mat()
        scaled.convertTo(out8, CvType.CV_8UC3, 1.08, -8.0)
        val out = Mat()
        Imgproc.cvtColor(out8, out, Imgproc.COLOR_RGB2RGBA)
        listOf(rgb, gray, bg, bgF, gain, gain3, rgbF, scaled, out8).forEach { it.release() }
        return out
    }
}
