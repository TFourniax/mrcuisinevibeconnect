package app.toque.ui

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import app.toque.core.foyer.FeedChanges
import app.toque.core.foyer.FoyerFeed
import app.toque.core.foyer.FoyerPlan
import app.toque.core.foyer.NoticeEvent
import app.toque.core.foyer.NoticeLog
import app.toque.core.foyer.ScreensaverConfig
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.recipes.RecipeSource
import app.toque.core.update.Releases
import app.toque.foyer.Foyer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** « Ce soir · 19 h 30 », « Demain », « Jeudi » : un repas prévu dans Foyer, en mots. */
object PlanText {
    /** « 19 h 30 », « 20 h » ; null sans heure. */
    fun time(t: String?): String? {
        if (t == null || t.length < 5) return null
        val h = t.substring(0, 2).trimStart('0').ifEmpty { "0" }
        val m = t.substring(3, 5)
        return if (m == "00") "$h h" else "$h h $m"
    }

    private fun daysFrom(today: String, day: String): Int {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val a = try { f.parse(today)?.time ?: 0L } catch (_: Exception) { 0L }
        val b = try { f.parse(day)?.time ?: 0L } catch (_: Exception) { 0L }
        return Math.round((b - a) / 86_400_000.0).toInt()
    }

    /** « Ce soir », « Aujourd'hui », « Demain », « Samedi ». */
    fun day(p: FoyerPlan, today: String): String {
        val days = daysFrom(today, p.day)
        return when {
            days <= 0 -> if ((p.time ?: "99:99") >= "17:00") tr("Ce soir") else tr("Aujourd'hui")
            days == 1 -> tr("Demain")
            else -> {
                val ms = try { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(p.day)?.time ?: 0L } catch (_: Exception) { 0L }
                SimpleDateFormat("EEEE", I18n.locale).format(Date(ms)).replaceFirstChar { it.uppercaseChar() }
            }
        }
    }

    /** « Ce soir · 19 h 30 ». */
    fun label(p: FoyerPlan, today: String): String {
        val t = time(p.time)
        return if (t != null) day(p, today) + " · " + t else day(p, today)
    }
}

/**
 * « À savoir » : ce que le robot a à dire, rangé en un seul endroit. La cloche de la barre du haut
 * ouvre la liste complète ; l'accueil en montre deux au plus (les non lus). Les alertes de
 * sécurité n'y passent jamais : elles prennent toute la barre du bas, en rouge.
 *
 * Évènements (gardés trois jours) : nouvelles recettes du foyer, menu changé, animations de
 * l'écran de veille. États : nouvelle version de Toque, recettes Lidl, Foyer injoignable.
 */
object Notices {
    enum class Group { FOYER, TOQUE }

    class Item(
        val key: String,
        val group: Group,
        val icon: Icon.Kind,
        val title: String,
        val detail: String,
        val time: String,
        val unread: Boolean,
        val action: String? = null,
        val open: ((MainActivity) -> Unit)? = null,
    )

    private val listeners = ArrayList<() -> Unit>()

    /** Suit Foyer et les recettes : relève les nouveautés et prévient [l] (et les autres abonnés). */
    fun watch(a: MainActivity, l: () -> Unit): () -> Unit {
        listeners += l
        val update = {
            track(a)
            fire()
        }
        val u1 = Foyer.of(a.app).onChange(update)
        val u2 = a.app.recipes.onChange(update)
        update()
        return {
            u1()
            u2()
            listeners -= l
        }
    }

    fun listen(l: () -> Unit): () -> Unit {
        listeners += l
        return { listeners -= l }
    }

    private fun fire() {
        for (l in ArrayList(listeners)) l()
    }

    // ------------------------------------------------------------------ relevé des nouveautés

    private const val HEADER = "v1"

