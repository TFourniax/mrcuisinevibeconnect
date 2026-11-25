package app.toque.ui

import android.app.Activity
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.Prefs
import app.toque.ToqueApp
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.foyer.Agents
import app.toque.foyer.AgentsWatch
import app.toque.foyer.Foyer
import app.toque.core.machine.EventType
import app.toque.core.machine.JobKind
import app.toque.core.machine.JobSpec
import app.toque.core.machine.JobState
import app.toque.core.machine.MachineEvent
import app.toque.core.machine.PauseReason
import app.toque.core.machine.SafetyPolicy
import app.toque.core.machine.Snapshot
import app.toque.core.machine.Verdict
import app.toque.core.ui.RingPolicy
import app.toque.core.ui.RingState
import app.toque.core.ui.ThemeClock
import app.toque.core.ui.ThemeMode
import app.toque.core.recipes.Recipe
import app.toque.recipes.LidlSync
import app.toque.recipes.SyncService
import app.toque.ui.screens.CookScreen
import app.toque.ui.screens.HomeScreen
import app.toque.ui.screens.ManualScreen
import app.toque.ui.screens.ProgramsScreen
import app.toque.ui.screens.RecipesScreen
import app.toque.ui.screens.ScaleScreen
import app.toque.ui.screens.SettingsScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Écran d'accueil du robot (lanceur) : une seule activité, des écrans empilés. */
class MainActivity : Activity() {
    lateinit var app: ToqueApp
        private set
    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var overlay: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var topBar: TopBar
    private lateinit var liveBar: LiveBar
    private lateinit var navBar: NavBar
    private val stack = ArrayList<Screen>()
    private val main = Handler(Looper.getMainLooper())
    private var unsubscribe: (() -> Unit)? = null
    private var unsubscribeEvents: (() -> Unit)? = null
    private var unsubscribeNotices: (() -> Unit)? = null
    private var unsubscribeRecipes: (() -> Unit)? = null
    private var unsubscribeAgents: (() -> Unit)? = null
    /** La carte des agents, tant qu'elle est ouverte. */
    private var agentsCard: AgentsCard? = null
    /** Appelé quand la carte affichée se ferme (ou est remplacée). */
    private var overlayClosed: (() -> Unit)? = null
    private var tone: ToneGenerator? = null
    private var toastView: TextView? = null
    private var video: VideoPanel? = null
    private var lastKnobMs = 0L
    private lateinit var knobNav: KnobNav
    private lateinit var screensaver: Screensaver
    private lateinit var ring: RingLight
    /** Le geste qui a fermé l'écran de veille ne va pas plus loin (doigt levé, touche relâchée). */
    private var swallowTouch = false
    private var swallowKey = -1
    private var lastSeen: Snapshot? = null
    var knobListener: ((KeyEvent) -> Unit)? = null

    val prefs: Prefs get() = app.prefs
    val machine get() = app.machine
    val snapshot: Snapshot get() = app.machine.snapshot

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = ToqueApp.of(this)
        val dm = resources.displayMetrics
        Ui.init(dm.widthPixels, dm.heightPixels)
        Ui.initFonts(assets)
        Ui.usePalette(if (wantDark()) Ui.DARK_PALETTE else Ui.LIGHT_PALETTE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this)
        buildChrome()
        overlay = FrameLayout(this)
        overlay.visibility = View.GONE
        root.addView(overlay, Ui.flp())
        setContentView(root)
        // La racine peut prendre le focus : un champ de recherche ne le garde pas sans qu'on tape.
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        knobNav = KnobNav(this, root)
        // Le cadre du bouton rotatif s'efface sous l'écran de veille.
        screensaver = Screensaver(this, root) { knobNav.touch() }
        ring = RingLight(prefs, state = { ringState() }, screensaverShown = { screensaver.showing }, lastActivityAt = { screensaver.lastActivityAt })
        screensaver.onChange = { ring.update() }
        // L'anneau RVB trouvé au démarrage : les réglages ouverts le montrent.
        ring.onFound = { (current as? SettingsScreen)?.refreshRing() }

        try {
            tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
        } catch (_: RuntimeException) {
        }

