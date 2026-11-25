package app.toque.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import app.toque.core.foyer.FoyerFeed
import app.toque.core.foyer.ScreensaverConfig
import app.toque.core.foyer.ScreensaverItem
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.foyer.Foyer
import app.toque.recipes.Photos
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random

/**
 * Écran de veille. Après quelques minutes sans personne au robot (ni doigt, ni bouton, ni
 * mouvement de la machine), et seulement machine au repos : l'horloge, ou les animations que
 * le foyer a ajoutées dans Foyer (GIF, vidéos MP4, images ; rien n'est fourni avec Toque).
 * Réglé dans Foyer › Réglages › Monsieur Cuisine. Le moindre geste le referme.
 */
class Screensaver(private val a: MainActivity, private val layer: FrameLayout, private val onShown: () -> Unit = {}) {
    private val main = Handler(Looper.getMainLooper())
    private var lastActivity = SystemClock.elapsedRealtime()
    private var view: SaverView? = null
    private var running = false

    val showing: Boolean get() = view != null

    /** Dernier geste ou mouvement de la machine (horloge monotone). */
    val lastActivityAt: Long get() = lastActivity

    /** Appelé à chaque geste, et quand l'écran de veille s'affiche ou se ferme (anneau lumineux). */
    var onChange: (() -> Unit)? = null

    private val check = object : Runnable {
        override fun run() {
            maybeShow()
            main.postDelayed(this, 10_000)
        }
    }

    fun start() {
        if (running) return
        running = true
        lastActivity = SystemClock.elapsedRealtime()
        main.postDelayed(check, 10_000)
    }

    fun stop() {
        running = false
        main.removeCallbacks(check)
        hide()
    }

    /** Quelqu'un au robot : l'écran de veille se ferme et le délai repart de zéro. */
    fun activity() {
        lastActivity = SystemClock.elapsedRealtime()
        if (view != null) hide()
        onChange?.invoke()
    }

    /** « Voir l'écran de veille » (Réglages) : tout de suite, s'il peut s'afficher. */
    fun showNow() {
        val c = config().let { if (it.mode == ScreensaverConfig.OFF) it.copy(mode = ScreensaverConfig.CLOCK) else it }
        if (a.screensaverAllowed()) show(c)
    }

    private fun config() = ScreensaverConfig.decode(a.prefs.screensaver)

    private fun maybeShow() {
        if (view != null) return
        val c = config()
        if (c.mode == ScreensaverConfig.OFF) return
        if (SystemClock.elapsedRealtime() - lastActivity < c.delayMinutes * 60_000L) return
        if (!a.screensaverAllowed()) return
        show(c)
    }

    private fun show(c: ScreensaverConfig) {
        hide()
        val foyer = Foyer.of(a.app)
        val v = SaverView(a, c) { foyer.screensaverFile(it.stem) }
        view = v
        layer.addView(v.root, Ui.flp())
        v.start()
        onShown()
        onChange?.invoke()
    }

    fun hide() {
        val v = view ?: return
        view = null
        v.release()
        layer.removeView(v.root)
        onChange?.invoke()
    }
}

/** Ce que montre l'écran de veille ; [release] libère lecteur, animation et image. */
private class SaverView(private val a: MainActivity, private val config: ScreensaverConfig, private val fileOf: (ScreensaverItem) -> File?) {
    val root = FrameLayout(a).apply {
        setBackgroundColor(Color.BLACK)
        // Les gestes sont pris par MainActivity (le premier ferme l'écran de veille) ; rien ne passe dessous.
        isClickable = true
    }
    private val main = Handler(Looper.getMainLooper())
    private val random = Random()
    private var released = false
    private var media: List<ScreensaverItem> = emptyList()
    private var index = -1
    private var failures = 0
    private var stage: View? = null
    private var lastDrift = 0L

