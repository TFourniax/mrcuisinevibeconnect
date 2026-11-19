package app.toque.recipes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.toque.core.i18n.tr
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Photos de recettes réduites pour l'écran du robot (800 px de large au plus), en JPEG. */
object Photos {
    const val MAX_WIDTH = 800
    private const val MAX_HEIGHT = 1600
    /** Au-delà, ou pour une image 20 fois plus haute que large (ou l'inverse), on refuse : un PNG de quelques Ko peut demander des centaines de Mo une fois décodé. */
    private const val MAX_SOURCE_PIXELS = 40_000_000L

    fun save(bytes: ByteArray, dir: File, name: String) {
        val bmp = decode(bytes)
        val tmp = File(dir, "$name.part")
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        if (!tmp.renameTo(File(dir, name))) tmp.delete()
    }

    /** Décode sans risque (taille et proportions vérifiées avant), réduit à [maxWidth] × [maxHeight] au plus. */
    fun decode(bytes: ByteArray, maxWidth: Int = MAX_WIDTH, maxHeight: Int = MAX_HEIGHT): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) throw IOException(tr("image illisible"))
        if (w.toLong() * h > MAX_SOURCE_PIXELS || w > 20L * h || h > 20L * w) throw IOException(tr("image illisible"))
        // Réduction choisie sur les deux côtés : l'image décodée tient en quelques Mo.
        var sample = 1
        while (w / (sample * 2) >= maxWidth || h / (sample * 2) >= maxHeight) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException(tr("image illisible"))
        if (bmp.width > maxWidth) {
            val scaled = Bitmap.createScaledBitmap(bmp, maxWidth, bmp.height * maxWidth / bmp.width, true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        return bmp
    }
}
