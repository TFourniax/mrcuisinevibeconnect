package app.toque.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import app.toque.core.i18n.tr
import app.toque.core.recipes.InstagramEmbed
import app.toque.core.recipes.Recipe
import app.toque.foyer.FoyerHttp
import app.toque.recipes.Photos

/**
 * La vidéo d'origine d'une recette (un réel), dans un panneau à côté de la recette ou en plein
 * écran. Le robot lit lui-même, depuis la maison, la page publique « à intégrer » du réel et en
 * tire l'adresse de la vidéo, lue en direct par le lecteur d'Android : rien n'est enregistré, ni
 * sur le robot ni chez Foyer. Instagram ne la donne pas toujours sans compte : le panneau propose
 * alors de l'ouvrir sur le téléphone.
 */
class VideoPanel(private val a: MainActivity, val recipe: Recipe) {
    val view = FrameLayout(a)
    var full = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val stage = FrameLayout(a)
    private val videoView = VideoView(a)
    private val poster = ImageView(a)
    private val info: TextView = Ui.text(a, tr("Chargement de la vidéo…"), 19f, Ui.TEXT2).apply {
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.15f)
    }
    private val phoneButton: LinearLayout? = if (PhoneLink.available(a, recipe)) {
        Ui.button(a, tr("Voir sur le téléphone"), Ui.Style.PRIMARY, Icon.Kind.LINK, height = 58, textSize = 18f) { b -> PhoneLink.send(a, recipe, b) }
    } else null
    private lateinit var playIcon: Icon
    private lateinit var muteLabel: TextView
    private lateinit var fullLabel: TextView
    private lateinit var clock: TextView
    private var player: MediaPlayer? = null
    private var muted = false
    private var released = false
    private var posterBitmap: Bitmap? = null

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            updateClock()
            main.postDelayed(this, 500)
        }
    }

    init {
        val card = Ui.vertical(a)
        card.background = Ui.round(Color.BLACK, 24f, Ui.STROKE, 1f)
        card.clipToOutline = true
        card.isClickable = true

        // ---- en-tête : titre, plein écran, fermer
        val head = Ui.horizontal(a)
        head.setPadding(Ui.u(16), Ui.u(8), Ui.u(8), Ui.u(4))
        head.addView(Ui.ellipsize(Ui.text(a, tr("Vidéo de la recette"), 17f, Ui.TEXT2, Ui.MEDIUM)), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val fullButton = Ui.button(a, tr("Plein écran"), Ui.Style.GHOST, height = 50, textSize = 16f) { setFull(!full) }
        fullLabel = fullButton.getChildAt(0) as TextView
        head.addView(fullButton, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        head.addView(Ui.button(a, "", Ui.Style.GHOST, Icon.Kind.CLOSE, height = 50, textSize = 18f) { a.closeVideo() })
        card.addView(head, Ui.lp())

        // ---- image : la vidéo, sinon la couverture et un message
        poster.scaleType = ImageView.ScaleType.FIT_CENTER
        stage.addView(poster, Ui.flp())
        stage.addView(videoView, Ui.flp(Ui.MATCH, Ui.MATCH, Gravity.CENTER))
        val message = Ui.vertical(a, Gravity.CENTER_HORIZONTAL)
        message.addView(info, Ui.lp(Ui.WRAP, Ui.WRAP))
        phoneButton?.let {
            it.visibility = View.GONE
            message.addView(it, Ui.lp(Ui.WRAP, Ui.WRAP, t = 16))
        }
        stage.addView(message, Ui.flp(Ui.WRAP, Ui.WRAP, Gravity.CENTER).apply { leftMargin = Ui.u(20); rightMargin = Ui.u(20) })
        card.addView(stage, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        // ---- commandes
        val controls = Ui.horizontal(a, Gravity.CENTER)
        controls.setPadding(Ui.u(8), Ui.u(6), Ui.u(8), Ui.u(10))
        controls.addView(Ui.button(a, tr("−10 s"), Ui.Style.GHOST, height = 54, textSize = 17f) { seekBy(-10_000) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        val play = Ui.button(a, "", Ui.Style.SECONDARY, Icon.Kind.PLAY, height = 54, textSize = 20f) { togglePlay() }
        playIcon = play.getChildAt(0) as Icon
        controls.addView(play, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        controls.addView(Ui.button(a, tr("+10 s"), Ui.Style.GHOST, height = 54, textSize = 17f) { seekBy(10_000) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        val mute = Ui.button(a, tr("Couper le son"), Ui.Style.GHOST, height = 54, textSize = 17f) { toggleMute() }
        muteLabel = mute.getChildAt(0) as TextView
        controls.addView(mute, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        clock = Ui.text(a, "", 16f, Ui.TEXT3)
        controls.addView(clock, Ui.lp(Ui.WRAP, Ui.WRAP))
        card.addView(controls, Ui.lp())

        view.addView(card, Ui.flp())
        view.isClickable = true
        videoView.visibility = View.INVISIBLE
        videoView.keepScreenOn = true
        videoView.setOnPreparedListener { mp ->
            if (released) return@setOnPreparedListener
            player = mp
            applyMute()
            info.visibility = View.GONE
            videoView.start()
            playIcon.set(Icon.Kind.PAUSE)
        }
        videoView.setOnCompletionListener { playIcon.set(Icon.Kind.PLAY) }
        videoView.setOnErrorListener { _, what, extra ->
            a.app.log.write("video", "lecture impossible ($what, $extra)")
            unavailable(true)
            true
        }
    }

    /** Cherche la vidéo (hors du fil de l'écran), puis la lit. */
    fun start() {
        val url = recipe.video
        val instagram = InstagramEmbed.embedUrl(url)
        if (instagram == null) {
            unavailable(false)
            return
        }
        Thread {
            val media = try {
                InstagramEmbed.parse(FoyerHttp.instagramPage(instagram, BROWSER_UA))
            } catch (e: Exception) {
                a.app.log.write("video", "page du réel : ${e.message}")
                null
            }
            val video = media?.video?.takeIf { FoyerHttp.safeMediaUrl(it) }
            val cover = media?.poster?.let { p ->
                try {
                    Photos.decode(FoyerHttp.download(p, BROWSER_UA), 1280, 1280)
                } catch (_: Throwable) {
                    null
                }
            }
            a.app.log.write("video", "réel : vidéo ${if (video != null) "trouvée" else "absente"}, couverture ${if (cover != null) "oui" else "non"}")
            main.post {
                if (released) {
                    cover?.recycle()
                    return@post
                }
                cover?.let {
                    posterBitmap = it
                    poster.setImageBitmap(it)
                }
                if (video == null) {
                    unavailable(true)
                } else {
                    // Le lecteur d'Android lit le flux dans ce processus : mêmes racines que Foyer.
                    FoyerHttp.useAsDefault()
                    // Visible avant d'ouvrir : sans surface, le lecteur ne démarre jamais.
                    videoView.visibility = View.VISIBLE
                    videoView.setVideoURI(Uri.parse(video))
                    main.post(tick)
                }
            }
        }.apply { name = "toque-video"; isDaemon = true }.start()
    }

    fun setFull(value: Boolean) {
        if (released) return
        full = value
        fullLabel.text = if (full) tr("Réduire") else tr("Plein écran")
        a.placeVideo()
    }

    /** Arrête la lecture et rend le lecteur (fermeture, autre écran, robot en veille). */
    fun release() {
        if (released) return
        released = true
        main.removeCallbacks(tick)
        try {
            videoView.stopPlayback()
        } catch (_: Exception) {
        }
        player = null
        poster.setImageDrawable(null)
        posterBitmap?.recycle()
        posterBitmap = null
    }

    private fun unavailable(instagram: Boolean) {
        if (released) return
        videoView.visibility = View.INVISIBLE
        info.visibility = View.VISIBLE
        info.text = if (instagram) tr("Instagram ne laisse pas lire cette vidéo ici.") else tr("Cette vidéo ne se lit pas sur le robot.")
        if (posterBitmap != null) info.setBackgroundColor(0xB0000000.toInt())
        info.setPadding(Ui.u(16), Ui.u(10), Ui.u(16), Ui.u(10))
        phoneButton?.visibility = View.VISIBLE
    }

    private fun togglePlay() {
        if (player == null) return
        if (videoView.isPlaying) {
            videoView.pause()
            playIcon.set(Icon.Kind.PLAY)
        } else {
            if (videoView.duration > 0 && videoView.currentPosition >= videoView.duration - 300) videoView.seekTo(0)
            videoView.start()
            playIcon.set(Icon.Kind.PAUSE)
        }
    }

    private fun seekBy(ms: Int) {
        if (player == null) return
        val d = videoView.duration
        val target = (videoView.currentPosition + ms).coerceAtLeast(0).let { if (d > 0) it.coerceAtMost(d - 200) else it }
        videoView.seekTo(target)
        updateClock()
    }

    private fun toggleMute() {
        muted = !muted
        applyMute()
        muteLabel.text = if (muted) tr("Remettre le son") else tr("Couper le son")
    }

    private fun applyMute() {
        try {
            val v = if (muted) 0f else 1f
            player?.setVolume(v, v)
        } catch (_: IllegalStateException) {
        }
    }

    private fun updateClock() {
        if (player == null) return
        val pos = videoView.currentPosition / 1000
        val total = videoView.duration / 1000
        clock.text = if (total > 0) "${pos / 60}:%02d / ${total / 60}:%02d".format(pos % 60, total % 60) else ""
    }

    companion object {
        /** La page « à intégrer » est faite pour un navigateur : le robot s'en présente comme un. */
        private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 6.0; Monsieur Cuisine) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}

/** « Voir sur le téléphone » : Foyer rouvre la recette et sa vidéo sur le téléphone de quelqu'un du foyer. */
object PhoneLink {
    fun available(a: MainActivity, r: Recipe): Boolean =
        r.video.isNotEmpty() && r.id.startsWith(app.toque.core.foyer.FoyerFeed.ID_PREFIX) && app.toque.foyer.Foyer.of(a.app).linked

    fun send(a: MainActivity, recipe: Recipe, button: View?, memberId: String? = null, memberName: String? = null) {
        if (button != null && !button.isEnabled) return
        button?.let { Ui.setButtonEnabled(it, false) }
        app.toque.foyer.Foyer.of(a.app).openOnPhone(recipe.id, memberId) { r ->
            button?.let { Ui.setButtonEnabled(it, true) }
            when (r) {
                is app.toque.foyer.Foyer.PhoneResult.Sent ->
                    a.toast(if (memberName != null) tr("Envoyé sur le téléphone de %s", memberName) else tr("Envoyé sur ton téléphone"), Ui.OK)
                is app.toque.foyer.Foyer.PhoneResult.Choose -> choose(a, recipe, button, r.phones)
                is app.toque.foyer.Foyer.PhoneResult.NoPhone -> a.toast(tr("Aucun téléphone du foyer n'a les notifications de Foyer."), Ui.WARN)
                is app.toque.foyer.Foyer.PhoneResult.Limited -> a.toast(tr("Trop d'envois pour aujourd'hui."), Ui.WARN)
                is app.toque.foyer.Foyer.PhoneResult.NotLinked -> a.toast(tr("Le robot n'est pas relié à Foyer."), Ui.WARN)
                is app.toque.foyer.Foyer.PhoneResult.Failed -> a.toast(tr("Foyer injoignable : réessaie dans un instant."), Ui.WARN)
            }
        }
    }

    /** Plusieurs téléphones : un grand bouton par personne. */
    private fun choose(a: MainActivity, recipe: Recipe, button: View?, phones: List<Pair<String, String>>) {
        val card = Ui.card(a, 28f, Ui.SURFACE2)
        card.setPadding(Ui.u(32), Ui.u(28), Ui.u(32), Ui.u(26))
        card.addView(Ui.text(a, tr("Sur quel téléphone ?"), 26f, Ui.TEXT, Ui.MEDIUM))
        card.addView(Ui.text(a, tr("La recette et sa vidéo s'ouvrent dans Foyer."), 18f, Ui.TEXT2), Ui.lp(t = 8, b = 14))
        for ((id, name) in phones) {
            card.addView(Ui.button(a, name, Ui.Style.SECONDARY, height = 64, textSize = 21f) {
                a.closeOverlay()
                send(a, recipe, button, id, name)
            }, Ui.lp(t = 10))
        }
        card.addView(Ui.button(a, tr("Annuler"), Ui.Style.GHOST, height = 58) { a.closeOverlay() }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 16))
        a.showOverlay(card, 560)
    }
}
