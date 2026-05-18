package it.dewarp.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.io.OutputStream

object PdfBoxInit {
    @Volatile private var initialized = false
    fun ensure(context: Context) {
        if (!initialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
        }
    }
}

/** Scrive pagine raddrizzate in un PDF. Le immagini vengono incorporate come JPEG. */
class PdfWriter(private val context: Context, private val outUri: Uri, private val dpi: Int = 300) : AutoCloseable {

    private val doc = PDDocument()

    init { PdfBoxInit.ensure(context) }

    fun addPage(image: Mat, jpegQuality: Int = 88) {
        // Converte Mat in Bitmap
        val bmp = Bitmap.createBitmap(image.cols(), image.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(image, bmp)
        // Dimensione fisica pagina in punti
        val widthPt = image.cols() * 72f / dpi
        val heightPt = image.rows() * 72f / dpi
        val page = PDPage(PDRectangle(widthPt, heightPt))
        doc.addPage(page)
        val img = JPEGFactory.createFromImage(doc, bmp, jpegQuality / 100f)
        PDPageContentStream(doc, page).use { cs ->
            cs.drawImage(img, 0f, 0f, widthPt, heightPt)
        }
        bmp.recycle()
    }

    override fun close() {
        val os: OutputStream = context.contentResolver.openOutputStream(outUri, "w")
            ?: error("Impossibile aprire $outUri in scrittura")
        os.use { doc.save(it) }
        doc.close()
    }
}
