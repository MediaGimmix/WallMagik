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
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.example.wallpaperlauncher.AppState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

    const val SAF_PREFIX = "saf:"

    fun init(ctx: Context) {
        app = ctx.applicationContext
        // Picked folders changed: rescan (the first emission does the initial scan).
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            AppState.config.map { it.safFolders }.distinctUntilChanged().collect { scan() }
        }
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
        if (hasPermission() && !observerRegistered) {
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
        val items = ArrayList<ImageItem>()
        val folders = ArrayList<Folder>()
        if (hasPermission()) scanMediaStore(items, folders)
        for (tree in AppState.config.value.safFolders) {
            try { scanTree(Uri.parse(tree), items, folders) } catch (e: Exception) { Log.w(TAG, "tree scan failed: $tree", e) }
        }
        _images.value = items
        _folders.value = folders.sortedBy { it.name.lowercase() }
    }

    /** Negative 64-bit id for a document so it never clashes with MediaStore's positive ids. */
    private fun docId(s: String): Long {
        var h = 1125899906842597L
        for (ch in s) h = 31 * h + ch.code
        return -(h and Long.MAX_VALUE) - 1
    }

    /**
     * A folder picked with the system folder picker, read through the documents API: works even when MediaStore
     * skips it (.nomedia) and on SD cards. Includes subfolders.
     */
    private fun scanTree(tree: Uri, items: MutableList<ImageItem>, folders: MutableList<Folder>) {
        val cr = app.contentResolver
        val bucket = SAF_PREFIX + tree
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val path = rootId.substringAfter(':')
        val name = path.substringAfterLast('/').ifEmpty { rootId.substringBefore(':') }
        val found = ArrayList<Pair<ImageItem, Long>>()
        val pending = ArrayDeque(listOf(rootId))
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        while (pending.isNotEmpty()) {
            val parent = pending.removeFirst()
            cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent), cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val mime = c.getString(1) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) pending += id
                    else if (mime.startsWith("image/")) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        found += ImageItem(docId(uri.toString()), bucket, uri) to c.getLong(2)
                    }
                }
            }
        }
        if (found.isEmpty()) return
        val sorted = found.sortedByDescending { it.second }.map { it.first }
        items += sorted
        folders += Folder(bucket, name, "Picked folder · $path", sorted.size, sorted.first())
    }

    private fun scanMediaStore(items: MutableList<ImageItem>, folders: MutableList<Folder>) {
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
        buckets.forEach { (id, a) -> folders += Folder(id, a.name, a.path, a.count, a.cover) }
    }

    fun imagesIn(bucketId: String): List<ImageItem> = _images.value.filter { it.bucketId == bucketId }
}
