package app.toque.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File

/**
 * Le Monsieur Cuisine Connect tourne sous Android 6 (API 23). Le module core est compilé contre
 * le JDK, où tout Java 8+ existe : un appel comme Map.putIfAbsent compile et passe les tests, puis
 * plante sur le robot (1.0.10 : NoSuchMethodError à l'ouverture d'Outils › Anneau lumineux). Ce
 * test lit le code compilé de core (pool de constantes des .class) et refuse les classes et
 * méthodes absentes d'Android 6 que D8 ne remplace pas lui-même.
 */
class AndroidApiTest {
    /** Paquets et classes arrivés après Android 6. */
    private val missingClasses = listOf(
        "java/time/", "java/util/stream/", "java/util/function/", "java/util/Optional", "java/util/Base64",
        "java/nio/file/", "java/util/concurrent/CompletableFuture", "java/util/StringJoiner", "java/util/Spliterator",
        "java/util/SplittableRandom",
    )

    private val maps = setOf("java/util/Map", "java/util/HashMap", "java/util/LinkedHashMap", "java/util/TreeMap", "java/util/SortedMap", "java/util/NavigableMap")
    private val collections = setOf(
        "java/lang/Iterable", "java/util/Collection", "java/util/List", "java/util/ArrayList", "java/util/LinkedList",
        "java/util/Set", "java/util/HashSet", "java/util/LinkedHashSet", "java/util/TreeSet", "java/util/Queue", "java/util/Deque", "java/util/ArrayDeque",
    )

    /** Méthodes arrivées après Android 6, que D8 ne réécrit pas (méthodes par défaut des interfaces Java 8, Java 9+). */
    private fun missing(owner: String, name: String, descriptor: String): Boolean = when {
        owner in maps -> when (name) {
            "getOrDefault", "putIfAbsent", "computeIfAbsent", "computeIfPresent", "compute", "merge", "forEach", "replaceAll", "replace", "of", "copyOf", "entry" -> true
            "remove" -> descriptor == "(Ljava/lang/Object;Ljava/lang/Object;)Z"
            else -> false
        }
        owner in collections -> when (name) {
            "removeIf", "stream", "parallelStream", "forEach", "spliterator", "sort", "replaceAll", "of", "copyOf" -> true
            "toArray" -> descriptor == "(Ljava/util/function/IntFunction;)[Ljava/lang/Object;"
            else -> false
        }
        owner == "java/util/Comparator" -> name in setOf(
            "reversed", "thenComparing", "thenComparingInt", "thenComparingLong", "thenComparingDouble", "comparing",
            "comparingInt", "comparingLong", "comparingDouble", "naturalOrder", "reverseOrder", "nullsFirst", "nullsLast",
        )
        owner == "java/lang/String" -> name in setOf("join", "chars", "codePoints", "repeat", "isBlank", "strip", "stripLeading", "stripTrailing", "lines")
        owner == "java/lang/CharSequence" -> name in setOf("chars", "codePoints")
        owner == "java/util/Arrays" -> name in setOf("stream", "spliterator", "setAll", "parallelSort", "parallelPrefix", "parallelSetAll")
        else -> false
    }

    @Test
    fun coreOnlyUsesWhatAndroid6Has() {
        val roots = System.getProperty("java.class.path").split(File.pathSeparator).map(::File)
            .filter { it.isDirectory && it.path.replace('\\', '/').let { p -> p.endsWith("classes/kotlin/main") || p.endsWith("classes/java/main") } }
        assertTrue("code compilé de core introuvable dans le classpath des tests", roots.isNotEmpty())
        val problems = ArrayList<String>()
        var classes = 0
        for (root in roots) {
            root.walk().filter { it.extension == "class" }.forEach { file ->
                classes++
                val refs = References.read(file.readBytes())
                val where = file.relativeTo(root).path.removeSuffix(".class")
                for (c in refs.classes) if (missingClasses.any { c.startsWith(it) }) problems += "$where → $c"
                for (m in refs.methods) if (missing(m.owner, m.name, m.descriptor)) problems += "$where → ${m.owner}.${m.name}${m.descriptor}"
            }
        }
        assertTrue("aucune classe lue", classes > 50)
        assertTrue("Absent d'Android 6 (le robot planterait) :\n" + problems.distinct().joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun theCheckSeesPutIfAbsent() {
        // Garde-fou du garde-fou : le contrôle reconnaît bien l'appel qui a fait planter 1.0.10.
        assertTrue(missing("java/util/Map", "putIfAbsent", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
        assertTrue(missing("java/util/List", "sort", "(Ljava/util/Comparator;)V"))
        assertTrue(missing("java/util/Comparator", "reversed", "()Ljava/util/Comparator;"))
        assertTrue(!missing("java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;"))
        assertTrue(!missing("java/util/List", "toArray", "([Ljava/lang/Object;)[Ljava/lang/Object;"))
        val self = References.read(javaClass.getResourceAsStream("/${AndroidApiTest::class.java.name.replace('.', '/')}.class")!!.readBytes())
        assertTrue(self.methods.any { it.owner == "java/lang/System" && it.name == "getProperty" })
    }
}

/** Les classes et méthodes qu'un fichier .class référence (pool de constantes). */
private class References(val classes: List<String>, val methods: List<Ref>) {
    data class Ref(val owner: String, val name: String, val descriptor: String)

    companion object {
        fun read(bytes: ByteArray): References {
            val d = DataInputStream(ByteArrayInputStream(bytes))
            require(d.readInt() == 0xCAFEBABE.toInt()) { "pas un fichier .class" }
            d.readUnsignedShort()
            d.readUnsignedShort()
            val count = d.readUnsignedShort()
            val utf8 = arrayOfNulls<String>(count)
            val classNames = IntArray(count)
            val nameAndTypes = HashMap<Int, Pair<Int, Int>>()
            val refs = ArrayList<Pair<Int, Int>>()
            var i = 1
            while (i < count) {
                when (val tag = d.readUnsignedByte()) {
                    1 -> utf8[i] = d.readUTF()
                    3, 4 -> d.readInt()
                    5, 6 -> { d.readLong(); i++ }
                    7 -> classNames[i] = d.readUnsignedShort()
                    8, 16, 19, 20 -> d.readUnsignedShort()
                    9 -> { d.readUnsignedShort(); d.readUnsignedShort() }
                    10, 11 -> refs += d.readUnsignedShort() to d.readUnsignedShort()
                    12 -> nameAndTypes[i] = d.readUnsignedShort() to d.readUnsignedShort()
                    15 -> { d.readUnsignedByte(); d.readUnsignedShort() }
                    17, 18 -> { d.readUnsignedShort(); d.readUnsignedShort() }
                    else -> error("constante inconnue $tag")
                }
                i++
            }
            val classes = classNames.filter { it != 0 }.mapNotNull { utf8[it] }
            val methods = refs.mapNotNull { (owner, nat) ->
                val ownerName = utf8[classNames[owner]] ?: return@mapNotNull null
                val (name, type) = nameAndTypes[nat] ?: return@mapNotNull null
                References.Ref(ownerName, utf8[name] ?: return@mapNotNull null, utf8[type] ?: return@mapNotNull null)
            }
            return References(classes, methods)
        }
    }
}
