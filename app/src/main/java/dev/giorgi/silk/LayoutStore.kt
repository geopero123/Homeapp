package dev.giorgi.silk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
 */
class LayoutStore(context: Context) {

    private val file = File(context.filesDir, "layout.json")
    private val io = Executors.newSingleThreadExecutor()

    val pages: MutableList<MutableList<HomeItem>> = mutableListOf()
    val dock: MutableList<String> = mutableListOf()

    var isFirstRun = false
        private set

    /** Set once the home screen has been filled with widgets and apps. */
    var homeSeeded = false

    fun load() {
        pages.clear()
        dock.clear()
        if (!file.exists()) {
            isFirstRun = true
        } else {
            try {
                val root = JSONObject(file.readText())
                homeSeeded = root.optBoolean("homeSeeded", false)
                val dockJson = root.optJSONArray("dock") ?: JSONArray()
                for (i in 0 until dockJson.length()) dock += dockJson.getString(i)
                val pagesJson = root.optJSONArray("pages") ?: JSONArray()
                for (p in 0 until pagesJson.length()) {
                    val pageJson = pagesJson.getJSONArray(p)
                    val page = mutableListOf<HomeItem>()
                    for (i in 0 until pageJson.length()) {
                        val o = pageJson.getJSONObject(i)
                        page += HomeItem(
                            o.optString("type", HomeItem.APP), o.getString("key"),
                            o.getInt("x"), o.getInt("y"),
                            o.optInt("spanX", 1), o.optInt("spanY", 1), o.optInt("widgetId", -1),
                        )
                    }
                    pages += page
                }
            } catch (e: Exception) {
                pages.clear()
                dock.clear()
            }
        }
        // Drop anything that no longer fits the grid (the grid size changed).
        for (page in pages) page.removeAll { it.x + it.spanX > Dimens.GRID_COLS || it.y + it.spanY > Dimens.GRID_ROWS }
        if (pages.isEmpty()) pages += mutableListOf<HomeItem>()
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
        val text = root.toString()
        io.execute {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
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
        for (p in startPage.coerceIn(0, pages.size - 1) until pages.size) {
            val cell = findFree(pages[p], sx, sy) ?: continue
            pages[p] += HomeItem(type, key, cell.first, cell.second, sx, sy, widgetId)
            return p
        }
        pages += mutableListOf(HomeItem(type, key, 0, 0, sx, sy, widgetId))
        return pages.size - 1
    }

    fun addToHome(key: String, startPage: Int): Int = place(HomeItem.APP, key, startPage)

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

    fun removePackage(packageName: String, userSerial: Long) {
        val prefix = "$packageName/"
        val suffix = "#$userSerial"
        for (page in pages) {
            page.removeAll { it.type == HomeItem.APP && it.key.startsWith(prefix) && it.key.endsWith(suffix) }
        }
        dock.removeAll { it.startsWith(prefix) && it.endsWith(suffix) }
    }
}
