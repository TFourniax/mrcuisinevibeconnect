package app.toque.core.ui

/** Réglage du thème : automatique (clair le jour, sombre le soir), toujours clair, toujours sombre. */
enum class ThemeMode(val key: String) {
    AUTO("auto"), LIGHT("light"), DARK("dark");

    companion object {
        fun of(key: String?): ThemeMode = values().firstOrNull { it.key == key } ?: AUTO
    }
}

/**
 * Le thème à afficher. En automatique : clair de 7 h à 20 h, sombre le reste du temps. La
 * lune (ou le soleil) de la barre du haut passe à l'autre thème ; en automatique ce choix tient
 * jusqu'au prochain changement (7 h ou 20 h), puis l'automatique reprend.
 */
object ThemeClock {
    const val DAY_START = 7 * 60
    const val NIGHT_START = 20 * 60
    private const val DAY = 24 * 60

    /** Sombre d'après l'heure seule ([minuteOfDay] : minutes depuis minuit, heure locale). */
    fun autoDark(minuteOfDay: Int): Boolean {
        val m = ((minuteOfDay % DAY) + DAY) % DAY
        return m < DAY_START || m >= NIGHT_START
    }

    /** Minutes jusqu'au prochain passage automatique (7 h ou 20 h), entre 1 et 24 h. */
    fun minutesToNextSwitch(minuteOfDay: Int): Int {
        val m = ((minuteOfDay % DAY) + DAY) % DAY
        val next = when {
            m < DAY_START -> DAY_START
            m < NIGHT_START -> NIGHT_START
            else -> DAY + DAY_START
        }
        return next - m
    }

    /**
     * Le thème effectif. [overrideDark] et [overrideUntilMs] : le choix fait à la lune, valable
     * jusqu'à [overrideUntilMs] (horloge murale) ; ignoré hors du mode automatique.
     */
    fun dark(mode: ThemeMode, minuteOfDay: Int, nowMs: Long, overrideDark: Boolean?, overrideUntilMs: Long): Boolean = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.AUTO -> if (overrideDark != null && nowMs < overrideUntilMs) overrideDark else autoDark(minuteOfDay)
    }

    /** Ce que fait un appui sur la lune ou le soleil. */
    data class Toggle(val mode: ThemeMode, val overrideDark: Boolean?, val overrideUntilMs: Long)

    fun toggle(mode: ThemeMode, currentlyDark: Boolean, minuteOfDay: Int, nowMs: Long): Toggle = when (mode) {
        // Thème fixé dans les réglages : la lune passe simplement à l'autre.
        ThemeMode.LIGHT, ThemeMode.DARK -> Toggle(if (currentlyDark) ThemeMode.LIGHT else ThemeMode.DARK, null, 0L)
        ThemeMode.AUTO -> {
            val wantDark = !currentlyDark
            // Revenir à ce que dit l'heure efface simplement le choix.
            if (wantDark == autoDark(minuteOfDay)) Toggle(ThemeMode.AUTO, null, 0L)
            else Toggle(ThemeMode.AUTO, wantDark, nowMs + minutesToNextSwitch(minuteOfDay) * 60_000L)
        }
    }
}
