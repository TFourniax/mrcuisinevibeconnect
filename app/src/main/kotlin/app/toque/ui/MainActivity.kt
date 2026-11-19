package app.toque.ui

import android.app.Activity
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import app.toque.foyer.Foyer
import app.toque.core.machine.EventType
import app.toque.core.machine.JobSpec
import app.toque.core.machine.MachineEvent
import app.toque.core.machine.PauseReason
import app.toque.core.machine.SafetyPolicy
import app.toque.core.machine.Snapshot
import app.toque.core.machine.Verdict
import app.toque.core.recipes.Recipe
import app.toque.recipes.LidlSync
import app.toque.recipes.SyncService
import app.toque.ui.screens.HomeScreen
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
    private lateinit var topBar: TopBar
    private lateinit var machineBar: MachineBar
    private val stack = ArrayList<Screen>()
    private val main = Handler(Looper.getMainLooper())
    private var unsubscribe: (() -> Unit)? = null
    private var unsubscribeEvents: (() -> Unit)? = null
    private var tone: ToneGenerator? = null
    private var toastView: TextView? = null
    private var video: VideoPanel? = null
    private var lastKnobMs = 0L
    private lateinit var knobNav: KnobNav
    var knobListener: ((KeyEvent) -> Unit)? = null

    val prefs: Prefs get() = app.prefs
    val machine get() = app.machine
    val snapshot: Snapshot get() = app.machine.snapshot

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = ToqueApp.of(this)
        val dm = resources.displayMetrics
        Ui.init(dm.widthPixels, dm.heightPixels)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Ui.BG))

        root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)
        val column = Ui.vertical(this)
        topBar = TopBar(this)
        column.addView(topBar, Ui.lp(Ui.MATCH, Ui.u(60)))
        content = FrameLayout(this)
        column.addView(content, Ui.lp(Ui.MATCH, 0, 1f))
        machineBar = MachineBar(this)
        column.addView(machineBar, Ui.lp(Ui.MATCH, Ui.WRAP))
        root.addView(column, Ui.flp())
        overlay = FrameLayout(this)
        overlay.visibility = View.GONE
        root.addView(overlay, Ui.flp())
        // Le panneau vidéo suit la hauteur de la barre machine.
        machineBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop && video != null) machineBar.post { placeVideo() }
        }
        setContentView(root)
        // La racine peut prendre le focus : un champ de recherche ne le garde pas sans qu'on tape.
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        knobNav = KnobNav(this, root)

        try {
            tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
        } catch (_: RuntimeException) {
        }

        app.machine.connect()
        app.recipes.load()
        SyncService.schedule(this)
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
            topBar.update(s)
            machineBar.update(s)
            stack.lastOrNull()?.onSnapshot(s)
        }
        unsubscribeEvents = app.machine.onEvent { e -> onMachineEvent(e) }
        topBar.startClock()
    }

    override fun onResume() {
        super.onResume()
        immersive()
        prefs.returnBubble = false
        ReturnBubble.stop(this)
        if (prefs.vendorSession) endVendorSession()
    }

    override fun onStop() {
        closeVideo()
        unsubscribe?.invoke()
        unsubscribeEvents?.invoke()
        topBar.stopClock()
        super.onStop()
    }

    override fun onDestroy() {
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

    private fun show(s: Screen) {
        content.removeAllViews()
        val v = s.view
        (v.parent as? FrameLayout)?.removeView(v)
        content.addView(v, Ui.flp())
        // La vidéo d'une recette reste ouverte de sa fiche à sa cuisson, pas au-delà.
        video?.let { if (it.recipe.id != s.recipeId) closeVideo() else placeVideo() }
        topBar.setTitle(s.title, stack.size > 1)
        s.onShow()
        s.onSnapshot(snapshot)
        knobNav.reset()
    }

    fun refreshTitle() {
        current?.let { topBar.setTitle(it.title, stack.size > 1) }
    }

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
            else -> listOfNotNull(topBar, content, v?.view, machineBar)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            Foyer.of(app).interaction()
            knobNav.touch()
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
                topMargin = topBar.height.takeIf { it > 0 }?.plus(Ui.u(6)) ?: Ui.u(66)
                bottomMargin = machineBar.height + Ui.u(12) + Ui.u(s?.videoBottomSpace ?: 0)
                if (side == Gravity.START) leftMargin = Ui.u(16) else rightMargin = Ui.u(16)
            }
        }
        p.view.layoutParams = lp
        p.view.requestLayout()
    }

    // ------------------------------------------------------------------ dialogues et messages

    fun toast(message: String, color: Int = Ui.TEXT) {
        toastView?.let { root.removeView(it) }
        val t = Ui.text(this, message, 19f, color, Ui.MEDIUM)
        t.setPadding(Ui.u(24), Ui.u(14), Ui.u(24), Ui.u(14))
        t.background = Ui.round(Ui.SURFACE3, 40f, Ui.STROKE, 1f)
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
    fun showOverlay(card: View, width: Int = 640, dismissOnScrim: Boolean = true) {
        hideKeyboard()
        overlay.removeAllViews()
        overlay.setBackgroundColor(Ui.SCRIM)
        overlay.isClickable = true
        overlay.setOnClickListener { if (dismissOnScrim) closeOverlay() }
        card.isClickable = true
        overlay.addView(card, Ui.flp(Ui.u(width), Ui.WRAP, Gravity.CENTER))
        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(120).start()
        knobNav.reset()
    }

    fun closeOverlay() {
        val was = overlay.visibility == View.VISIBLE
        overlay.visibility = View.GONE
        overlay.removeAllViews()
        if (was) knobNav.reset()
    }

    /** Dialogue de confirmation. */
    fun confirm(title: String, message: String, yes: String, no: String = tr("Annuler"),
                danger: Boolean = false, icon: Icon.Kind = Icon.Kind.WARNING, onYes: () -> Unit) {
        val card = Ui.card(this, 28f, Ui.SURFACE2)
        card.setPadding(Ui.u(32), Ui.u(28), Ui.u(32), Ui.u(26))
        val head = Ui.horizontal(this)
        head.addView(Icon(this, icon, if (danger) Ui.DANGER else Ui.ACCENT), LinearLayout.LayoutParams(Ui.u(34), Ui.u(34)).apply { rightMargin = Ui.u(14) })
        head.addView(Ui.text(this, title, 26f, Ui.TEXT, Ui.MEDIUM))
        card.addView(head)
        card.addView(Ui.text(this, message, 19f, Ui.TEXT2).apply { setLineSpacing(0f, 1.2f) }, Ui.lp(t = 16, b = 26))
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

    fun openVendorApp() {
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

    companion object {
        private var reopenSettings = false

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
