package dev.giorgi.silk

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.view.View
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Collator
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * One launchable activity. [key] is what gets stored in the saved layout;
 * [version] is the package's last update time, used to know when a cached icon is stale.
 */
class AppInfo(
    val component: ComponentName,
    val user: UserHandle,
    val userSerial: Long,
    val label: String,
    val icon: Bitmap,
    val key: String,
    val version: Long,
)

/**
 * Source of truth for "which apps exist".
 *
 * Startup is two-step so the drawer is never empty:
 *  1. Read the app list and icons cached on disk last time (fast PNG decode).
 *  2. Ask LauncherApps for the real list; only apps that are new or updated
 *     have their icons rendered again, on 4 threads in parallel.
 */
class AppRepository(private val context: Context) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val io = Executors.newSingleThreadExecutor()
    private val iconPool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private val iconPx = context.dp(Dimens.ICON_DP)
    private val iconDir = File(context.filesDir, "icons").apply { mkdirs() }
    // Bump the version whenever icon rendering changes, so old cached icons are dropped.
    private val indexFile = File(context.filesDir, "apps_index_v3.json")

    var apps: List<AppInfo> = emptyList()
        private set
    private var byKey: Map<String, AppInfo> = emptyMap()

    /** Last list built on the io thread; only touched from [io]. */
    private var lastLoaded: List<AppInfo> = emptyList()

    @Volatile
    private var reloadQueued = false

    var onAppsChanged: (() -> Unit)? = null
    var onPackageRemoved: ((packageName: String, userSerial: Long) -> Unit)? = null

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            this@AppRepository.onPackageRemoved?.invoke(
                packageName, userManager.getSerialNumberForUser(user)
            )
            reload()
        }

        override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
        override fun onPackagesAvailable(p: Array<String>, user: UserHandle, r: Boolean) = reload()
        override fun onPackagesUnavailable(p: Array<String>, user: UserHandle, r: Boolean) = reload()
    }

    fun start() {
        launcherApps.registerCallback(callback, main)
        io.execute { loadFromCache() }
        reload()
    }

    /** Called from onDestroy: drops callbacks into the dead activity and frees the threads. */
    fun stop() {
        launcherApps.unregisterCallback(callback)
        onAppsChanged = null
        onPackageRemoved = null
        main.removeCallbacksAndMessages(null)
        io.execute { iconPool.shutdown() } // after any queued reload has finished
        io.shutdown()
    }

    /** True once [apps] comes from a complete LauncherApps query (not the disk cache). */
    var isLive = false
        private set
    private var livePublished = false // io thread only

    fun find(key: String): AppInfo? = byKey[key]

    fun findByPackage(packageName: String): AppInfo? {
        val me = Process.myUserHandle()
        return apps.firstOrNull { it.component.packageName == packageName && it.user == me }
    }

    private fun publish(list: List<AppInfo>, live: Boolean = false) {
        val map = list.associateBy { it.key }
        main.post {
            apps = list
            byKey = map
            isLive = live
            onAppsChanged?.invoke()
        }
    }

    // ---- step 1: disk cache -------------------------------------------------

    private fun loadFromCache() {
        val t0 = SystemClock.uptimeMillis()
        if (!indexFile.exists()) return
        try {
            val arr = JSONArray(indexFile.readText())
            val result = ArrayList<AppInfo>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val serial = o.getLong("serial")
                val user = userManager.getUserForSerialNumber(serial) ?: continue
                val component = ComponentName.unflattenFromString(o.getString("component")) ?: continue
                val key = o.getString("key")
                val bitmap = BitmapFactory.decodeFile(iconFile(key).path) ?: continue
                if (bitmap.width != iconPx) continue // drawn at another size: render it again
                result += AppInfo(component, user, serial, o.getString("label"), bitmap, key, o.getLong("version"))
            }
            lastLoaded = result
            publish(result)
            Log.i(TAG, "cache: ${result.size} apps in ${SystemClock.uptimeMillis() - t0} ms")
        } catch (e: Exception) {
            Log.w(TAG, "icon cache unreadable, rebuilding", e)
        }
    }

    // ---- step 2: real list ----------------------------------------------------

    fun reload() {
        if (reloadQueued || io.isShutdown) return
        reloadQueued = true
        io.execute {
            reloadQueued = false
            val t0 = SystemClock.uptimeMillis()
            val previous = lastLoaded.associateBy { it.key }
            val futures = ArrayList<Future<AppInfo>>()
            val fresh = HashSet<String>()
            var complete = true
            try {
                for (user in userManager.userProfiles) {
                    val serial = userManager.getSerialNumberForUser(user)
                    for (info in launcherApps.getActivityList(null, user)) {
                        val component = info.componentName
                        val key = component.flattenToString() + "#" + serial
                        val label = info.label?.toString() ?: component.packageName
                        val version = versionOf(info)
                        val cached = previous[key]
                        futures += if (cached != null && cached.version == version && cached.label == label) {
                            iconPool.submit(Callable { cached })
                        } else {
                            fresh += key
                            iconPool.submit(Callable {
                                try {
                                    AppInfo(component, user, serial, label,
                                        render(info.getIcon(0), user), key, version)
                                } catch (e: Throwable) {
                                    // Keep the app, with its previous icon if there is one.
                                    Log.w(TAG, "icon render failed: $key", e)
                                    previous[key] ?: throw e
                                }
                            })
                        }
                    }
                }
            } catch (e: Exception) {
                complete = false
                Log.w(TAG, "profile changed while loading", e)
            }
            val result = ArrayList<AppInfo>(futures.size)
            for (f in futures) {
                try {
                    result += f.get()
                } catch (e: Exception) {
                    // One broken icon must not take down the whole list.
                }
            }
            val collator = Collator.getInstance()
            result.sortWith { a, b -> collator.compare(a.label, b.label) }

            val changed = fresh.isNotEmpty() || result.size != lastLoaded.size
            lastLoaded = result
            if (changed || (complete && !livePublished)) {
                if (complete) livePublished = true
                publish(result, live = complete)
            }
            Log.i(TAG, "reload: ${result.size} apps, ${fresh.size} icons rendered, " +
                "${SystemClock.uptimeMillis() - t0} ms")
            if (changed) writeCache(result, fresh)
        }
    }

    private fun versionOf(info: LauncherActivityInfo): Long {
        val ai = info.applicationInfo
        if (info.user == Process.myUserHandle()) {
            try {
                return context.packageManager.getPackageInfo(ai.packageName, 0).lastUpdateTime
            } catch (e: Exception) {
                // Fall through.
            }
        }
        // Other profiles: the APK directory is renamed on every update.
        val dir = ai.sourceDir ?: return info.firstInstallTime
        return (dir.hashCode().toLong() shl 32) or ((File(dir).lastModified() / 1000) and 0xFFFFFFFFL)
    }

    private fun iconFile(key: String) =
        File(iconDir, key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".png")

    private fun writeCache(list: List<AppInfo>, fresh: Set<String>) {
        try {
            val arr = JSONArray()
            val keep = HashSet<String>()
            for (app in list) {
                val file = iconFile(app.key)
                keep += file.name
                if (app.key in fresh || !file.exists()) {
                    file.outputStream().use { app.icon.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                arr.put(JSONObject()
                    .put("key", app.key)
                    .put("component", app.component.flattenToString())
                    .put("serial", app.userSerial)
                    .put("label", app.label)
                    .put("version", app.version))
            }
            iconDir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
            val tmp = File(indexFile.parentFile, indexFile.name + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(indexFile)
        } catch (e: Exception) {
            Log.w(TAG, "could not write icon cache", e)
        }
    }

    /**
     * Icons are drawn once into a bitmap, clipped to the iOS squircle.
     * Adaptive icons are drawn full-bleed (their layers are 1.5x the visible
     * size); old-style icons are centred on a white tile. Work-profile apps
     * get their briefcase badge added on top afterwards.
     */
    private fun render(drawable: Drawable, user: UserHandle): Bitmap {
        val size = iconPx.toFloat()
        // Draw the icon into a plain layer, then mask it with an anti-aliased squircle
        // (clipPath on a bitmap canvas leaves hard, jagged edges).
        val layer = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(layer)
        if (drawable is AdaptiveIconDrawable) {
            val inset = (iconPx * 0.25f).toInt()
            for (layer in listOf(drawable.background, drawable.foreground)) {
                layer ?: continue
                layer.setBounds(-inset, -inset, iconPx + inset, iconPx + inset)
                layer.draw(canvas)
            }
        } else {
            canvas.drawColor(Color.WHITE)
            val pad = (iconPx * 0.14f).toInt()
            drawable.setBounds(pad, pad, iconPx - pad, iconPx - pad)
            drawable.draw(canvas)
        }
        val bitmap = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawPath(Glass.squircle(size), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(layer, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        })
        layer.recycle()
        if (user == Process.myUserHandle()) return bitmap
        val badged = context.packageManager.getUserBadgedIcon(BitmapDrawable(context.resources, bitmap), user)
        val out = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
        badged.setBounds(0, 0, iconPx, iconPx)
        badged.draw(Canvas(out))
        return out
    }

    fun launch(app: AppInfo, source: View) {
        val loc = IntArray(2)
        source.getLocationOnScreen(loc)
        val bounds = Rect(loc[0], loc[1], loc[0] + source.width, loc[1] + source.height)
        // The app window grows out of the icon. (The full iOS icon-to-window
        // morph needs a system-only API, so this is the closest available.)
        val options = ActivityOptions
            .makeScaleUpAnimation(source, 0, 0, source.width, source.height)
            .toBundle()
        try {
            launcherApps.startMainActivity(app.component, app.user, bounds, options)
        } catch (e: Exception) {
            Toast.makeText(context, "Can't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    fun showAppInfo(app: AppInfo, source: View) {
        try {
            launcherApps.startAppDetailsActivity(app.component, app.user, null, null)
        } catch (e: Exception) {
            Toast.makeText(context, "Can't open app info", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val TAG = "Silk"
    }
}
