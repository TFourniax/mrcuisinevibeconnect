package app.toque.core.ui

/**
 * L'anneau lumineux autour du bouton rotatif. On ne sait pas d'avance comment la tablette le
 * commande : s'il apparaît parmi les voyants Linux (`/sys/class/leds/<nom>/brightness`), la
 * personne le désigne une fois (Réglages › Outils › Anneau lumineux) et Toque l'allume ou
 * l'éteint selon [RingPolicy]. Rien ne passe par la liaison série de la base moteur.
 */
object RingPolicy {
    /** Allumé quand on se sert du robot, éteint sinon (par défaut). */
    const val AUTO = "auto"
    const val ALWAYS_ON = "on"
    const val ALWAYS_OFF = "off"

    /** Sans écran de veille : l'anneau s'éteint après 2 minutes sans geste. */
    const val IDLE_WITHOUT_SAVER_MS = 2 * 60_000L

    fun mode(value: String?): String = when (value) {
        ALWAYS_ON, ALWAYS_OFF -> value
        else -> AUTO
    }

    /** Après combien de temps sans geste l'anneau s'éteint : le délai de l'écran de veille, sinon 2 minutes. */
    fun idleLimitMs(screensaverOff: Boolean, screensaverDelayMinutes: Int): Long =
        if (screensaverOff) IDLE_WITHOUT_SAVER_MS else screensaverDelayMinutes.coerceIn(1, 60) * 60_000L

    /**
     * Allumé ou pas. En automatique : toujours pendant une cuisson (ou une pause, un turbo, un
     * défaut, une étape qui attend), jamais derrière l'écran de veille, sinon tant que le dernier
     * geste (doigt, bouton) date de moins de [idleLimitMs].
     */
    fun lit(mode: String, busy: Boolean, screensaverShown: Boolean, idleMs: Long, idleLimitMs: Long): Boolean = when (mode(mode)) {
        ALWAYS_ON -> true
        ALWAYS_OFF -> false
        else -> busy || (!screensaverShown && idleMs < idleLimitMs)
    }
}

/** Un voyant de `/sys/class/leds` : nom, luminosité, maximum, déclencheur courant (ou vide). */
data class Led(val name: String, val brightness: Int, val max: Int, val trigger: String)

/** Lecture et écriture des voyants, en commandes shell sûres (noms et valeurs vérifiés). */
object Leds {
    const val ROOT = "/sys/class/leds"
    private val NAME = Regex("^[A-Za-z0-9_.:+-]{1,64}$")

    fun validName(name: String?): Boolean = name != null && NAME.matches(name) && name != "." && name != ".."

    /** Le rétroéclairage de l'écran : jamais proposé comme anneau (l'éteindre rendrait l'écran noir). */
    fun isScreen(name: String): Boolean = name.lowercase().let { n -> listOf("lcd", "panel", "display", "screen").any { it in n } }

    /** Liste « nom|luminosité|max|déclencheurs » de chaque voyant. */
    const val LIST_COMMAND =
        "for d in $ROOT/*; do [ -e \"\$d/brightness\" ] || continue; " +
            "echo \"\${d##*/}|\$(cat \$d/brightness 2>/dev/null)|\$(cat \$d/max_brightness 2>/dev/null)|\$(cat \$d/trigger 2>/dev/null)\"; done"

    fun parseList(text: String): List<Led> = text.lineSequence().mapNotNull { line ->
        val parts = line.trim().split('|')
        if (parts.size < 3) return@mapNotNull null
        val name = parts[0]
        if (!validName(name)) return@mapNotNull null
        val brightness = parts[1].trim().toIntOrNull() ?: 0
        val max = parts[2].trim().toIntOrNull()?.takeIf { it > 0 } ?: 255
        Led(name, brightness.coerceAtLeast(0), max.coerceAtMost(100_000), currentTrigger(parts.getOrNull(3).orEmpty()))
    }.distinctBy { it.name }.sortedBy { it.name }.toList()

    /** Le déclencheur actif est celui entre crochets : « none [timer] heartbeat » → « timer ». */
    fun currentTrigger(text: String): String = Regex("\\[([^\\]\\s]{1,40})]").find(text)?.groupValues?.get(1).orEmpty()

    /** Commande qui fixe la luminosité d'un voyant (déclencheur coupé d'abord), ou null si le nom ou la valeur sont douteux. */
    fun setCommand(name: String, value: Int): String? {
        if (!validName(name) || value < 0 || value > 100_000) return null
        val d = "$ROOT/$name"
        return "[ -e $d/brightness ] && { echo none > $d/trigger 2>/dev/null; echo $value > $d/brightness; }"
    }

    /**
     * Boucle d'observation en une seule session root : toutes les ~400 ms, une ligne
     * « nom=valeur » par voyant puis « -- » ; [samples] tours au plus.
     */
    fun watchCommand(samples: Int): String {
        val n = samples.coerceIn(1, 1000)
        return "i=0; while [ \$i -lt $n ]; do for d in $ROOT/*; do [ -e \"\$d/brightness\" ] && echo \"\${d##*/}=\$(cat \$d/brightness 2>/dev/null)\"; done; " +
            "echo --; i=\$((i+1)); usleep 400000 2>/dev/null || sleep 1; done"
    }

    /** Une ligne « nom=valeur » de la boucle d'observation, ou null. */
    fun parseSample(line: String): Pair<String, Int>? {
        val i = line.indexOf('=')
        if (i <= 0) return null
        val name = line.substring(0, i).trim()
        val value = line.substring(i + 1).trim().toIntOrNull() ?: return null
        return if (validName(name)) name to value else null
    }
}

/** Les changements vus pendant l'observation : pour chaque voyant, les instants et les valeurs. */
class LedWatch {
    data class Change(val name: String, val atMs: Long, val from: Int, val to: Int)

    private val last = HashMap<String, Int>()
    private val changes = ArrayList<Change>()

    /** Une valeur lue à [atMs] (depuis le début) ; renvoie le changement s'il y en a un. */
    fun sample(name: String, value: Int, atMs: Long): Change? {
        val before = last.put(name, value) ?: return null
        if (before == value) return null
        return Change(name, atMs, before, value).also { if (changes.size < 2000) changes += it }
    }

    fun changes(): List<Change> = changes.toList()

    /** Les voyants qui ont bougé, du plus actif au moins actif. */
    fun moved(): List<Pair<String, Int>> = changes.groupingBy { it.name }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
}
