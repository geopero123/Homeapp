package dev.giorgi.silk

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Something on a home page.
 *   type APP        key = app key
 *   type SILK       key = built-in widget name (clock, calendar, battery)
 *   type APPWIDGET  key = provider component, widgetId = AppWidgetHost id
 */
class HomeItem(
    val type: String,
    val key: String,
    var x: Int,
    var y: Int,
    val spanX: Int = 1,
    val spanY: Int = 1,
    val widgetId: Int = -1,
) {
    companion object {
        const val APP = "app"
        const val SILK = "silk"
        const val APPWIDGET = "appwidget"
    }
}

/**
 * The saved home-screen layout: pages of items plus the dock, stored as a
 * small JSON file in private storage. Mutate on the main thread, then [save].
 *
 * Writes go through AtomicFile (fsync + rename, with a backup) on one
 * process-wide thread, so a crash, a full disk, or two activity instances
 * can't corrupt the file, and a damaged entry only loses that entry.
 */
class LayoutStore(context: Context) {

    private val file = File(context.filesDir, "layout.json")
    private val atomic = AtomicFile(file)

    val pages: MutableList<MutableList<HomeItem>> = mutableListOf()
    val dock: MutableList<String> = mutableListOf()

    var isFirstRun = false
        private set

    /** Set once the home screen has been filled with widgets and apps. */
    var homeSeeded = false

    fun load() {
        // Let saves queued by an earlier activity instance land first.
        try {
            io.submit {}.get()
        } catch (e: Exception) {
            // Ignored: worst case we read the previous save.
        }
        pages.clear()
        dock.clear()
        homeSeeded = false
        val bak = File(file.path + ".bak")
        if (!file.exists() && !bak.exists()) {
            isFirstRun = true
        } else {
            try {
                val root = JSONObject(String(atomic.readFully(), Charsets.UTF_8))
                val dockJson = root.optJSONArray("dock") ?: JSONArray()
                val loadedDock = mutableListOf<String>()
                for (i in 0 until dockJson.length()) {
                    val k = dockJson.optString(i)
                    if (k.isNotEmpty() && k !in loadedDock) loadedDock += k
                }
                val loadedPages = mutableListOf<MutableList<HomeItem>>()
                val pagesJson = root.optJSONArray("pages") ?: JSONArray()
                for (p in 0 until pagesJson.length()) {
                    val pageJson = pagesJson.optJSONArray(p) ?: continue
                    val page = mutableListOf<HomeItem>()
                    for (i in 0 until pageJson.length()) {
                        // One bad entry is skipped; it never throws away the whole layout.
                        val o = pageJson.optJSONObject(i) ?: continue
                        val key = o.optString("key", "")
                        if (key.isEmpty() || !o.has("x") || !o.has("y")) continue
                        page += HomeItem(
                            o.optString("type", HomeItem.APP), key,
                            o.optInt("x"), o.optInt("y"),
                            o.optInt("spanX", 1), o.optInt("spanY", 1), o.optInt("widgetId", -1),
                        )
                    }
                    loadedPages += page
                }
                homeSeeded = root.optBoolean("homeSeeded", false)
                dock += loadedDock
                pages += loadedPages
            } catch (e: Exception) {
                Log.e("Silk", "layout unreadable, keeping a copy", e)
                try {
                    file.copyTo(File(file.parentFile, "layout.bad-${System.currentTimeMillis()}.json"), overwrite = true)
                } catch (e2: Exception) {
                    // Nothing more we can do.
                }
                pages.clear()
                dock.clear()
            }
        }
        // Re-place anything that no longer fits or collides, instead of deleting it.
        val old = pages.map { it.toList() }
        pages.clear()
        val displaced = mutableListOf<HomeItem>()
        for (page in old) {
            val kept = mutableListOf<HomeItem>()
            for (item in page) {
                if (item.x >= 0 && item.y >= 0 && isFree(kept, item.x, item.y, item.spanX, item.spanY)) kept += item
                else displaced += item
            }
            pages += kept
        }
        if (pages.isEmpty()) pages += mutableListOf<HomeItem>()
        for (item in displaced) place(item.type, item.key, 0, item.spanX, item.spanY, item.widgetId)
    }

