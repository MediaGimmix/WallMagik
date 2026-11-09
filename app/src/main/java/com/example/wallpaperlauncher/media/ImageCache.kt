package com.example.wallpaperlauncher.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.util.Log
import android.util.LruCache
import android.util.Size
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decodes wallpapers at roughly screen size (software ARGB_8888, so they can be uploaded to GL) and keeps the
 * last few in memory. Shared by the launcher, the live wallpaper and the settings preview.
 */
object ImageCache {
    private const val TAG = "ImageCache"
    private lateinit var resolver: ContentResolver
    private val exec = Executors.newFixedThreadPool(2)
    private val lru = LinkedHashMap<Long, Bitmap>(8, 0.75f, true)
    private const val MAX = 4
    private val waiting = HashMap<Long, MutableList<(Bitmap?) -> Unit>>()

    @Volatile private var shortSide = 1080
    @Volatile private var longSide = 2400

    fun init(ctx: Context) {
        resolver = ctx.contentResolver
        val b = ctx.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        shortSide = min(b.width(), b.height()).coerceAtLeast(320)
        longSide = max(b.width(), b.height()).coerceAtLeast(480)
    }

    fun get(id: Long): Bitmap? = synchronized(this) { lru[id] }

    /** Drop decoded wallpapers (low memory); the renderers keep what's on screen as textures. */
    fun clear() = synchronized(this) { lru.clear() }

    /** Decodes [item] if needed; [cb] runs on a worker thread (or right away if cached). */
    fun load(item: ImageItem, cb: ((Bitmap?) -> Unit)? = null) {
        val cached = synchronized(this) {
            val b = lru[item.id]
            if (b == null) {
                val list = waiting[item.id]
                if (list != null) {
                    cb?.let(list::add)
                    return
                }
                waiting[item.id] = mutableListOf<(Bitmap?) -> Unit>().also { l -> cb?.let(l::add) }
            }
            b
        }
        if (cached != null) {
            cb?.invoke(cached)
            return
        }
        exec.execute {
            val bmp = decode(item)
            val cbs = synchronized(this) {
                if (bmp != null) {
                    lru[item.id] = bmp
                    while (lru.size > MAX) lru.remove(lru.keys.first())
                }
                waiting.remove(item.id) ?: emptyList()
            }
            cbs.forEach { it(bmp) }
        }
    }

    /**
     * Safety net for EXIF rotation. MediaStore's width/height are the stored (unrotated) size, so for 90/270 a
     * result with the same shape means the decoder didn't rotate. That answer is reused for 180 photos.
     */
    private fun fixRotation(b: Bitmap, item: ImageItem): Bitmap {
        val deg = ((item.orientation % 360) + 360) % 360
        if (deg == 0) return b
        if ((deg == 90 || deg == 270) && item.rawW > 0 && item.rawH > 0 && item.rawW != item.rawH) {
            val applied = (b.width > b.height) != (item.rawW > item.rawH)
            decoderRotates = applied
            return if (applied) b else rotate(b, deg)
        }
        return if (decoderRotates == false) rotate(b, deg) else b
    }

    suspend fun await(item: ImageItem): Bitmap? = get(item.id) ?: suspendCancellableCoroutine { cont ->
        load(item) { if (cont.isActive) cont.resume(it) }
    }

    /** Learned on the first 90/270 image: whether ImageDecoder already applied the EXIF rotation. */
    @Volatile private var decoderRotates: Boolean? = null

    private fun decode(item: ImageItem): Bitmap? = try {
        val src = ImageDecoder.createSource(resolver, item.uri)
        val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val w = info.size.width
            val h = info.size.height
            // Scale so the image still covers the screen in either orientation, never upscale.
            val scale = max(
                shortSide.toFloat() / min(w, h),
                longSide.toFloat() / max(w, h)
            )
            if (scale < 1f) {
                decoder.setTargetSize(
                    (w * scale).roundToInt().coerceAtLeast(1),
                    (h * scale).roundToInt().coerceAtLeast(1)
                )
            }
        }
        val argb = if (bmp.config != Bitmap.Config.ARGB_8888) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
        fixRotation(argb, item)
    } catch (e: Exception) {
        Log.w(TAG, "decode failed: ${item.uri}", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "out of memory decoding ${item.uri}")
        synchronized(this) { lru.clear() }
        null
    }
}

private fun ImageCache.rotate(b: Bitmap, deg: Int): Bitmap =
    Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(deg.toFloat()) }, true)

/** Small thumbnails for the folder picker. */
object Thumbs {
    // Capped by bytes, not count: 300 thumbnails could reach ~170 MB and helped push the JM21 out of memory.
    private val cache = object : LruCache<Long, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }

    fun clear() = cache.evictAll()

    fun cached(id: Long): Bitmap? = cache.get(id)

    /** Blocking; call from a background thread. */
    fun load(resolver: ContentResolver, item: ImageItem, px: Int): Bitmap? {
        cache.get(item.id)?.let { return it }
        return try {
            resolver.loadThumbnail(item.uri, Size(px, px), null).also { cache.put(item.id, it) }
        } catch (e: Exception) {
            // Some document providers have no thumbnails: decode small instead.
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, item.uri)) { d, info, _ ->
                    d.setTargetSampleSize(maxOf(1, minOf(info.size.width, info.size.height) / px))
                }.also { cache.put(item.id, it) }
            } catch (e2: Exception) {
                null
            }
        }
    }
}
