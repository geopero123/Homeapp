package dev.giorgi.silk

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** The app drawer: a search box above an alphabetical, vertically scrolling grid. */
class DrawerView(context: Context) : LinearLayout(context) {

    var onAppClick: ((AppInfo, View) -> Unit)? = null
    var onAppLongClick: ((AppInfo, View) -> Unit)? = null

    private val search = EditText(context)
    private val list = RecyclerView(context)
    private val adapter = AppsAdapter()
    private var allApps: List<AppInfo> = emptyList()

    init {
        orientation = LinearLayout.VERTICAL
        // Consume touches so nothing falls through to the home screen underneath,
        // and take initial focus so the search box doesn't pop the keyboard.
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        // Plain dark scrim for now; Phase 2 replaces it with a real blur.
        setBackgroundColor(0xE6101216.toInt())

        search.hint = "Search apps"
        search.setSingleLine(true)
        search.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        search.imeOptions = EditorInfo.IME_ACTION_GO
        search.setTextColor(Color.WHITE)
        search.setHintTextColor(0x99FFFFFF.toInt())
        search.background = GradientDrawable().apply {
            cornerRadius = context.dp(24).toFloat()
            setColor(0x33FFFFFF)
        }
        search.setPadding(context.dp(20), 0, context.dp(20), 0)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        // "Go" on the keyboard opens the first result.
        search.setOnEditorActionListener { v, actionId, _ ->
            val first = adapter.items.firstOrNull()
            if (actionId == EditorInfo.IME_ACTION_GO && first != null) {
                onAppClick?.invoke(first, v)
                true
            } else {
                false
            }
        }
        val searchParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, context.dp(48)
        )
        searchParams.setMargins(context.dp(16), context.dp(12), context.dp(16), context.dp(8))
        addView(search, searchParams)

        list.layoutManager = GridLayoutManager(context, Dimens.DRAWER_COLS)
        list.adapter = adapter
        list.setHasFixedSize(true)
        list.clipToPadding = false
        list.overScrollMode = View.OVER_SCROLL_NEVER
        addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun setApps(apps: List<AppInfo>) {
        allApps = apps
        applyFilter()
    }

    fun setInsets(top: Int, bottom: Int) {
        setPadding(0, top, 0, 0)
        list.setPadding(0, 0, 0, bottom)
    }

    /** True when the list can't scroll further up, so a downward drag should close the drawer. */
    fun isAtTop(): Boolean = !list.canScrollVertically(-1)

    /** Called when the drawer has fully closed. */
    fun reset() {
        context.getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(windowToken, 0)
        search.clearFocus()
        if (search.text.isNotEmpty()) search.text.clear()
        list.scrollToPosition(0)
    }

    private fun applyFilter() {
        val query = search.text.toString().trim()
        adapter.items =
            if (query.isEmpty()) allApps
            else allApps.filter { it.label.contains(query, ignoreCase = true) }
        adapter.notifyDataSetChanged()
    }

    private class Holder(val icon: IconView) : RecyclerView.ViewHolder(icon)

    private inner class AppsAdapter : RecyclerView.Adapter<Holder>() {
        var items: List<AppInfo> = emptyList()

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
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
            return Holder(icon)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.icon.bind(items[position])
        }
    }
}
