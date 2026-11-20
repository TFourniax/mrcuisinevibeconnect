package app.toque.core.ui

/**
 * L'anneau lumineux autour du bouton rotatif. Sur le Monsieur Cuisine Connect c'est une seule
 * lumière RVB faite de trois voyants Linux (`/sys/class/leds/red`, `green`, `blue` : tous allumés
 * = le blanc d'origine). Toque la trouve tout seul ([RingLeds.detect]) ; sur une tablette sans
 * jeu RVB, la personne désigne un voyant (Réglages › Outils › Anneau lumineux). La couleur, la
 * force et le moment où il s'allume suivent [RingPolicy]. Rien ne passe par la base moteur.
 */

/** Ce que fait le robot, pour la couleur « selon l'état ». */
enum class RingState { IDLE, COOKING, WAITING, DONE, ALERT }

/** Une couleur proposée, en 0–255 par canal (avant la force choisie). */
data class RingColor(val key: String, val r: Int, val g: Int, val b: Int)

object RingColors {
    // Le vert de ces voyants éclaire bien plus que le rouge : l'orange et le jaune en ont peu.
    val WHITE = RingColor("white", 255, 255, 255)
    val ORANGE = RingColor("orange", 255, 60, 0)
    val RED = RingColor("red", 255, 0, 0)
    val GREEN = RingColor("green", 0, 255, 0)
    val BLUE = RingColor("blue", 0, 0, 255)
    val VIOLET = RingColor("violet", 170, 0, 255)
    val CYAN = RingColor("cyan", 0, 255, 255)
    val YELLOW = RingColor("yellow", 255, 150, 0)
    val ALL = listOf(WHITE, ORANGE, RED, GREEN, BLUE, VIOLET, CYAN, YELLOW)

    /** Blanc par défaut, comme l'appli d'origine. */
    fun of(key: String?): RingColor = ALL.firstOrNull { it.key == key } ?: WHITE
}

/** Force de l'anneau : part du maximum de chaque voyant. Forte par défaut (le blanc d'origine). */
object RingIntensity {
    const val LOW = "low"
    const val MEDIUM = "medium"
    const val HIGH = "high"

    fun of(key: String?): String = when (key) {
        LOW, MEDIUM -> key
        else -> HIGH
    }

    fun factor(key: String?): Double = when (of(key)) {
        LOW -> 0.2
        MEDIUM -> 0.5
        else -> 1.0
    }
}

/** Ce que l'anneau doit montrer : allumé ou non, sa couleur, et s'il clignote. */
data class RingLook(val on: Boolean, val color: RingColor, val blink: Boolean = false)

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

    /** Combien de temps « c'est prêt » reste en vert, avec les couleurs selon l'état. */
    const val DONE_MS = 2 * 60_000L

    /**
     * Ce que montre l'anneau. Allumé comme [lit] (une cuisson, une étape qui attend ou une alerte
     * comptent comme « on s'en sert » ; « c'est prêt » aussi avec les couleurs selon l'état).
     * Couleur choisie par défaut ; avec [byState] : orange en cuisson, bleu quand une étape
     * attend quelqu'un, vert quand c'est prêt, rouge clignotant pour une alerte.
     */
    fun look(mode: String, colorKey: String?, byState: Boolean, state: RingState, screensaverShown: Boolean, idleMs: Long, idleLimitMs: Long): RingLook {
        val chosen = RingColors.of(colorKey)
        val busy = state == RingState.COOKING || state == RingState.WAITING || state == RingState.ALERT || (byState && state == RingState.DONE)
        if (!lit(mode, busy, screensaverShown, idleMs, idleLimitMs)) return RingLook(false, chosen)
        if (!byState) return RingLook(true, chosen)
        return when (state) {
            RingState.COOKING -> RingLook(true, RingColors.ORANGE)
            RingState.WAITING -> RingLook(true, RingColors.BLUE)
            RingState.DONE -> RingLook(true, RingColors.GREEN)
            RingState.ALERT -> RingLook(true, RingColors.RED, blink = true)
            RingState.IDLE -> RingLook(true, chosen)
        }
    }

    /**
     * Valeur à écrire sur un canal de maximum [max] pour la composante [component] (0–255) à la
     * force [intensity] : arrondie, au moins 1 quand la composante n'est pas nulle.
     */
    fun level(component: Int, intensity: String?, max: Int): Int {
        val c = component.coerceIn(0, 255)
        if (c == 0 || max <= 0) return 0
        val v = Math.round(c / 255.0 * RingIntensity.factor(intensity) * max).toInt()
        return v.coerceIn(1, max)
    }
}