        app.machine.connect()
        app.recipes.load()
        SyncService.schedule(this)
        // « À savoir » : nouveautés du foyer, mise à jour ; la pastille de la cloche suit.
        unsubscribeNotices = Notices.watch(this) { refreshBadge() }
        unsubscribeRecipes = app.recipes.onChange { liveCache = null }
        // Statut des agents : l'icône de la barre du haut (et la carte ouverte) suivent Foyer.
        unsubscribeAgents = AgentsWatch.of(app).onChange { refreshAgents() }
        if (debuggable() && intent?.getBooleanExtra(PROBE_AGENTS, false) == true) AgentsWatch.of(app).probe()
        push(HomeScreen(this))
        if (reopenSettings) {
            reopenSettings = false
            push(SettingsScreen(this))
        }
    }

    /** Applique la langue choisie dans les réglages : l'interface est reconstruite, on revient aux réglages. */
    fun applyLanguage() {
        I18n.select(I18n.resolve(prefs.language, Locale.getDefault().language).code)
        reopenSettings = true
        recreate()
    }

    override fun onStart() {
        super.onStart()
        unsubscribe = app.machine.onSnapshot { s ->
            if (machineMoved(lastSeen, s)) screensaver.activity()
            lastSeen = s
            ring.update()
            topBar.update(s)
            liveBar.update(s)
            stack.lastOrNull()?.onSnapshot(s)
        }
        unsubscribeEvents = app.machine.onEvent { e -> onMachineEvent(e) }
        topBar.startClock()
        screensaver.start()
        ring.start()
    }

    override fun onResume() {
        super.onResume()
        immersive()
        prefs.returnBubble = false
        ReturnBubble.stop(this)
        if (prefs.vendorSession) endVendorSession()
    }

    override fun onStop() {
        // Une autre appli devant (appli d'origine, paramètres) : l'anneau lui est rendu allumé.
        ring.stop(otherApp = prefs.returnBubble || prefs.vendorSession)
        screensaver.stop()
        closeVideo()
        unsubscribe?.invoke()
        unsubscribeEvents?.invoke()
        topBar.stopClock()
        super.onStop()
    }

    override fun onDestroy() {
        unsubscribeNotices?.invoke()
        unsubscribeRecipes?.invoke()
        unsubscribeAgents?.invoke()
        tone?.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    // ------------------------------------------------------------------ navigation

    val current: Screen? get() = stack.lastOrNull()
    val depth: Int get() = stack.size

    fun push(s: Screen) {
        hideKeyboard()
        stack.lastOrNull()?.onHide()
        stack += s
        show(s)
    }

    /** Remplace l'écran courant (pas de retour vers lui). */
    fun replace(s: Screen) {
        hideKeyboard()
        stack.removeLastOrNull()?.onHide()
        stack += s
        show(s)
    }

    fun back() {
        if (stack.size <= 1) return
        val top = stack.last()
        if (!top.canLeave()) return
        hideKeyboard()
        top.onHide()
        stack.removeAt(stack.size - 1)
        show(stack.last())
    }

    fun home() {
        hideKeyboard()
        while (stack.size > 1) stack.removeAt(stack.size - 1).onHide()
        show(stack.first())
    }

    /**
     * Une section de la barre du bas : on quitte l'écran en cours (et ses écrans de détail) pour
     * la racine de la section ; déjà dans la section, on revient à sa racine.
     */
    fun openSection(section: Section, recipesSource: app.toque.core.recipes.RecipeSource? = null) {
        if (section == Section.HOME) {
            if (stack.size > 1 && current?.canLeave() == false) return
            home()
            return
        }
        val top = current ?: return
        if (!top.canLeave()) return
        val root = stack.getOrNull(1)
        if (root != null && root.section == section && recipesSource == null) {
            (root as? ProgramsScreen)?.applyPending()
            if (stack.size == 2) return
            hideKeyboard()
            top.onHide()
            while (stack.size > 2) stack.removeAt(stack.size - 1)
            show(root)
            return
        }
        hideKeyboard()
        top.onHide()
        while (stack.size > 1) stack.removeAt(stack.size - 1)
        val s = when (section) {
            Section.RECIPES -> RecipesScreen(this, source = recipesSource)
            Section.MANUAL -> ManualScreen(this)
            Section.PROGRAMS -> ProgramsScreen(this)
            Section.SCALE -> ScaleScreen(this)
            Section.HOME -> return
        }
        stack += s
        show(s)
    }

    fun openSettings() {
        if (current !is SettingsScreen) push(SettingsScreen(this))
    }

    fun openNotices() = Notices.showSheet(this)

    /** La section mise en avant dans la barre du bas : celle de l'écran de base de la pile. */
    private fun activeSection(): Section = stack.getOrNull(1)?.section ?: Section.HOME

    private fun show(s: Screen) {
        content.removeAllViews()
        val v = s.view
        (v.parent as? FrameLayout)?.removeView(v)
        content.addView(v, Ui.flp())
        setTopBar(s)
        navBar.visibility = if (s.fullScreen) View.GONE else View.VISIBLE
        navBar.setActive(activeSection())
        // Sans barre du bas, la barre du programme en cours garde une marge sous elle.
        liveBar.setPadding(0, 0, 0, if (s.fullScreen) Ui.u(12) else 0)
        refreshBadge()
        // La vidéo d'une recette reste ouverte de sa fiche à sa cuisson, pas au-delà.
        video?.let { if (it.recipe.id != s.recipeId) closeVideo() else placeVideo() }
        s.onShow()
        s.onSnapshot(snapshot)
        liveBar.update(snapshot)
        knobNav.reset()
    }

    private fun setTopBar(s: Screen) {
        val mode = when {
            stack.size <= 1 -> TopBar.Mode.HOME
            stack.size == 2 && s.section != null -> TopBar.Mode.SECTION
            else -> TopBar.Mode.DETAIL
        }
        topBar.setScreen(s.title, mode, s.fullScreen, s is SettingsScreen)
    }

    fun refreshTitle() {
        current?.let { setTopBar(it) }
    }

    /** Icône des agents (et carte ouverte) : nouvelle réponse de Foyer, ou le temps passe. */
    fun refreshAgents() {
        if (!::topBar.isInitialized) return
        val watch = AgentsWatch.of(app)
        val now = System.currentTimeMillis()
        val signal = Agents.signal(watch.runs, now, prefs.agentsSeenMs)
        topBar.setAgents(signal, Agents.iconDescription(signal, Agents.shown(watch.runs, now), now))
        agentsCard?.update()
    }

    /** La carte des agents, sous l'icône. */
    fun openAgents() {
        val card = AgentsCard(this)
        card.show()
        agentsCard = card
        refreshAgents()
        // Oubliée ouverte, elle se ferme : l'écran de veille peut revenir et Foyer n'est plus interrogé toutes les 10 s.
        root.postDelayed({ if (agentsCard === card) closeOverlay() }, 5 * 60_000L)
    }

    private fun debuggable() = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Le nombre sur la cloche : messages non lus de « À savoir ». */
    fun refreshBadge() {
        if (::topBar.isInitialized) topBar.setBadge(Notices.unread(this))
    }

    // ------------------------------------------------------------------ thème

    /** Construit la barre du haut, la zone des écrans, la barre du programme et celle du bas. */
    private fun buildChrome() {
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Ui.BG))
        root.setBackgroundColor(Ui.BG)
        column = Ui.vertical(this)
        topBar = TopBar(this)
        column.addView(topBar, Ui.lp(Ui.MATCH, Ui.u(58)))
        content = FrameLayout(this)
        column.addView(content, Ui.lp(Ui.MATCH, 0, 1f))
        liveBar = LiveBar(this)
        column.addView(liveBar, Ui.lp(Ui.MATCH, Ui.WRAP))
        navBar = NavBar(this)
        column.addView(navBar, Ui.lp(Ui.MATCH, Ui.WRAP))
        root.addView(column, 0, Ui.flp())
        // Le panneau vidéo suit la hauteur des barres du bas.
        val follow = View.OnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop && video != null) v.post { placeVideo() }
        }
        liveBar.addOnLayoutChangeListener(follow)
        navBar.addOnLayoutChangeListener(follow)
        // Nouvelle barre du haut (thème changé) : l'icône des agents tout de suite, pas au prochain tic.
        if (::app.isInitialized) refreshAgents()
    }

    private fun minuteOfDay(): Int {
        val cal = java.util.Calendar.getInstance()
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    }

    /** Le thème voulu maintenant : réglage, heure, et choix fait à la lune. */
    private fun wantDark(): Boolean {
        val p = ToqueApp.of(this).prefs
        val override = when (p.themeOverride) {
            "dark" -> true
            "light" -> false
            else -> null
        }
        return ThemeClock.dark(ThemeMode.of(p.themeMode), minuteOfDay(), System.currentTimeMillis(), override, p.themeOverrideUntil)
    }

    /** La lune ou le soleil de la barre du haut. */
    fun toggleTheme() {
        val t = ThemeClock.toggle(ThemeMode.of(prefs.themeMode), Ui.isDark, minuteOfDay(), System.currentTimeMillis())
        prefs.themeMode = t.mode.key
        prefs.themeOverride = when (t.overrideDark) {
            true -> "dark"
            false -> "light"
            null -> ""
        }
        prefs.themeOverrideUntil = t.overrideUntilMs
        applyTheme()
    }

    /** Réglages › Apparence. */
    fun setThemeMode(mode: ThemeMode) {
        prefs.themeMode = mode.key
        prefs.themeOverride = ""
        prefs.themeOverrideUntil = 0
        // Même thème qu'avant (Automatique → Clair en journée) : les réglages se redessinent quand même.
        applyTheme(force = true)
    }

    /**
     * Passage automatique (7 h, 20 h, fin d'un choix fait à la lune) : seulement quand rien
     * n'est en cours de saisie à l'écran (dialogue, clavier, réglage au bouton, vidéo).
     */
    fun checkTheme() {
        if (wantDark() == Ui.isDark) return
        // Pas au milieu d'un geste : Turbo maintenu (la reconstruction le relâcherait), clavier,
        // fenêtre, vidéo, réglage au bouton.
        if (overlay.visibility == View.VISIBLE || keyboardOpen() || video != null || knobNav.adjustingNow || snapshot.turboActive) return
        applyTheme()
    }

    /** Applique le thème voulu : toute l'interface est refaite, la pile d'écrans et leur état restent. */
    fun applyTheme(force: Boolean = false) {
        val dark = wantDark()
        if (dark == Ui.isDark && !force) return
        Ui.usePalette(if (dark) Ui.DARK_PALETTE else Ui.LIGHT_PALETTE)
        hideKeyboard()
        val reopen = video?.recipe
        closeVideo()
        closeOverlay()
        toastView?.let { root.removeView(it) }
        toastView = null
        val top = current
        top?.onHide()
        for (s in stack) s.dropView()
        topBar.stopClock()
        root.removeView(column)
        buildChrome()
        topBar.startClock()
        topBar.update(snapshot)
        liveBar.update(snapshot)
        knobNav.restyle()
        themeReplay = true
        try {
            if (top != null) show(top)
        } finally {
            themeReplay = false
        }
        reopen?.let { openVideo(it) }
    }

    /** Vrai pendant que [applyTheme] réaffiche l'écran courant (pas un vrai retour sur l'écran). */
    var themeReplay = false
        private set

    override fun onBackPressed() {
        if (overlay.visibility == View.VISIBLE) {
            closeOverlay()
            return
        }
        video?.let {
            if (it.full) it.setFull(false) else closeVideo()
            return
        }
        back()
    }

    // ------------------------------------------------------------------ bouton rotatif

    /** Un dialogue ou la vidéo en plein écran : le bouton ne parcourt qu'eux. */
    val knobModal: Boolean get() = overlay.visibility == View.VISIBLE || video?.full == true

    /** L'écran courant (son élément de départ est cherché là, pas dans la barre du haut). */
    val knobContent: View get() = content

    /** Les zones parcourues au bouton, dans l'ordre. */
    fun knobAreas(): List<View> {
        val v = video
        return when {
            overlay.visibility == View.VISIBLE -> listOf(overlay)
            v != null && v.full -> listOf(v.view)
            else -> listOfNotNull(topBar, content, v?.view, liveBar, navBar)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            Foyer.of(app).interaction()
            knobNav.touch()
            // Le doigt qui réveille l'écran ne touche rien dessous.
            swallowTouch = screensaver.showing
            screensaver.activity()
        }
        if (swallowTouch) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) swallowTouch = false
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    /** -1 tourner à gauche, 1 à droite, 2 appui, 0 autre touche. Les codes réglés à la main l'emportent. */
    private fun knobKey(k: Int): Int {
        val left = prefs.knobLeft.let { if (it >= 0) it == k else k in DEFAULT_LEFT }
        val right = prefs.knobRight.let { if (it >= 0) it == k else k in DEFAULT_RIGHT }
        val press = prefs.knobPress.let { if (it >= 0) it == k else k in DEFAULT_PRESS }
        return when {
            left -> -1
            right -> 1
            press -> 2
            else -> 0
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) Foyer.of(app).interaction()
        // Un tour ou un appui réveille l'écran de veille, sans rien faire d'autre.
        if (event.action == KeyEvent.ACTION_DOWN && screensaver.showing) {
            screensaver.activity()
            swallowKey = event.keyCode
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == swallowKey) {
            swallowKey = -1
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) screensaver.activity()
        knobListener?.let { l ->
            if (event.action == KeyEvent.ACTION_DOWN) l(event)
            return true
        }
        val kind = knobKey(event.keyCode)
        if (kind == 0) return super.dispatchKeyEvent(event)
        val focused = currentFocus
        if (focused is android.widget.EditText) {
            // En train de taper (clavier ouvert) : les touches restent au champ.
            if (keyboardOpen()) return super.dispatchKeyEvent(event)
            focused.clearFocus()
            root.requestFocus()
        }
        if (event.action == KeyEvent.ACTION_DOWN && (kind != 2 || event.repeatCount == 0)) knob(kind)
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            Foyer.of(app).interaction()
            if (screensaver.showing) {
                screensaver.activity()
                return true
            }
            screensaver.activity()
            // Encodeur rotatif (AXIS_SCROLL) ou molette (AXIS_VSCROLL)
            val v = event.getAxisValue(AXIS_SCROLL).let { if (it == 0f) -event.getAxisValue(MotionEvent.AXIS_VSCROLL) else it }
            val now = System.currentTimeMillis()
            if (v != 0f && now - lastKnobMs > 40) {
                lastKnobMs = now
                knob(if (v > 0) 1 else -1)
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /** Un geste du bouton : d'abord l'écran s'il le gère lui-même (réglage du bouton), sinon le parcours. */
    private fun knob(kind: Int) {
        val s = current
        val own = !knobModal && s != null && if (kind == 2) s.onKnobPress() else s.onKnob(kind)
        if (own) return
        if (kind == 2) knobNav.press() else knobNav.turn(kind)
    }

    private fun keyboardOpen(): Boolean {
        val r = android.graphics.Rect()
        root.getWindowVisibleDisplayFrame(r)
        val h = root.rootView.height
        return h > 0 && h - r.height() > h / 5
    }

    // ------------------------------------------------------------------ évènements machine

    private fun onMachineEvent(e: MachineEvent) {
        // Une liaison qui hésite avec la base ne compte pas comme quelqu'un au robot.
        if (e.type != EventType.LINK_CHANGED) screensaver.activity()
        current?.onEvent(e)
        when (e.type) {
            EventType.JOB_FINISHED -> chime(2)
            EventType.FAULT -> { chime(3); toast(e.message, Ui.DANGER) }
            EventType.LID_OPENED -> if (snapshot.busy || snapshot.state != null) toast(tr("Couvercle ouvert : pause"), Ui.WARN)
            EventType.JOB_PAUSED -> if (e.message != PauseReason.USER.label) toast(e.message, Ui.WARN)
            EventType.TURBO_END -> toast(e.message, Ui.TEXT2)
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ écran de veille

    /**
     * L'écran de veille peut-il s'afficher maintenant ? Jamais pendant une cuisson (ni en pause,
     * ni turbo), ni avec un défaut, une étape qui attend, un arrêt de sécurité affiché, un dialogue
     * ouvert, une vidéo de recette, ou sur le réglage du bouton rotatif.
     */
    fun screensaverAllowed(): Boolean {
        val s = snapshot
        if (s.busy || s.jobActive || s.turboActive || s.hasFault) return false
        if (Foyer.of(app).needsAttention()) return false
        if (overlay.visibility == View.VISIBLE || video != null) return false
        if (current is app.toque.ui.screens.KnobScreen) return false
        return true
    }

    /** Réglages › « Voir l'écran de veille ». */
    fun showScreensaver() = screensaver.showNow()

    // ------------------------------------------------------------------ anneau lumineux

    private var ringHeld: String? = null
    private var ringDoneAt = 0L

    /**
     * Ce que fait le robot, pour l'anneau : alerte (défaut, arrêt de sécurité), une étape qui
     * attend quelqu'un, une cuisson (ou pause, turbo), « c'est prêt » pendant 2 minutes, ou rien.
     */
    private fun ringState(): RingState {
        val s = snapshot
        val held = Foyer.of(app).tracker.heldState
        val now = SystemClock.elapsedRealtime()
        if (held == "done" && ringHeld != "done") ringDoneAt = now
        ringHeld = held
        return when {
            s.hasFault || held == "alert" || (s.state == JobState.PAUSED && s.pauseReason == PauseReason.SAFETY.name) -> RingState.ALERT
            held == "waiting" -> RingState.WAITING
            s.busy || s.jobActive || s.turboActive -> RingState.COOKING
            held == "done" && now - ringDoneAt < RingPolicy.DONE_MS -> RingState.DONE
            else -> RingState.IDLE
        }
    }

    /** L'anneau ou son réglage a changé (Réglages, Outils › Anneau lumineux). */
    fun ringChanged() = ring.reset()

    /** Une couleur ou une force vient d'être choisie : l'anneau la montre quelques secondes. */
    fun ringPreview() = ring.preview()

    /** « Réinitialiser l'anneau » : blanc d'origine, réglages par défaut ; [done] sur le fil principal. */
    fun ringResetAll(done: (Boolean) -> Unit) {
        Thread({
            val ok = try {
                RingLight.resetAll(prefs)
            } catch (_: Throwable) {
                false
            }
            runOnUiThread {
                ring.reset()
                done(ok)
            }
        }, "toque-anneau-reinit").start()
    }

    /** Quelque chose bouge à la machine (couvercle, bol, poids, cuisson, défaut) : quelqu'un est là. */
    private fun machineMoved(before: Snapshot?, now: Snapshot): Boolean {
        if (before == null) return false
        return before.lidOpen != now.lidOpen || before.bowlDetected != now.bowlDetected ||
            before.busy != now.busy || before.jobActive != now.jobActive || before.turboActive != now.turboActive ||
            before.hasFault != now.hasFault ||
            (now.hasSample && before.hasSample && kotlin.math.abs(now.weightG - before.weightG) >= 10)
    }

    fun chime(times: Int = 1) {
        if (!prefs.sound) return
        val t = tone ?: return
        for (i in 0 until times) main.postDelayed({
            try {
                t.startTone(if (times >= 3) ToneGenerator.TONE_PROP_NACK else ToneGenerator.TONE_PROP_ACK, 180)
            } catch (_: RuntimeException) {
            }
        }, i * 260L)
    }

    // ------------------------------------------------------------------ vidéo d'une recette

    /** Ouvre la vidéo de [recipe] dans un panneau, à côté de la recette (plein écran en un appui). */
    fun openVideo(recipe: Recipe) {
        if (video?.recipe?.id == recipe.id) return
        closeVideo()
        val p = VideoPanel(this, recipe)
        video = p
        // Sous les dialogues et les messages, au-dessus de l'écran.
        root.addView(p.view, 1, Ui.flp())
        placeVideo()
        p.start()
    }

    fun closeVideo() {
        val p = video ?: return
        video = null
        p.release()
        root.removeView(p.view)
    }

    /** Place le panneau : moitié d'écran du côté que l'écran courant laisse libre, ou tout l'écran. */
    fun placeVideo() {
        val p = video ?: return
        val lp = if (p.full) {
            Ui.flp()
        } else {
            val s = current
            val side = s?.videoSide ?: Gravity.END
            val width = ((if (root.width > 0) root.width else resources.displayMetrics.widthPixels) * 0.45f).toInt()
            Ui.flp(width, Ui.MATCH, Gravity.TOP or side).apply {
                topMargin = topBar.height.takeIf { it > 0 }?.plus(Ui.u(6)) ?: Ui.u(64)
                val bars = liveBar.height + (if (navBar.visibility == View.VISIBLE) navBar.height else 0)
                bottomMargin = bars + Ui.u(12) + Ui.u(s?.videoBottomSpace ?: 0)
                if (side == Gravity.START) leftMargin = Ui.u(16) else rightMargin = Ui.u(16)
            }
        }
        p.view.layoutParams = lp
        p.view.requestLayout()
    }

    // ------------------------------------------------------------------ dialogues et messages

    fun toast(message: String, color: Int = Ui.TEXT) {
        toastView?.let { root.removeView(it) }
        val t = Ui.text(this, message, 18f, color, Ui.SEMIBOLD)
        t.setPadding(Ui.u(24), Ui.u(14), Ui.u(24), Ui.u(14))
        t.background = Ui.round(if (Ui.isDark) Ui.SURFACE3 else Ui.SURFACE, 40f, Ui.STROKE, 1f)
        Ui.lift(t, 6f)
        t.gravity = Gravity.CENTER
        t.maxWidth = Ui.u(820)
        val lp = Ui.flp(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lp.bottomMargin = Ui.u(110)
        root.addView(t, lp)
        toastView = t
        t.alpha = 0f
        t.animate().alpha(1f).setDuration(150).start()
        main.postDelayed({
            if (toastView === t) {
                t.animate().alpha(0f).setDuration(200).withEndAction { root.removeView(t); if (toastView === t) toastView = null }.start()
            }
        }, 3_200)
    }

    fun refusal(v: Verdict) {
        if (!v.ok) toast(v.message, Ui.WARN)
    }

    /** Affiche une carte modale au centre de l'écran. */
    fun showOverlay(card: View, width: Int = 640, dismissOnScrim: Boolean = true) =
        showOverlay(card, Ui.flp(Ui.u(width), Ui.WRAP, Gravity.CENTER), dismissOnScrim)

    /** Panneau à droite, sur toute la hauteur (« À savoir »). */
    fun showSheet(sheet: View, width: Int = 460) =
        showOverlay(sheet, Ui.flp(Ui.u(width), Ui.MATCH, Gravity.END), true)

    /** Petite carte sous la barre du haut, à droite (agents), sur un voile léger ; [onClose] à sa fermeture. */
    fun showPopover(card: View, width: Int, onClose: () -> Unit) {
        val lp = Ui.flp(Ui.u(width), Ui.WRAP, Gravity.TOP or Gravity.END).apply {
            topMargin = Ui.u(54)
            rightMargin = Ui.u(12)
        }
        showOverlay(card, lp, true, scrim = (Ui.SCRIM ushr 25 shl 24) or (Ui.SCRIM and 0x00FFFFFF))
        overlayClosed = onClose
    }

    private fun showOverlay(card: View, lp: FrameLayout.LayoutParams, dismissOnScrim: Boolean, scrim: Int = Ui.SCRIM) {
        hideKeyboard()
        endOverlay()
        overlay.removeAllViews()
        overlay.setBackgroundColor(scrim)
        overlay.isClickable = true
        overlay.setOnClickListener { if (dismissOnScrim) closeOverlay() }
        card.isClickable = true
        overlay.addView(card, lp)
        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(120).start()
        knobNav.reset()
    }

    fun closeOverlay() {
        val was = overlay.visibility == View.VISIBLE
        overlay.visibility = View.GONE
        overlay.removeAllViews()
        endOverlay()
        if (was) knobNav.reset()
    }

    /** La carte affichée s'en va : celle des agents arrête ses questions rapprochées. */
    private fun endOverlay() {
        agentsCard = null
        val closed = overlayClosed
        overlayClosed = null
        closed?.invoke()
    }

    /** Dialogue de confirmation. */
    fun confirm(title: String, message: String, yes: String, no: String = tr("Annuler"),
                danger: Boolean = false, icon: Icon.Kind = Icon.Kind.WARNING, onYes: () -> Unit) {
        val card = Ui.card(this, 28f, if (Ui.isDark) Ui.SURFACE2 else Ui.SURFACE)
        card.setPadding(Ui.u(32), Ui.u(28), Ui.u(32), Ui.u(26))
        val head = Ui.horizontal(this)
        head.addView(Ui.iconCircle(this, icon, 48, 24, if (danger) Ui.DANGER_DIM else Ui.ACCENT_DIM, if (danger) Ui.DANGER else Ui.ACCENT),
            LinearLayout.LayoutParams(Ui.u(48), Ui.u(48)).apply { rightMargin = Ui.u(16) })
        head.addView(Ui.text(this, title, 24f, Ui.TEXT, Ui.SEMIBOLD), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        card.addView(head)
        card.addView(Ui.text(this, message, 17f, Ui.TEXT2).apply { setLineSpacing(0f, 1.25f) }, Ui.lp(t = 16, b = 26))
        val row = Ui.horizontal(this, Gravity.END or Gravity.CENTER_VERTICAL)
        row.addView(Ui.button(this, no, Ui.Style.GHOST, height = 60) { closeOverlay() })
        row.addView(Ui.spacer(this, w = 14))
        row.addView(Ui.button(this, yes, if (danger) Ui.Style.DANGER else Ui.Style.PRIMARY, height = 60) {
            closeOverlay()
            onYes()
        })
        card.addView(row)
        showOverlay(card, 680)
    }

    /** Pause, ou reprise (avec la confirmation de mixage si le contenu a chauffé entre-temps). */
    fun togglePause() {
        val s = snapshot
        if (s.state == JobState.PAUSED) {
            machine.resume(s.hotBlendConfirmed) { v ->
                if (!v.ok && v.has(SafetyPolicy.HOT_BLEND)) {
                    confirm(tr("Contenu chaud"), tr("Le bol est à %d °C. Reprendre le mixage ?", s.actualTempC), tr("Reprendre"), icon = Icon.Kind.THERMO) {
                        machine.resume(true) { refusal(it) }
                    }
                } else refusal(v)
            }
        } else machine.pause { refusal(it) }
    }

    /** La recette suivie pas à pas dont le programme tourne (null : un programme ou la cuisson manuelle). */
    fun liveRecipe(): Recipe? {
        val g = Foyer.of(app).tracker.guided ?: return null
        if (prefs.guidedRecipe != g.recipeId) return null
        // Appelé à chaque instantané (une dizaine par seconde en cuisson) : la recette est lue
        // une fois (base + JSON), puis gardée jusqu'au prochain changement des recettes.
        liveCache?.let { if (it.first == g.recipeId) return it.second }
        return app.recipes.get(g.recipeId).also { liveCache = g.recipeId to it }
    }
    private var liveCache: Pair<String, Recipe?>? = null

    /** L'écran courant montre déjà le programme en cours (« Voir » n'a rien à ouvrir). */
    fun liveShownHere(): Boolean {
        val s = snapshot
        return when (val cur = current) {
            is HomeScreen -> true
            is CookScreen -> liveRecipe()?.id == cur.recipeId
            is ManualScreen -> s.kind == JobKind.MANUAL && liveRecipe() == null
            is ProgramsScreen -> s.kind != JobKind.MANUAL && liveRecipe() == null
            else -> false
        }
    }

    /** « Voir » : la recette en cours, la cuisson manuelle ou le programme. */
    fun openLive() {
        val r = liveRecipe()
        val s = snapshot
        when {
            r != null -> push(CookScreen(this, r, Foyer.of(app).tracker.guided?.stepIndex ?: prefs.guidedStep))
            s.kind == JobKind.MANUAL -> openSection(Section.MANUAL)
            else -> {
                ProgramsScreen.select(s.kind)
                openSection(Section.PROGRAMS)
            }
        }
    }

    /**
     * Démarre un programme ; si le contenu est chaud, demande d'abord la confirmation
     * de mixage (comme l'appli d'origine, mais avant tout mouvement).
     */
    fun startJob(spec: JobSpec, onHotConfirmed: () -> Unit = {}, done: (Verdict) -> Unit = {}) {
        machine.start(spec) { v ->
            if (!v.ok && v.has(SafetyPolicy.HOT_BLEND) && v.violations.size == 1) {
                confirm(
                    tr("Contenu chaud"),
                    tr("Le bol est à %d °C. Mixer chaud peut provoquer des projections.\n\n" +
                        "Vérifie que le couvercle est verrouillé, que le gobelet est en place et que le bol n'est pas trop rempli.", snapshot.actualTempC) +
                        if (prefs.hotRamp) " " + tr("La vitesse montera progressivement.") else "",
                    tr("Mixer quand même"), icon = Icon.Kind.THERMO,
                ) {
                    onHotConfirmed()
                    machine.start(spec.copy(hotBlendConfirmed = true)) { v2 -> refusal(v2); done(v2) }
                }
            } else {
                refusal(v)
                done(v)
            }
        }
    }

    fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    // ------------------------------------------------------------------ appli d'origine

    /** Retour depuis l'appli d'origine : on la coupe du moteur, on reprend la main, on importe. */
    private fun endVendorSession() {
        prefs.vendorSession = false
        // Observation de l'anneau (Outils › Anneau lumineux) : le relevé s'arrête au retour.
        if (RingWatch.running) {
            RingWatch.stop()
            main.postDelayed({ (current as? app.toque.ui.screens.RingScreen)?.refreshObserved() }, 1_500)
        }
        toast(tr("Reprise de la main sur le robot…"))
        Thread {
            val sync = LidlSync(this, prefs, app.recipes, app.log)
            sync.forceStop()
            sync.setVendorHardware(false)
            main.post { machine.resumePort() }
            val r = sync.importNow()
            main.post { toast(r.message, if (r.ok) Ui.OK else Ui.WARN) }
        }.start()
    }

    fun openVendorApp(onOpen: (() -> Unit)? = null) {
        if (snapshot.busy) {
            toast(tr("Arrête d'abord le programme en cours"), Ui.WARN)
            return
        }
        confirm(
            tr("Ouvrir l'appli d'origine"),
            tr("Toque libère le moteur et lance l'appli Monsieur Cuisine d'origine, qui se synchronise avec les serveurs Lidl comme d'habitude.\n\n" +
                "Reviens ensuite à Toque avec le bouton « Retour à Toque » affiché par-dessus (tu peux le déplacer) : les recettes seront importées automatiquement."),
            tr("Ouvrir"), icon = Icon.Kind.SYNC,
        ) {
            machine.suspendPort { v ->
                if (!v.ok) { refusal(v); return@suspendPort }
                Thread {
                    // Sans bouton de retour, impossible de revenir à Toque : on n'ouvre pas.
                    if (!ReturnBubble.ensurePermission(this)) {
                        main.post {
                            toast(tr("Impossible d'afficher le bouton « Retour à Toque » (root ?) : ouverture annulée"), Ui.WARN)
                            machine.resumePort()
                        }
                        return@Thread
                    }
                    val sync = LidlSync(this, prefs, app.recipes, app.log)
                    val enabled = sync.setVendorHardware(true)
                    val launch = sync.components().launch
                    main.post {
                        if (!enabled || launch == null) {
                            toast(tr("Appli d'origine indisponible (root ?)"), Ui.WARN)
                            machine.resumePort()
                        } else {
                            prefs.vendorSession = true
                            prefs.returnBubble = true
                            ReturnBubble.start(this)
                            onOpen?.invoke()
                            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }.start()
            }
        }
    }

    /** Paramètres Android (accès caché), avec le bouton « Retour à Toque » pour en revenir. */
    fun openAndroidSettings() {
        Thread {
            val ok = ReturnBubble.ensurePermission(this)
            main.post {
                if (!ok) {
                    toast(tr("Impossible d'afficher le bouton « Retour à Toque » (root ?) : ouverture annulée"), Ui.WARN)
                    return@post
                }
                prefs.returnBubble = true
                ReturnBubble.start(this)
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    prefs.returnBubble = false
                    ReturnBubble.stop(this)
                }
            }
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (debuggable() && intent.getBooleanExtra(PROBE_AGENTS, false)) AgentsWatch.of(app).probe()
    }

    companion object {
        private var reopenSettings = false

        /**
         * Version de développement : « am start … --ez app.toque.PROBE_AGENTS true » vérifie la
         * connexion à la base de Foyer (journal « ToqueAgents »). Sans effet dans la version publiée.
         */
        const val PROBE_AGENTS = "app.toque.PROBE_AGENTS"

        /** MotionEvent.AXIS_SCROLL (API 26), valeur fixe pour Android 6. */
        private const val AXIS_SCROLL = 26

        /**
         * Codes du bouton rotatif reconnus sans réglage : les flèches, et F7 / F6 / F5 envoyés par
         * le bouton du Monsieur Cuisine Connect (relevés sur un SKMC 1200 B2).
         */
        val DEFAULT_LEFT = intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_F7)
        val DEFAULT_RIGHT = intArrayOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_F6)
        val DEFAULT_PRESS = intArrayOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_F5)
        private val clockFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)

        /** Heure affichée ; le fuseau peut changer en cours de route (réglage automatique). */
        fun now(): String {
            clockFormat.timeZone = java.util.TimeZone.getDefault()
            return clockFormat.format(Date())
        }
    }
}
