package app.toque.core.foyer

import app.toque.core.machine.JobState
import app.toque.core.machine.PauseReason
import app.toque.core.machine.Snapshot
import app.toque.core.protocol.Mcc

/** La recette suivie pas à pas, telle que l'écran de cuisson la montre. */
data class Guided(
    val recipeId: String,
    val title: String,
    val stepIndex: Int,
    val stepCount: Int,
    /** Texte de l'étape suivante (vide à la dernière). */
    val nextText: String,
    /** Étapes avec un réglage machine après l'étape affichée. */
    val machineStepsAfter: Int,
)

/** Ce que le robot dit à Foyer (écran Maison et notifications). */
data class StatusReport(
    val state: String,
    val recipeId: String? = null,
    val title: String? = null,
    val stepIndex: Int? = null,
    val stepCount: Int? = null,
    val stepText: String? = null,
    val tempC: Int? = null,
    val speed: Int? = null,
    /** Fin de l'étape sur l'horloge du suivi (monotone sur le robot). */
    val endsAtMs: Long? = null,
    val alert: String? = null,
    val notify: Boolean = false,
) {
    /**
     * Même chose à dire, notification mise à part (l'heure de fin bouge à peine pendant une
     * cuisson : 30 s de marge). À envoyer : ce qui diffère, ou ce qui demande une notification.
     */
    fun sameAs(o: StatusReport?): Boolean {
        if (o == null) return false
        val ends = when {
            endsAtMs == null && o.endsAtMs == null -> true
            endsAtMs == null || o.endsAtMs == null -> false
            else -> kotlin.math.abs(endsAtMs - o.endsAtMs) < 30_000
        }
        return ends && copy(endsAtMs = null, notify = false) == o.copy(endsAtMs = null, notify = false)
    }

    fun worthSending(last: StatusReport?): Boolean = notify || !sameAs(last)

    /** Pour Foyer, au moment de l'envoi [nowMs] (même horloge que [endsAtMs]) : le temps restant, pas une heure. */
    fun toMap(nowMs: Long): Map<String, Any?> = linkedMapOf(
        "state" to state, "recipeId" to recipeId, "title" to title?.take(160), "stepIndex" to stepIndex,
        "stepCount" to stepCount, "stepText" to stepText?.take(300), "tempC" to tempC, "speed" to speed,
        "remainingSeconds" to endsAtMs?.let { ((it - nowMs) / 1000).coerceIn(0L, 6L * 3600) },
        "alert" to alert?.take(160), "notify" to notify,
    )
}

/**
 * Suit ce que fait le robot et décide quoi dire à Foyer, sans réseau ni Android :
 * en cuisson (étape, température, vitesse, heure de fin), en pause, arrêté par sécurité
 * (prévient tout de suite), une étape finie qui attend quelqu'un, ou c'est prêt.
 * Pour ces deux derniers, la notification part seulement si personne n'a touché
 * le robot pendant [graceMs] : inutile de prévenir quelqu'un qui est devant.
 */
class StatusTracker(private val clock: () -> Long, private val graceMs: Long = 45_000, private val doneForgetMs: Long = 30 * 60_000) {
    var guided: Guided? = null
    private var held: StatusReport? = null
    private var heldAt = 0L
    private var heldNotified = false
    private var lastInteraction = 0L
    private var lastSnapshot = Snapshot()
    private var wasActive = false
    private var alertNotified: String? = null

    /** Ce qui reste affiché pour Foyer (« waiting », « done », « alert ») ; null au repos. Lecture seule. */
    val heldState: String? get() = held?.state

    fun interaction() {
        lastInteraction = clock()
        // Quelqu'un est revenu au robot après « c'est prêt » (hors recette) ou après un arrêt
        // de sécurité (il l'a vu sur l'écran) : au repos.
        if ((held?.state == "done" && guided == null) || held?.state == "alert") held = null
    }

    /** Une étape ou un travail vient de se terminer. */
    fun onJobFinished(jobLabel: String): StatusReport {
        val g = guided
        val report = if (g != null && g.machineStepsAfter > 0) {
            StatusReport("waiting", g.recipeId, g.title, g.stepIndex + 1, g.stepCount, g.nextText.ifBlank { null })
        } else {
            StatusReport("done", g?.recipeId, g?.title ?: jobLabel.ifBlank { null })
        }
        held = report
        heldAt = clock()
        heldNotified = false
        return report
    }

    /** Travail arrêté à la main : rien à annoncer (un arrêt de sécurité reste affiché). */
    fun onJobAborted() {
        if (held?.state != "alert") held = null
    }

    /** L'état à dire maintenant, d'après le dernier échantillon de la machine. */
    fun current(s: Snapshot = lastSnapshot): StatusReport {
        lastSnapshot = s
        val now = clock()
        val state = s.state
        val active = state == JobState.RUNNING || state == JobState.STARTING || state == JobState.STOPPING || state == JobState.PAUSED || s.turboActive
        if (active) held = null
        val g = guided
        val base = StatusReport("idle", g?.recipeId, g?.title, g?.stepIndex, g?.stepCount)

        val fault = s.hasFault && (active || wasActive)
        val safetyPause = state == JobState.PAUSED && s.pauseReason == PauseReason.SAFETY.name
        wasActive = active
        if (fault || safetyPause) {
            val text = if (fault) s.faultMessage.ifBlank { PauseReason.SAFETY.label } else PauseReason.SAFETY.label
            val notify = alertNotified != text
            alertNotified = text
            val report = base.copy(state = "alert", alert = text, notify = notify)
            // Reste affiché dans Foyer jusqu'à ce que quelqu'un vienne au robot.
            held = report.copy(notify = false)
            heldAt = now
            heldNotified = true
            return report
        }
        alertNotified = null
        if (state == JobState.PAUSED) return base.copy(state = "paused")
        if (active) {
            val temp = if (s.jobTempLevel > 0) Mcc.celsiusForLevel(s.jobTempLevel) else 0
            return base.copy(
                state = "cooking",
                title = g?.title ?: s.jobLabel.ifBlank { null },
                tempC = temp.takeIf { it > 0 },
                speed = s.jobSpeed.takeIf { it > 0 },
                endsAtMs = if (s.totalMs > 0 && s.remainingMs > 0) now + s.remainingMs else null,
            )
        }
        val h = held
        if (h != null) {
            if (h.state == "done" && now - heldAt > doneForgetMs) {
                held = null
                return base
            }
            // Une fois le délai passé sans personne devant le robot : on prévient (une seule fois).
            val due = !heldNotified && now - heldAt >= graceMs && lastInteraction < heldAt
            if (due) heldNotified = true
            return h.copy(notify = due)
        }
        return base
    }
}
