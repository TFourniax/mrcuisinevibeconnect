package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.recipes.McOnline
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.Step
import app.toque.recipes.Reviews
import app.toque.ui.Icon
import app.toque.ui.ImageLoader
import app.toque.ui.MainActivity
import app.toque.ui.PhoneLink
import app.toque.ui.Screen
import app.toque.ui.Ui

/** Fiche recette : ingrédients à gauche, étapes à droite, « Cuisiner » pour le pas-à-pas. */
class RecipeScreen(a: MainActivity, private val recipe: Recipe) : Screen(a) {
    override val title = recipe.title
    override val recipeId: String get() = recipe.id
    private var cookBtn: View? = null
    private lateinit var favIcon: Icon
    private lateinit var favLabel: TextView

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(24), Ui.u(4), Ui.u(24), Ui.u(6))

        // ---- colonne gauche
        val left = Ui.vertical(c)
        val header = FrameLayout(c)
        header.background = Ui.gradient(Ui.placeholderColors(recipe.title), 24f)
        header.addView(Ui.dishIcon(c, recipe.title, recipe.category), Ui.flp(Ui.u(88), Ui.u(88), Gravity.CENTER))
        val img = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        header.addView(img, Ui.flp())
        header.clipToOutline = true
        header.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, Ui.uf(24f))
        }
        ImageLoader.load(img, a.app.recipes.imageFile(recipe.image), Ui.u(420))
        left.addView(header, LinearLayout.LayoutParams(Ui.MATCH, Ui.u(160)))

        val meta = Ui.horizontal(c)
        if (recipe.totalMinutes > 0) meta.addView(Ui.chip(c, "${recipe.totalMinutes} min", Icon.Kind.CLOCK), Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        if (recipe.servings > 0) meta.addView(Ui.chip(c, tr("%d pers.", recipe.servings), Icon.Kind.POT), Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        meta.addView(Ui.chip(c, when (recipe.difficulty) { 1 -> tr("Facile"); 2 -> tr("Moyen"); else -> tr("Difficile") }, Icon.Kind.STAR))
        left.addView(meta, Ui.lp(t = 12))

        // Auteur et note du site.
        val byline = ArrayList<String>()
        if (recipe.author.isNotBlank()) byline += tr("Par %s", authorName(recipe.author))
        if (recipe.ratingCount > 0) byline += "★ " + tr("%s/5 · %d notes", rating(recipe.rating), recipe.ratingCount)
        if (byline.isNotEmpty()) {
            left.addView(Ui.ellipsize(Ui.text(c, byline.joinToString("   "), 14f, Ui.TEXT2)), Ui.lp(t = 10))
        }

        val actions = Ui.horizontal(c)
        val cook = Ui.button(c, tr("Cuisiner"), Ui.Style.PRIMARY, Icon.Kind.PLAY, height = 60, textSize = 20f) {
            a.push(CookScreen(a, recipe, 0))
        }
        cookBtn = cook
        actions.addView(cook, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(12) })
        val fav = Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.STAR, height = 60) { toggleFavorite() }
        favIcon = fav.getChildAt(0) as Icon
        favLabel = Ui.text(c, "", 17f, Ui.TEXT, Ui.SEMIBOLD)
        fav.addView(favLabel, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = Ui.u(8) })
        actions.addView(fav)
        left.addView(actions, Ui.lp(t = 14))

        // La vidéo d'origine (un réel) : la revoir ici, ou sur un téléphone.
        val video = Ui.horizontal(c)
        val phoneToo = PhoneLink.available(a, recipe)
        if (recipe.video.isNotEmpty()) {
            val watch = Ui.button(c, tr("Revoir la vidéo"), Ui.Style.GHOST, Icon.Kind.VIDEO, height = 52, textSize = 15f) { a.openVideo(recipe) }
            Ui.fitWords(watch.getChildAt(1) as TextView, 13f)
            // Avec « Voir sur le téléphone » (plus long) : la vidéo à sa largeur, le reste au téléphone,
            // pour que les deux libellés tiennent sur une ligne.
            video.addView(watch, (if (phoneToo) LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP) else LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                .apply { rightMargin = Ui.u(10) })
        }
        if (phoneToo) {
            val phone = Ui.button(c, tr("Voir sur le téléphone"), Ui.Style.GHOST, Icon.Kind.PHONE, height = 52, textSize = 15f) { b -> PhoneLink.send(a, recipe, b) }
            Ui.fitWords(phone.getChildAt(1) as TextView, 13f)
            video.addView(phone, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        }
        if (video.childCount > 0) left.addView(video, Ui.lp(t = 10))

        val ing = Ui.vertical(c)
        ing.addView(section(tr("Ingrédients")))
        for (g in recipe.ingredients) {
            if (g.title.isNotBlank()) ing.addView(Ui.text(c, g.title, 16f, Ui.ACCENT, Ui.SEMIBOLD), Ui.lp(t = 12, b = 2))
            for (i in g.items) {
                val row = Ui.horizontal(c, Gravity.TOP)
                row.addView(Ui.text(c, "•", 18f, Ui.TEXT3), Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
                row.addView(Ui.text(c, i.display, 16f, Ui.TEXT).apply { setLineSpacing(0f, 1.15f) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                ing.addView(row, Ui.lp(t = 8))
            }
        }
        if (recipe.ingredients.isEmpty()) ing.addView(Ui.text(c, tr("Pas de liste d'ingrédients."), 17f, Ui.TEXT3), Ui.lp(t = 8))
        left.addView(Ui.scroll(c, ing), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = Ui.u(10) })
        root.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 0.42f).apply { rightMargin = Ui.u(24) })

        // ---- colonne droite
        val steps = Ui.vertical(c)
        steps.addView(section(tr("Étapes")))
        for ((i, s) in recipe.steps.withIndex()) steps.addView(stepRow(i, s), Ui.lp(t = 12))
        McOnline.apiIdOf(recipe.id)?.let { apiId ->
            steps.addView(reviewsBox, Ui.lp(t = 22))
            reviewsBox.addView(section(if (recipe.ratingCount > 0)
                tr("Avis · %s/5 (%d notes)", rating(recipe.rating), recipe.ratingCount) else tr("Avis")))
            reviewsBox.addView(reviewsState, Ui.lp(t = 10))
            loadReviews(apiId, 1)
        }
        val stepsBox = Ui.vertical(c)
        stepsBox.background = Ui.cardBg(24f)
        Ui.lift(stepsBox)
        stepsBox.setPadding(Ui.u(22), Ui.u(10), Ui.u(22), Ui.u(18))
        stepsBox.addView(Ui.scroll(c, steps), Ui.lp(Ui.MATCH, Ui.MATCH))
        root.addView(stepsBox, LinearLayout.LayoutParams(0, Ui.MATCH, 0.58f))
        refreshFavorite()
        return root
    }

    // ------------------------------------------------------------------ avis

    private val reviewsBox by lazy { Ui.vertical(c) }
    private val reviewsState by lazy { Ui.text(c, "", 16f, Ui.TEXT3) }
    private var moreButton: View? = null
    private var loading = false

    private fun loadReviews(apiId: Long, page: Int) {
        if (loading) return
        loading = true
        reviewsState.visibility = View.VISIBLE
        reviewsState.text = tr("Chargement des avis…")
        Thread {
            val result = try {
                Reviews.load(apiId, page)
            } catch (e: Exception) {
                null
            }
            reviewsBox.post {
                loading = false
                if (result == null) {
                    reviewsState.text = tr("Avis indisponibles (pas de connexion ?)")
                    return@post
                }
                showReviews(apiId, result)
            }
        }.start()
    }

    private fun showReviews(apiId: Long, p: McOnline.ReviewPage) {
        moreButton?.let { reviewsBox.removeView(it) }
        moreButton = null
        if (p.page == 1 && p.reviews.isEmpty()) {
            reviewsState.text = tr("Pas encore de commentaire sur cette recette.")
            return
        }
        reviewsState.visibility = View.GONE
        for (r in p.reviews) reviewsBox.addView(reviewRow(r), Ui.lp(t = 14))
        if (p.page < p.totalPages) {
            val shown = reviewsBox.childCount - 2
            val more = Ui.button(c, tr("Voir plus d'avis (%d sur %d)", shown, p.total), Ui.Style.GHOST, height = 50, textSize = 16f) {
                loadReviews(apiId, p.page + 1)
            }
            moreButton = more
            reviewsBox.addView(more, Ui.lp(Ui.WRAP, Ui.WRAP, t = 12))
        }
    }

    private fun reviewRow(r: McOnline.Review): View {
        val box = Ui.vertical(c)
        box.background = Ui.round(Ui.SURFACE2, 16f, Ui.STROKE, 1f)
        box.setPadding(Ui.u(16), Ui.u(12), Ui.u(16), Ui.u(12))
        val head = Ui.horizontal(c)
        head.addView(Ui.text(c, "★".repeat(r.rating) + "☆".repeat(5 - r.rating), 17f, Ui.ACCENT), Ui.lp(Ui.WRAP, Ui.WRAP, r = 12))
        val who = listOf(r.author.ifBlank { tr("Anonyme") }, r.date).filter { it.isNotBlank() }.joinToString(" · ")
        head.addView(Ui.ellipsize(Ui.text(c, who, 15f, Ui.TEXT3)), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        box.addView(head)
        if (r.message.isNotBlank()) {
            box.addView(Ui.text(c, r.message, 17f, Ui.TEXT).apply { setLineSpacing(0f, 1.12f) }, Ui.lp(t = 6))
        }
        if (r.reply.isNotBlank()) {
            box.addView(Ui.text(c, tr("Réponse de Monsieur Cuisine : %s", r.reply), 15f, Ui.TEXT2)
                .apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 8))
        }
        return box
    }

    private fun rating(v: Double): String = String.format(I18n.locale, "%.1f", v)

    private fun authorName(name: String): String =
        if (name.equals("Monsieur Cuisine Official", ignoreCase = true)) tr("Monsieur Cuisine (recette officielle)") else name

    private fun section(t: String): TextView =
        Ui.overline(c, t).apply { setPadding(0, Ui.u(10), 0, 0) }

    private fun stepRow(i: Int, s: Step): View {
        val row = Ui.horizontal(c, Gravity.TOP)
        val num = Ui.text(c, "${i + 1}", 15f, Ui.ACCENT, Ui.SEMIBOLD).apply { gravity = Gravity.CENTER }
        num.background = Ui.round(Ui.ACCENT_DIM, 18f)
        row.addView(num, LinearLayout.LayoutParams(Ui.u(36), Ui.u(36)).apply { rightMargin = Ui.u(14) })
        val col = Ui.vertical(c)
        if (s.text.isNotBlank()) col.addView(Ui.text(c, s.text, 16f, Ui.TEXT).apply { setLineSpacing(0f, 1.2f) })
        val chips = Ui.horizontal(c)
        s.weigh?.let { chips.addView(Ui.chip(c, tr("Peser %d g", it.grams), Icon.Kind.SCALE, Ui.TEXT2, size = 15f), Ui.lp(Ui.WRAP, Ui.WRAP, r = 8)) }
        s.machine?.let { m ->
            val ok = m.valid
            chips.addView(Ui.chip(c, m.summary, if (ok) Icon.Kind.POT else Icon.Kind.WARNING,
                if (ok) Ui.ACCENT else Ui.WARN, if (ok) Ui.ACCENT_DIM else Ui.WARN_DIM, 15f))
        }
        if (chips.childCount > 0) col.addView(chips, Ui.lp(t = 8))
        row.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        return row
    }

    private fun toggleFavorite() {
        val now = !a.app.recipes.isFavorite(recipe.id)
        a.app.recipes.setFavorite(recipe.id, now)
        refreshFavorite()
        a.toast(if (now) tr("Ajoutée aux favoris") else tr("Retirée des favoris"))
    }

    private fun refreshFavorite() {
        val f = a.app.recipes.isFavorite(recipe.id)
        favIcon.set(if (f) Icon.Kind.STAR_FILLED else Icon.Kind.STAR)
        favIcon.color = if (f) Ui.ACCENT else Ui.TEXT
        favLabel.text = tr("Favori")
    }

    /** Au bouton rotatif : on arrive sur « Cuisiner ». */
    override val knobStart: View? get() = cookBtn
}