    private fun track(a: MainActivity) {
        // Pas de pile d'horloge : au démarrage le robot se croit en 2010 jusqu'à la mise à
        // l'heure. Rien n'est noté ni élagué avant (les dates seraient fausses).
        if (!app.toque.TimeSync.clockPlausible()) return
        val p = a.prefs
        val app = a.app
        val now = System.currentTimeMillis()
        val before = p.noticeLog
        var log = NoticeLog.prune(NoticeLog.decode(before), now)

        // Recettes du foyer (une fois la base lue : sinon tout paraîtrait nouveau).
        if (app.recipes.loaded) {
            val entries = app.recipes.index.entries.filter { it.source == RecipeSource.FOYER }
            val current = entries.map { it.id }
            val stored = p.noticeRecipes.split('\n')
            val initialised = stored.firstOrNull() == HEADER
            val known = if (initialised) stored.drop(1).filter { it.isNotEmpty() } else emptyList()
            val added = FeedChanges.added(known, current)
            // Premières recettes reçues (robot tout juste relié) : rien à signaler.
            if (known.isNotEmpty() && added.isNotEmpty()) {
                val titles = added.mapNotNull { id -> entries.firstOrNull { it.id == id }?.title }
                log = NoticeLog.add(log, NoticeEvent("recipes:" + added.sorted().joinToString(",").hashCode(), "recipes", titles, now), now)
            }
            if (!initialised || known != current) p.noticeRecipes = (listOf(HEADER) + current).joinToString("\n")
        }

        // Repas prévus.
        val plansNow = p.foyerPlans
        val knownPlans = p.noticePlans
        if (knownPlans != plansNow) {
            if (knownPlans.isNotEmpty()) {
                val (today, _) = Foyer.todayAndTime()
                val old = FoyerFeed.decodePlans(knownPlans)
                val changed = FeedChanges.changedPlans(old, FoyerFeed.decodePlans(plansNow), today)
                if (old.isNotEmpty() && changed.isNotEmpty()) {
                    val items = changed.map { it.day + "|" + (it.time ?: "") + "|" + it.title }
                    log = NoticeLog.add(log, NoticeEvent("menu:" + items.joinToString().hashCode(), "menu", items, now), now)
                }
            }
            p.noticePlans = plansNow
        }

        // Animations de l'écran de veille ajoutées dans Foyer.
        val saver = ScreensaverConfig.decode(p.screensaver).items.size
        val knownSaver = p.noticeSaverItems
        if (knownSaver != saver) {
            if (knownSaver >= 0 && saver > knownSaver) {
                log = NoticeLog.add(log, NoticeEvent("saver:$saver", "saver", listOf((saver - knownSaver).toString()), now), now)
            }
            p.noticeSaverItems = saver
        }

        val after = NoticeLog.encode(log)
        if (after != before) p.noticeLog = after
    }

    // ------------------------------------------------------------------ la liste

    private fun readKeys(a: MainActivity): Set<String> = a.prefs.noticeRead.split('\n').filter { it.isNotEmpty() }.toSet()

    /** Les dernières mises à jour vues seulement (une ligne par version : la liste ne grossit pas). */
    private fun keepRead(keys: Set<String>): String = keys.toList().takeLast(5).joinToString("\n")

