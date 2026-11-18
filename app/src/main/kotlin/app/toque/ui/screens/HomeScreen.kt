package app.toque.ui.screens

import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.foyer.FoyerFeed
import app.toque.core.foyer.FoyerPlan
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.SearchIndex
import app.toque.foyer.Foyer
import app.toque.recipes.RecipeRepo
import app.toque.ui.Icon
import app.toque.ui.ImageLoader
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Accueil centré sur le foyer : à gauche, le repas prévu dans Foyer (« Ce soir · 20 h ») avec
 * un bouton pour le cuisiner, ou la carte de liaison tant que le robot n'est pas relié, ou
 * l'heure quand rien n'est prévu ; en dessous, la recherche. À droite, « Nos recettes »
 * (à tester puis favoris), les recettes Lidl et les outils de la machine.
 */
class HomeScreen(a: MainActivity) : Screen(a) {
    override val title = "Toque"
    private val foyer get() = Foyer.of(a.app)
    private lateinit var lead: FrameLayout
    private lateinit var resumeCard: LinearLayout
    private lateinit var resumeText: TextView
    private lateinit var cardsTitle: TextView
    private lateinit var cardsMore: TextView
    private lateinit var cards: LinearLayout
    private lateinit var lidlSub: TextView
    private var unsubRecipes: (() -> Unit)? = null
    private var unsubFoyer: (() -> Unit)? = null
    private var clockView: TextView? = null
    private val tick = object : Runnable {
        override fun run() {
            clockView?.text = MainActivity.now()
            view.postDelayed(this, 10_000)
        }
    }

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(22))

        // ---- Colonne de gauche : le repas prévu (ou la liaison), la reprise, la recherche
        val left = Ui.vertical(c)
        lead = FrameLayout(c)
        left.addView(lead, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        resumeCard = Ui.horizontal(c)
        resumeCard.setPadding(Ui.u(20), Ui.u(14), Ui.u(18), Ui.u(14))
        resumeCard.background = Ui.pressable(Ui.round(Ui.ACCENT_DIM, 22f), 22f)
        resumeCard.addView(Icon(c, Icon.Kind.PLAY, Ui.ACCENT), LinearLayout.LayoutParams(Ui.u(24), Ui.u(24)).apply { rightMargin = Ui.u(12) })
        resumeText = Ui.ellipsize(Ui.text(c, "", 18f, Ui.TEXT, Ui.MEDIUM), 1)
        resumeCard.addView(resumeText, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        resumeCard.isClickable = true
        resumeCard.setOnClickListener {
            val r = a.app.recipes.get(a.prefs.guidedRecipe)
            if (r != null) a.push(CookScreen(a, r, a.prefs.guidedStep)) else { a.prefs.guidedRecipe = ""; refresh() }
        }
        left.addView(resumeCard, Ui.lp(t = 12))
        val search = Ui.horizontal(c)
        search.setPadding(Ui.u(22), 0, Ui.u(22), 0)
        search.background = Ui.pressable(Ui.round(Ui.SURFACE, 32f), 32f)
        search.addView(Icon(c, Icon.Kind.SEARCH, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(26), Ui.u(26)).apply { rightMargin = Ui.u(14) })
        search.addView(Ui.ellipsize(Ui.text(c, tr("Chercher : « risotto », « poulet, curry »…"), 19f, Ui.TEXT3)))
        search.isClickable = true
        search.setOnClickListener { a.push(RecipesScreen(a, focusSearch = true)) }
        left.addView(search, Ui.lp(Ui.MATCH, Ui.u(64), t = 12))
        root.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 0.42f).apply { rightMargin = Ui.u(20) })

        // ---- Colonne de droite : nos recettes, puis les outils
        val right = Ui.vertical(c)
        val head = Ui.horizontal(c, Gravity.BOTTOM)
        cardsTitle = Ui.text(c, "", 24f, Ui.TEXT, Ui.MEDIUM)
        head.addView(cardsTitle, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        cardsMore = Ui.text(c, "", 16f, Ui.TEXT2)
        cardsMore.setPadding(Ui.u(12), Ui.u(8), 0, Ui.u(8))
        head.addView(cardsMore)
        head.isClickable = true
        head.setOnClickListener { openList() }
        right.addView(head)
        cards = Ui.horizontal(c, Gravity.TOP)
        right.addView(cards, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = Ui.u(10) })
        val tools = Ui.horizontal(c)
        tools.addView(tool(Icon.Kind.POT, tr("Cuisson\nmanuelle")) { a.push(ManualScreen(a)) }.first, toolLp(true))
        tools.addView(tool(Icon.Kind.KNEAD, tr("Programmes")) { a.push(ProgramsScreen(a)) }.first, toolLp(true))
        tools.addView(tool(Icon.Kind.SCALE, tr("Balance")) { a.push(ScaleScreen(a)) }.first, toolLp(true))
        val (lidl, sub) = tool(Icon.Kind.BOOK, tr("Recettes Lidl")) { a.push(RecipesScreen(a, source = RecipeSource.IMPORTED)) }
        lidlSub = sub
        tools.addView(lidl, toolLp(true))
        tools.addView(tool(Icon.Kind.SETTINGS, tr("Réglages")) { a.push(SettingsScreen(a)) }.first, toolLp(false))
        right.addView(tools, LinearLayout.LayoutParams(Ui.MATCH, Ui.u(100)).apply { topMargin = Ui.u(14) })
        root.addView(right, LinearLayout.LayoutParams(0, Ui.MATCH, 0.58f))
        return root
    }

    private fun toolLp(gap: Boolean) = LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { if (gap) rightMargin = Ui.u(10) }

    private fun tool(icon: Icon.Kind, label: String, onClick: () -> Unit): Pair<LinearLayout, TextView> {
        val t = Ui.vertical(c)
        t.setPadding(Ui.u(14), Ui.u(12), Ui.u(12), Ui.u(12))
        t.background = Ui.pressable(Ui.round(Ui.SURFACE, 20f), 20f)
        t.addView(Icon(c, icon, Ui.ACCENT), LinearLayout.LayoutParams(Ui.u(26), Ui.u(26)))
        t.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))
        t.addView(Ui.text(c, label, 16f, Ui.TEXT, Ui.MEDIUM).apply { setLineSpacing(0f, 1.0f) })
        val sub = Ui.ellipsize(Ui.text(c, "", 13f, Ui.TEXT2))
        sub.visibility = View.GONE
        t.addView(sub)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t to sub
    }

    private fun openList() {
        if (a.app.recipes.countOf(RecipeRepo.FOYER) > 0) a.push(RecipesScreen(a, source = RecipeSource.FOYER))
        else a.push(RecipesScreen(a, source = RecipeSource.IMPORTED))
    }

    // ------------------------------------------------------------------ contenu

    private fun refresh() {
        val repo = a.app.recipes
        renderLead()
        val guided = a.prefs.guidedRecipe
        val title = if (guided.isEmpty()) null else repo.index.entries.firstOrNull { it.id == guided }?.title
        resumeCard.visibility = if (title != null) View.VISIBLE else View.GONE
        if (title != null) resumeText.text = tr("Reprendre « %s » — étape %d", title, a.prefs.guidedStep + 1)
        val lidl = repo.countOf(RecipeRepo.LIDL)
        lidlSub.visibility = if (lidl > 0) View.VISIBLE else View.GONE
        lidlSub.text = tr("%d recettes", lidl)
        renderCards()
    }

    /** Recettes du foyer : à tester d'abord, puis favoris, puis les autres (Lidl les mieux notées sinon). */
    private fun renderCards() {
        val repo = a.app.recipes
        val ours = repo.index.entries.filter { it.source == RecipeSource.FOYER }
        val picks: List<SearchIndex.Entry>
        if (ours.isNotEmpty()) {
            cardsTitle.text = tr("Nos recettes")
            cardsMore.text = tr("%d · tout voir", ours.size)
            picks = ours.sortedBy { e -> if ("À tester" in e.tags) 0 else if (e.favorite || "Favori" in e.tags) 1 else 2 }.take(3)
        } else {
            val lidl = repo.index.entries.filter { it.source != RecipeSource.FOYER }
            cardsTitle.text = if (!repo.loaded) tr("Chargement…") else tr("Recettes Lidl")
            cardsMore.text = if (lidl.isEmpty()) "" else tr("%d · tout voir", lidl.size)
            picks = (lidl.filter { it.favorite } + lidl.filter { !it.favorite }.sortedByDescending { it.rating * minOf(it.ratingCount, 50) }).take(3)
        }
        cards.removeAllViews()
        if (picks.isEmpty()) {
            val empty = Ui.card(c, 20f)
            empty.addView(Ui.text(c, if (foyer.linked) tr("Les recettes partagées dans Foyer apparaîtront ici.")
                else tr("Réglages › Recettes Lidl › Synchroniser pour télécharger les recettes officielles."), 18f, Ui.TEXT2)
                .apply { setLineSpacing(0f, 1.2f) })
            cards.addView(empty, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
            return
        }
        for ((i, e) in picks.withIndex()) cards.addView(recipeCard(e), LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { if (i < 2) rightMargin = Ui.u(12) })
        repeat(3 - picks.size) { cards.addView(View(c), LinearLayout.LayoutParams(0, Ui.MATCH, 1f)) }
    }

    private fun recipeCard(e: SearchIndex.Entry): View {
        val card = Ui.vertical(c)
        card.background = Ui.pressable(Ui.round(Ui.SURFACE, 20f), 20f)
        rounded(card, 20f)
        val photo = FrameLayout(c)
        photo.setBackgroundColor(Ui.SURFACE2)
        val img = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        photo.addView(img, Ui.flp())
        ImageLoader.load(img, a.app.recipes.imageFile(e.image), Ui.u(240))
        val tag = when {
            e.source != RecipeSource.FOYER -> if (e.ratingCount > 0) "★ " + String.format(I18n.locale, "%.1f", e.rating) else ""
            e.tags.any { it == "À tester" } -> tr("À tester")
            e.favorite || e.tags.any { it == "Favori" } -> tr("Favori")
            else -> ""
        }
        if (tag.isNotEmpty()) {
            val badge = Ui.text(c, tag, 13f, if (tag == tr("Favori")) Ui.ACCENT else Ui.TEXT, Ui.MEDIUM)
            badge.setPadding(Ui.u(10), Ui.u(4), Ui.u(10), Ui.u(4))
            badge.background = Ui.round(0xB8130E0B.toInt(), 14f)
            photo.addView(badge, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = Ui.u(10); topMargin = Ui.u(10) })
        }
        card.addView(photo, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        val text = Ui.vertical(c)
        text.setPadding(Ui.u(14), Ui.u(10), Ui.u(12), Ui.u(12))
        text.addView(Ui.ellipsize(Ui.text(c, e.title, 17f, Ui.TEXT, Ui.MEDIUM).apply { setLineSpacing(0f, 1.05f) }, 2))
        val meta = listOfNotNull(
            if (e.totalMinutes > 0) tr("%d min", e.totalMinutes) else null,
            if (e.machineSteps > 0) tr("%d étapes au robot", e.machineSteps) else null,
        ).joinToString(" · ")
        if (meta.isNotEmpty()) text.addView(Ui.ellipsize(Ui.text(c, meta, 14f, Ui.TEXT2)), Ui.lp(t = 4))
        card.addView(text)
        card.isClickable = true
        card.setOnClickListener { a.app.recipes.get(e.id)?.let { a.push(RecipeScreen(a, it)) } }
        return card
    }

    private fun rounded(v: View, radius: Float) {
        v.clipToOutline = true
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, Ui.uf(radius))
        }
    }

    /** Le bloc de gauche : repas prévu, liaison à Foyer, ou l'heure. */
    private fun renderLead() {
        lead.removeAllViews()
        clockView = null
        val f = foyer
        val p = a.prefs
        if (p.foyerEnabled && !f.linked) {
            lead.addView(linkCard(p.foyerCode), Ui.flp())
            return
        }
        val (today, now) = Foyer.todayAndTime()
        val plan = FoyerFeed.nextPlan(f.plans, today, now)
        val recipe = plan?.recipeId?.let { a.app.recipes.get(it) }
        if (plan != null) lead.addView(planCard(plan, recipe, today), Ui.flp()) else lead.addView(clockCard(), Ui.flp())
    }

    private fun planCard(plan: FoyerPlan, recipe: Recipe?, today: String): View {
        val box = FrameLayout(c)
        box.background = Ui.round(Ui.SURFACE, 24f)
        rounded(box, 24f)
        val file = recipe?.let { a.app.recipes.imageFile(it.image) }
        if (file != null) {
            val img = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            box.addView(img, Ui.flp())
            ImageLoader.load(img, file, Ui.u(440))
            // Dégradé pour lire le texte sur la photo.
            box.addView(View(c).apply {
                background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x00130E0B, 0x66130E0B, 0xE6130E0B.toInt()))
            }, Ui.flp())
        }
        val col = Ui.vertical(c, Gravity.BOTTOM)
        col.setPadding(Ui.u(22), Ui.u(20), Ui.u(22), Ui.u(20))
        col.addView(Ui.text(c, whenLabel(plan, today).uppercase(I18n.locale), 14f, Ui.ACCENT, Ui.MEDIUM).apply { letterSpacing = 0.1f })
        col.addView(Ui.ellipsize(Ui.text(c, recipe?.title ?: plan.title, 32f, Ui.TEXT, Ui.MEDIUM).apply { setLineSpacing(0f, 1.05f) }, 3), Ui.lp(t = 6))
        if (recipe != null) {
            val meta = listOfNotNull(
                if (recipe.totalMinutes > 0) tr("%d min", recipe.totalMinutes) else null,
                if (recipe.servings > 0) tr("%d pers.", recipe.servings) else null,
                if (recipe.machineSteps > 0) tr("%d étapes au robot", recipe.machineSteps) else null,
            ).joinToString(" · ")
            if (meta.isNotEmpty()) col.addView(Ui.text(c, meta, 17f, Ui.TEXT2), Ui.lp(t = 6))
            val actions = Ui.horizontal(c)
            actions.addView(Ui.button(c, tr("Cuisiner"), Ui.Style.PRIMARY, Icon.Kind.PLAY, height = 62, textSize = 21f) {
                a.push(CookScreen(a, recipe, 0))
            }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 12))
            actions.addView(Ui.button(c, tr("La recette"), Ui.Style.SECONDARY, height = 62, textSize = 19f) { a.push(RecipeScreen(a, recipe)) })
            col.addView(actions, Ui.lp(t = 14))
        } else {
            col.addView(Ui.text(c, tr("Prévu dans Foyer, sans recette enregistrée."), 17f, Ui.TEXT2), Ui.lp(t = 6))
        }
        box.addView(col, Ui.flp())
        return box
    }

    /** « Ce soir · 20 h », « Aujourd'hui · 12 h 30 », « Demain · 20 h », « Samedi · 20 h ». */
    private fun whenLabel(plan: FoyerPlan, today: String): String {
        val time = plan.time?.let { t -> t.substring(0, 2).trimStart('0').ifEmpty { "0" } + " h" + t.substring(3).let { m -> if (m == "00") "" else " $m" } }
        val parse = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dayMs = try { parse.parse(plan.day)?.time ?: 0L } catch (_: Exception) { 0L }
        val todayMs = try { parse.parse(today)?.time ?: 0L } catch (_: Exception) { 0L }
        val days = Math.round((dayMs - todayMs) / 86_400_000.0).toInt()
        val day = when {
            days <= 0 -> if (plan.time == null || plan.time >= "17:00") tr("Ce soir") else tr("Aujourd'hui")
            days == 1 -> tr("Demain")
            else -> SimpleDateFormat("EEEE", I18n.locale).format(Date(dayMs)).replaceFirstChar { it.uppercaseChar() }
        }
        return if (time != null) "$day · $time" else day
    }

    private fun linkCard(code: String): View {
        val card = Ui.vertical(c)
        card.background = Ui.round(Ui.SURFACE, 24f, 0x59E79363, 1f)
        card.setPadding(Ui.u(26), Ui.u(22), Ui.u(26), Ui.u(22))
        card.addView(Ui.text(c, tr("Relier à Foyer").uppercase(I18n.locale), 14f, Ui.ACCENT, Ui.MEDIUM).apply { letterSpacing = 0.1f })
        card.addView(Ui.text(c, tr("Vos recettes, ici, toutes seules"), 27f, Ui.TEXT, Ui.MEDIUM), Ui.lp(t = 8))
        val shown = if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else tr("Connexion…")
        card.addView(Ui.text(c, shown, if (code.length == 6) 66f else 28f, Ui.TEXT, Ui.LIGHT).apply { letterSpacing = 0.08f }, Ui.lp(t = 10))
        val steps = listOf(
            tr("Sur ton téléphone : Foyer › Réglages › Monsieur Cuisine."),
            tr("Sur le même Wi-Fi, touche « Relier ». Sinon, tape ce code."),
            tr("C'est tout : le lien reste, sans rien refaire."),
        )
        for ((i, s) in steps.withIndex()) card.addView(Ui.text(c, "${i + 1}. $s", 17f, Ui.TEXT2).apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = if (i == 0) 12 else 4))
        return card
    }

    private fun clockCard(): View {
        val col = Ui.vertical(c, Gravity.CENTER_VERTICAL)
        col.setPadding(Ui.u(6), 0, 0, 0)
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        col.addView(Ui.text(c, when (h) {
            in 5..11 -> tr("Bonjour")
            in 12..17 -> tr("Bon après-midi")
            else -> tr("Bonsoir")
        }, 24f, Ui.TEXT2, Ui.LIGHT))
        val clock = Ui.text(c, MainActivity.now(), 120f, Ui.TEXT, Ui.THIN)
        clockView = clock
        col.addView(clock, Ui.lp(t = 2))
        col.addView(Ui.text(c, SimpleDateFormat(tr("EEEE d MMMM"), I18n.locale).format(Date()).replaceFirstChar { it.uppercaseChar() }, 22f, Ui.TEXT2, Ui.LIGHT), Ui.lp(t = 2))
        if (foyer.linked) col.addView(Ui.text(c, tr("Rien de prévu dans Foyer pour l'instant."), 17f, Ui.TEXT3), Ui.lp(t = 16))
        return col
    }

    override fun onShow() {
        refresh()
        unsubRecipes = a.app.recipes.onChange { refresh() }
        unsubFoyer = foyer.onChange { refresh() }
        foyer.showing(true)
        foyer.syncSoon()
        view.removeCallbacks(tick)
        view.postDelayed(tick, 10_000)
    }

    override fun onHide() {
        unsubRecipes?.invoke()
        unsubFoyer?.invoke()
        foyer.showing(false)
        view.removeCallbacks(tick)
    }
}