    fun save() {
        isFirstRun = false
        val root = JSONObject()
        root.put("homeSeeded", homeSeeded)
        root.put("dock", JSONArray(dock))
        val pagesJson = JSONArray()
        for (page in pages) {
            val pageJson = JSONArray()
            for (item in page) {
                pageJson.put(
                    JSONObject().put("type", item.type).put("key", item.key)
                        .put("x", item.x).put("y", item.y)
                        .put("spanX", item.spanX).put("spanY", item.spanY)
                        .put("widgetId", item.widgetId)
                )
            }
            pagesJson.put(pageJson)
        }
        root.put("pages", pagesJson)
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        io.execute {
            var out: FileOutputStream? = null
            try {
                out = atomic.startWrite()
                out.write(bytes)
                atomic.finishWrite(out)
            } catch (e: Exception) {
                if (out != null) atomic.failWrite(out)
                Log.w("Silk", "layout save failed; previous file kept", e)
            }
        }
    }

    private fun isFree(page: List<HomeItem>, x: Int, y: Int, spanX: Int, spanY: Int): Boolean {
        if (x + spanX > Dimens.GRID_COLS || y + spanY > Dimens.GRID_ROWS) return false
        return page.none { o ->
            x < o.x + o.spanX && o.x < x + spanX && y < o.y + o.spanY && o.y < y + spanY
        }
    }

    private fun findFree(page: List<HomeItem>, spanX: Int, spanY: Int): Pair<Int, Int>? {
        for (y in 0 until Dimens.GRID_ROWS) {
            for (x in 0 until Dimens.GRID_COLS) {
                if (isFree(page, x, y, spanX, spanY)) return x to y
            }
        }
        return null
    }

    /** Puts an item in the first space big enough, from [startPage] on, adding a page if needed. */
    fun place(
        type: String, key: String, startPage: Int,
        spanX: Int = 1, spanY: Int = 1, widgetId: Int = -1,
    ): Int {
        val sx = spanX.coerceIn(1, Dimens.GRID_COLS)
        val sy = spanY.coerceIn(1, Dimens.GRID_ROWS)
        if (pages.isEmpty()) pages += mutableListOf<HomeItem>()
        for (p in startPage.coerceIn(0, pages.size - 1) until pages.size) {
            val cell = findFree(pages[p], sx, sy) ?: continue
            pages[p] += HomeItem(type, key, cell.first, cell.second, sx, sy, widgetId)
            return p
        }
        pages += mutableListOf(HomeItem(type, key, 0, 0, sx, sy, widgetId))
        return pages.size - 1
    }

    fun isOnHome(key: String): Boolean =
        pages.any { page -> page.any { it.type == HomeItem.APP && it.key == key } }

    /** Adds an app icon to the first free cell, or returns -1 if it's already on Home. */
    fun addToHome(key: String, startPage: Int): Int {
        if (isOnHome(key)) return -1
        return place(HomeItem.APP, key, startPage)
    }

    fun remove(item: HomeItem) {
        for (page in pages) page.remove(item)
    }

    fun isHomeEmpty(): Boolean = pages.all { it.isEmpty() }

    fun addPage(): Int {
        pages += mutableListOf<HomeItem>()
        return pages.size - 1
    }

    fun removePage(index: Int): Boolean {
        if (pages.size <= 1 || index !in pages.indices || pages[index].isNotEmpty()) return false
        pages.removeAt(index)
        return true
    }

    /** Removes an uninstalled package's icons and widgets; returns the widget ids to free. */
    fun removePackage(packageName: String, userSerial: Long): List<Int> {
        val prefix = "$packageName/"
        val suffix = "#$userSerial"
        val widgetIds = mutableListOf<Int>()
        for (page in pages) {
            page.removeAll { item ->
                val isApp = item.type == HomeItem.APP && item.key.startsWith(prefix) && item.key.endsWith(suffix)
                val isWidget = item.type == HomeItem.APPWIDGET && item.key.startsWith(prefix)
                if (isWidget) widgetIds += item.widgetId
                isApp || isWidget
            }
        }
        dock.removeAll { it.startsWith(prefix) && it.endsWith(suffix) }
        return widgetIds
    }

    private companion object {
        /** One writer for the whole process: activity instances come and go, the file doesn't. */
        val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "silk-layout").apply { isDaemon = true }
        }
    }
}
