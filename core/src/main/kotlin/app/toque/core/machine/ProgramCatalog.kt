package app.toque.core.machine

import app.toque.core.i18n.tr
import app.toque.core.i18n.trKey
import app.toque.core.protocol.Direction
import app.toque.core.protocol.Mcc
import app.toque.core.protocol.Operation

/** Un choix d'un programme : cuisson des œufs, sorte de riz, température de l'eau. */
class ProgramChoice(
    val key: String,
    private val fr: String,
    /** Durée proposée par ce choix (0 : celle du programme). */
    val seconds: Int = 0,
    /** Température visée par ce choix (Chauffer l'eau). */
    val celsius: Int = 0,
) {
    val label: String get() = tr(fr)
}

/**
 * Un programme de l'écran Programmes : ce qu'il faut préparer, ses choix, sa durée réglable et
 * le travail envoyé à la machine. Les réglages sont ceux validés le 25/11/2026 (tableau
 * « Réglages des programmes ») ; tous passent par [SafetyPolicy] comme le reste.
 */
class ProgramDef(
    val kind: JobKind,
    /** Repère court sous l'icône (« 95 °C », « 7 à 12 min »). */
    private val noteFr: String,
    /** À préparer avant de lancer. */
    private val prepFr: String,
    val choices: List<ProgramChoice> = emptyList(),
    val defaultChoice: Int = 0,
    /** Durée par défaut en secondes (sans choix qui la fixe). */
    val seconds: Int = 0,
    val minSeconds: Int = 0,
    val maxSeconds: Int = 0,
    /** Pas du réglage de la durée ; 0 : durée fixe (Prélaver) ou fin à la température (Chauffer l'eau). */
    val stepSeconds: Int = 0,
    /** Conseil d'une ligne sous les choix. */
    private val hintFr: String = "",
    private val build: (seconds: Int, choice: ProgramChoice?) -> JobSpec,
) {
    val label: String get() = kind.label
    val note: String get() = tr(noteFr)
    val prep: String get() = tr(prepFr)
    val hint: String get() = if (hintFr.isEmpty()) "" else tr(hintFr)
    val adjustable: Boolean get() = stepSeconds > 0

    /** Durée de départ pour [choice] (celle du choix, sinon celle du programme). */
    fun startSeconds(choice: Int): Int = choices.getOrNull(choice)?.seconds?.takeIf { it > 0 } ?: seconds

    /** Le travail pour [seconds] (bornée) et le choix [choice]. */
    fun job(seconds: Int, choice: Int): JobSpec {
        val c = choices.getOrNull(choice)
        val s = if (adjustable) seconds.coerceIn(minSeconds, maxSeconds) else seconds
        return build(s, c)
    }

    /** Un cran de plus ou de moins sur la durée, dans les bornes. */
    fun step(seconds: Int, dir: Int): Int = if (!adjustable) seconds else (seconds + dir * stepSeconds).coerceIn(minSeconds, maxSeconds)
}

