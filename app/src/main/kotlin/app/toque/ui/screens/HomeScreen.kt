package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.foyer.FoyerFeed
import app.toque.core.foyer.FoyerPlan
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.machine.JobKind
import app.toque.core.machine.JobState
import app.toque.core.machine.MachineEvent
import app.toque.core.machine.ProgramCatalog
import app.toque.core.machine.Snapshot
import app.toque.core.protocol.Mcc
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.SearchIndex
import app.toque.foyer.Foyer
import app.toque.recipes.RecipeRepo
import app.toque.ui.Icon
import app.toque.ui.ImageLoader
import app.toque.ui.Knob
import app.toque.ui.Live
import app.toque.ui.MainActivity
import app.toque.ui.Notices
import app.toque.ui.PlanText
import app.toque.ui.Screen
import app.toque.ui.Section
import app.toque.ui.StepSegments
import app.toque.ui.TimerRing
import app.toque.ui.Ui
import app.toque.ui.shown

/**
 * Accueil centré sur le foyer. À gauche, une seule grande carte, choisie dans cet ordre : le
 * programme en cours (avec Pause et Arrêter), la recette à reprendre, le repas prévu dans Foyer,
 * le code de liaison, sinon une invitation à cuisiner ; en dessous, les recettes à tester. À
 * droite : « À savoir » (deux messages non lus au plus, le reste sous la cloche), puis « Cette
 * semaine » (les repas prévus dans Foyer) ou, sans repas prévu, des raccourcis vers les programmes.
 */
class HomeScreen(a: MainActivity) : Screen(a) {
    override val title = "Toque"
    override val section: Section get() = Section.HOME
    private val foyer get() = Foyer.of(a.app)

    private enum class Lead { LIVE, RESUME, PLAN, LINK, IDLE }

    private lateinit var lead: FrameLayout
    private lateinit var recipesHead: LinearLayout
    private lateinit var recipesTitle: TextView
    private lateinit var recipesMore: TextView
    private lateinit var recipesRow: LinearLayout
    private lateinit var right: LinearLayout
    private var leadKind: Lead? = null
    private var leadKey = ""
    private var unsubRecipes: (() -> Unit)? = null
    private var unsubFoyer: (() -> Unit)? = null
    private var unsubNotices: (() -> Unit)? = null
    private var counted = false
    private val tick = object : Runnable {
        override fun run() {
            // « Ce soir » devient « Demain »… : on relit le repas prévu de temps en temps.
            if (leadKind != Lead.LIVE) refresh()
            view.postDelayed(this, 60_000)
        }
    }

