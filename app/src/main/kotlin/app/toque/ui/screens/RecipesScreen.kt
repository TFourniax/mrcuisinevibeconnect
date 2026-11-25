package app.toque.ui.screens

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.Prefs
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.recipes.RecipeFilter
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.SearchIndex
import app.toque.ui.Icon
import app.toque.ui.ImageLoader
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Section
import app.toque.ui.Ui
import java.util.concurrent.Executors

/**
 * Catalogue des recettes Lidl : recherche instantanée tolérante (accents, pluriels, fautes de
 * frappe), filtres, et recherche par ingrédients (séparés par des virgules).
 */
class RecipesScreen(a: MainActivity, private val focusSearch: Boolean = false, favorites: Boolean = false, source: RecipeSource? = null) : Screen(a) {
    override val title = when {
        favorites -> tr("Favoris")
        source == RecipeSource.FOYER -> tr("Nos recettes")
        source == RecipeSource.IMPORTED -> tr("Recettes Lidl")
        else -> tr("Recettes")
    }
    override val section: Section get() = Section.RECIPES

    private var filter = RecipeFilter(favoritesOnly = favorites, source = source)
    private var query = ""
    private var results: List<SearchIndex.Hit> = emptyList()
    private var generation = 0
    private lateinit var input: EditText
    private lateinit var chips: LinearLayout
    private lateinit var grid: GridView
    private lateinit var count: TextView
    private lateinit var empty: LinearLayout
    private lateinit var emptyText: TextView
    private val adapter = Adapter()
    private var unsub: (() -> Unit)? = null
    private val runSearch = Runnable { search() }

