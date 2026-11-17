package dev.giorgi.silk

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * The home screen. One activity, no fragments:
 *
 *   LauncherRootLayout
 *     home (LinearLayout)
 *       ViewPager2      one CellLayout per home page
 *       PageIndicator
 *       CellLayout      the dock
 *     DrawerView        slides up over the home screen
 */
class LauncherActivity : ComponentActivity() {

    private lateinit var repo: AppRepository
    private lateinit var store: LayoutStore

    private lateinit var root: LauncherRootLayout
    private lateinit var home: LinearLayout
    private lateinit var pager: ViewPager2
    private lateinit var indicator: PageIndicator
    private lateinit var dock: CellLayout
    private lateinit var drawer: DrawerView

    private val pagesAdapter = PagesAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the status and navigation bars, like every modern launcher.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        repo = AppRepository(applicationContext)
        store = LayoutStore(this)
        store.load()

        buildViews()
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            home.setPadding(0, bars.top, 0, bars.bottom)
            drawer.setInsets(bars.top, maxOf(bars.bottom, ime.bottom))
            insets
        }

        // On a home screen, Back closes the drawer and otherwise does nothing.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (root.isOpen) root.close()
            }
        })

        repo.onAppsChanged = { onAppsChanged() }
        repo.onPackageRemoved = { packageName, serial ->
            store.removePackage(packageName, serial)
            store.save()
        }
        repo.start()
    }

    override fun onDestroy() {
        repo.stop()
        super.onDestroy()
    }

    /**
     * Because the activity is singleTask, pressing Home while we are already
     * running arrives here instead of creating a new instance.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action != Intent.ACTION_MAIN) return
        val alreadyOnHome = hasWindowFocus()
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
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, offset: Float, offsetPixels: Int) {
                indicator.position = position + offset
            }
        })

        dock = CellLayout(this, Dimens.DOCK_SLOTS, 1)

        home = LinearLayout(this)
        home.orientation = LinearLayout.VERTICAL
        home.addView(pager, LinearLayout.LayoutParams(MATCH, 0, 1f))
        home.addView(indicator, LinearLayout.LayoutParams(MATCH, dp(20)))
        home.addView(dock, LinearLayout.LayoutParams(MATCH, dp(Dimens.DOCK_HEIGHT_DP)))

        drawer = DrawerView(this)
        drawer.onAppClick = { app, view -> repo.launch(app, view) }
        drawer.onAppLongClick = { app, view -> showDrawerIconMenu(app, view) }

        root = LauncherRootLayout(this, home, drawer)
        root.onSwipeDown = { expandNotifications() }
    }

    private fun onAppsChanged() {
        if (store.isFirstRun) seedDock()
        drawer.setApps(repo.apps)
        refreshHome()
    }

    /** First launch only: put the default phone, messages, browser and camera apps in the dock. */
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
            icon.setOnClickListener { repo.launch(app, icon) }
            icon.setOnLongClickListener {
                showMenu(
                    icon,
                    "Remove from dock" to {
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

    private fun showMenu(anchor: View, vararg entries: Pair<String, () -> Unit>) {
        val popup = PopupMenu(this, anchor)
        entries.forEachIndexed { index, entry -> popup.menu.add(0, index, index, entry.first) }
        popup.setOnMenuItemClickListener { item ->
            entries[item.itemId].second.invoke()
            true
        }
        popup.show()
    }

    private fun showHomeIconMenu(app: AppInfo, item: HomeItem, anchor: View) {
        showMenu(
            anchor,
            "Remove from home" to {
                store.removeFromHome(item)
                store.save()
                refreshHome()
            },
            "App info" to { repo.showAppInfo(app, anchor) },
            "Uninstall" to { uninstall(app) },
        )
    }

    private fun showDrawerIconMenu(app: AppInfo, anchor: View) {
        showMenu(
            anchor,
            "Add to home" to {
                val page = store.addToHome(app.key, pager.currentItem)
                store.save()
                refreshHome()
                root.close()
                pager.setCurrentItem(page, false)
            },
            "Add to dock" to {
                when {
                    app.key in store.dock -> toast("Already in the dock")
                    store.dock.size >= Dimens.DOCK_SLOTS -> toast("The dock is full")
                    else -> {
                        store.dock += app.key
                        store.save()
                        refreshHome()
                        toast("Added to dock")
                    }
                }
            },
            "App info" to { repo.showAppInfo(app, anchor) },
            "Uninstall" to { uninstall(app) },
        )
    }

    /** Long-press on empty home-screen space. */
    private fun showHomeMenu() {
        showMenu(
            indicator,
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
            "Wallpaper" to {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
            },
            "Default home app" to { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) },
        )
    }

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
            // ViewPager2 insists that every page is match_parent in both directions.
            cells.layoutParams = RecyclerView.LayoutParams(MATCH, MATCH)
            cells.setOnLongClickListener {
                showHomeMenu()
                true
            }
            return PageHolder(cells)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val cells = holder.cells
            cells.removeAllViews()
            for (item in store.pages[position]) {
                // An app that is temporarily missing keeps its cell but shows nothing.
                val app = repo.find(item.key) ?: continue
                val icon = IconView(this@LauncherActivity)
                icon.bind(app)
                icon.setOnClickListener { repo.launch(app, icon) }
                icon.setOnLongClickListener {
                    showHomeIconMenu(app, item, icon)
                    true
                }
                cells.addAt(icon, item.x, item.y)
            }
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
