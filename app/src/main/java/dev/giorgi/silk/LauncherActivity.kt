package dev.giorgi.silk

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.UserManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The home screen. One activity, no fragments:
 *
 *   LauncherRootLayout
 *     home (LinearLayout)
 *       ViewPager2        one CellLayout per home page (icons + widgets)
 *       PageIndicator     dots on a glass capsule
 *       glass dock        CellLayout with up to 4 icons
 *     DrawerView          slides up over the home screen
 */
class LauncherActivity : ComponentActivity() {

    private lateinit var repo: AppRepository
    private lateinit var store: LayoutStore
    private lateinit var widgetManager: AppWidgetManager
    private lateinit var widgetHost: AppWidgetHost

    private lateinit var root: LauncherRootLayout
    private lateinit var home: LinearLayout
    private lateinit var pager: ViewPager2
    private lateinit var indicator: PageIndicator
    private lateinit var dock: CellLayout
    private lateinit var drawer: DrawerView

    private val pagesAdapter = PagesAdapter()
    private lateinit var dockFrame: FrameLayout
    private lateinit var menu: GlassMenu

    /** Set when we open an app, so the return to home gets the zoom-out. */
    private var launchedApp = false

    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            toast("Setting wallpaper…")
            WallpaperSource.applyPhoto(this, uri) { ok -> if (!ok) toast("Couldn't use that photo") }
        }
    }

    /** Set while the "All files access" settings screen is open; re-read the wallpaper on return. */
    private var awaitingFileAccess = false

    /** Android widget views, kept so a page rebind doesn't recreate them. */
    private val widgetViews = HashMap<Int, AppWidgetHostView>()

    /** The Android widget being added while the bind/configure screens are open. */
    /** Persisted: survives Silk being killed while a widget's bind/setup screen is open. */
    private var pendingWidgetId: Int
        get() = getSharedPreferences("silk", MODE_PRIVATE).getInt("pendingWidgetId", -1)
        set(v) {
            getSharedPreferences("silk", MODE_PRIVATE).edit().putInt("pendingWidgetId", v).apply()
        }
    private var pendingWidgetInfo: AppWidgetProviderInfo? = null

    /** Frees widget ids that were allocated but never ended up on the home screen. */
    private fun deleteOrphanWidgetIds() {
        val used = store.pages.flatten().filter { it.type == HomeItem.APPWIDGET }.map { it.widgetId }.toHashSet()
        val pending = pendingWidgetId
        try {
            for (id in widgetHost.appWidgetIds) {
                if (id !in used && id != pending) widgetHost.deleteAppWidgetId(id)
            }
        } catch (e: Exception) {
            Log.w(AppRepository.TAG, "widget cleanup failed", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        repo = AppRepository(applicationContext)
        store = LayoutStore(this)
        store.load()
        widgetManager = AppWidgetManager.getInstance(this)
        widgetHost = AppWidgetHost(applicationContext, WIDGET_HOST_ID)
        deleteOrphanWidgetIds()

        buildViews()
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            home.setPadding(0, bars.top, 0, bars.bottom)
            drawer.setInsets(bars.top, maxOf(bars.bottom, ime.bottom))
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    menu.isShowing -> menu.dismiss()
                    root.isOpen -> root.close()
                }
            }
        })

        WallpaperSource.start(this) { maybeAskForWallpaper() }

        repo.onAppsChanged = { onAppsChanged() }
        repo.onPackageRemoved = { packageName, serial ->
            for (id in store.removePackage(packageName, serial)) {
                widgetViews.remove(id)
                widgetHost.deleteAppWidgetId(id)
            }
            store.save()
            refreshHome()
        }
        repo.start()
    }

    override fun onStart() {
        super.onStart()
        try {
            widgetHost.startListening()
        } catch (e: Exception) {
            Log.w(AppRepository.TAG, "widget host failed to start", e)
        }
    }

    override fun onPause() {
        super.onPause()
        menu.dismiss(immediate = true)
    }

    override fun onResume() {
        super.onResume()
        if (awaitingFileAccess) {
            awaitingFileAccess = false
            WallpaperSource.reload(this) {
                if (!WallpaperSource.available) toast("Still can't read the wallpaper. Try \"Choose a photo\".")
            }
        }
        if (launchedApp) {
            launchedApp = false
            // iOS-style return: the home screen settles back from slightly zoomed in.
            for (v in listOf(pager, indicator, dockFrame)) {
                Motion.cancel(v)
                // One GPU layer for the zoom: the content is drawn once, then only scaled.
                v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                v.scaleX = 1.07f
                v.scaleY = 1.07f
                v.alpha = 0.4f
                Motion.scale(v, 1f, 320f, 0.82f)
                Motion.spring(v, DynamicAnimation.ALPHA, 1f, 300f, 1f) {
                    v.setLayerType(View.LAYER_TYPE_NONE, null)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            widgetHost.stopListening()
        } catch (e: Exception) {
            // Ignored: the host may already be stopped.
        }
    }

    override fun onDestroy() {
        repo.stop()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action != Intent.ACTION_MAIN) return
        val alreadyOnHome = hasWindowFocus()
        if (menu.isShowing) menu.dismiss()
        if (root.isOpen) {
            root.close()
        } else if (alreadyOnHome) {
            pager.setCurrentItem(0, true)
        }
    }

    private fun buildViews() {
        indicator = PageIndicator(this)
        indicator.count = store.pages.size

        pager = ViewPager2(this)
        pager.adapter = pagesAdapter
        pager.offscreenPageLimit = 1
        (pager.getChildAt(0) as? RecyclerView)?.apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            setItemViewCacheSize(10) // built pages stay built; swiping back never rebuilds them
        }
        // Depth while swiping: the outgoing and incoming pages shrink and fade a little.
        pager.setPageTransformer { page, position ->
            val a = abs(position.coerceIn(-1f, 1f))
            val s = 1f - 0.08f * a
            page.scaleX = s
            page.scaleY = s
            page.alpha = 1f - 0.45f * a
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                indicator.position = position + positionOffset
                // Widgets move over a still wallpaper, so their glass must redraw.
                WallpaperSource.invalidatePagerGlass()
            }
        })

        dock = CellLayout(this, Dimens.DOCK_SLOTS, 1)
        dock.setPadding(dp(6), 0, dp(6), 0)
        dockFrame = FrameLayout(this)
        dockFrame.background = LiquidGlass(dockFrame, dpf(34f), 0.04f, followsPager = false)
        dockFrame.addView(dock, FrameLayout.LayoutParams(MATCH, MATCH))

        home = LinearLayout(this)
        home.orientation = LinearLayout.VERTICAL
        home.addView(pager, LinearLayout.LayoutParams(MATCH, 0, 1f))
        home.addView(indicator, LinearLayout.LayoutParams(MATCH, dp(34)))
        val dockParams = LinearLayout.LayoutParams(MATCH, dp(Dimens.DOCK_HEIGHT_DP))
        dockParams.setMargins(dp(12), 0, dp(12), dp(8))
        home.addView(dockFrame, dockParams)

        drawer = DrawerView(this)
        drawer.onAppClick = { app, view -> launch(app, view) }
        drawer.onAppLongClick = { app, view -> showDrawerIconMenu(app, view) }

        root = LauncherRootLayout(this, home, Backdrop(this), drawer)
        root.onSwipeDown = { expandNotifications() }
        menu = GlassMenu(root)
        menu.onShowingChanged = { showing -> root.gesturesEnabled = !showing }
        root.homeSettling = { pager.scrollState == ViewPager2.SCROLL_STATE_SETTLING }
    }

    private fun onAppsChanged() {
        if (repo.apps.isNotEmpty()) {
            if (store.isFirstRun) seedDock()
            // Seed from the complete live list, so no apps are missed.
            if (!store.homeSeeded && repo.isLive) seedHome()
        }
        pruneDeadWidgets()
        pruneMissingApps()
        drawer.setApps(repo.apps)
        refreshHome()
    }

    /** Widgets whose app was uninstalled (even while Silk wasn't running) leave no holes. */
    private fun pruneDeadWidgets() {
        var changed = false
        for (page in store.pages) {
            val it = page.iterator()
            while (it.hasNext()) {
                val item = it.next()
                if (item.type == HomeItem.APPWIDGET && widgetManager.getAppWidgetInfo(item.widgetId) == null) {
                    widgetViews.remove(item.widgetId)
                    widgetHost.deleteAppWidgetId(item.widgetId)
                    it.remove()
                    changed = true
                }
            }
        }
        if (changed) store.save()
    }

    /** Apps uninstalled while Silk wasn't running: drop their icons once the full list is known. */
    private fun pruneMissingApps() {
        if (!repo.isLive) return
        // Only the main profile: a paused work profile can legitimately list nothing.
        val mine = "#" + getSystemService(UserManager::class.java)
            .getSerialNumberForUser(Process.myUserHandle())
        fun gone(key: String) = key.endsWith(mine) && repo.find(key) == null
        var changed = store.dock.removeAll { gone(it) }
        for (page in store.pages) {
            if (page.removeAll { it.type == HomeItem.APP && gone(it.key) }) changed = true
        }
        if (changed) store.save()
    }

    private fun seedDock() {
        val probes = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
        )
        for (probe in probes) {
            if (store.dock.size >= Dimens.DOCK_SLOTS) break
            val packageName = packageManager.resolveActivity(probe, 0)
                ?.activityInfo?.packageName ?: continue
            val app = repo.findByPackage(packageName) ?: continue
            if (app.key !in store.dock) store.dock += app.key
        }
        store.save()
    }

    /**
     * iOS-style first fill: clock, calendar and battery widgets on page 1,
     * then every app not in the dock, alphabetically, across the pages.
     * Runs once (only while the home screen is still empty).
     */
    private fun seedHome() {
        if (repo.apps.isEmpty()) return
        if (store.isHomeEmpty()) {
            for ((key, _, span) in SilkWidgets.all) {
                store.place(HomeItem.SILK, key, 0, span.first, span.second)
            }
            for (app in repo.apps) {
                if (app.key !in store.dock) store.addToHome(app.key, 0)
            }
        }
        store.homeSeeded = true
        store.save()
    }

    private fun refreshHome() {
        pagesAdapter.notifyDataSetChanged()
        indicator.count = store.pages.size

        dock.removeAllViews()
        var slot = 0
        for (key in store.dock) {
            val app = repo.find(key) ?: continue
            if (slot >= Dimens.DOCK_SLOTS) break
            val icon = IconView(this)
            icon.bind(app, showLabel = false)
            icon.setOnClickListener { launch(app, icon) }
            icon.setOnLongClickListener {
                showMenu(
                    icon,
                    "Remove from Dock" to {
                        store.dock.remove(key)
                        store.save()
                        refreshHome()
                    },
                    "App info" to { repo.showAppInfo(app, icon) },
                )
                true
            }
            dock.addAt(icon, slot, 0)
            slot++
        }
    }

    // ---- menus -----------------------------------------------------------

    private fun launch(app: AppInfo, view: View) {
        launchedApp = true
        repo.launch(app, view)
        // The launch has read the icon's position; now the library can close behind the app.
        if (root.isOpen) root.close()
    }

    private fun showMenu(anchor: View?, vararg entries: Pair<String, () -> Unit>) {
        menu.show(anchor, null, entries.toList())
    }

    // ---- wallpaper for Liquid Glass -----------------------------------------

    private fun pickPhoto() {
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    /** Once: if the wallpaper can't be read, explain and offer the two ways to fix it. */
    private fun maybeAskForWallpaper() {
        if (isFinishing || isDestroyed) return
        if (WallpaperSource.available) return
        val prefs = getSharedPreferences("silk", MODE_PRIVATE)
        if (prefs.getBoolean("askedWallpaper", false)) return
        prefs.edit().putBoolean("askedWallpaper", true).apply()
        root.post {
            menu.show(
                null, "Liquid Glass needs to see your wallpaper",
                listOf(
                    "Use my current wallpaper" to { requestFileAccess() },
                    "Choose a wallpaper photo" to { pickPhoto() },
                    "Not now" to {},
                )
            )
        }
    }

    /**
     * Android 13 only lets apps read the wallpaper image with "All files
     * access". It's optional: picking a photo inside Silk works without it.
     */
    private fun requestFileAccess() {
        awaitingFileAccess = true
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: Exception) {
                awaitingFileAccess = false
                toast("Can't open the file access settings")
            }
        }
    }

    private fun showWallpaperMenu() {
        menu.show(
            null, "Wallpaper",
            listOf(
                "Choose a photo" to { pickPhoto() },
                "Use my current wallpaper" to { requestFileAccess() },
                "System wallpaper settings" to {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
                },
            )
        )
    }

    private fun showHomeIconMenu(app: AppInfo, item: HomeItem, anchor: View) {
        showMenu(
            anchor,
            "Remove from Home" to {
                store.remove(item)
                store.save()
                refreshHome()
            },
            "Add to Dock" to { addToDock(app) },
            "App info" to { repo.showAppInfo(app, anchor) },
            "Uninstall" to { uninstall(app) },
        )
    }

    private fun showWidgetMenu(item: HomeItem, anchor: View) {
        showMenu(
            anchor,
            "Remove widget" to {
                if (item.type == HomeItem.APPWIDGET) {
                    widgetViews.remove(item.widgetId)
                    widgetHost.deleteAppWidgetId(item.widgetId)
                }
                store.remove(item)
                store.save()
                refreshHome()
            },
        )
    }

    private fun showDrawerIconMenu(app: AppInfo, anchor: View) {
        showMenu(
            anchor,
            "Add to Home" to {
                val page = store.addToHome(app.key, pager.currentItem)
                if (page < 0) {
                    toast("Already on Home")
                } else {
                    store.save()
                    refreshHome()
                    root.close()
                    pager.setCurrentItem(page, false)
                }
            },
            "Add to Dock" to { addToDock(app) },
            "App info" to { repo.showAppInfo(app, anchor) },
            "Uninstall" to { uninstall(app) },
        )
    }

    private fun addToDock(app: AppInfo) {
        when {
            app.key in store.dock -> toast("Already in the Dock")
            store.dock.count { repo.find(it) != null } >= Dimens.DOCK_SLOTS -> toast("The Dock is full")
            else -> {
                store.dock += app.key
                store.save()
                refreshHome()
            }
        }
    }

    private fun showHomeMenu() {
        showMenu(
            indicator,
            "Add widget" to { showWidgetPicker() },
            "Add page" to {
                val page = store.addPage()
                store.save()
                refreshHome()
                pager.setCurrentItem(page, true)
            },
            "Remove this page" to {
                if (store.removePage(pager.currentItem)) {
                    store.save()
                    refreshHome()
                } else {
                    toast("Only an empty page can be removed, and one page must stay")
                }
            },
            "Wallpaper" to { showWallpaperMenu() },
            "Default home app" to { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) },
        )
    }

    // ---- widgets ---------------------------------------------------------

    private fun showWidgetPicker() {
        val providers = try {
            widgetManager.getInstalledProvidersForProfile(Process.myUserHandle())
        } catch (e: Exception) {
            emptyList()
        }
        class Entry(val title: String, val action: () -> Unit)

        val entries = ArrayList<Entry>()
        for ((key, title, span) in SilkWidgets.all) {
            entries += Entry("Silk · $title  (${span.first}×${span.second})") {
                val page = store.place(HomeItem.SILK, key, pager.currentItem, span.first, span.second)
                store.save()
                refreshHome()
                pager.setCurrentItem(page, true)
            }
        }
        val androidEntries = providers.map { info ->
            val appName = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(info.provider.packageName, 0)
                ).toString()
            } catch (e: Exception) {
                info.provider.packageName
            }
            Entry("$appName · ${info.loadLabel(packageManager)}") { addAppWidget(info) }
        }.sortedBy { it.title.lowercase() }
        entries += androidEntries

        menu.show(null, "Add widget", entries.map { it.title to it.action })
    }

    /**
     * Adding an Android widget takes up to three steps:
     *  1. allocate an id from our AppWidgetHost
     *  2. bind it to the provider; the first time per app, Android shows an
     *     "Allow Silk to create widgets?" screen
     *  3. if the widget has a setup screen, open it
     */
    @Suppress("DEPRECATION")
    private fun addAppWidget(info: AppWidgetProviderInfo) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        pendingWidgetInfo = info
        if (widgetManager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)) {
            configureOrPlace(id)
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            startActivityForResult(intent, REQ_BIND)
        }
    }

    private fun configureOrPlace(id: Int) {
        // After Silk was recreated, pendingWidgetInfo is gone; the bound widget still knows its provider.
        val info = widgetManager.getAppWidgetInfo(id) ?: pendingWidgetInfo
        if (info == null) {
            widgetHost.deleteAppWidgetId(id)
            pendingWidgetId = -1
            return
        }
        pendingWidgetInfo = info
        if (info.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, null)
                return
            } catch (e: Exception) {
                Log.w(AppRepository.TAG, "widget setup screen failed", e)
            }
        }
        placeAppWidget(id)
    }

    @Deprecated("Widget binding still uses the platform result API")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_BIND && requestCode != REQ_CONFIGURE) return
        val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId) ?: pendingWidgetId
        if (resultCode != RESULT_OK) {
            if (id != -1) widgetHost.deleteAppWidgetId(id)
            pendingWidgetInfo = null
            pendingWidgetId = -1
            return
        }
        if (requestCode == REQ_BIND) configureOrPlace(id) else placeAppWidget(id)
    }

    private fun placeAppWidget(id: Int) {
        val info = widgetManager.getAppWidgetInfo(id) ?: pendingWidgetInfo
        pendingWidgetInfo = null
        pendingWidgetId = -1
        if (info == null) {
            widgetHost.deleteAppWidgetId(id)
            return
        }
        // Same maths as the page's CellLayout, minus its padding.
        val cellW = (if (pager.width > 0) pager.width - 2 * dp(10) else resources.displayMetrics.widthPixels) / Dimens.GRID_COLS
        val cellH = if (pager.height > 0) (pager.height - dp(8)) / Dimens.GRID_ROWS else cellW
        val spanX = if (info.targetCellWidth > 0) info.targetCellWidth
            else ceil(info.minWidth / cellW.toDouble()).toInt()
        val spanY = if (info.targetCellHeight > 0) info.targetCellHeight
            else ceil(info.minHeight / cellH.toDouble()).toInt()
        val page = store.place(
            HomeItem.APPWIDGET, info.provider.flattenToString(), pager.currentItem,
            spanX, spanY, id
        )
        store.save()
        refreshHome()
        pager.setCurrentItem(page, true)
    }

    private fun widgetView(item: HomeItem): View? {
        if (item.type == HomeItem.SILK) return SilkWidgets.create(this, item.key)
        widgetViews[item.widgetId]?.let { cached ->
            (cached.parent as? ViewGroup)?.removeView(cached)
            return cached
        }
        val info = widgetManager.getAppWidgetInfo(item.widgetId) ?: return null
        val view = widgetHost.createView(applicationContext, item.widgetId, info)
        // Tell the widget how big it really is, so it can pick a matching layout.
        view.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) {
                val d = resources.displayMetrics.density
                (v as AppWidgetHostView).updateAppWidgetSize(
                    Bundle(), listOf(SizeF((r - l) / d, (b - t) / d))
                )
            }
        }
        widgetViews[item.widgetId] = view
        return view
    }

    // ---- misc ------------------------------------------------------------

    private fun uninstall(app: AppInfo) {
        try {
            val intent = Intent(
                Intent.ACTION_DELETE,
                Uri.fromParts("package", app.component.packageName, null)
            )
            intent.putExtra(Intent.EXTRA_USER, app.user)
            startActivity(intent)
        } catch (e: Exception) {
            toast("Can't uninstall ${app.label}")
        }
    }

    /**
     * Opens the notification shade. StatusBarManager.expandNotificationsPanel()
     * is hidden from the SDK but allowed for normal apps (with the
     * EXPAND_STATUS_BAR permission), so it is called by reflection.
     */
    @SuppressLint("WrongConstant")
    private fun expandNotifications() {
        try {
            val statusBar = getSystemService("statusbar") ?: return
            statusBar.javaClass.getMethod("expandNotificationsPanel").invoke(statusBar)
        } catch (e: Exception) {
            Log.w(AppRepository.TAG, "could not open notifications", e)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ---- home pages ------------------------------------------------------

    private class PageHolder(val cells: CellLayout) : RecyclerView.ViewHolder(cells)

    private inner class PagesAdapter : RecyclerView.Adapter<PageHolder>() {

        override fun getItemCount(): Int = store.pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val cells = CellLayout(this@LauncherActivity, Dimens.GRID_COLS, Dimens.GRID_ROWS)
            cells.layoutParams = RecyclerView.LayoutParams(MATCH, MATCH)
            cells.setPadding(dp(10), dp(8), dp(10), 0)
            cells.setOnLongClickListener { v ->
                v.parent?.requestDisallowInterceptTouchEvent(true)
                showHomeMenu()
                true
            }
            return PageHolder(cells)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val cells = holder.cells
            cells.removeAllViews()
            for (item in store.pages[position]) {
                if (item.type == HomeItem.APP) {
                    val app = repo.find(item.key) ?: continue
                    val icon = IconView(this@LauncherActivity)
                    icon.bind(app)
                    icon.setOnClickListener { launch(app, icon) }
                    icon.setOnLongClickListener {
                        showHomeIconMenu(app, item, icon)
                        true
                    }
                    cells.addAt(icon, item.x, item.y)
                } else {
                    val content = widgetView(item) ?: continue
                    val frame = WidgetFrame(this@LauncherActivity)
                    frame.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
                    frame.onLongPress = { showWidgetMenu(item, frame) }
                    cells.addAt(frame, item.x, item.y, item.spanX, item.spanY, fill = true)
                }
            }
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WIDGET_HOST_ID = 1024
        const val REQ_BIND = 11
        const val REQ_CONFIGURE = 12
    }
}
