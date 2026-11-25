package app.toque.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import app.toque.core.foyer.AgentRun
import app.toque.core.foyer.Agents
import app.toque.core.i18n.tr
import app.toque.foyer.AgentsWatch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * La carte des agents, ouverte d'un toucher sur l'icône de la barre du haut : une ligne par agent
 * (qui t'attend d'abord), mise à jour en direct tant qu'elle est ouverte. Elle se ferme d'un
 * toucher à côté, sur la croix, au bouton rotatif (la croix est son seul élément) ou au retour.
 */
class AgentsCard(private val a: MainActivity) {
    private val watch = AgentsWatch.of(a.app)
    private val cardColor = if (Ui.isDark) Ui.SURFACE2 else Ui.SURFACE
    private val card = Ui.card(a, 24f, cardColor)
    private val summary = Ui.text(a, "", 14f, Ui.TEXT2)
    private val list = Ui.vertical(a)
    private val footer = Ui.text(a, "", 13f, Ui.TEXT3)

    fun show() {
        card.setPadding(Ui.u(22), Ui.u(14), Ui.u(14), Ui.u(14))
        card.elevation = Ui.uf(12f)
        val head = Ui.horizontal(a)
        head.addView(Ui.iconCircle(a, Icon.Kind.AGENT, 40, 20, Ui.SURFACE3, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(40), Ui.u(40)).apply { rightMargin = Ui.u(12) })
        val titles = Ui.vertical(a)
        titles.addView(Ui.text(a, tr("Agents"), 20f, Ui.TEXT, Ui.SEMIBOLD))
        titles.addView(summary, Ui.lp(t = 2))
        head.addView(titles, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        head.addView(Ui.iconButton(a, Icon.Kind.CLOSE, tr("Fermer"), Ui.Style.SECONDARY, 44, 20) { a.closeOverlay() },
            LinearLayout.LayoutParams(Ui.u(44), Ui.u(44)))
        card.addView(head)
        card.addView(list, Ui.lp(t = 8, r = 8))
        card.addView(footer, Ui.lp(t = 10))
        update()
        a.showPopover(card, 470) { watch.opened(false) }
        watch.opened(true)
    }

    /** Nouvelle réponse de Foyer, ou une minute de plus : la carte se refait (six lignes au plus). */
    fun update() {
        val now = System.currentTimeMillis()
        val lines = Agents.shown(watch.runs, now)
        summary.text = Agents.summary(lines, now)
        list.removeAllViews()
        val several = lines.map { it.who }.filter { it.isNotEmpty() }.toSet().size > 1
        for ((i, r) in lines.withIndex()) {
            if (i > 0) list.addView(Ui.divider(a))
            list.addView(row(r, now, several))
        }
        if (lines.isEmpty()) {
            list.addView(Ui.text(a, tr("Aucun agent en cours ni terminé ces dernières heures."), 15f, Ui.TEXT2).apply {
                setLineSpacing(0f, 1.2f)
            }, Ui.lp(t = 12, b = 6))
        }
        val time = SimpleDateFormat("HH:mm", Locale.FRANCE)
        footer.text = when {
            watch.failingSinceMs > 0 -> tr("Foyer injoignable depuis %s", time.format(Date(watch.failingSinceMs)))
            watch.lastOkMs > 0 -> tr("Nouvelles de Foyer à %s", time.format(Date(watch.lastOkMs)))
            else -> tr("Connexion à Foyer…")
        }
        footer.setTextColor(if (watch.failingSinceMs > 0) Ui.WARN else Ui.TEXT3)
    }

    private fun stateColor(r: AgentRun, now: Long): Int = when {
        Agents.stale(r, now) -> Ui.TEXT3
        r.state == Agents.WAITING -> Ui.WARN
        r.state == Agents.ERROR -> Ui.DANGER
        r.state == Agents.WORKING -> Ui.COOL
        r.state == Agents.DONE -> Ui.OK
        else -> Ui.TEXT3
    }

    private fun glyph(r: AgentRun, now: Long): StatusGlyph.Kind = when {
        Agents.stale(r, now) -> StatusGlyph.Kind.QUIET
        r.state == Agents.WORKING -> StatusGlyph.Kind.WORKING
        r.state == Agents.WAITING -> StatusGlyph.Kind.WAITING
        r.state == Agents.DONE -> StatusGlyph.Kind.DONE
        r.state == Agents.ERROR -> StatusGlyph.Kind.ERROR
        else -> StatusGlyph.Kind.QUIET
    }

    /**
     * Une ligne : le signe de l'état, puis **l'agent** · le projet, la tâche en italique dessous ;
     * à droite l'état (sa couleur) et depuis quand.
     */
    private fun row(r: AgentRun, now: Long, showWho: Boolean): View {
        val color = stateColor(r, now)
        val row = Ui.horizontal(a)
        row.setPadding(0, Ui.u(11), 0, Ui.u(11))
        row.addView(StatusGlyph(a, glyph(r, now), color, cardColor),
            LinearLayout.LayoutParams(Ui.u(22), Ui.u(22)).apply { rightMargin = Ui.u(14) })

        val middle = Ui.vertical(a)
        val first = Ui.horizontal(a, Gravity.CENTER_VERTICAL)
        first.addView(Ui.text(a, Agents.name(r.agent), 17f, Ui.TEXT, Ui.BOLD), LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        val project = listOf(r.project, r.who.takeIf { showWho }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")
        if (project.isNotEmpty()) {
            first.addView(Ui.ellipsize(Ui.text(a, "·  $project", 15f, Ui.TEXT2, Ui.MEDIUM)),
                LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = Ui.u(8) })
        }
        middle.addView(first)
        if (r.title.isNotEmpty()) {
            val t = Ui.text(a, r.title, 14f, Ui.TEXT2)
            // Montserrat n'a pas d'italique embarquée : Android penche la police régulière.
            t.setTypeface(Ui.REGULAR, Typeface.ITALIC)
            t.maxLines = 1
            t.ellipsize = TextUtils.TruncateAt.END
            middle.addView(t, Ui.lp(t = 3))
        }
        row.addView(middle, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))

        val right = Ui.vertical(a, Gravity.END)
        // Largeur propre (pas « toute la colonne ») : la colonne prend le plus long des deux textes.
        right.addView(Ui.text(a, Agents.stateLabel(r, now), 15f, color, Ui.SEMIBOLD).apply { gravity = Gravity.END },
            Ui.lp(Ui.WRAP, Ui.WRAP))
        right.addView(Ui.text(a, Agents.timeLabel(r, now), 13f, Ui.TEXT3).apply {
            gravity = Gravity.END
            maxLines = 1
        }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 3))
        row.addView(right, Ui.lp(Ui.WRAP, Ui.WRAP, l = 12))
        // Lu d'un bloc par un lecteur d'écran.
        row.contentDescription = listOf(Agents.name(r.agent), r.project, r.title, Agents.stateLabel(r, now), Agents.timeLabel(r, now))
            .filter { it.isNotEmpty() }.joinToString(", ")
        return row
    }
}

