package app.toque.foyer

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import android.util.Base64
import app.toque.TimeSync
import app.toque.ToqueApp
import app.toque.core.foyer.FoyerFeed
import app.toque.core.foyer.FoyerPlan
import app.toque.core.foyer.Guided
import app.toque.core.foyer.ScreensaverConfig
import app.toque.core.foyer.StatusReport
import app.toque.core.foyer.StatusTracker
import app.toque.core.i18n.tr
import app.toque.core.json.Json
import app.toque.core.json.list
import app.toque.core.json.obj
import app.toque.core.json.str
import app.toque.core.machine.EventType
import app.toque.core.machine.JobKind
import app.toque.core.machine.MachineEvent
import app.toque.core.machine.Snapshot
import app.toque.core.recipes.Recipe
import app.toque.recipes.Photos
import app.toque.recipes.RecipeRepo
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Lien durable avec Foyer, l'appli du foyer. Au premier démarrage, Toque crée un secret de
 * 256 bits (gardé dans ses fichiers privés ; Foyer n'en garde que l'empreinte) et affiche un
 * code à 6 chiffres. Une fois relié depuis Foyer, plus rien à faire : toutes les 5 minutes
 * Toque récupère les recettes partagées du foyer et les repas prévus (rien n'est téléchargé
 * si rien n'a changé), et dit à Foyer ce qu'il fait (cuisson, étape qui attend, c'est prêt,
 * arrêt de sécurité) pour l'écran Maison et les notifications. Délié depuis Foyer, il garde
 * ses recettes et réaffiche un code.
 */
class Foyer private constructor(private val app: ToqueApp) {
    private val prefs = app.prefs
    private val log = app.log
    private val worker = HandlerThread("toque-foyer").apply { start() }
    private val bg = Handler(worker.looper)
    // Photos sur leur propre fil : un site lent ne retarde jamais un état ou une alerte.
    private val photoWorker = HandlerThread("toque-foyer-photos").apply { start() }
    private val photosBg = Handler(photoWorker.looper)
    // Fichiers de l'écran de veille : leur propre fil, un à la fois (jusqu'à 15 Mo chacun).
    private val mediaWorker = HandlerThread("toque-foyer-media").apply { start() }
    private val mediaBg = Handler(mediaWorker.looper)
    private val mediaTried = HashMap<String, Long>()
    @Volatile private var mediaBusy = false
    private val main = Handler(Looper.getMainLooper())
    private val secretFile = File(app.filesDir, "foyer-secret")
    private val version = try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }
    private val userAgent = "Toque/$version (Android)"

    /** Suivi de l'état pour Foyer : fil principal uniquement. */
    // Horloge monotone : une remise à l'heure du robot ne fausse ni les délais ni le temps restant.
    val tracker = StatusTracker({ SystemClock.elapsedRealtime() })
    private var lastQueued: StatusReport? = null
    @Volatile private var visible = 0
    /** Depuis quand le code est affiché ou quelqu'un a touché le robot (interrogations rapprochées 10 min). */
    @Volatile private var attentionSince = 0L
    private val listeners = ArrayList<() -> Unit>()

    // Fil « toque-foyer » uniquement.
    private var pending: StatusReport? = null
    private var quickSyncs = 0
    /** Dernier essai de chaque photo manquante (horloge monotone) : une adresse cassée n'est réessayée qu'une fois par heure. */
    private val photoTried = HashMap<String, Long>()

    val linked: Boolean get() = prefs.foyerHousehold.isNotEmpty()
    val plans: List<FoyerPlan> get() = FoyerFeed.decodePlans(prefs.foyerPlans)

    private val loopTask = Runnable { loop() }
    private val sendTask = Runnable { send() }
    private val tick = object : Runnable {
        override fun run() {
            report(tracker.current())
            main.postDelayed(this, 5_000)
        }
    }

    private fun start() {
        FoyerHttp.init(app)
        // Nouvelle version de Toque : le prochain contenu de Foyer est repris en entier (photos comprises).
        if (prefs.foyerAppliedVersion != version) {
            prefs.foyerEtag = ""
            prefs.foyerFeedHash = ""
            prefs.foyerAppliedVersion = version
        }
        app.machine.connect()
        app.machine.onSnapshot { s -> onSnapshot(s) }
        app.machine.onEvent { e -> onEvent(e) }
        main.postDelayed(tick, 5_000)
        bg.postDelayed(loopTask, 3_000)
    }

    // ------------------------------------------------------------------ interface (fil principal)

    fun onChange(l: () -> Unit): () -> Unit {
        listeners += l
        return { listeners -= l }
    }

    private fun changed() = main.post { for (l in ArrayList(listeners)) l() }

    /** Un écran montre le code de liaison : on interroge Foyer plus souvent (toutes les 20 s). */
    fun showing(on: Boolean) {
        visible = (visible + if (on) 1 else -1).coerceAtLeast(0)
        if (on) attentionSince = System.currentTimeMillis()
        if (on && !linked) syncNow()
    }

    /** Tant qu'il n'est pas relié : toutes les 20 s pendant 10 min d'attention, puis toutes les 2 min (5 min code caché). */
    private fun helloDelay(): Long = when {
        visible > 0 && System.currentTimeMillis() - attentionSince < 10 * 60_000L -> 20_000L
        visible > 0 -> 2 * 60_000L
        else -> 5 * 60_000L
    }

    /** Relié : toutes les 5 min le jour, toutes les 30 min de 23 h à 6 h (personne ne cuisine). */
    private fun idleDelay(): Long {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return if (hour >= 23 || hour < 6) 30 * 60_000L else 5 * 60_000L
    }

    /** Synchro tout de suite (accueil affiché, bouton « Synchroniser »). */
    fun syncNow() {
        bg.removeCallbacks(loopTask)
        bg.post(loopTask)
    }

    /** Synchro si la dernière date de plus d'une minute. */
    fun syncSoon() {
        if (System.currentTimeMillis() - prefs.foyerLastSyncMs > 60_000) syncNow()
    }

    /** Une étape attend quelqu'un ou un arrêt de sécurité reste affiché : pas d'écran de veille. */
    fun needsAttention(): Boolean = tracker.heldState == "waiting" || tracker.heldState == "alert"

    fun interaction() {
        tracker.interaction()
        if (!linked) attentionSince = System.currentTimeMillis()
    }

    /** L'écran de cuisson guidée montre [index] de [recipe] (null : plus de recette suivie). */
    fun guided(recipe: Recipe?, index: Int) {
        tracker.guided = recipe?.let { r ->
            Guided(
                recipeId = r.id,
                title = r.title,
                stepIndex = index,
                stepCount = r.steps.size,
                nextText = r.steps.getOrNull(index + 1)?.text.orEmpty(),
                machineStepsAfter = r.steps.drop(index + 1).count { it.machine != null },
            )
        }
        report(tracker.current())
    }

    /** Recette du foyer cuisinée jusqu'au bout : Foyer la compte comme « cuisinée ». */
    fun cooked(recipeId: String?) {
        if (recipeId == null || !recipeId.startsWith(FoyerFeed.ID_PREFIX) || !linked) return
        bg.post {
            try {
                FoyerHttp.call("POST", "/api/robot/cooked", secret(), userAgent, Json.write(linkedMapOf("recipeId" to recipeId)))
            } catch (e: Exception) {
                log.write("foyer", "cuisinée non envoyée : ${e.message}")
            }
        }
    }

    /** Ce que Foyer répond à « Voir sur le téléphone ». */
    sealed class PhoneResult {
        object Sent : PhoneResult()
        /** Plusieurs téléphones du foyer peuvent la recevoir : à qui ? (identifiant, prénom) */
        class Choose(val phones: List<Pair<String, String>>) : PhoneResult()
        object NoPhone : PhoneResult()
        object Limited : PhoneResult()
        object NotLinked : PhoneResult()
        object Failed : PhoneResult()
    }

    /**
     * « Voir sur le téléphone » : Foyer envoie une notification qui rouvre la recette (et sa vidéo)
     * sur le téléphone de [memberId], ou du seul membre qui a un téléphone. Hors du fil de l'écran ;
     * [done] est appelé sur le fil de l'écran.
     */
    fun openOnPhone(recipeId: String, memberId: String?, done: (PhoneResult) -> Unit) {
        if (!linked || !recipeId.startsWith(FoyerFeed.ID_PREFIX)) {
            done(PhoneResult.NotLinked)
            return
        }
        Thread {
            val result = try {
                val body = linkedMapOf<String, Any?>("recipeId" to recipeId)
                if (memberId != null) body["memberId"] = memberId
                val r = FoyerHttp.call("POST", "/api/robot/open", secret(), userAgent, Json.write(body))
                val m = try {
                    Json.parse(r.body).obj()
                } catch (_: Exception) {
                    emptyMap<String, Any?>()
                }
                val status = m.str("status")
                when {
                    r.code == 200 && status == "sent" -> PhoneResult.Sent
                    r.code == 200 && status == "choose" -> {
                        val phones = m["phones"].list().mapNotNull { p ->
                            val o = p.obj()
                            val id = o.str("id") ?: return@mapNotNull null
                            id to (o.str("name")?.trim().orEmpty().ifEmpty { tr("Quelqu'un") })
                        }
                        if (phones.isEmpty()) PhoneResult.Failed else PhoneResult.Choose(phones)
                    }
                    r.code == 200 && status == "no_phone" -> PhoneResult.NoPhone
                    r.code == 429 -> PhoneResult.Limited
                    r.code == 401 -> PhoneResult.NotLinked
                    else -> {
                        log.write("foyer", "téléphone : réponse ${r.code} ${status.orEmpty()}")
                        PhoneResult.Failed
                    }
                }
            } catch (e: Exception) {
                log.write("foyer", "téléphone : ${e.message}")
                PhoneResult.Failed
            }
            main.post { done(result) }
        }.apply { name = "toque-foyer-phone"; isDaemon = true }.start()
    }

    /** Oublie le lien de ce côté : nouveau secret, nouveau code (à relier de nouveau). */
    fun forget() {
        bg.post {
            secretFile.delete()
            unlinked()
            loop()
        }
    }

    private fun onSnapshot(s: Snapshot) = report(tracker.current(s))

    private fun onEvent(e: MachineEvent) {
        when (e.type) {
            EventType.JOB_FINISHED -> if (app.machine.snapshot.kind != JobKind.TURBO) {
                val r = tracker.onJobFinished(app.machine.snapshot.jobLabel)
                if (r.state == "done") cooked(r.recipeId)
            }
            EventType.JOB_ABORTED -> tracker.onJobAborted()
            else -> {}
        }
        report(tracker.current())
    }

    private fun report(r: StatusReport) {
        if (!linked || !prefs.foyerEnabled || !r.worthSending(lastQueued)) return
        lastQueued = r
        bg.post {
            pending = r
            bg.removeCallbacks(sendTask)
            // Les changements rapprochés (étapes enchaînées…) partent ensemble.
            bg.postDelayed(sendTask, 2_000)
        }
    }

    // ------------------------------------------------------------------ réseau (fil toque-foyer)

    /**
     * Le secret du robot, créé une seule fois. S'il existe mais ne peut pas être lu, on s'arrête
     * là (erreur, réessayée plus tard) : en créer un autre délierait le robot sans rien dire.
     */
    private fun secret(): String {
        if (secretFile.exists()) {
            val existing = secretFile.readText().trim()
            if (existing.length >= 40) return existing
            throw java.io.IOException("secret du robot illisible")
        }
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val fresh = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val tmp = File(secretFile.path + ".part")
        tmp.writeText(fresh)
        if (!tmp.renameTo(secretFile)) throw java.io.IOException("secret du robot non enregistré")
        return fresh
    }

    /** Le secret du robot s'il existe déjà (jamais créé ici) : pour le statut des agents. Hors du fil de l'écran. */
    fun existingSecret(): String? = try {
        if (secretFile.exists()) secretFile.readText().trim().takeIf { it.length >= 40 } else null
    } catch (_: Exception) {
        null
    }

    private fun loop() {
        bg.removeCallbacks(loopTask)
        val next = when {
            !prefs.foyerEnabled -> 10 * 60_000L
            // Robot tout juste allumé (pas de pile d'horloge) : la connexion sécurisée échouerait.
            !TimeSync.clockPlausible() -> clockNotSet()
            else -> try {
                if (linked) sync() else hello()
            } catch (e: Exception) {
                if (clockProblem(e)) clockNotSet() else {
                    prefs.foyerLastResult = tr("Foyer injoignable : %s", e.message ?: e.toString())
                    log.write("foyer", "injoignable : $e")
                    changed()
                    if (visible > 0) 30_000L else 5 * 60_000L
                }
            }
        }
        bg.postDelayed(loopTask, next)
    }

    /** L'heure du robot est fausse : on la fait régler tout de suite et on repasse dans 20 s. */
    private fun clockNotSet(): Long {
        TimeSync.kick()
        val line = tr("L'heure du robot n'est pas encore réglée : Foyer se connecte dès qu'elle l'est.")
        if (prefs.foyerLastResult != line) {
            prefs.foyerLastResult = line
            log.write("foyer", "heure du robot pas encore réglée")
            changed()
        }
        return 20_000L
    }

    private fun hello(): Long {
        val r = FoyerHttp.call("POST", "/api/robot/hello", secret(), userAgent, Json.write(linkedMapOf("version" to version)))
        val m = try { Json.parse(r.body).obj() } catch (_: Exception) { emptyMap<String, Any?>() }
        val status = if (r.code == 200) m.str("status") else null
        return when {
            status == "paired" -> {
                prefs.foyerHousehold = m.str("household").orEmpty().ifBlank { tr("Foyer") }
                prefs.foyerCode = ""
                prefs.foyerCodeUntil = 0
                prefs.foyerEtag = ""
                log.write("foyer", "relié à ${prefs.foyerHousehold}")
                // À vérifier d'un coup d'œil : c'est bien notre foyer.
                val household = prefs.foyerHousehold
                main.post { Toast.makeText(app, tr("Relié au foyer « %s »", household), Toast.LENGTH_LONG).show() }
                changed()
                500L
            }
            status == "pending" -> {
                val code = m.str("code").orEmpty()
                prefs.foyerCodeUntil = parseIso(m.str("expiresAt")) ?: (System.currentTimeMillis() + 10 * 60_000L)
                if (code != prefs.foyerCode) {
                    prefs.foyerCode = code
                    changed()
                }
                helloDelay()
            }
            r.code == 429 -> 10 * 60_000L
            else -> {
                prefs.foyerLastResult = tr("Foyer a répondu %d", r.code)
                changed()
                5 * 60_000L
            }
        }
    }

    private fun sync(): Long {
        val r = FoyerHttp.call("GET", "/api/robot/sync", secret(), userAgent, etag = prefs.foyerEtag)
        return when (r.code) {
            304 -> {
                prefs.foyerLastSyncMs = System.currentTimeMillis()
                queueMissingPhotos()
                queueScreensaver()
                idleDelay()
            }
            200 -> {
                val feed = FoyerFeed.parse(r.body)
                // Le serveur ne répond pas toujours 304 (ETag réécrit en route) : un contenu
                // identique au précédent ne range rien de nouveau.
                val hash = sha1(r.body)
                if (hash != prefs.foyerFeedHash) {
                    apply(feed)
                    // Enregistré avant les photos : une photo qui pose problème ne fait jamais tout recommencer.
                    prefs.foyerFeedHash = hash
                    prefs.foyerEtag = r.etag.orEmpty()
                }
                // Photos manquantes, que le contenu ait changé ou non (une photo refusée la dernière fois est reprise).
                queueMissingPhotos()
                queueScreensaver()
                prefs.foyerEtag = r.etag.orEmpty()
                prefs.foyerLastSyncMs = System.currentTimeMillis()
                prefs.foyerLastResult = tr("%d recettes du foyer", feed.recipes.size)
                changed()
                // Réglages robot en préparation chez Foyer : on repasse bientôt (dix fois de suite au plus).
                if (feed.pendingSteps > 0 && quickSyncs < 10) {
                    quickSyncs++
                    60_000L
                } else {
                    quickSyncs = 0
                    idleDelay()
                }
            }
            401, 409 -> {
                log.write("foyer", "délié depuis Foyer")
                unlinked()
                1_000L
            }
            429 -> 2 * 60_000L
            else -> {
                prefs.foyerLastResult = tr("Foyer a répondu %d", r.code)
                changed()
                2 * 60_000L
            }
        }
    }

    /** Plus relié : les recettes et les repas du foyer quittent le robot (vendu, prêté, relié ailleurs). */
    private fun unlinked() {
        prefs.foyerHousehold = ""
        prefs.foyerEtag = ""
        prefs.foyerFeedHash = ""
        prefs.foyerCode = ""
        prefs.foyerCodeUntil = 0
        prefs.foyerPlans = "[]"
        prefs.foyerRobotName = ""
        prefs.foyerPhotos = "{}"
        photoTried.clear()
        // L'écran de veille du foyer s'en va aussi : l'horloge revient.
        prefs.screensaver = ""
        mediaTried.clear()
        mediaBg.post { cleanScreensaver(emptyList()) }
        try {
            app.recipes.removeAllExcept(emptySet(), origin = RecipeRepo.FOYER)
        } catch (e: Exception) {
            log.write("foyer", "recettes du foyer non effacées : $e")
        }
        changed()
    }

    /** Range les recettes reçues (celles retirées de Foyer disparaissent) et note l'adresse de leurs photos. */
    private fun apply(feed: FoyerFeed) {
        val repo = app.recipes
        val photos = LinkedHashMap<String, String>()
        val recipes = feed.recipes.map { r ->
            if (r.image.startsWith("https://")) {
                val name = photoName(r.image)
                photos[name] = r.image
                r.copy(image = name)
            } else r.copy(image = "")
        }
        repo.upsertAll(recipes, rebuild = false)
        repo.removeAllExcept(recipes.map { it.id }.toHashSet(), rebuild = false, origin = RecipeRepo.FOYER)
        repo.refresh()
        prefs.foyerPlans = FoyerFeed.encodePlans(feed.plans)
        if (feed.household.isNotBlank()) prefs.foyerHousehold = feed.household
        prefs.foyerRobotName = feed.robotName
        prefs.foyerPhotos = Json.write(photos)
        prefs.screensaver = ScreensaverConfig.encode(feed.screensaver ?: ScreensaverConfig.DEFAULT)
        changed()
    }

    // ------------------------------------------------------------------ écran de veille

    /** Dossier des fichiers de l'écran de veille (privé à Toque). */
    val screensaverDir: File get() = File(app.filesDir, "screensaver").apply { mkdirs() }

    /** Le fichier prêt pour [stem] (« <id>-<version> »), ou null tant qu'il n'est pas téléchargé. */
    fun screensaverFile(stem: String): File? =
        screensaverDir.listFiles()?.firstOrNull { it.name.startsWith("$stem.") && !it.name.endsWith(".part") && it.length() > 0 }

    /**
     * Fichiers de l'écran de veille encore absents, téléchargés un à un sur leur fil (après chaque
     * synchro, 304 compris). Chacun est essayé au plus une fois par heure ; ceux que Foyer ne liste
     * plus sont effacés.
     */
    private fun queueScreensaver() {
        val config = ScreensaverConfig.decode(prefs.screensaver)
        val items = config.items
        if (mediaBusy) return
        val now = SystemClock.elapsedRealtime()
        val due = items.filter { item ->
            if (screensaverFile(item.stem) != null) return@filter false
            val last = mediaTried[item.stem]
            if (last != null && now - last < 60 * 60_000L) return@filter false
            mediaTried[item.stem] = now
            true
        }
        mediaBusy = true
        mediaBg.post {
            try {
                cleanScreensaver(items.map { it.stem })
                for (item in due) {
                    if (screensaverFile(item.stem) != null) continue
                    try {
                        val key = if (FoyerHttp.isFoyerFile(item.url)) secret() else null
                        FoyerHttp.downloadMedia(item.url, item.kind, userAgent, key, screensaverDir, item.stem)
                        changed()
                    } catch (t: Throwable) {
                        log.write("foyer", "écran de veille : fichier ignoré (${t.javaClass.simpleName} ${t.message.orEmpty()})")
                    }
                }
            } finally {
                mediaBusy = false
            }
        }
    }

    /** Efface les fichiers que Foyer ne liste plus (et les restes d'un téléchargement coupé). */
    private fun cleanScreensaver(keep: List<String>) {
        val wanted = keep.toHashSet()
        for (f in screensaverDir.listFiles().orEmpty()) {
            val stem = f.name.substringBeforeLast('.')
            if (f.name.endsWith(".part") || stem !in wanted) f.delete()
        }
    }

    /**
     * Photos des recettes du foyer encore absentes du robot, téléchargées sur leur propre fil.
     * Chaque adresse est essayée au plus une fois par heure : une photo refusée (robot pas encore
     * à jour, site lent) revient toute seule, sans marteler une adresse cassée.
     */
    private fun queueMissingPhotos() {
        val stored = try { Json.parse(prefs.foyerPhotos).obj() } catch (_: Exception) { emptyMap<String, Any?>() }
        if (stored.isEmpty()) return
        val repo = app.recipes
        val now = SystemClock.elapsedRealtime()
        val due = LinkedHashMap<String, String>()
        for ((name, value) in stored) {
            val url = value as? String ?: continue
            if (repo.imageFile(name) != null) continue
            val last = photoTried[url]
            if (last != null && now - last < 60 * 60_000L) continue
            photoTried[url] = now
            due[name] = url
        }
        if (due.isNotEmpty()) photosBg.post { fetchPhotos(due) }
    }

    /** Fil des photos : chacune 20 s au plus ; une image piégée (mémoire, format) est sautée, jamais fatale. */
    private fun fetchPhotos(photos: Map<String, String>) {
        val repo = app.recipes
        var failures = 0
        var saved = 0
        for ((name, url) in photos) {
            if (repo.imageFile(name) != null) continue
            try {
                // La clé du robot ne part qu'avec une photo gardée par Foyer lui-même.
                val key = if (FoyerHttp.isFoyerFile(url)) secret() else null
                Photos.save(FoyerHttp.download(url, userAgent, key), repo.imagesDir, name)
                saved++
                Thread.sleep(150)
            } catch (t: Throwable) {
                log.write("foyer", "photo ignorée : ${t.javaClass.simpleName} ${t.message.orEmpty()}")
                if (++failures >= 5) break
            }
        }
        if (saved > 0) repo.refresh()
    }

    private fun send() {
        val r = pending ?: return
        pending = null
        if (!linked) return
        try {
            val res = FoyerHttp.call("POST", "/api/robot/status", secret(), userAgent, Json.write(r.toMap(SystemClock.elapsedRealtime())))
            when {
                res.code == 401 || res.code == 409 -> unlinked()
                res.code == 429 || res.code >= 500 -> retry(r)
            }
        } catch (e: Exception) {
            log.write("foyer", "état non envoyé : ${e.message}")
            retry(r)
        }
    }

    /** Réessaie dans 30 s, sauf si un état plus récent attend déjà. */
    private fun retry(r: StatusReport) {
        if (pending == null) pending = r
        bg.removeCallbacks(sendTask)
        bg.postDelayed(sendTask, 30_000)
    }

    companion object {
        @Volatile private var instance: Foyer? = null

        /** Démarré une fois par le processus de l'interface (ToqueApp). */
        fun start(app: ToqueApp): Foyer = synchronized(this) {
            instance ?: Foyer(app).also {
                instance = it
                it.start()
            }
        }

        fun of(app: ToqueApp): Foyer = start(app)

        /** Échec dû à l'heure du robot : certificat « pas encore valide » ou « expiré » (horloge en 2010…). */
        fun clockProblem(e: Throwable): Boolean {
            if (!TimeSync.clockPlausible()) return true
            var t: Throwable? = e
            var depth = 0
            while (t != null && depth++ < 10) {
                if (t is java.security.cert.CertificateNotYetValidException || t is java.security.cert.CertificateExpiredException) return true
                val m = t.message.orEmpty()
                if ("not valid until" in m || "Certificate expired" in m) return true
                t = t.cause
            }
            return false
        }

        fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        fun photoName(url: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray(Charsets.UTF_8))
            return "foyer-" + digest.take(10).joinToString("") { "%02x".format(it) } + ".jpg"
        }

        private fun isoFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        fun iso(ms: Long): String = isoFormat().format(Date(ms))

        /** « 2026-11-18T19:12:00.123+00:00 » ou « …Z » → ms (null si illisible). */
        fun parseIso(s: String?): Long? {
            if (s == null) return null
            val m = Regex("""(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}:\d{2})(?:\.\d+)?(Z|[+-]\d{2}:?\d{2})""").find(s) ?: return null
            val zone = m.groupValues[3].let { if (it == "Z") "+0000" else it.replace(":", "") }
            return try {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).parse("${m.groupValues[1]}T${m.groupValues[2]}$zone")?.time
            } catch (_: Exception) {
                null
            }
        }

        /** Date du jour et heure « HH:mm », à l'heure du robot (pour le prochain repas prévu). */
        fun todayAndTime(): Pair<String, String> {
            val now = Date()
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now) to SimpleDateFormat("HH:mm", Locale.US).format(now)
        }
    }
}