    // Carte « en cours » (mise à jour à chaque échantillon de la machine).
    private var liveOverline: TextView? = null
    private var liveTitle: TextView? = null
    private var liveSegments: StepSegments? = null
    private var liveRing: TimerRing? = null
    private var liveText: TextView? = null
    private var liveState: TextView? = null
    private var liveChips: LinearLayout? = null
    private var liveChipsKey = ""
    private var livePause: LinearLayout? = null
    private var liveStop: LinearLayout? = null
    private var liveOk: LinearLayout? = null
    private var liveOpen: LinearLayout? = null

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(24), Ui.u(4), Ui.u(24), Ui.u(4))

        // ---- à gauche : la grande carte, puis les recettes (au bouton rotatif, chaque colonne d'un bloc)
        val left = Knob.region(Ui.vertical(c))
        lead = FrameLayout(c)
        left.addView(lead, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        recipesHead = Ui.horizontal(c)
        recipesTitle = Ui.text(c, "", 17f, Ui.TEXT, Ui.SEMIBOLD)
        recipesHead.addView(recipesTitle, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        recipesMore = Ui.text(c, "", 15f, Ui.TEXT2, Ui.MEDIUM)
        recipesHead.addView(recipesMore)
        recipesHead.addView(Icon(c, Icon.Kind.CHEVRON, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(18), Ui.u(18)).apply { leftMargin = Ui.u(2) })
        recipesHead.setPadding(Ui.u(4), Ui.u(6), Ui.u(2), Ui.u(6))
        recipesHead.background = Ui.pressable(Ui.round(0, 12f), 12f)
        recipesHead.isClickable = true
        recipesHead.setOnClickListener { openList() }
        left.addView(recipesHead, Ui.lp(t = 8))
        recipesRow = Ui.horizontal(c, Gravity.TOP)
        left.addView(recipesRow, Ui.lp(Ui.MATCH, Ui.u(92), t = 6))
        root.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 0.6f).apply { rightMargin = Ui.u(24) })

        // ---- à droite : À savoir, puis Cette semaine (ou Ensuite pendant une recette, ou des raccourcis)
        right = Knob.region(Ui.vertical(c))
        root.addView(right, LinearLayout.LayoutParams(0, Ui.MATCH, 0.4f))
        return root
    }

    // ------------------------------------------------------------------ choix de la grande carte

    private fun leadFor(s: Snapshot): Lead {
        if (s.jobActive || s.turboActive) return Lead.LIVE
        val p = a.prefs
        if (p.guidedRecipe.isNotEmpty() && a.app.recipes.get(p.guidedRecipe) != null) return Lead.RESUME
        if (p.foyerEnabled && !foyer.linked) return Lead.LINK
        val (today, now) = Foyer.todayAndTime()
        if (FoyerFeed.nextPlan(foyer.plans, today, now) != null) return Lead.PLAN
        return Lead.IDLE
    }

    private fun refresh() {
        val s = a.snapshot
        val kind = leadFor(s)
        val p = a.prefs
        val (today, now) = Foyer.todayAndTime()
        val plan = FoyerFeed.nextPlan(foyer.plans, today, now)
        // Clé de ce qu'affiche la carte : elle n'est refaite que si ça change.
        val key = when (kind) {
            Lead.LIVE -> "live:" + (a.liveRecipe()?.id ?: s.jobKind)
            Lead.RESUME -> "resume:${p.guidedRecipe}:${p.guidedStep}"
            Lead.PLAN -> "plan:$plan:${plan?.recipeId?.let { a.app.recipes.get(it)?.image }}:${PlanText.label(plan!!, today)}"
            Lead.LINK -> "link:${p.foyerCode}"
            Lead.IDLE -> "idle:${greetingKey()}"
        }
        if (key != leadKey || kind != leadKind) {
            leadKey = key
            leadKind = kind
            lead.removeAllViews()
            clearLive()
            val v = when (kind) {
                Lead.LIVE -> liveCard()
                Lead.RESUME -> resumeCard()
                Lead.PLAN -> planCard(plan!!, today)
                Lead.LINK -> linkCard(p.foyerCode)
                Lead.IDLE -> idleCard()
            }
            lead.addView(v, Ui.flp())
        }
        val live = kind == Lead.LIVE
        recipesHead.visibility = if (live) View.GONE else View.VISIBLE
        recipesRow.visibility = if (live) View.GONE else View.VISIBLE
        if (!live) renderRecipes()
        renderRight(live, plan)
        if (live) updateLive(s)
    }

    // ------------------------------------------------------------------ cartes à photo

    /** Une carte à photo (ou à dégradé et icône du plat), avec un bandeau clair en bas. */
    private fun photoCard(recipe: Recipe?, fallbackTitle: String, badge: String?, overline: String, title: String, meta: String,
                          action: String?, actionIcon: Icon.Kind, onAction: () -> Unit, onOpen: (() -> Unit)?): View {
        val box = FrameLayout(c)
        Ui.clip(box, 24f)
        box.background = Ui.gradient(Ui.placeholderColors(recipe?.title ?: fallbackTitle), 24f)
        box.addView(Ui.dishIcon(c, recipe?.title ?: fallbackTitle, recipe?.category ?: ""), Ui.flp(Ui.u(150), Ui.u(150), Gravity.TOP or Gravity.END).apply {
            topMargin = Ui.u(22); rightMargin = Ui.u(56)
        })
        val file = recipe?.let { a.app.recipes.imageFile(it.image) }
        if (file != null) {
            val img = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            box.addView(img, Ui.flp())
            ImageLoader.load(img, file, Ui.u(600))
        }
        if (badge != null) {
            val chip = Ui.chip(c, badge, Icon.Kind.HOME, Ui.TEXT, Ui.GLASS, 14f)
            (chip.getChildAt(0) as Icon).color = Ui.ACCENT
            box.addView(chip, Ui.flp(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.START).apply { topMargin = Ui.u(16); leftMargin = Ui.u(16) })
        }
        val panel = Ui.horizontal(c)
        panel.setPadding(Ui.u(20), Ui.u(16), Ui.u(16), Ui.u(16))
        panel.background = Ui.round(Ui.GLASS, 18f)
        val col = Ui.vertical(c)
        col.addView(Ui.overline(c, overline, Ui.ACCENT))
        col.addView(Ui.ellipsize(Ui.text(c, title, 25f, Ui.TEXT, Ui.SEMIBOLD).apply { setLineSpacing(0f, 1.05f) }, 2), Ui.lp(t = 5))
        if (meta.isNotEmpty()) col.addView(Ui.ellipsize(Ui.text(c, meta, 15f, Ui.TEXT2)), Ui.lp(t = 6))
        if (onOpen != null) {
            col.isClickable = true
            col.background = Ui.pressable(Ui.round(0, 12f), 12f)
            col.setOnClickListener { onOpen() }
        }
        panel.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        if (action != null) {
            panel.addView(Ui.button(c, action, Ui.Style.PRIMARY, actionIcon, height = 56, textSize = 18f) { onAction() }, Ui.lp(Ui.WRAP, Ui.WRAP, l = 14))
        }
        box.addView(panel, Ui.flp(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM).apply { setMargins(Ui.u(12), 0, Ui.u(12), Ui.u(12)) })
        return box
    }

    private fun recipeMeta(r: Recipe): String = listOfNotNull(
        if (r.totalMinutes > 0) tr("%d min", r.totalMinutes) else null,
        if (r.servings > 0) tr("%d pers.", r.servings) else null,
        if (r.machineSteps > 0) (if (r.machineSteps == 1) tr("1 étape au robot") else tr("%d étapes au robot", r.machineSteps)) else null,
    ).joinToString(" · ")

    private fun planCard(plan: FoyerPlan, today: String): View {
        val recipe = plan.recipeId?.let { a.app.recipes.get(it) }
        return photoCard(
            recipe, plan.title, tr("Prévu dans Foyer"), PlanText.label(plan, today), recipe?.title ?: plan.title,
            if (recipe != null) recipeMeta(recipe) else tr("Prévu dans Foyer, sans recette enregistrée."),
            if (recipe != null) tr("Cuisiner") else null, Icon.Kind.PLAY,
            onAction = { recipe?.let { a.push(CookScreen(a, it, 0)) } },
            onOpen = recipe?.let { r -> { a.push(RecipeScreen(a, r)) } },
        )
    }

    private fun resumeCard(): View {
        val r = a.app.recipes.get(a.prefs.guidedRecipe)!!
        val step = a.prefs.guidedStep.coerceIn(0, maxOf(0, r.steps.size - 1))
        return photoCard(
            r, r.title, null, tr("À reprendre · étape %d sur %d", step + 1, r.steps.size), r.title,
            r.steps.getOrNull(step)?.text?.takeIf { it.isNotBlank() } ?: recipeMeta(r),
            tr("Reprendre"), Icon.Kind.PLAY,
            onAction = { a.push(CookScreen(a, r, step)) },
            onOpen = { a.push(RecipeScreen(a, r)) },
        )
    }

    private fun linkCard(code: String): View {
        val card = Ui.vertical(c)
        card.background = Ui.cardBg(24f)
        Ui.lift(card)
        card.setPadding(Ui.u(28), Ui.u(24), Ui.u(28), Ui.u(24))
        card.addView(Ui.overline(c, tr("Relier à Foyer"), Ui.ACCENT))
        card.addView(Ui.text(c, tr("Nos recettes, ici, toutes seules"), 26f, Ui.TEXT, Ui.SEMIBOLD), Ui.lp(t = 8))
        val shown = if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else tr("Connexion…")
        card.addView(Ui.tabular(Ui.text(c, shown, if (code.length == 6) 64f else 28f, Ui.TEXT, Ui.LIGHT)).apply { letterSpacing = 0.08f }, Ui.lp(t = 10))
        val steps = listOf(
            tr("Sur ton téléphone : Foyer › Réglages › Monsieur Cuisine."),
            tr("Tape ce code, puis touche « Relier »."),
            tr("C'est tout : le lien reste, sans rien refaire."),
        )
        for ((i, s) in steps.withIndex()) card.addView(Ui.text(c, "${i + 1}. $s", 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }, Ui.lp(t = if (i == 0) 12 else 4))
        return card
    }

    private fun greetingKey(): Int {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (h) {
            in 5..11 -> 0
            in 12..17 -> 1
            else -> 2
        }
    }

    /** Rien de prévu : un mot, et de quoi s'y mettre. */
    private fun idleCard(): View {
        val box = FrameLayout(c)
        Ui.clip(box, 24f)
        box.background = Ui.gradient(Ui.placeholderColors("Toque"), 24f)
        box.addView(Icon(c, Icon.Kind.CHEF, 0x99FFFFFF.toInt()).apply { weight = 1.1f }, Ui.flp(Ui.u(170), Ui.u(170), Gravity.TOP or Gravity.END).apply {
            topMargin = Ui.u(18); rightMargin = Ui.u(48)
        })
        val panel = Ui.vertical(c)
        panel.setPadding(Ui.u(22), Ui.u(18), Ui.u(18), Ui.u(18))
        panel.background = Ui.round(Ui.GLASS, 18f)
        panel.addView(Ui.overline(c, when (greetingKey()) {
            0 -> tr("Bonjour")
            1 -> tr("Bon après-midi")
            else -> tr("Bonsoir")
        }, Ui.ACCENT))
        panel.addView(Ui.text(c, if (foyer.linked) tr("Rien de prévu dans Foyer pour l'instant.") else tr("Qu'est-ce qu'on cuisine ?"),
            23f, Ui.TEXT, Ui.SEMIBOLD).apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 6))
        val actions = Ui.horizontal(c)
        actions.addView(Ui.button(c, tr("Chercher une recette"), Ui.Style.PRIMARY, Icon.Kind.SEARCH, height = 54, textSize = 17f) {
            a.push(RecipesScreen(a, focusSearch = true))
        }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        actions.addView(Ui.button(c, tr("Programmes"), Ui.Style.SECONDARY, Icon.Kind.GRID, height = 54, textSize = 17f) {
            a.openSection(Section.PROGRAMS)
        })
        panel.addView(actions, Ui.lp(t = 14))
        box.addView(panel, Ui.flp(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM).apply { setMargins(Ui.u(12), 0, Ui.u(12), Ui.u(12)) })
        return box
    }

    // ------------------------------------------------------------------ programme en cours

    private fun clearLive() {
        liveOverline = null; liveTitle = null; liveSegments = null; liveRing = null; liveText = null
        liveState = null; liveChips = null; liveChipsKey = ""; livePause = null; liveStop = null; liveOk = null; liveOpen = null
    }

    private fun liveCard(): View {
        val card = Ui.vertical(c)
        card.background = Ui.cardBg(24f)
        Ui.lift(card)
        card.setPadding(Ui.u(24), Ui.u(22), Ui.u(24), Ui.u(22))
        val recipe = a.liveRecipe()
        val head = Ui.horizontal(c)
        if (recipe != null) {
            val thumb = FrameLayout(c)
            Ui.clip(thumb, 14f)
            thumb.background = Ui.gradient(Ui.placeholderColors(recipe.title), 14f)
            thumb.addView(Ui.dishIcon(c, recipe.title, recipe.category), Ui.flp(Ui.u(30), Ui.u(30), Gravity.CENTER))
            val img = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            thumb.addView(img, Ui.flp())
            ImageLoader.load(img, a.app.recipes.imageFile(recipe.image), Ui.u(120))
            head.addView(thumb, LinearLayout.LayoutParams(Ui.u(52), Ui.u(52)).apply { rightMargin = Ui.u(14) })
        } else {
            head.addView(Ui.iconCircle(c, Live.kindIcon(a.snapshot.kind), 52, 26, Ui.ACCENT, Ui.ON_ACCENT),
                LinearLayout.LayoutParams(Ui.u(52), Ui.u(52)).apply { rightMargin = Ui.u(14) })
        }
        val titles = Ui.vertical(c)
        liveOverline = Ui.overline(c, "", Ui.ACCENT).also { titles.addView(it) }
        liveTitle = Ui.ellipsize(Ui.text(c, "", 22f, Ui.TEXT, Ui.SEMIBOLD)).also { titles.addView(it, Ui.lp(t = 4)) }
        head.addView(titles, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        card.addView(head)
        if (recipe != null) {
            liveSegments = StepSegments(c).also { card.addView(it, Ui.lp(Ui.MATCH, Ui.u(6), t = 16)) }
        }

        val middle = Ui.horizontal(c)
        liveRing = TimerRing(c).also { middle.addView(it, LinearLayout.LayoutParams(Ui.u(190), Ui.u(190)).apply { rightMargin = Ui.u(26) }) }
        val col = Ui.vertical(c)
        liveText = Ui.ellipsize(Ui.text(c, "", 20f, Ui.TEXT, Ui.MEDIUM).apply { setLineSpacing(0f, 1.25f) }, 4).also { col.addView(it) }
        liveChips = Ui.horizontal(c).also { col.addView(it, Ui.lp(t = 14)) }
        liveState = Ui.ellipsize(Ui.text(c, "", 15f, Ui.WARN, Ui.MEDIUM), 2).also { col.addView(it, Ui.lp(t = 10)) }
        middle.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        card.addView(middle, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        val buttons = Ui.horizontal(c)
        livePause = Ui.button(c, tr("Pause"), Ui.Style.SECONDARY, Icon.Kind.PAUSE, height = 56, textSize = 17f) { a.togglePause() }
            .also { buttons.addView(it, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10)) }
        liveStop = Ui.button(c, tr("Arrêter"), Ui.Style.DANGER_SOFT, Icon.Kind.STOP, height = 56, textSize = 17f) { a.machine.stop() }
            .also { buttons.addView(it, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10)) }
        liveOk = Ui.button(c, "OK", Ui.Style.ACCENT_SOFT, Icon.Kind.CHECK, height = 56, textSize = 17f) { a.machine.clearFinished() }
            .also { buttons.addView(it, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10)) }
        buttons.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(0, 1, 1f))
        val open = Ui.button(c, if (recipe != null) tr("Ouvrir l'étape") else tr("Voir"), Ui.Style.PRIMARY, height = 56, textSize = 17f) { a.openLive() }
        open.addView(Icon(c, Icon.Kind.CHEVRON, Ui.ON_ACCENT), LinearLayout.LayoutParams(Ui.u(20), Ui.u(20)).apply { leftMargin = Ui.u(6) })
        liveOpen = open
        buttons.addView(open)
        card.addView(buttons)
        return card
    }

    private fun updateLive(s: Snapshot) {
        val recipe = a.liveRecipe()
        val g = foyer.tracker.guided
        val st = s.state
        val step = if (recipe != null && g != null) " · " + tr("étape %d sur %d", g.stepIndex + 1, g.stepCount) else ""
        liveOverline?.shown = (when {
            s.turboActive -> "Turbo"
            st == JobState.PAUSED -> tr("En pause")
            st == JobState.FINISHED -> tr("Terminé")
            st == JobState.ABORTED -> tr("Arrêté")
            else -> tr("En cours")
        } + step).uppercase(I18n.locale)
        liveTitle?.shown = recipe?.title ?: if (s.turboActive) "Turbo" else s.jobLabel
        if (recipe != null && g != null) liveSegments?.set(g.stepCount, g.stepIndex)
        val total = if (s.totalMs > 0) Ui.duration(((s.totalMs + 999) / 1000).toInt()) else ""
        val sub = when {
            st == JobState.FINISHED -> tr("Terminé")
            Live.followsTemperature(s) -> tr("vers %d °C", Mcc.celsiusForLevel(s.jobTempLevel))
            total.isNotEmpty() -> tr("reste, sur %s", total)
            else -> ""
        }
        liveRing?.set(Live.progress(s), Live.bigValue(s), sub, if (s.jobTempLevel > 0) Ui.ACCENT else Ui.COOL)
        liveText?.shown = when {
            recipe != null && g != null -> recipe.steps.getOrNull(g.stepIndex)?.let { it.text.ifBlank { it.machine?.summary ?: "" } } ?: ""
            else -> ProgramCatalog.of(s.kind)?.prep ?: Live.settingsText(s)
        }
        val settings = Live.settingsText(s)
        if (settings != liveChipsKey) {
            liveChipsKey = settings
            liveChips?.let { row ->
                row.removeAllViews()
                val t = if (s.jobTempLevel > 0) Mcc.celsiusForLevel(s.jobTempLevel) else 0
                // Trois puces au plus sur ~300 px : taille 14 et marges serrées pour qu'elles tiennent.
                fun add(chip: LinearLayout, last: Boolean) {
                    chip.setPadding(Ui.u(9), Ui.u(6), Ui.u(11), Ui.u(6))
                    row.addView(chip, Ui.lp(Ui.WRAP, Ui.WRAP, r = if (last) 0 else 5))
                }
                if (t > 0) add(Ui.chip(c, if (s.kind == JobKind.STEAM || s.kind == JobKind.EGGS) tr("Vapeur %d °C", t) else "$t °C",
                    Icon.Kind.THERMO, Ui.ACCENT, Ui.ACCENT_DIM, 14f), last = s.jobSpeed == 0 && !s.jobReverse)
                if (s.jobSpeed > 0) add(Ui.chip(c, tr("Vitesse %d", s.jobSpeed), Icon.Kind.SPEED, Ui.TEXT, size = 14f), last = !s.jobReverse)
                if (s.jobReverse) add(Ui.chip(c, tr("Inverse"), Icon.Kind.REVERSE, Ui.COOL, size = 14f), last = true)
            }
        }
        val stateLine = if (st == JobState.RUNNING) "" else Live.stateText(s)
        liveState?.shown = stateLine
        liveState?.setTextColor(Live.stateColor(s))
        liveState?.visibility = if (stateLine.isEmpty()) View.GONE else View.VISIBLE
        val ended = st == JobState.FINISHED || st == JobState.ABORTED
        livePause?.visibility = if (ended || s.turboActive) View.GONE else View.VISIBLE
        liveStop?.visibility = if (ended) View.GONE else View.VISIBLE
        liveOk?.visibility = if (ended) View.VISIBLE else View.GONE
        liveOpen?.visibility = if (s.turboActive) View.GONE else View.VISIBLE
        livePause?.let { b ->
            val paused = st == JobState.PAUSED
            (b.getChildAt(0) as Icon).set(if (paused) Icon.Kind.PLAY else Icon.Kind.PAUSE)
            (b.getChildAt(1) as TextView).text = if (paused) tr("Reprendre") else tr("Pause")
        }
    }

    // ------------------------------------------------------------------ colonne de droite

    private fun sectionCard(title: String, trailing: String?, onTrailing: (() -> Unit)?): LinearLayout {
        val card = Ui.vertical(c)
        card.background = Ui.cardBg(24f)
        Ui.lift(card)
        card.setPadding(Ui.u(20), Ui.u(14), Ui.u(14), Ui.u(6))
        val head = Ui.horizontal(c)
        head.addView(Ui.text(c, title, 17f, Ui.TEXT, Ui.SEMIBOLD), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        if (trailing != null) {
            val t = Ui.text(c, trailing, 14f, Ui.TEXT2, Ui.MEDIUM)
            t.setPadding(Ui.u(10), Ui.u(8), Ui.u(6), Ui.u(8))
            if (onTrailing != null) {
                t.background = Ui.pressable(Ui.round(0, 12f), 12f)
                t.isClickable = true
                t.setOnClickListener { onTrailing() }
            }
            head.addView(t)
        }
        card.addView(head, Ui.lp(b = 2))
        return card
    }

    private fun renderRight(live: Boolean, heroPlan: FoyerPlan?) {
        right.removeAllViews()
        val recipe = if (live) a.liveRecipe() else null
        val g = foyer.tracker.guided
        if (recipe != null && g != null) {
            right.addView(nextCard(recipe, g.stepIndex), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        }
        val unread = Notices.items(a).filter { it.unread }
        if (unread.isNotEmpty()) {
            val max = if (recipe != null) 1 else 2
            val card = sectionCard(tr("À savoir"), tr("Tout voir")) { a.openNotices() }
            val head = card.getChildAt(0) as LinearLayout
            val count = Ui.text(c, unread.size.toString(), 13f, Ui.ACCENT, Ui.BOLD).apply { gravity = Gravity.CENTER }
            count.setPadding(Ui.u(7), 0, Ui.u(7), 0)
            count.minWidth = Ui.u(22)
            count.background = Ui.round(Ui.ACCENT_DIM, 11f)
            head.addView(count, 1, LinearLayout.LayoutParams(Ui.WRAP, Ui.u(22)).apply { leftMargin = Ui.u(8) })
            (head.getChildAt(0).layoutParams as LinearLayout.LayoutParams).apply { width = Ui.WRAP; weight = 0f }
            head.addView(Ui.spacer(c, weight = 1f), 2, LinearLayout.LayoutParams(0, 1, 1f))
            for ((i, item) in unread.take(max).withIndex()) {
                if (i > 0) card.addView(Ui.divider(c))
                card.addView(Notices.row(a, item, compact = true) { Notices.open(a, item) })
            }
            right.addView(card, Ui.lp(t = if (right.childCount > 0) 16 else 0))
        }
        if (recipe != null && g != null) return
        val week = weekCard(heroPlan)
        val filler = week ?: shortcutsCard()
        right.addView(filler, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = Ui.u(if (right.childCount > 0) 16 else 0) })
    }

    /** Les repas prévus après celui de la grande carte (trois au plus). */
    private fun weekCard(heroPlan: FoyerPlan?): View? {
        val (today, now) = Foyer.todayAndTime()
        val plans = foyer.plans
            .filter { p -> val t = p.time; p.day > today || (p.day == today && (t == null || t >= now)) }
            .filter { it != heroPlan }
            .sortedWith(compareBy<FoyerPlan>({ it.day }, { it.time ?: "99:99" }))
            .take(3)
        if (plans.isEmpty()) return null
        val card = sectionCard(tr("Cette semaine"), tr("d'après Foyer"), null)
        for ((i, p) in plans.withIndex()) {
            if (i > 0) card.addView(Ui.divider(c))
            val row = Ui.horizontal(c)
            row.minimumHeight = Ui.u(60)
            val when_ = Ui.vertical(c)
            when_.addView(Ui.ellipsize(Ui.text(c, PlanText.day(p, today), 15f, Ui.TEXT, Ui.SEMIBOLD)))
            PlanText.time(p.time)?.let { when_.addView(Ui.text(c, it, 13f, Ui.TEXT2), Ui.lp(t = 2)) }
            row.addView(when_, LinearLayout.LayoutParams(Ui.u(92), Ui.WRAP).apply { rightMargin = Ui.u(10) })
            val recipe = p.recipeId?.let { a.app.recipes.get(it) }
            row.addView(Ui.ellipsize(Ui.text(c, recipe?.title ?: p.title, 15f, Ui.TEXT).apply { setLineSpacing(0f, 1.1f) }, 2),
                LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            if (recipe != null) {
                row.addView(Icon(c, Icon.Kind.CHEVRON, Ui.TEXT3), LinearLayout.LayoutParams(Ui.u(18), Ui.u(18)).apply { leftMargin = Ui.u(6) })
                row.background = Ui.pressable(Ui.round(0, 12f), 12f)
                row.isClickable = true
                row.setOnClickListener { a.push(RecipeScreen(a, recipe)) }
            }
            card.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
        return card
    }

    /** Sans repas prévu : quatre programmes à portée de doigt. */
    private fun shortcutsCard(): View {
        val card = sectionCard(tr("Raccourcis"), null, null)
        val grid = Ui.vertical(c)
        val kinds = listOf(JobKind.EGGS, JobKind.RICE, JobKind.WATER, JobKind.PREWASH)
        for (r in 0 until 2) {
            val line = Ui.horizontal(c)
            for (k in 0 until 2) {
                val kind = kinds[r * 2 + k]
                val def = ProgramCatalog.of(kind) ?: continue
                // Icône en haut, nom et durée en bas : la tuile se lit d'un coup d'œil quelle que soit sa hauteur.
                val tile = Ui.vertical(c)
                tile.setPadding(Ui.u(14), Ui.u(14), Ui.u(12), Ui.u(12))
                tile.background = Ui.pressable(Ui.round(Ui.SURFACE2, 18f, Ui.STROKE, 1f), 18f)
                tile.addView(Ui.iconCircle(c, Live.kindIcon(kind), 46, 22), LinearLayout.LayoutParams(Ui.u(46), Ui.u(46)))
                tile.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))
                tile.addView(Ui.ellipsize(Ui.text(c, def.label, 16f, Ui.TEXT, Ui.SEMIBOLD)), Ui.lp(Ui.MATCH, Ui.WRAP, t = 6))
                tile.addView(Ui.ellipsize(Ui.text(c, def.note, 13f, Ui.TEXT2)), Ui.lp(Ui.MATCH, Ui.WRAP, t = 2))
                tile.isClickable = true
                tile.setOnClickListener {
                    ProgramsScreen.select(kind)
                    a.openSection(Section.PROGRAMS)
                }
                line.addView(tile, LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { if (k == 0) rightMargin = Ui.u(10) })
            }
            grid.addView(line, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { if (r == 0) bottomMargin = Ui.u(10) })
        }
        card.addView(grid, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = Ui.u(6); bottomMargin = Ui.u(10) })
        return card
    }

    /** Pendant une recette : les étapes qui viennent. */
    private fun nextCard(recipe: Recipe, index: Int): View {
        val left = recipe.steps.size - index - 1
        val card = sectionCard(tr("Ensuite"), if (left > 0) tr("%d étapes restantes", left) else null, null)
        val next = recipe.steps.withIndex().filter { it.index > index }.take(3)
        if (next.isEmpty()) {
            card.addView(Ui.text(c, tr("C'est la dernière étape."), 15f, Ui.TEXT2), Ui.lp(t = 10, b = 12))
        }
        for ((i, iv) in next.withIndex()) {
            if (i > 0) card.addView(Ui.divider(c))
            val row = Ui.horizontal(c)
            row.minimumHeight = Ui.u(60)
            val num = Ui.text(c, "${iv.index + 1}", 14f, Ui.TEXT2, Ui.SEMIBOLD).apply { gravity = Gravity.CENTER }
            num.background = Ui.round(Ui.SURFACE3, 16f)
            row.addView(num, LinearLayout.LayoutParams(Ui.u(32), Ui.u(32)).apply { rightMargin = Ui.u(14) })
            val col = Ui.vertical(c)
            val st = iv.value
            col.addView(Ui.ellipsize(Ui.text(c, st.text.ifBlank { st.machine?.summary ?: "" }, 15f, Ui.TEXT, Ui.MEDIUM), 2))
            val sub = st.weigh?.let { tr("Pèse %d g", it.grams) } ?: st.machine?.takeIf { st.text.isNotBlank() }?.summary
            if (sub != null) col.addView(Ui.ellipsize(Ui.text(c, sub, 13f, Ui.TEXT2)), Ui.lp(t = 3))
            row.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            card.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
        if (foyer.linked) {
            val foot = Ui.horizontal(c)
            foot.setPadding(0, Ui.u(10), 0, Ui.u(10))
            foot.addView(Icon(c, Icon.Kind.PHONE, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(18), Ui.u(18)).apply { rightMargin = Ui.u(10) })
            foot.addView(Ui.text(c, tr("Les téléphones du foyer sont prévenus quand une étape t'attend."), 13f, Ui.TEXT2)
                .apply { setLineSpacing(0f, 1.2f) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            card.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))
            card.addView(Ui.divider(c))
            card.addView(foot)
        }
        return card
    }

    // ------------------------------------------------------------------ recettes à tester

    private fun openList() {
        if (a.app.recipes.countOf(RecipeRepo.FOYER) > 0) a.openSection(Section.RECIPES, RecipeSource.FOYER)
        else a.openSection(Section.RECIPES, RecipeSource.IMPORTED)
    }

    /** Recettes du foyer : à tester d'abord, puis favoris, puis les autres (Lidl les mieux notées sinon). */
    private fun renderRecipes() {
        val repo = a.app.recipes
        val ours = repo.index.entries.filter { it.source == RecipeSource.FOYER }
        val picks: List<SearchIndex.Entry>
        if (ours.isNotEmpty()) {
            val toTry = ours.any { "À tester" in it.tags }
            recipesTitle.text = if (toTry) tr("À tester") else tr("Nos recettes")
            recipesMore.text = tr("Nos recettes · %d", ours.size)
            picks = ours.sortedBy { e -> if ("À tester" in e.tags) 0 else if (e.favorite || "Favori" in e.tags) 1 else 2 }.take(3)
        } else {
            val lidl = repo.index.entries.filter { it.source != RecipeSource.FOYER }
            recipesTitle.text = if (!repo.loaded) tr("Chargement…") else tr("Recettes Lidl")
            recipesMore.text = if (lidl.isEmpty()) "" else tr("Tout voir · %d", lidl.size)
            picks = (lidl.filter { it.favorite } + lidl.filter { !it.favorite }.sortedByDescending { it.rating * minOf(it.ratingCount, 50) }).take(3)
        }
        recipesRow.removeAllViews()
        if (picks.isEmpty()) {
            val empty = Ui.horizontal(c)
            empty.background = Ui.cardBg(18f)
            empty.setPadding(Ui.u(18), Ui.u(12), Ui.u(18), Ui.u(12))
            empty.addView(Icon(c, Icon.Kind.BOOK, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(26), Ui.u(26)).apply { rightMargin = Ui.u(14) })
            empty.addView(Ui.text(c, if (foyer.linked) tr("Les recettes partagées dans Foyer apparaîtront ici.")
                else tr("Réglages › Recettes Lidl › Synchroniser pour télécharger les recettes officielles."), 15f, Ui.TEXT2)
                .apply { setLineSpacing(0f, 1.2f) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            recipesRow.addView(empty, LinearLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
            return
        }
        for ((i, e) in picks.withIndex()) recipesRow.addView(miniCard(e), LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { if (i < 2) rightMargin = Ui.u(12) })
        repeat(3 - picks.size) { recipesRow.addView(View(c), LinearLayout.LayoutParams(0, Ui.MATCH, 1f)) }
    }

    private fun miniCard(e: SearchIndex.Entry): View {
        val card = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        card.setPadding(Ui.u(10), Ui.u(10), Ui.u(12), Ui.u(10))
        card.background = Ui.pressable(Ui.cardBg(18f), 18f)
        Ui.lift(card, 2f)
        val thumb = FrameLayout(c)
        Ui.clip(thumb, 14f)
        thumb.background = Ui.gradient(Ui.placeholderColors(e.title), 14f)
        thumb.addView(Ui.dishIcon(c, e.title, e.category), Ui.flp(Ui.u(32), Ui.u(32), Gravity.CENTER))
        val img = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        thumb.addView(img, Ui.flp())
        ImageLoader.load(img, a.app.recipes.imageFile(e.image), Ui.u(140))
        card.addView(thumb, LinearLayout.LayoutParams(Ui.u(62), Ui.u(62)).apply { rightMargin = Ui.u(10) })
        val col = Ui.vertical(c)
        col.addView(Ui.ellipsize(Ui.text(c, e.title, 14f, Ui.TEXT, Ui.SEMIBOLD).apply { setLineSpacing(0f, 1.1f) }, 2))
        val meta = listOfNotNull(
            if (e.totalMinutes > 0) tr("%d min", e.totalMinutes) else null,
            if (e.source != RecipeSource.FOYER && e.ratingCount > 0) "★ " + String.format(I18n.locale, "%.1f", e.rating) else null,
        ).joinToString(" · ")
        if (meta.isNotEmpty()) col.addView(Ui.ellipsize(Ui.text(c, meta, 13f, Ui.TEXT2)), Ui.lp(t = 4))
        card.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        card.isClickable = true
        card.setOnClickListener { a.app.recipes.get(e.id)?.let { a.push(RecipeScreen(a, it)) } }
        return card
    }

    // ------------------------------------------------------------------ cycle de vie

    override fun onSnapshot(s: Snapshot) {
        val live = s.jobActive || s.turboActive
        if ((leadKind == Lead.LIVE) != live) refresh()
        else if (live) updateLive(s)
    }

    override fun onEvent(e: MachineEvent) {
        // Fin d'une étape : la recette guidée passe à la suivante, l'accueil suit.
        if (leadKind == Lead.LIVE) refresh()
    }

    override fun onShow() {
        leadKey = ""
        refresh()
        unsubRecipes?.invoke()
        unsubRecipes = a.app.recipes.onChange { leadKey = ""; refresh() }
        unsubFoyer?.invoke()
        unsubFoyer = foyer.onChange { refresh() }
        unsubNotices?.invoke()
        unsubNotices = Notices.listen { refresh() }
        if (!counted) foyer.showing(true)
        counted = true
        foyer.syncSoon()
        view.removeCallbacks(tick)
        view.postDelayed(tick, 60_000)
    }

    override fun onHide() {
        unsubRecipes?.invoke()
        unsubFoyer?.invoke()
        unsubNotices?.invoke()
        unsubRecipes = null
        unsubFoyer = null
        unsubNotices = null
        if (counted) foyer.showing(false)
        counted = false
        view.removeCallbacks(tick)
    }

    /** Au bouton rotatif : on arrive sur l'action de la grande carte. */
    override val knobStart: View? get() = null
}
