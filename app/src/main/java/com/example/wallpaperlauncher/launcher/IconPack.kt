package com.example.wallpaperlauncher.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import kotlin.math.abs
import kotlin.math.roundToInt

data class IconPackInfo(val pkg: String, val label: String, val icon: Drawable?)

/**
 * An ADW/Nova-style icon pack: appfilter.xml (res/xml or assets) maps components to drawables, and may define
 * iconback/iconmask/iconupon/scale for apps the pack doesn't cover.
 */
class IconPack(ctx: Context, val pkg: String) {
    private val res: Resources = ctx.packageManager.getResourcesForApplication(pkg)
    private val map = HashMap<String, String>()
    private val backs = ArrayList<String>()
    private var mask: String? = null
    private var upon: String? = null
    private var scale = 1f
    private val drawableCache = HashMap<String, Drawable?>()

    init {
        try {
            parse()
        } catch (e: Exception) {
            Log.w(TAG, "couldn't read appfilter of $pkg", e)
        }
    }

    val canThemeOthers: Boolean get() = backs.isNotEmpty() || mask != null || upon != null

    private fun parse() {
        val id = res.getIdentifier("appfilter", "xml", pkg)
        val parser: XmlPullParser = if (id != 0) res.getXml(id) else {
            XmlPullParserFactory.newInstance().newPullParser().apply {
                setInput(res.assets.open("appfilter.xml"), "utf-8")
            }
        }
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val comp = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (comp != null && drawable != null) {
                            normalize(comp)?.let { map.putIfAbsent(it, drawable) }
                        }
                    }
                    "iconback" -> for (i in 0 until parser.attributeCount) {
                        if (parser.getAttributeName(i).startsWith("img")) backs += parser.getAttributeValue(i)
                    }
                    "iconmask" -> mask = parser.getAttributeValue(null, "img1")
                    "iconupon" -> upon = parser.getAttributeValue(null, "img1")
                    "scale" -> scale = parser.getAttributeValue(null, "factor")?.toFloatOrNull() ?: 1f
                }
            }
            ev = parser.next()
        }
    }

    /** "ComponentInfo{pkg/.Cls}" -> "pkg/pkg.Cls" (same as ComponentName.flattenToString's long form). */
    private fun normalize(comp: String): String? {
        val inner = comp.removePrefix("ComponentInfo{").removeSuffix("}")
        val slash = inner.indexOf('/')
        if (slash <= 0) return null
        val p = inner.substring(0, slash)
        var c = inner.substring(slash + 1)
        if (c.startsWith(".")) c = p + c
        return "$p/$c"
    }

    private fun drawable(name: String): Drawable? = drawableCache.getOrPut(name) {
        var id = res.getIdentifier(name, "drawable", pkg)
        if (id == 0) id = res.getIdentifier(name, "mipmap", pkg)
        if (id == 0) null else runCatching { res.getDrawable(id, null) }.getOrNull()
    }

    fun iconFor(cn: ComponentName): Drawable? =
        map["${cn.packageName}/${cn.className}"]?.let(::drawable)

    /** Puts an app the pack lacks onto the pack's background, mask and overlay. */
    fun themeOther(icon: Bitmap, cn: ComponentName, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val full = Rect(0, 0, size, size)
        val back = if (backs.isEmpty()) null else drawable(backs[abs(cn.hashCode()) % backs.size])
        back?.let { it.bounds = full; it.draw(c) }
        val s = (size * scale).roundToInt()
        val o = (size - s) / 2
        val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        lc.drawBitmap(icon, null, Rect(o, o, o + s, o + s), Paint(Paint.FILTER_BITMAP_FLAG))
        mask?.let(::drawable)?.let { m ->
            val mb = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            m.bounds = full
            m.draw(Canvas(mb))
            lc.drawBitmap(mb, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        }
        c.drawBitmap(layer, 0f, 0f, null)
        upon?.let(::drawable)?.let { it.bounds = full; it.draw(c) }
        return out
    }

    companion object {
        private const val TAG = "IconPack"
        private val ACTIONS = listOf(
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.teslacoilsw.launcher.THEME",
            "com.gau.go.launcherex.theme",
            "com.anddoes.launcher.THEME",
            "org.adw.launcher.icons.ACTION_PICK_ICON",
        )

        fun installed(ctx: Context): List<IconPackInfo> {
            val pm = ctx.packageManager
            val pkgs = LinkedHashSet<String>()
            for (a in ACTIONS) {
                pm.queryIntentActivities(Intent(a), PackageManager.GET_META_DATA).forEach {
                    pkgs += it.activityInfo.packageName
                }
            }
            return pkgs.mapNotNull { p ->
                runCatching {
                    val ai = pm.getApplicationInfo(p, 0)
                    IconPackInfo(p, pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai))
                }.getOrNull()
            }.sortedBy { it.label.lowercase() }
        }
    }
}

/** Draws [d] into a square bitmap. With [transparent], adaptive icons lose their background layer. */
fun Drawable.toIconBitmap(size: Int, transparent: Boolean): Bitmap {
    val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    if (transparent && this is AdaptiveIconDrawable && foreground != null) {
        // The foreground layer is 108 units with the 72-unit safe zone in the middle: scale that zone to fill.
        val fg = foreground
        val big = (size * 1.5f).roundToInt()
        val off = (size - big) / 2
        fg.setBounds(off, off, off + big, off + big)
        fg.draw(c)
    } else {
        setBounds(0, 0, size, size)
        draw(c)
    }
    return b
}
