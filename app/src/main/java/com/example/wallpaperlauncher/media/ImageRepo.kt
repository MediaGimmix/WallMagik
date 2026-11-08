package com.example.wallpaperlauncher.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** [orientation] is MediaStore's EXIF rotation in degrees. */
data class ImageItem(
    val id: Long,
    val bucketId: String,
    val uri: Uri,
    val orientation: Int = 0,
    val rawW: Int = 0,   // MediaStore size, before rotation
    val rawH: Int = 0,
)

data class Folder(
    val id: String,
    val name: String,
    val path: String,
    val count: Int,
    val cover: ImageItem,
)

/** Every image MediaStore knows about, grouped into folders (buckets). */
object ImageRepo {
    private const val TAG = "ImageRepo"
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanLock = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private var observerRegistered = false

    private val _images = MutableStateFlow<List<ImageItem>>(emptyList())
    val images: StateFlow<List<ImageItem>> = _images.asStateFlow()

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    val permission: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun init(ctx: Context) {
        app = ctx.applicationContext
        scan()
    }

    fun hasPermission(ctx: Context = app) =
        ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private val rescan = Runnable { scan() }
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            main.removeCallbacks(rescan)
            main.postDelayed(rescan, 3000)
        }
    }

    fun scan() {
        if (!hasPermission()) return
        if (!observerRegistered) {
            observerRegistered = true
            app.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
            )
        }
        scope.launch {
            scanLock.withLock {
                _scanning.value = true
                try {
                    doScan()
                } catch (e: Exception) {
                    Log.w(TAG, "scan failed", e)
                } finally {
                    _scanning.value = false
                }
            }
        }
    }

    private fun doScan() {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.ORIENTATION,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        )
        val items = ArrayList<ImageItem>()
        data class Acc(val name: String, val path: String, var count: Int, val cover: ImageItem)
        val buckets = LinkedHashMap<String, Acc>()
        app.contentResolver.query(
            collection, projection, null, null,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val pCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val oCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)
            val wCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val hCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val bucket = c.getString(bCol) ?: continue
                val item = ImageItem(id, bucket, ContentUris.withAppendedId(collection, id), c.getInt(oCol), c.getInt(wCol), c.getInt(hCol))
                items += item
                val acc = buckets[bucket]
                if (acc == null) {
                    buckets[bucket] = Acc(
                        c.getString(nCol) ?: "Unknown",
                        c.getString(pCol)?.trimEnd('/') ?: "",
                        1, item
                    )
                } else acc.count++
            }
        }
        _images.value = items
        _folders.value = buckets.map { (id, a) -> Folder(id, a.name, a.path, a.count, a.cover) }
            .sortedBy { it.name.lowercase() }
    }

    fun imagesIn(bucketId: String): List<ImageItem> = _images.value.filter { it.bucketId == bucketId }
}
