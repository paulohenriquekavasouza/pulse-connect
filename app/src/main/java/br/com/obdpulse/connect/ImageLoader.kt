package br.com.obdpulse.connect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object ImageLoader {

    private val memory = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun bindVehicle(scope: CoroutineScope, view: ImageView, url: String?) {
        val cached = url?.let { memory.get(it) }
        if (cached != null) {
            showBitmap(view, cached)
            return
        }
        showPlaceholder(view)
        if (url == null) return
        view.tag = url
        scope.launch {
            val bitmap = load(view.context.applicationContext, url)
            if (bitmap != null && view.tag == url) showBitmap(view, bitmap)
        }
    }

    suspend fun load(context: Context, url: String): Bitmap? {
        memory.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "vehicle_images").apply { mkdirs() }
            val file = File(dir, sha1(url) + ".png")
            val bytes = if (file.length() > 0) file.readBytes() else download(url)?.also { file.writeBytes(it) }
            bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.also { memory.put(url, it) }
        }
    }

    private fun showBitmap(view: ImageView, bitmap: Bitmap) {
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        view.imageTintList = null
        view.setPadding(0, 0, 0, 0)
        view.setImageBitmap(bitmap)
    }

    private fun showPlaceholder(view: ImageView) {
        val pad = (view.resources.displayMetrics.density * 56).toInt()
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        view.setPadding(pad, pad, pad, pad)
        view.imageTintList = view.context.getColorStateList(R.color.text_muted)
        view.setImageResource(R.drawable.ic_car)
    }

    private fun download(url: String): ByteArray? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        try {
            val type = connection.contentType.orEmpty()
            if (connection.responseCode in 200..299 && type.startsWith("image/")) {
                connection.inputStream.use { it.readBytes() }
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