/** Les trois voyants qui font l'anneau RVB. */
data class RgbLeds(val red: String, val green: String, val blue: String) {
    val names: List<String> get() = listOf(red, green, blue)

    fun encode(): String = "$red,$green,$blue"

    companion object {
        fun decode(text: String?): RgbLeds? {
            val parts = text?.split(',')?.map { it.trim() } ?: return null
            if (parts.size != 3 || parts.any { !Leds.validName(it) || Leds.isScreen(it) } || parts.toSet().size != 3) return null
            return RgbLeds(parts[0], parts[1], parts[2])
        }
    }
}

/** Trouve l'anneau RVB parmi les voyants : `red`/`green`/`blue`, `led_r`/`led_g`/`led_b`, `x:red`… */
object RingLeds {
    // No regex here: Android's ICU engine reads a character class starting with "[:" as a POSIX
    // property and throws (1.0.9 crashed at every start on the robot, while the JVM tests passed).
    private const val SEPARATORS = ":_.+-"

    /** "led_red:2" → ["led", "_", "red", ":", "2"]: words and separators, in order. */
    internal fun tokens(name: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        for (ch in name) {
            if (ch in SEPARATORS) {
                if (word.isNotEmpty()) out += word.toString().also { word.setLength(0) }
                out += ch.toString()
            } else {
                word.append(ch)
            }
        }
        if (word.isNotEmpty()) out += word.toString()
        return out
    }
    private val CHANNELS = mapOf("red" to 'r', "r" to 'r', "green" to 'g', "g" to 'g', "blue" to 'b', "b" to 'b')

    /** Canal (r, g ou b) et « famille » du voyant (son nom, la couleur remplacée par *), ou null. */
    fun channel(name: String): Pair<Char, String>? {
        if (!Leds.validName(name) || Leds.isScreen(name)) return null
        val tokens = tokens(name.lowercase())
        for (i in tokens.indices.reversed()) {
            val ch = CHANNELS[tokens[i]] ?: continue
            return ch to tokens.mapIndexed { j, t -> if (j == i) "*" else t }.joinToString("")
        }
        return null
    }

    /**
     * Le jeu RVB, ou null. Plusieurs familles complètes : celle qui parle d'anneau, de bouton ou
     * de RVB d'abord, puis la plus courte (« red » plutôt que « led_red_2 »).
     */
    fun detect(names: Collection<String>): RgbLeds? {
        val families = LinkedHashMap<String, MutableMap<Char, String>>()
        for (name in names.distinct().sorted()) {
            val (ch, family) = channel(name) ?: continue
            // Pas de Map.putIfAbsent : il n'existe qu'à partir d'Android 7 (1.0.10 plantait ici).
            val channels = families.getOrPut(family) { HashMap() }
            if (ch !in channels) channels[ch] = name
        }
        val complete = families.filter { it.value.size == 3 }
        if (complete.isEmpty()) return null
        val hint = Regex("ring|knob|button|key|rgb")
        val best = complete.entries.sortedWith(compareBy({ if (hint.containsMatchIn(it.key)) 0 else 1 }, { it.key.length }, { it.key })).first().value
        return RgbLeds(best.getValue('r'), best.getValue('g'), best.getValue('b'))
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

    private val TRIGGER = Regex("^[A-Za-z0-9_.:-]{1,40}$")

    /** Remet un voyant comme il était : sa luminosité puis son déclencheur d'origine (un nom sûr), ou null. */
    fun restoreCommand(name: String, brightness: Int, trigger: String): String? {
        if (!validName(name) || isScreen(name) || brightness < 0 || brightness > 100_000) return null
        val d = "$ROOT/$name"
        val t = trigger.takeIf { it.isNotEmpty() && it != "none" && TRIGGER.matches(it) }
        return "[ -e $d/brightness ] && { echo none > $d/trigger 2>/dev/null; echo $brightness > $d/brightness; " +
            (if (t != null) "echo $t > $d/trigger 2>/dev/null; " else "") + "}"
    }

    /** L'état d'origine des voyants (hors écran), gardé une fois avant toute écriture : « nom=lum|déclencheur;… ». */
    fun encodeOriginal(leds: List<Led>): String =
        leds.filter { validName(it.name) && !isScreen(it.name) }.joinToString(";") { "${it.name}=${it.brightness}|${it.trigger}" }

    fun decodeOriginal(text: String?): List<Led> = text.orEmpty().split(';').mapNotNull { part ->
        val eq = part.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val name = part.substring(0, eq)
        val rest = part.substring(eq + 1).split('|')
        val brightness = rest.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        if (!validName(name) || isScreen(name)) return@mapNotNull null
        Led(name, brightness.coerceIn(0, 100_000), 0, rest.getOrNull(1).orEmpty().take(40))
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
