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

    fun save(bytes: ByteArray, dir: File, name: String) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) throw IOException(tr("image illisible"))
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException(tr("image illisible"))
        if (bmp.width > MAX_WIDTH) {
            val scaled = Bitmap.createScaledBitmap(bmp, MAX_WIDTH, bmp.height * MAX_WIDTH / bmp.width, true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        val tmp = File(dir, "$name.part")
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        if (!tmp.renameTo(File(dir, name))) tmp.delete()
    }
}
