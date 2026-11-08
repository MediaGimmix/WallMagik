package com.example.wallpaperlauncher.media

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.wallpaperlauncher.AppState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * The one slideshow clock for the whole process: the launcher and the live wallpaper (home and lock screen)
 * show [current]. It only ticks while something is visible ([acquire]/[release]). Order is a shuffle bag:
 * every image once before any repeats. The next image is decoded ahead so even very short intervals keep up.
 */
object Slideshow {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private val _current = MutableStateFlow<ImageItem?>(null)
    val current: StateFlow<ImageItem?> = _current.asStateFlow()

    private var pool: List<ImageItem> = emptyList()
    private val bag = ArrayDeque<ImageItem>()
    private var upcoming: ImageItem? = null
    private var waitingFor: ImageItem? = null
    private var lastChange = 0L       // wall clock, so long intervals survive process restarts
    private var lastSaved = 0L        // uptime
    private var users = 0

    private val tick = Runnable { advance() }

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("slideshow", Context.MODE_PRIVATE)
        lastChange = prefs.getLong("last_change", 0L)
        val scope = MainScope()
        scope.launch {
            combine(
                ImageRepo.images,
                AppState.config.map { it.selectedFolders to it.excludedImages }.distinctUntilChanged()
            ) { images, (folders, excluded) ->
                images.filter { it.bucketId in folders && it.id !in excluded }
            }.collect { setPool(it) }
        }
        scope.launch {
            AppState.config.map { it.intervalMs }.distinctUntilChanged().collect { reschedule() }
        }
    }

    val poolSize: Int get() = pool.size

    fun acquire() {
        users++
        if (users == 1) reschedule()
    }

    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) main.removeCallbacks(tick)
    }

    /** Skip to the next image now. */
    fun next() {
        main.removeCallbacks(tick)
        advance(force = true)
    }

    private fun setPool(p: List<ImageItem>) {
        pool = p
        bag.clear()
        upcoming = null
        waitingFor = null
        if (p.isEmpty()) {
            _current.value = null
            main.removeCallbacks(tick)
            return
        }
        val cur = _current.value
        if (cur == null || p.none { it.id == cur.id }) {
            val saved = prefs.getLong("current", -1L)
            val restored = if (cur == null) p.firstOrNull { it.id == saved } else null
            if (restored != null) {
                _current.value = restored
                ImageCache.load(restored)
            } else {
                val first = nextFromBag()
                if (first != null) {
                    // Show it as soon as it's decoded.
                    waitingFor = first
                    ImageCache.load(first) { bmp -> main.post { if (waitingFor == first) {
                        waitingFor = null
                        publish(first, bmp != null)
                    } } }
                    return
                }
            }
        }
        prepareUpcoming()
        reschedule()
    }

    private fun nextFromBag(): ImageItem? {
        if (pool.isEmpty()) return null
        if (bag.isEmpty()) {
            val cur = _current.value
            val shuffled = pool.shuffled().toMutableList()
            // Don't show the same image twice in a row across bag refills.
            if (shuffled.size > 1 && shuffled.first().id == cur?.id) {
                shuffled.add(shuffled.removeAt(0))
            }
            bag.addAll(shuffled)
        }
        return bag.removeFirst()
    }

    private fun prepareUpcoming() {
        val next = upcoming ?: nextFromBag() ?: return
        upcoming = next
        ImageCache.load(next)
    }

    private fun advance(force: Boolean = false, attempts: Int = 0) {
        if (!force && users == 0) return
        if (attempts > 5) return
        val next = upcoming ?: nextFromBag() ?: return
        upcoming = next
        if (ImageCache.get(next.id) != null) {
            publish(next, true)
            return
        }
        waitingFor = next
        ImageCache.load(next) { bmp ->
            main.post {
                if (waitingFor != next) return@post
                waitingFor = null
                if (bmp != null) publish(next, true)
                else {
                    upcoming = null       // undecodable: skip it
                    advance(force, attempts + 1)
                }
            }
        }
    }

    private fun publish(item: ImageItem, ok: Boolean) {
        if (upcoming == item) upcoming = null
        if (ok) _current.value = item
        lastChange = System.currentTimeMillis()
        val now = SystemClock.uptimeMillis()
        if (now - lastSaved > 5000) {
            lastSaved = now
            prefs.edit().putLong("current", item.id).putLong("last_change", lastChange).apply()
        }
        prepareUpcoming()
        reschedule()
    }

    private fun reschedule() {
        main.removeCallbacks(tick)
        if (users == 0 || pool.isEmpty()) return
        val interval = AppState.config.value.intervalMs.coerceAtLeast(50)
        val now = System.currentTimeMillis()
        val wait = (nextChangeAt(now, interval) - now).coerceIn(0, interval)
        main.postDelayed(tick, wait)
    }

    /**
     * Intervals of 1 s or more are anchored to the system clock in local time: a 1 min interval changes at :00
     * of each minute, 1 h on the hour, 1 day at midnight. A boundary missed while nothing was visible changes
     * right away. Shorter intervals just count from the last change.
     */
    private fun nextChangeAt(now: Long, interval: Long): Long {
        if (interval < 1000) return lastChange + interval
        val offset = TimeZone.getDefault().getOffset(now)
        val local = now + offset
        val lastBoundary = local - Math.floorMod(local, interval) - offset
        return if (lastChange < lastBoundary) now else lastBoundary + interval
    }
}
