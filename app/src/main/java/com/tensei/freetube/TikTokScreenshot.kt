package com.tensei.freetube

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

internal object TikTokScreenshot {
    private val client = OkHttpClient()
    private const val revision = "87416418657359cb625c412a48b6e1d6d41c29bd"

    @Synchronized private fun models(context: Context): File {
        val root = File(context.filesDir, "ocr/$revision")
        val folder = File(root, "tessdata")
        check(folder.isDirectory || folder.mkdirs())
        for (language in listOf("ara", "eng")) {
            val target = File(folder, "$language.traineddata")
            if (target.isFile) continue
            val temp = File.createTempFile(language, ".part", folder)
            try {
                val url = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/$revision/${target.name}"
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    check(response.isSuccessful)
                    val body = requireNotNull(response.body)
                    val size = temp.outputStream().use { body.byteStream().copyTo(it) }
                    check(size > 0 && (body.contentLength() < 0 || body.contentLength() == size))
                }
                check(temp.renameTo(target))
            } finally { temp.delete() }
        }
        return root
    }

    fun read(context: Context, uri: Uri): String {
        val resolver = context.contentResolver
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        require(opts.outWidth > 0 && opts.outHeight > 0)
        opts.inJustDecodeBounds = false
        opts.inSampleSize = 1
        while (maxOf(opts.outWidth, opts.outHeight) / opts.inSampleSize > 2400) opts.inSampleSize *= 2
        val root = models(context)
        val bitmap = requireNotNull(resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) })
        val engine = TessBaseAPI()
        try {
            check(engine.init(root.absolutePath, "ara+eng"))
            engine.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
            engine.setImage(bitmap)
            val text = engine.getUTF8Text().orEmpty().trim()
            return Regex("@[A-Za-z0-9_.]{1,24}").find(text)?.value ?: text.replace(Regex("\\s+"), " ").take(300)
        } finally {
            engine.recycle()
            bitmap.recycle()
        }
    }
}
