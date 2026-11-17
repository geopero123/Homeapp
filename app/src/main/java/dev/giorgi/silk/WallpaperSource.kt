package dev.giorgi.silk

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Keeps blurred copies of the home-screen wallpaper so glass panels can show
 * what is "behind" them. Android doesn't let a single view blur the window
 * below it, so we blur the wallpaper image ourselves once, and each glass
 * panel draws the part of it that sits under the panel.
 *
 * Two blurs are kept, both cropped exactly like the system crops a static
 * wallpaper to the screen:
 *   glass  1/4 size, light blur  -> dock, widgets, menus
 *   heavy  1/12 size, deep blur  -> App Library background
 */
object WallpaperSource {

    const val GLASS_SCALE = 4f
    const val HEAVY_SCALE = 12f
    private const val TAG = "Silk"

    var glassBitmap: Bitmap? = null
        private set
    var heavyBitmap: Bitmap? = null
        private set

    /** Maps screen pixel coordinates onto [glassBitmap]. */
    var glassShader: BitmapShader? = null
        private set

    val available: Boolean get() = glassBitmap != null

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val glasses: MutableSet<LiquidGlass> = Collections.newSetFromMap(WeakHashMap())
    private val listeners = ArrayList<() -> Unit>()
    private var watching = false

    fun register(glass: LiquidGlass) {
        glasses += glass
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    /** Redraws every glass panel, e.g. while a page scrolls under the wallpaper. */
    fun invalidateAll() {
        for (g in glasses.toList()) g.invalidateSelf()
    }

    fun start(context: Context, done: (() -> Unit)? = null) {
        val app = context.applicationContext
        if (!watching) {
            watching = true
            // Fires whenever the wallpaper changes.
            WallpaperManager.getInstance(app).addOnColorsChangedListener(
                WallpaperManager.OnColorsChangedListener { _, _ -> reload(app) }, main
            )
        }
        reload(app, done)
    }

    fun reload(context: Context, done: (() -> Unit)? = null) {
        val app = context.applicationContext
        io.execute {
            val src = readSystem(app) ?: readSaved(app)
            val built = try {
                src?.let { build(app, it) }
            } catch (e: Exception) {
                Log.w(TAG, "wallpaper blur failed", e)
                null
            }
            main.post {
                glassBitmap = built?.first
                heavyBitmap = built?.second
                glassShader = built?.first?.let { bmp ->
                    BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                        setLocalMatrix(Matrix().apply { setScale(GLASS_SCALE, GLASS_SCALE) })
                        if (Build.VERSION.SDK_INT >= 33) filterMode = BitmapShader.FILTER_MODE_LINEAR
                    }
                }
                Log.i(TAG, "wallpaper glass: ${if (built != null) "ready" else "unavailable"}")
                invalidateAll()
                listeners.forEach { it() }
                done?.invoke()
            }
        }
    }

    /**
     * Uses a photo the user picked: saves our own copy (always readable)
     * and sets it as the home-screen wallpaper.
     */
    fun applyPhoto(context: Context, uri: Uri, done: (Boolean) -> Unit) {
        val app = context.applicationContext
        io.execute {
            val ok = try {
                val (w, h) = screenSize(app)
                val target = max(w, h) * 1.5f
                val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val f = min(1f, target / max(info.size.width, info.size.height))
                    if (f < 1f) {
                        decoder.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
                    }
                }
                File(app.filesDir, "wallpaper.jpg").outputStream().use {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
                }
                WallpaperManager.getInstance(app).setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM)
                true
            } catch (e: Exception) {
                Log.w(TAG, "could not use photo", e)
                false
            }
            main.post { done(ok) }
            reload(app)
        }
    }

    private fun readSystem(context: Context): Bitmap? {
        return try {
            val d = WallpaperManager.getInstance(context).drawable ?: return null
            toSoftware(d)
        } catch (e: SecurityException) {
            Log.i(TAG, "no permission to read the system wallpaper")
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun readSaved(context: Context): Bitmap? {
        val f = File(context.filesDir, "wallpaper.jpg")
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    private fun toSoftware(d: Drawable): Bitmap? {
        if (d is BitmapDrawable) {
            val b = d.bitmap ?: return null
            return if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
        }
        val w = d.intrinsicWidth.coerceIn(1, 2000)
        val h = d.intrinsicHeight.coerceIn(1, 4000)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(out))
        return out
    }

    private fun screenSize(context: Context): Pair<Int, Int> {
        val b = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return min(b.width(), b.height()) to max(b.width(), b.height())
    }

    private fun build(context: Context, src: Bitmap): Pair<Bitmap, Bitmap> {
        val (w, h) = screenSize(context)
        // A static wallpaper is scaled to cover the screen and centred.
        val scale = max(w / src.width.toFloat(), h / src.height.toFloat())
        val cropW = (w / scale).toInt()
        val cropH = (h / scale).toInt()
        val left = (src.width - cropW) / 2
        val top = (src.height - cropH) / 2
        val crop = Rect(left, top, left + cropW, top + cropH)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        fun shrink(f: Float): Bitmap {
            val out = Bitmap.createBitmap(
                (w / f).toInt().coerceAtLeast(1), (h / f).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888
            )
            Canvas(out).drawBitmap(src, crop, Rect(0, 0, out.width, out.height), paint)
            return out
        }
        return boxBlur(shrink(GLASS_SCALE), 3) to boxBlur(shrink(HEAVY_SCALE), 5)
    }

    /** Three box-blur passes in each direction, a close match to a Gaussian blur. */
    private fun boxBlur(bmp: Bitmap, r: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val a = IntArray(w * h)
        bmp.getPixels(a, 0, w, 0, 0, w, h)
        val b = IntArray(w * h)
        repeat(3) {
            pass(a, b, w, h, r, horizontal = true)
            pass(b, a, w, h, r, horizontal = false)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(a, 0, w, 0, 0, w, h)
        return out
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun idx(k: Int): Int {
                val c = k.coerceIn(0, len - 1)
                return if (horizontal) line * w + c else c * w + line
            }
            var sa = 0
            var sr = 0
            var sg = 0
            var sb = 0
            for (k in -r..r) {
                val p = src[idx(k)]
                sa += p ushr 24
                sr += (p shr 16) and 255
                sg += (p shr 8) and 255
                sb += p and 255
            }
            for (k in 0 until len) {
                dst[idx(k)] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val pin = src[idx(k + r + 1)]
                val pout = src[idx(k - r)]
                sa += (pin ushr 24) - (pout ushr 24)
                sr += ((pin shr 16) and 255) - ((pout shr 16) and 255)
                sg += ((pin shr 8) and 255) - ((pout shr 8) and 255)
                sb += (pin and 255) - (pout and 255)
            }
        }
    }
}