    private val clockBlock = Ui.vertical(a, Gravity.CENTER_HORIZONTAL)
    // Toujours les couleurs du soir : l'écran de veille est noir, quel que soit le thème.
    private val night = Ui.DARK_PALETTE
    private val time = Ui.text(a, "", 128f, night.text2, Ui.THIN).apply { gravity = Gravity.CENTER; includeFontPadding = false }
    private val date = Ui.text(a, "", 28f, night.text3, Ui.LIGHT).apply { gravity = Gravity.CENTER }
    private val plan = Ui.text(a, "", 24f, night.accent, Ui.LIGHT).apply { gravity = Gravity.CENTER; alpha = 0.8f }
    private val corner = Ui.text(a, "", 26f, night.text2, Ui.LIGHT).apply {
        alpha = 0.7f
        setShadowLayer(8f, 0f, 0f, Color.BLACK)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            updateTexts()
            val now = SystemClock.elapsedRealtime()
            // L'horloge glisse un peu chaque minute : rien ne reste marqué sur l'écran.
            if (now - lastDrift >= 60_000L) {
                lastDrift = now
                clockBlock.animate().translationX((random.nextInt(161) - 80) * Ui.u(1).toFloat())
                    .translationY((random.nextInt(81) - 40) * Ui.u(1).toFloat()).setDuration(2_000).start()
            }
            main.postDelayed(this, 10_000)
        }
    }
    private val advance = Runnable { next() }

    fun start() {
        media = if (config.mode == ScreensaverConfig.MEDIA) config.items.filter { fileOf(it) != null } else emptyList()
        if (media.isEmpty()) showClock() else {
            root.addView(corner, Ui.flp(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, Ui.u(24), Ui.u(16)) })
            next()
        }
        lastDrift = SystemClock.elapsedRealtime()
        main.post(tick)
    }

    fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
        clockBlock.animate().cancel()
        clearStage()
    }

    private fun showClock() {
        clearStage()
        root.removeView(corner)
        if (clockBlock.parent == null) {
            clockBlock.addView(time)
            clockBlock.addView(date, Ui.lp(t = 4))
            clockBlock.addView(plan, Ui.lp(t = 18))
            root.addView(clockBlock, Ui.flp(Ui.WRAP, Ui.WRAP, Gravity.CENTER))
        }
        updateTexts()
    }

    private fun updateTexts() {
        val now = Date()
        val hm = SimpleDateFormat("HH:mm", Locale.FRANCE).format(now)
        time.text = hm
        corner.text = hm
        date.text = SimpleDateFormat("EEEE d MMMM", I18n.locale).format(now).replaceFirstChar { it.titlecase(I18n.locale) }
        val (today, nowHm) = Foyer.todayAndTime()
        // Un repas sans heure compte pour la soirée.
        val tonight = FoyerFeed.decodePlans(a.prefs.foyerPlans).filter { it.day == today && (it.time ?: "99:99") >= nowHm }
            .minByOrNull { it.time ?: "99:99" }
        plan.text = tonight?.let { p ->
            val at = p.time
            if (at != null) tr("Ce soir : %s · %s", p.title, at) else tr("Ce soir : %s", p.title)
        }.orEmpty()
        plan.visibility = if (tonight != null) View.VISIBLE else View.GONE
    }

    /** Animation suivante ; une qui ne se lit pas est sautée, et si aucune ne se lit, l'horloge. */
    private fun next() {
        if (released) return
        main.removeCallbacks(advance)
        if (media.isEmpty() || failures >= media.size) {
            showClock()
            return
        }
        // Une seule animation : elle reste (une vidéo ou un GIF tourne en boucle).
        if (media.size == 1 && stage != null) return
        index = (index + 1) % media.size
        val item = media[index]
        clearStage()
        val view = try {
            val file = fileOf(item) ?: throw IOException("fichier absent")
            when (item.kind) {
                "gif" -> GifView(a, file)
                "video" -> video(file)
                else -> picture(file)
            }
        } catch (t: Throwable) {
            failures++
            main.post { next() }
            return
        }
        stage = view
        // Une vidéo ne compte comme lisible qu'une fois prête (onPrepared).
        if (item.kind != "video") failures = 0
        root.addView(view, 0, Ui.flp(Ui.MATCH, Ui.MATCH, Gravity.CENTER))
        if (media.size > 1) main.postDelayed(advance, config.itemSeconds * 1000L)
    }

    private fun skip() {
        if (released) return
        failures++
        // La vidéo en échec s'en va : la suivante (ou l'horloge) prend sa place.
        clearStage()
        main.post { next() }
    }

    private fun video(file: File): View {
        // Centrée, proportions gardées : VideoView se mesure d'après la vidéo.
        val box = FrameLayout(a)
        val v = VideoView(a)
        v.setOnPreparedListener { mp ->
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            failures = 0
            v.start()
        }
        v.setOnErrorListener { _, _, _ ->
            skip()
            true
        }
        v.setVideoPath(file.path)
        box.addView(v, Ui.flp(Ui.MATCH, Ui.MATCH, Gravity.CENTER))
        box.tag = v
        return box
    }

    private fun picture(file: File): View {
        if (file.length() > 15L * 1024 * 1024) throw IOException("image trop lourde")
        val bmp = Photos.decode(file.readBytes(), maxOf(root.width, 1024), maxOf(root.height, 600))
        return ImageView(a).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bmp)
        }
    }

    private fun clearStage() {
        val s = stage ?: return
        stage = null
        when (s) {
            is GifView -> s.release()
            is ImageView -> {
                val bmp = (s.drawable as? BitmapDrawable)?.bitmap
                s.setImageDrawable(null)
                bmp?.recycle()
            }
            else -> (s.tag as? VideoView)?.stopPlayback()
        }
        root.removeView(s)
    }
}

/**
 * Un GIF animé lu avec android.graphics.Movie (Android 6 n'a pas mieux), ajusté à l'écran sans
 * le déformer, à 20 images par seconde au plus pour la petite tablette.
 */
@Suppress("DEPRECATION")
@SuppressLint("ViewConstructor")
private class GifView(c: Context, file: File) : View(c) {
    private var movie: Movie? = Movie.decodeFile(file.path)?.takeIf { it.width() > 0 && it.height() > 0 } ?: throw IOException("GIF illisible")
    private val started = SystemClock.uptimeMillis()

    init {
        // Movie ne se dessine pas sur un canevas accéléré.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val m = movie ?: return
        val w = m.width().toFloat()
        val h = m.height().toFloat()
        val scale = minOf(width / w, height / h)
        val duration = m.duration()
        m.setTime(if (duration > 0) ((SystemClock.uptimeMillis() - started) % duration).toInt() else 0)
        canvas.save()
        canvas.translate((width - w * scale) / 2f, (height - h * scale) / 2f)
        canvas.scale(scale, scale)
        m.draw(canvas, 0f, 0f)
        canvas.restore()
        if (duration > 0) postInvalidateDelayed(50)
    }

    fun release() {
        movie = null
    }
}
