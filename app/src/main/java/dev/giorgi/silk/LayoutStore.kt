package dev.giorgi.silk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** An icon on a home page, at a grid cell. */
class HomeItem(val key: String, var x: Int, var y: Int)

/**
 * The saved home-screen layout: pages of icons plus the dock.
 * Stored as one small JSON file in the app's private storage.
 * Mutate only from the main thread, then call [save].
 */
class LayoutStore(context: Context) {

    private val file = File(context.filesDir, "layout.json")
    private val io = Executors.newSingleThreadExecutor()

    val pages: MutableList<MutableList<HomeItem>> = mutableListOf()
    val dock: MutableList<String> = mutableListOf()

    /** True until the first [save]; used to seed the dock once. */
    var isFirstRun = false
        private set

    fun load() {
        pages.clear()
        dock.clear()
        if (!file.exists()) {
            isFirstRun = true
        } else {
            try {
                val root = JSONObject(file.readText())
                val dockJson = root.optJSONArray("dock") ?: JSONArray()
                for (i in 0 until dockJson.length()) dock += dockJson.getString(i)
                val pagesJson = root.optJSONArray("pages") ?: JSONArray()
                for (p in 0 until pagesJson.length()) {
                    val pageJson = pagesJson.getJSONArray(p)
                    val page = mutableListOf<HomeItem>()
                    for (i in 0 until pageJson.length()) {
                        val item = pageJson.getJSONObject(i)
                        page += HomeItem(item.getString("key"), item.getInt("x"), item.getInt("y"))
                    }
                    pages += page
                }
            } catch (e: Exception) {
                // Corrupt file: start over rather than crash the home screen.
                pages.clear()
                dock.clear()
            }
        }
        if (pages.isEmpty()) pages += mutableListOf<HomeItem>()
    }

    fun save() {
        isFirstRun = false
        val root = JSONObject()
        root.put("dock", JSONArray(dock))
        val pagesJson = JSONArray()
        for (page in pages) {
            val pageJson = JSONArray()
            for (item in page) {
                pageJson.put(JSONObject().put("key", item.key).put("x", item.x).put("y", item.y))
            }
            pagesJson.put(pageJson)
        }
        root.put("pages", pagesJson)
        val text = root.toString()
        io.execute {
            // Write to a temp file and rename, so a crash mid-write can't corrupt the layout.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
        }
    }

    private fun firstFreeCell(page: List<HomeItem>): Pair<Int, Int>? {
        for (y in 0 until Dimens.GRID_ROWS) {
            for (x in 0 until Dimens.GRID_COLS) {
                if (page.none { it.x == x && it.y == y }) return x to y
            }
        }
        return null
    }

    /** Puts the app in the first free cell from [startPage] on. Returns the page used. */
    fun addToHome(key: String, startPage: Int): Int {
        for (p in startPage.coerceIn(0, pages.size - 1) until pages.size) {
            val cell = firstFreeCell(pages[p]) ?: continue
            pages[p] += HomeItem(key, cell.first, cell.second)
            return p
        }
        pages += mutableListOf(HomeItem(key, 0, 0))
        return pages.size - 1
    }

    fun removeFromHome(item: HomeItem) {
        for (page in pages) page.remove(item)
    }

    fun addPage(): Int {
        pages += mutableListOf<HomeItem>()
        return pages.size - 1
    }

    /** Only empty pages can be removed, and one page always remains. */
    fun removePage(index: Int): Boolean {
        if (pages.size <= 1 || index !in pages.indices || pages[index].isNotEmpty()) return false
        pages.removeAt(index)
        return true
    }

    /** Drops every icon of an uninstalled package. Keys look like "pkg/class#userSerial". */
    fun removePackage(packageName: String, userSerial: Long) {
        val prefix = "$packageName/"
        val suffix = "#$userSerial"
        for (page in pages) page.removeAll { it.key.startsWith(prefix) && it.key.endsWith(suffix) }
        dock.removeAll { it.startsWith(prefix) && it.endsWith(suffix) }
    }
}
