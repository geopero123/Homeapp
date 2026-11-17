package dev.giorgi.silk

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * The app drawer, One UI style: a search pill on top, apps in horizontal
 * pages of a fixed grid with page dots below. While a search query is typed,
 * the pages are swapped for a scrolling list of matches.
 */
class DrawerView(context: Context) : LinearLayout(context) {

    var onAppClick: ((AppInfo, View) -> Unit)? = null
    var onAppLongClick: ((AppInfo, View) -> Unit)? = null

    private val search = EditText(context)
    private val pager = ViewPager2(context)
    private val dots = PageIndicator(context)
    private val results = RecyclerView(context)
    private val pagesAdapter = PagesAdapter()
    private val resultsAdapter = ResultsAdapter()
    private var allApps: List<AppInfo> = emptyList()
    private var pages: List<List<AppInfo>> = emptyList()

    private val perPage = Dimens.DRAWER_COLS * Dimens.DRAWER_ROWS

    init {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(0xE6121214.toInt())

        search.hint = "App Library"
        search.setSingleLine(true)
        search.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        search.imeOptions = EditorInfo.IME_ACTION_GO
        search.setTextColor(Color.WHITE)
        search.setHintTextColor(0xB3FFFFFF.toInt())
        search.textSize = 17f
        search.gravity = Gravity.CENTER
        search.background = GradientDrawable().apply {
            cornerRadius = context.dp(14).toFloat()
            setColor(0x24FFFFFF)
        }
        search.setPadding(context.dp(22), 0, context.dp(22), 0)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        search.setOnEditorActionListener { v, actionId, _ ->
            val first = resultsAdapter.items.firstOrNull()
            if (actionId == EditorInfo.IME_ACTION_GO && first != null) {
                onAppClick?.invoke(first, v)
                true
            } else {
                false
            }
        }
        val searchParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(44))
        searchParams.setMargins(context.dp(20), context.dp(14), context.dp(20), context.dp(10))
        addView(search, searchParams)

        pager.adapter = pagesAdapter
        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                dots.position = position + positionOffset
            }
        })
        (pager.getChildAt(0) as? RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER

        results.layoutManager = GridLayoutManager(context, Dimens.DRAWER_COLS)
        results.adapter = resultsAdapter
        results.overScrollMode = View.OVER_SCROLL_NEVER
        results.clipToPadding = false
        results.visibility = View.GONE

        val content = FrameLayout(context)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        content.addView(pager, FrameLayout.LayoutParams(match, match))
        content.addView(results, FrameLayout.LayoutParams(match, match))
        addView(content, LinearLayout.LayoutParams(match, 0, 1f))
        addView(dots, LinearLayout.LayoutParams(match, context.dp(28)))
    }

    fun setApps(apps: List<AppInfo>) {
        allApps = apps
        pages = apps.chunked(perPage)
        pagesAdapter.notifyDataSetChanged()
        dots.count = pages.size
        applyFilter()
    }

    fun setInsets(top: Int, bottom: Int) {
        setPadding(0, top, 0, bottom)
    }

    /** True when a downward drag should close the drawer instead of scrolling. */
    fun isAtTop(): Boolean =
        results.visibility != View.VISIBLE || !results.canScrollVertically(-1)

    /** Called when the drawer has fully closed. */
    fun reset() {
        context.getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(windowToken, 0)
        search.clearFocus()
        if (search.text.isNotEmpty()) search.text.clear()
        pager.setCurrentItem(0, false)
        results.scrollToPosition(0)
    }

    private fun applyFilter() {
        val query = search.text.toString().trim()
        val searching = query.isNotEmpty()
        results.visibility = if (searching) View.VISIBLE else View.GONE
        pager.visibility = if (searching) View.INVISIBLE else View.VISIBLE
        dots.visibility = if (searching) View.INVISIBLE else View.VISIBLE
        resultsAdapter.items =
            if (searching) allApps.filter { it.label.contains(query, ignoreCase = true) }
            else emptyList()
        resultsAdapter.notifyDataSetChanged()
    }

    private fun makeIcon(app: AppInfo): IconView {
        val icon = IconView(context)
        icon.bind(app)
        icon.setOnClickListener { v -> onAppClick?.invoke(app, v) }
        icon.setOnLongClickListener { v ->
            onAppLongClick?.invoke(app, v)
            true
        }
        return icon
    }

    // ---- pages ----------------------------------------------------------------

    private class PageHolder(val cells: CellLayout) : RecyclerView.ViewHolder(cells)

    private inner class PagesAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount(): Int = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val cells = CellLayout(parent.context, Dimens.DRAWER_COLS, Dimens.DRAWER_ROWS)
            cells.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            cells.setPadding(context.dp(8), 0, context.dp(8), 0)
            return PageHolder(cells)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val cells = holder.cells
            cells.removeAllViews()
            pages[position].forEachIndexed { i, app ->
                cells.addAt(makeIcon(app), i % Dimens.DRAWER_COLS, i / Dimens.DRAWER_COLS)
            }
        }
    }

    // ---- search results -------------------------------------------------------

    private class ResultHolder(val icon: IconView) : RecyclerView.ViewHolder(icon)

    private inner class ResultsAdapter : RecyclerView.Adapter<ResultHolder>() {
        var items: List<AppInfo> = emptyList()

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultHolder {
            val icon = IconView(parent.context)
            icon.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            icon.setOnClickListener { v ->
                val app = (v as IconView).app
                if (app != null) onAppClick?.invoke(app, v)
            }
            icon.setOnLongClickListener { v ->
                val app = (v as IconView).app
                if (app != null) onAppLongClick?.invoke(app, v)
                true
            }
            return ResultHolder(icon)
        }

        override fun onBindViewHolder(holder: ResultHolder, position: Int) {
            holder.icon.bind(items[position])
        }
    }
}