    override fun build(): View {
        val root = Ui.vertical(c)
        root.setPadding(Ui.u(24), Ui.u(4), Ui.u(24), 0)

        val bar = Ui.horizontal(c)
        val field = Ui.horizontal(c)
        field.setPadding(Ui.u(20), 0, Ui.u(8), 0)
        field.background = Ui.round(Ui.SURFACE, 28f, Ui.STROKE, 1f)
        field.addView(Icon(c, Icon.Kind.SEARCH, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(22), Ui.u(22)).apply { rightMargin = Ui.u(14) })
        input = EditText(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.uf(17f))
            typeface = Ui.REGULAR
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT3)
            hint = tr("Chercher : « risotto », « poulet, curry »…")
            background = null
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c1: Int, a1: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c1: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString() ?: ""
                    view.removeCallbacks(runSearch)
                    view.postDelayed(runSearch, 120)
                }
            })
            setOnEditorActionListener { _, _, _ -> a.hideKeyboard(); true }
        }
        field.addView(input, LinearLayout.LayoutParams(0, Ui.MATCH, 1f))
        // Interface refaite (changement de thème) : la recherche en cours reste.
        if (query.isNotEmpty()) input.setText(query)
        val clear = Icon(c, Icon.Kind.CLOSE, Ui.TEXT2)
        clear.setPadding(Ui.u(10), Ui.u(10), Ui.u(10), Ui.u(10))
        clear.setOnClickListener { input.setText("") }
        field.addView(clear, LinearLayout.LayoutParams(Ui.u(48), Ui.u(48)))
        bar.addView(field, LinearLayout.LayoutParams(0, Ui.u(56), 1f))
        count = Ui.text(c, "", 15f, Ui.TEXT2, Ui.MEDIUM)
        bar.addView(count, Ui.lp(Ui.WRAP, Ui.WRAP, l = 18))
        root.addView(bar)

        val scroller = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        chips = Ui.horizontal(c)
        scroller.addView(chips)
        root.addView(scroller, Ui.lp(t = 12, b = 14))

        val body = FrameLayout(c)
        grid = GridView(c).apply {
            numColumns = 4
            horizontalSpacing = Ui.u(14)
            verticalSpacing = Ui.u(14)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(0)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, 0, 0, Ui.u(20))
            clipToPadding = false
            adapter = this@RecipesScreen.adapter
            setOnItemClickListener { _, _, pos, _ ->
                val hit = results.getOrNull(pos) ?: return@setOnItemClickListener
                val r = a.app.recipes.get(hit.entry.id)
                if (r != null) a.push(RecipeScreen(a, r))
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: AbsListView?, state: Int) {
                    if (state != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) a.hideKeyboard()
                }
                override fun onScroll(v: AbsListView?, first: Int, visible: Int, total: Int) {}
            })
        }
        body.addView(grid, Ui.flp())
        empty = Ui.vertical(c, Gravity.CENTER)
        empty.addView(Icon(c, Icon.Kind.BOOK, Ui.TEXT3), LinearLayout.LayoutParams(Ui.u(64), Ui.u(64)))
        emptyText = Ui.text(c, "", 21f, Ui.TEXT2).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }
        empty.addView(emptyText, Ui.lp(Ui.u(640), Ui.WRAP, t = 16))
        body.addView(empty, Ui.flp())
        root.addView(body, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        return root
    }

    private fun buildChips() {
        chips.removeAllViews()
        fun chip(label: String, selected: Boolean, f: RecipeFilter) {
            chips.addView(Ui.toggleChip(c, label, selected) { filter = f; buildChips(); search() }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        }
        val base = RecipeFilter()
        chip(tr("Toutes"), filter == base, base)
        val ours = a.app.recipes.countOf(app.toque.recipes.RecipeRepo.FOYER)
        if (ours > 0) {
            chip(tr("Nos recettes · %d", ours), filter.source == RecipeSource.FOYER && filter.tag.isEmpty(), base.copy(source = RecipeSource.FOYER))
            if (a.app.recipes.index.entries.any { it.source == RecipeSource.FOYER && "À tester" in it.tags })
                chip(tr("À tester"), filter.tag == "À tester", base.copy(source = RecipeSource.FOYER, tag = "À tester"))
            val lidl = a.app.recipes.countOf(app.toque.recipes.RecipeRepo.LIDL)
            chip(if (lidl > 0) tr("Lidl · %d", lidl) else tr("Lidl"), filter.source == RecipeSource.IMPORTED, base.copy(source = RecipeSource.IMPORTED))
        }
        chip(tr("Favoris"), filter.favoritesOnly, base.copy(favoritesOnly = true))
        chip(tr("30 min max"), filter.maxMinutes == 30, base.copy(maxMinutes = 30))
        for (cat in a.app.recipes.index.categories.take(30)) chip(cat, filter.category == cat, base.copy(category = cat))
    }

    private fun search() {
        val gen = ++generation
        val q = query
        val f = filter
        val index = a.app.recipes.index
        searchExec.execute {
            val hits = if (q.contains(',')) index.withIngredients(q.split(','), f) else index.search(q, f)
            view.post {
                if (gen != generation) return@post
                results = hits
                adapter.notifyDataSetChanged()
                refreshEmpty()
            }
        }
    }

    private fun refreshEmpty() {
        val repo = a.app.recipes
        val none = repo.loaded && repo.count == 0
        count.text = if (results.isEmpty()) "" else if (results.size == 1) tr("1 recette") else tr("%d recettes", results.size)
        empty.visibility = if (results.isEmpty() && repo.loaded) View.VISIBLE else View.GONE
        emptyText.text = when {
            none && a.prefs.mode == Prefs.Mode.HARDWARE ->
                tr("Les recettes Lidl apparaîtront après la première synchronisation.\nRéglages › Recettes Lidl › Synchroniser.")
            none -> tr("Les recettes Lidl apparaîtront après la première synchronisation, une fois Toque installé sur le robot.")
            filter.favoritesOnly && query.isBlank() -> tr("Aucun favori pour l'instant.\nTouche l'étoile d'une recette pour l'ajouter.")
            else -> tr("Aucune recette ne correspond.\nAstuce : sépare plusieurs ingrédients par des virgules.")
        }
    }

    override fun onShow() {
        buildChips()
        search()
        unsub = a.app.recipes.onChange { buildChips(); search() }
        if (focusSearch) view.postDelayed({
            input.requestFocus()
            (c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    override fun onHide() {
        unsub?.invoke()
    }

    /** Au bouton rotatif : on arrive sur la première recette affichée (la grille défile au fil des tours). */
    override val knobStart: View? get() = if (::grid.isInitialized && grid.childCount > 0) grid.getChildAt(0) else null

    private companion object {
        /** Un seul fil de recherche partagé par tous les écrans Recettes. */
        val searchExec = Executors.newSingleThreadExecutor()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = results.size
        override fun getItem(position: Int) = results[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder = (convertView?.tag as? Holder) ?: Holder(c)
            holder.bind(results[position].entry)
            return holder.root
        }
    }

    private inner class Holder(c: Context) {
        val root = Ui.vertical(c)
        val imageBox = FrameLayout(c)
        val image = ImageView(c)
        val dish = Ui.dishIcon(c)
        val title = Ui.ellipsize(Ui.text(c, "", 15f, Ui.TEXT, Ui.SEMIBOLD).apply { setLineSpacing(0f, 1.1f) }, 2)
        val meta = Ui.ellipsize(Ui.text(c, "", 13f, Ui.TEXT2))
        val star = Icon(c, Icon.Kind.STAR_FILLED, Ui.ACCENT)
        val badge = Ui.text(c, "", 13f, Ui.TEXT, Ui.SEMIBOLD)

        init {
            root.tag = this
            root.background = Ui.cardBg(20f)
            root.layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, Ui.u(214))
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            imageBox.addView(dish, Ui.flp(Ui.u(52), Ui.u(52), Gravity.CENTER))
            imageBox.addView(image, Ui.flp())
            imageBox.addView(star, Ui.flp(Ui.u(24), Ui.u(24), Gravity.TOP or Gravity.END).apply { setMargins(0, Ui.u(10), Ui.u(10), 0) })
            badge.setPadding(Ui.u(10), Ui.u(5), Ui.u(10), Ui.u(5))
            badge.background = Ui.round(Ui.GLASS, 13f)
            imageBox.addView(badge, Ui.flp(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.START).apply { setMargins(Ui.u(10), Ui.u(10), 0, 0) })
            imageBox.clipToOutline = true
            imageBox.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height + Ui.u(20), Ui.uf(20f))
                }
            }
            root.addView(imageBox, LinearLayout.LayoutParams(Ui.MATCH, Ui.u(128)))
            root.addView(title, Ui.lp(l = 14, t = 10, r = 12))
            root.addView(meta, Ui.lp(l = 14, t = 5, r = 12))
        }

        fun bind(e: SearchIndex.Entry) {
            title.text = e.title
            val parts = ArrayList<String>()
            if (e.ratingCount > 0) parts += "★ " + String.format(I18n.locale, "%.1f", e.rating)
            if (e.totalMinutes > 0) parts += "${e.totalMinutes} min"
            if (e.machineSteps > 0) parts += if (e.machineSteps == 1) tr("1 étape au robot") else tr("%d étapes au robot", e.machineSteps)
            else if (e.category.isNotBlank()) parts += e.category
            meta.text = parts.joinToString(" · ")
            star.visibility = if (a.app.recipes.isFavorite(e.id)) View.VISIBLE else View.GONE
            val toTry = e.source == RecipeSource.FOYER && "À tester" in e.tags
            badge.visibility = if (toTry) View.VISIBLE else View.GONE
            badge.text = tr("À tester")
            imageBox.background = Ui.gradient(Ui.placeholderColors(e.title), 0f)
            Ui.setDish(dish, e.title, e.category)
            ImageLoader.load(image, a.app.recipes.imageFile(e.image), Ui.u(260))
        }
    }
}
