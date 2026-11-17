package dev.giorgi.silk

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.view.View
import android.widget.Toast
import java.text.Collator
import java.util.concurrent.Executors

/** One launchable activity. [key] is what gets stored in the saved layout. */
class AppInfo(
    val component: ComponentName,
    val user: UserHandle,
    val label: String,
    val icon: Bitmap,
    val key: String,
)

/**
 * Source of truth for "which apps exist".
 *
 * LauncherApps is the launcher-specific system service: unlike PackageManager
 * it also covers the work profile, gives badged icons, and tells us when
 * packages are installed, updated or removed.
 *
 * Everything slow (listing apps, rendering icons) runs on a background thread;
 * results are published on the main thread.
 */
class AppRepository(private val context: Context) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val iconPx = (Dimens.ICON_DP * context.resources.displayMetrics.density).toInt()

    var apps: List<AppInfo> = emptyList()
        private set
    private var byKey: Map<String, AppInfo> = emptyMap()

    /** Called on the main thread whenever [apps] has been replaced. */
    var onAppsChanged: (() -> Unit)? = null

    /** Called on the main thread when a package is really uninstalled. */
    var onPackageRemoved: ((packageName: String, userSerial: Long) -> Unit)? = null

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            this@AppRepository.onPackageRemoved?.invoke(packageName, userManager.getSerialNumberForUser(user))
            reload()
        }

        override fun onPackageAdded(packageName: String, user: UserHandle) = reload()

        override fun onPackageChanged(packageName: String, user: UserHandle) = reload()

        override fun onPackagesAvailable(
            packageNames: Array<String>, user: UserHandle, replacing: Boolean
        ) = reload()

        override fun onPackagesUnavailable(
            packageNames: Array<String>, user: UserHandle, replacing: Boolean
        ) = reload()
    }

    fun start() {
        launcherApps.registerCallback(callback, main)
        reload()
    }

    fun stop() {
        launcherApps.unregisterCallback(callback)
    }

    fun find(key: String): AppInfo? = byKey[key]

    fun findByPackage(packageName: String): AppInfo? {
        val me = Process.myUserHandle()
        return apps.firstOrNull { it.component.packageName == packageName && it.user == me }
    }

    fun reload() {
        io.execute {
            val result = ArrayList<AppInfo>()
            try {
                for (user in userManager.userProfiles) {
                    val serial = userManager.getSerialNumberForUser(user)
                    for (info in launcherApps.getActivityList(null, user)) {
                        val component = info.componentName
                        result += AppInfo(
                            component = component,
                            user = user,
                            label = info.label?.toString() ?: component.packageName,
                            icon = render(info.getBadgedIcon(0)),
                            key = component.flattenToString() + "#" + serial,
                        )
                    }
                }
            } catch (e: Exception) {
                // A profile can disappear while we iterate; the next callback reloads.
            }
            val collator = Collator.getInstance()
            result.sortWith { a, b -> collator.compare(a.label, b.label) }
            val map = result.associateBy { it.key }
            main.post {
                apps = result
                byKey = map
                onAppsChanged?.invoke()
            }
        }
    }

    /**
     * Icons are drawn once into a bitmap. Adaptive icons are layered vector
     * drawables and expensive to draw every frame; a bitmap is one texture
     * the GPU can scale and move for free during animations.
     */
    private fun render(drawable: Drawable): Bitmap {
        val bitmap = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, iconPx, iconPx)
        drawable.draw(canvas)
        return bitmap
    }

    fun launch(app: AppInfo, source: View) {
        val loc = IntArray(2)
        source.getLocationOnScreen(loc)
        val bounds = Rect(loc[0], loc[1], loc[0] + source.width, loc[1] + source.height)
        // The best open animation an unprivileged launcher is allowed to ask for:
        // the new window is revealed growing out of the icon.
        val options = ActivityOptions
            .makeClipRevealAnimation(source, 0, 0, source.width, source.height)
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
}
