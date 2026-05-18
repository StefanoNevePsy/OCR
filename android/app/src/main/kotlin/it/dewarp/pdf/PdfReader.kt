package it.dewarp.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.opencv.android.Utils
import org.opencv.core.Mat

/** Streamy reader: rende le pagine ad alto DPI evitando di tenerle tutte in memoria. */
class PdfReader(private val context: Context, private val uri: Uri) : AutoCloseable {

    private val pfd: ParcelFileDescriptor =
        context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Impossibile aprire $uri in lettura")
    private val renderer: PdfRenderer = PdfRenderer(pfd)

    val pageCount: Int get() = renderer.pageCount

    /**
     * @param dpi target di rendering (default 300)
     * @return Mat BGRA uint8
     */
    fun renderPage(index: Int, dpi: Int = 300): Mat {
        val page = renderer.openPage(index)
        // pagina in punti (1/72"); converti in pixel a `dpi`
        val widthPx = (page.width * dpi / 72.0).toInt().coerceAtLeast(1)
        val heightPx = (page.height * dpi / 72.0).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        page.close()
        val mat = Mat()
        Utils.bitmapToMat(bmp, mat)
        bmp.recycle()
        return mat
    }

    override fun close() {
        renderer.close()
        pfd.close()
    }
}
