package app.toque.ui

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
    private val card = Ui.card(a, 24f, if (Ui.isDark) Ui.SURFACE2 else Ui.SURFACE)
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

    private fun row(r: AgentRun, now: Long, showWho: Boolean): View {
        val color = stateColor(r, now)
        val row = Ui.horizontal(a)
        row.setPadding(0, Ui.u(10), 0, Ui.u(10))
        val dot = View(a)
        dot.background = Ui.round(color, 5f)
        row.addView(dot, LinearLayout.LayoutParams(Ui.u(10), Ui.u(10)).apply { rightMargin = Ui.u(12) })

        // Dans l'ordre voulu : l'agent, le projet, puis la tâche ; l'état à droite.
        val middle = Ui.vertical(a)
        val first = Ui.horizontal(a, Gravity.CENTER_VERTICAL)
        first.addView(Ui.text(a, Agents.name(r.agent), 16f, Ui.TEXT, Ui.SEMIBOLD), LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        val project = listOf(r.project, r.who.takeIf { showWho }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")
        if (project.isNotEmpty()) {
            first.addView(Ui.ellipsize(Ui.text(a, project, 15f, Ui.TEXT2, Ui.MEDIUM)),
                LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = Ui.u(10) })
        }
        middle.addView(first)
        if (r.title.isNotEmpty()) {
            val t = Ui.text(a, r.title, 14f, Ui.TEXT2)
            t.maxLines = 1
            t.ellipsize = TextUtils.TruncateAt.END
            middle.addView(t, Ui.lp(t = 2))
        }
        row.addView(middle, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))

        val right = Ui.vertical(a, Gravity.END)
        // Largeur propre (pas « toute la colonne ») : la colonne prend le plus long des deux textes.
        right.addView(Ui.text(a, Agents.stateLabel(r, now), 14f, color, Ui.MEDIUM).apply { gravity = Gravity.END },
            Ui.lp(Ui.WRAP, Ui.WRAP))
        right.addView(Ui.text(a, Agents.timeLabel(r, now), 13f, Ui.TEXT3).apply {
            gravity = Gravity.END
            maxLines = 1
        }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 2))
        row.addView(right, Ui.lp(Ui.WRAP, Ui.WRAP, l = 12))
        // Lu d'un bloc par un lecteur d'écran.
        row.contentDescription = listOf(Agents.name(r.agent), r.project, r.title, Agents.stateLabel(r, now), Agents.timeLabel(r, now))
            .filter { it.isNotEmpty() }.joinToString(", ")
        return row
    }
}