/** Les dix programmes, dans l'ordre de l'écran. */
object ProgramCatalog {
    val all: List<ProgramDef> = listOf(
        ProgramDef(
            JobKind.KNEAD, trKey("1 min 30"), trKey("Pâte à pain, pizza, brioche. Pour pétrir plus longtemps, une pause puis on relance."),
            seconds = 90, minSeconds = 30, maxSeconds = 120, stepSeconds = 5,
        ) { s, _ -> Programs.knead(s * 1000L) },
        ProgramDef(
            JobKind.STEAM, trKey("120 °C"), trKey("500 ml d'eau dans le bol ; plus d'eau pour une longue cuisson."),
            seconds = 20 * 60, minSeconds = 5 * 60, maxSeconds = 60 * 60, stepSeconds = 60,
        ) { s, _ -> Programs.steam(s * 1000L) },
        ProgramDef(
            JobKind.TOAST, trKey("130 °C"), trKey("Un filet d'huile ; oignons, lardons, viande."),
            seconds = 7 * 60, minSeconds = 3 * 60, maxSeconds = 14 * 60, stepSeconds = 30,
        ) { s, _ -> Programs.toast(s * 1000L) },
        ProgramDef(
            JobKind.SIMMER, trKey("95 °C"), trKey("Sauces, ragoûts, compotes ; le gobelet en place."),
            seconds = 30 * 60, minSeconds = 10 * 60, maxSeconds = 99 * 60, stepSeconds = 5 * 60,
        ) { s, _ -> Programs.simmer(s * 1000L) },
        ProgramDef(
            JobKind.RICE, trKey("25 à 45 min"), trKey("1,5 L d'eau froide salée, 200 g de riz rincé au plus, dans le panier."),
            choices = listOf(
                ProgramChoice("white", trKey("Blanc"), seconds = 30 * 60),
                ProgramChoice("basmati", trKey("Basmati"), seconds = 25 * 60),
                ProgramChoice("brown", trKey("Complet"), seconds = 45 * 60),
            ),
            minSeconds = 15 * 60, maxSeconds = 60 * 60, stepSeconds = 60,
        ) { s, _ -> Programs.rice(s * 1000L) },
        ProgramDef(
            JobKind.EGGS, trKey("7 à 12 min"), trKey("500 ml d'eau froide dans le bol, les œufs dans le panier."),
            choices = listOf(
                ProgramChoice("soft", trKey("À la coque"), seconds = 7 * 60),
                ProgramChoice("medium", trKey("Mollet"), seconds = 9 * 60),
                ProgramChoice("hard", trKey("Dur"), seconds = 12 * 60),
            ),
            defaultChoice = 1,
            minSeconds = 5 * 60, maxSeconds = 20 * 60, stepSeconds = 60,
            hintFr = trKey("Gros œufs : 1 min de plus. Passe-les sous l'eau froide à la fin."),
        ) { s, _ -> Programs.eggs(s * 1000L) },
        ProgramDef(
            JobKind.WATER, trKey("70 à 100 °C"), trKey("Jusqu'à 1,5 L. S'arrête dès que l'eau est à la température."),
            choices = listOf(
                ProgramChoice("70", trKey("70 °C"), celsius = 70),
                ProgramChoice("80", trKey("80 °C"), celsius = 80),
                ProgramChoice("90", trKey("90 °C"), celsius = 90),
                ProgramChoice("100", trKey("100 °C"), celsius = 100),
            ),
            defaultChoice = 3,
            seconds = (Limits.MAX_MS_WATER / 1000).toInt(),
            hintFr = trKey("Thé vert 70 à 80 °C, café filtre 90 °C, thé noir et tisanes 100 °C."),
        ) { _, c -> Programs.water(c?.celsius ?: 100) },
        ProgramDef(
            JobKind.BLEND, trKey("1 min"), trKey("Soupes, purées, sauces. Contenu chaud : confirmation demandée."),
            seconds = 60, minSeconds = 20, maxSeconds = 180, stepSeconds = 10,
        ) { s, _ -> Programs.blend(s * 1000L) },
        ProgramDef(
            JobKind.SMOOTHIE, trKey("1 min"), trKey("Fruits et lait ou jus ; à froid seulement."),
            seconds = 60, minSeconds = 30, maxSeconds = 120, stepSeconds = 10,
        ) { s, _ -> Programs.smoothie(s * 1000L) },
        ProgramDef(
            JobKind.PREWASH, trKey("3 min"), trKey("1 L d'eau et une goutte de liquide vaisselle ; rince le bol ensuite."),
            seconds = 160,
        ) { _, _ -> Programs.prewash() },
    )

    fun of(kind: JobKind?): ProgramDef? = all.firstOrNull { it.kind == kind }

    /**
     * Les réglages fixes d'un travail, en pastilles : « Vapeur 120 °C » / « 95 °C », « Lame
     * immobile » / « Vitesse 1 » / « Vitesse 5 → 10 », « Inverse » / « Sens alterné ».
     */
    fun settings(spec: JobSpec): List<String> {
        val active = spec.phases.map { it.setting }.filter { !it.isRest }
        if (active.isEmpty()) return emptyList()
        val out = ArrayList<String>(3)
        val hottest = active.maxOf { it.temperatureLevel }
        val steam = active.any { it.operation == Operation.STEAM }
        if (hottest > 0) out += if (steam) tr("Vapeur %d °C", Mcc.celsiusForLevel(hottest)) else "${Mcc.celsiusForLevel(hottest)} °C"
        // Les temps de repos d'un brassage par à-coups (vitesse 0) ne comptent pas.
        val speeds = active.map { it.speed }.filter { it > 0 }
        val lo = speeds.minOrNull() ?: 0
        val hi = speeds.maxOrNull() ?: 0
        out += when {
            hi == 0 -> tr("Lame immobile")
            lo == hi -> tr("Vitesse %d", hi)
            else -> tr("Vitesse %d → %d", lo, hi)
        }
        val ccw = active.any { it.direction == Direction.COUNTER_CLOCKWISE && it.speed > 0 }
        val cw = active.any { it.direction == Direction.CLOCKWISE && it.speed > 0 }
        if (ccw && cw) out += tr("Sens alterné") else if (ccw) out += tr("Inverse")
        return out
    }
}