    private fun installedVersion(a: MainActivity): String =
        try { a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    /** Tous les messages, les non lus d'abord, puis du plus récent au plus ancien. */
    fun items(a: MainActivity): List<Item> {
        val p = a.prefs
        val now = System.currentTimeMillis()
        val (today, _) = Foyer.todayAndTime()
        val out = ArrayList<Item>()
        for (e in NoticeLog.prune(NoticeLog.decode(p.noticeLog), now)) {
            val time = ago(e.atMs, now)
            when (e.kind) {
                "recipes" -> out += Item(
                    e.key, Group.FOYER, Icon.Kind.SPARKLE,
                    if (e.items.size == 1) tr("Une nouvelle recette du foyer") else tr("%d nouvelles recettes du foyer", e.items.size),
                    e.items.joinToString(", "), time, !e.read,
                ) { act -> act.openSection(Section.RECIPES, RecipeSource.FOYER) }
                "menu" -> {
                    val plans = e.items.map { it.split('|') }.filter { it.size >= 3 }
                        .map { FoyerPlan(it[0], it[1].ifEmpty { null }, it.subList(2, it.size).joinToString("|"), null) }
                    val first = plans.firstOrNull()
                    val detail = if (first == null) "" else PlanText.label(first, today) + " : " + first.title +
                        if (plans.size > 1) " " + tr("(et %d autres)", plans.size - 1) else ""
                    out += Item(e.key, Group.FOYER, Icon.Kind.CALENDAR, tr("Le menu a changé"), detail, time, !e.read) { act -> act.home() }
                }
                "saver" -> {
                    val n = e.items.firstOrNull()?.toIntOrNull() ?: 1
                    out += Item(e.key, Group.FOYER, Icon.Kind.IMAGE, tr("Écran de veille"),
                        if (n == 1) tr("Une animation ajoutée, après quelques minutes sans personne au robot.")
                        else tr("%d animations ajoutées, après quelques minutes sans personne au robot.", n),
                        time, !e.read) { act -> act.openSettings() }
                }
            }
        }
        val read = readKeys(a)
        val available = p.updateAvailable
        if (available.isNotEmpty() && Releases.isNewer(available, installedVersion(a))) {
            val key = "update:$available"
            out += Item(
                key, Group.TOQUE, Icon.Kind.DOWNLOAD, tr("Toque %s est prête", available),
                if (a.snapshot.busy) tr("À installer une fois le robot au repos.") else tr("S'installe en une minute, depuis les réglages."),
                "", key !in read, tr("Installer"),
            ) { act -> act.openSettings() }
        }
        val f = Foyer.of(a.app)
        if (p.foyerEnabled && f.linked && p.foyerLastSyncMs > 0 && now - p.foyerLastSyncMs > 30 * 60_000L && p.foyerLastResult.isNotBlank()) {
            out += Item("foyer", Group.FOYER, Icon.Kind.WARNING, tr("Pas de nouvelles de Foyer"), p.foyerLastResult,
                ago(p.foyerLastSyncMs, now), false) { act -> act.openSettings() }
        }
        if (p.lastSyncMs > 0) {
            out += Item("lidl", Group.TOQUE, Icon.Kind.SYNC, tr("Recettes Lidl"),
                p.lastSyncResult.ifBlank { tr("%d recettes", a.app.recipes.countOf(app.toque.recipes.RecipeRepo.LIDL)) },
                ago(p.lastSyncMs, now), false) { act -> act.openSettings() }
        }
        return out.filter { it.unread } + out.filter { !it.unread }
    }

    fun unread(a: MainActivity): Int = items(a).count { it.unread }

    fun markRead(a: MainActivity, key: String) {
        val p = a.prefs
        p.noticeLog = NoticeLog.encode(NoticeLog.markRead(NoticeLog.decode(p.noticeLog), key))
        if (key.startsWith("update:")) p.noticeRead = keepRead(readKeys(a) + key)
        fire()
    }

    fun markAllRead(a: MainActivity) {
        val p = a.prefs
        val keys = items(a).filter { it.unread }.map { it.key }
        p.noticeLog = NoticeLog.encode(NoticeLog.markRead(NoticeLog.decode(p.noticeLog)))
        p.noticeRead = keepRead(readKeys(a) + keys.filter { it.startsWith("update:") })
        fire()
    }

    /** « à l'instant », « il y a 12 min », « il y a 2 h », « hier », « lun. ». */
    fun ago(atMs: Long, nowMs: Long): String {
        val d = nowMs - atMs
        if (d < 60_000L) return tr("à l'instant")
        if (d < 3600_000L) return tr("il y a %d min", (d / 60_000L).toInt())
        val then = Calendar.getInstance().apply { timeInMillis = atMs }
        val now = Calendar.getInstance().apply { timeInMillis = nowMs }
        val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        if (sameDay) return tr("il y a %d h", (d / 3600_000L).toInt())
        now.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        if (yesterday) return tr("hier")
        return SimpleDateFormat("EEE d", I18n.locale).format(Date(atMs))
    }

    // ------------------------------------------------------------------ affichage

    /** Une ligne de message (cloche et accueil). [onTap] après l'avoir marquée lue. */
    fun row(a: MainActivity, item: Item, compact: Boolean, onTap: () -> Unit): LinearLayout {
        val c = a
        val row = Ui.horizontal(c)
        row.minimumHeight = Ui.u(if (compact) 64 else 70)
        row.setPadding(0, Ui.u(8), 0, Ui.u(8))
        val circle = if (item.unread) Ui.iconCircle(c, item.icon, 42, 20) else Ui.iconCircle(c, item.icon, 42, 20, Ui.SURFACE3, Ui.TEXT2)
        row.addView(circle, LinearLayout.LayoutParams(Ui.u(42), Ui.u(42)).apply { rightMargin = Ui.u(14) })
        val col = Ui.vertical(c)
        val head = Ui.horizontal(c)
        val title = Ui.ellipsize(Ui.text(c, item.title, 15f, Ui.TEXT, if (item.unread) Ui.SEMIBOLD else Ui.MEDIUM), if (compact) 1 else 2)
        head.addView(title, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        if (item.unread && !compact) {
            head.addView(View(c).apply { background = Ui.round(Ui.ACCENT, 4f) }, LinearLayout.LayoutParams(Ui.u(8), Ui.u(8)).apply { leftMargin = Ui.u(8); rightMargin = Ui.u(8) })
        }
        if (!compact && item.time.isNotEmpty()) head.addView(Ui.text(c, item.time, 13f, Ui.TEXT2), Ui.lp(Ui.WRAP, Ui.WRAP, l = 6))
        col.addView(head)
        if (item.detail.isNotEmpty()) col.addView(Ui.ellipsize(Ui.text(c, item.detail, 13f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }, if (compact) 1 else 2), Ui.lp(t = 3))
        row.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val action = item.action
        if (action != null) {
            val b = Ui.button(c, action, if (compact) Ui.Style.ACCENT_SOFT else Ui.Style.PRIMARY, height = 40, textSize = 14f) { onTap() }
            b.setPadding(Ui.u(16), 0, Ui.u(16), 0)
            row.addView(b, Ui.lp(Ui.WRAP, Ui.u(40), l = 10))
        } else if (compact && item.open != null) {
            row.addView(Icon(c, Icon.Kind.CHEVRON, Ui.TEXT3), LinearLayout.LayoutParams(Ui.u(18), Ui.u(18)).apply { leftMargin = Ui.u(8) })
        }
        row.background = Ui.pressable(Ui.round(0, 12f), 12f)
        row.isClickable = true
        row.setOnClickListener { onTap() }
        return row
    }

    /** Ouvre ce dont parle le message, après l'avoir marqué lu. */
    fun open(a: MainActivity, item: Item) {
        markRead(a, item.key)
        a.closeOverlay()
        item.open?.invoke(a)
    }

    /** Le panneau de la cloche, à droite, sur toute la hauteur. */
    fun showSheet(a: MainActivity) {
        val c = a
        val sheet = Ui.vertical(c)
        sheet.setPadding(Ui.u(26), Ui.u(18), Ui.u(18), Ui.u(16))
        sheet.background = GradientDrawable().apply {
            setColor(Ui.SURFACE)
            val r = Ui.uf(28f)
            setCornerRadii(floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r))
        }
        sheet.elevation = Ui.uf(16f)
        val items = items(a)
        val unread = items.count { it.unread }
        val head = Ui.horizontal(c)
        val titles = Ui.vertical(c)
        titles.addView(Ui.text(c, tr("À savoir"), 26f, Ui.TEXT, Ui.SEMIBOLD))
        titles.addView(Ui.text(c, when (unread) {
            0 -> tr("Rien de nouveau")
            1 -> tr("1 nouveau")
            else -> tr("%d nouveaux", unread)
        }, 14f, Ui.TEXT2), Ui.lp(t = 4))
        head.addView(titles, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        head.addView(Ui.iconButton(c, Icon.Kind.CLOSE, tr("Fermer"), Ui.Style.SECONDARY, 48, 22) { a.closeOverlay() },
            LinearLayout.LayoutParams(Ui.u(48), Ui.u(48)))
        sheet.addView(head)

        val list = Ui.vertical(c)
        for (g in Group.values()) {
            val rows = items.filter { it.group == g }
            if (rows.isEmpty()) continue
            list.addView(Ui.overline(c, if (g == Group.FOYER) tr("Du foyer") else "Toque"), Ui.lp(t = 18, b = 4))
            for ((i, it) in rows.withIndex()) {
                if (i > 0) list.addView(Ui.divider(c))
                list.addView(row(a, it, compact = false) { open(a, it) })
            }
        }
        if (items.isEmpty()) {
            val empty = Ui.vertical(c, Gravity.CENTER_HORIZONTAL)
            empty.addView(Ui.iconCircle(c, Icon.Kind.BELL, 64, 28, Ui.SURFACE3, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(64), Ui.u(64)))
            empty.addView(Ui.text(c, tr("Rien à signaler. Les nouvelles recettes du foyer, le menu de la semaine et les mises à jour s'afficheront ici."),
                16f, Ui.TEXT2).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f) }, Ui.lp(t = 16))
            list.addView(empty, Ui.lp(t = 60))
        }
        sheet.addView(Ui.scroll(c, list), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        if (unread > 0) {
            val all = Ui.button(c, tr("Tout marquer comme lu"), Ui.Style.GHOST, Icon.Kind.CHECK, height = 50, textSize = 15f) {
                markAllRead(a)
                a.closeOverlay()
            }
            sheet.addView(all, Ui.lp(Ui.WRAP, Ui.WRAP, t = 10))
        }
        a.showSheet(sheet)
    }
}