/**
 * Le petit signe devant l'agent, lisible d'un coup d'œil : un cercle qui tourne (en cours), une
 * coche (terminé), un « ! » (t'attend), une croix (erreur), un cercle en pointillés (silencieux).
 * Le cercle qui tourne suit l'horloge : la carte peut se refaire sans qu'il saute ; il ne
 * s'anime que tant qu'il est à l'écran.
 */
class StatusGlyph(c: Context, private val kind: Kind, private val color: Int, private val cutout: Int) : View(c) {
    enum class Kind { WORKING, WAITING, DONE, ERROR, QUIET }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        if (s <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val r = s / 2f
        paint.pathEffect = null
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        when (kind) {
            Kind.WORKING -> {
                val w = s * 0.14f
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = w
                paint.color = (color and 0x00FFFFFF) or 0x33000000
                canvas.drawCircle(cx, cy, r - w / 2f, paint)
                paint.color = color
                oval.set(cx - r + w / 2f, cy - r + w / 2f, cx + r - w / 2f, cy + r - w / 2f)
                val angle = (SystemClock.uptimeMillis() % 1_000L) * 0.36f
                canvas.drawArc(oval, angle - 90f, 110f, false, paint)
                // Une trentaine d'images par seconde suffisent à un si petit cercle.
                postInvalidateDelayed(33)
            }
            Kind.QUIET -> {
                val w = s * 0.11f
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = w
                paint.color = color
                paint.pathEffect = DashPathEffect(floatArrayOf(s * 0.1f, s * 0.12f), 0f)
                canvas.drawCircle(cx, cy, r - w / 2f, paint)
            }
            else -> {
                paint.style = Paint.Style.FILL
                paint.color = color
                canvas.drawCircle(cx, cy, r, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = s * 0.12f
                paint.color = cutout
                path.reset()
                when (kind) {
                    Kind.DONE -> {
                        path.moveTo(s * 0.29f, s * 0.52f)
                        path.lineTo(s * 0.44f, s * 0.67f)
                        path.lineTo(s * 0.72f, s * 0.37f)
                        canvas.drawPath(path, paint)
                    }
                    Kind.ERROR -> {
                        canvas.drawLine(s * 0.35f, s * 0.35f, s * 0.65f, s * 0.65f, paint)
                        canvas.drawLine(s * 0.65f, s * 0.35f, s * 0.35f, s * 0.65f, paint)
                    }
                    else -> {
                        canvas.drawLine(cx, s * 0.26f, cx, s * 0.56f, paint)
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(cx, s * 0.73f, s * 0.075f, paint)
                    }
                }
            }
        }
    }
}
