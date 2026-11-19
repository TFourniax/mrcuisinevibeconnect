package app.toque.core.foyer

import app.toque.core.json.Json
import app.toque.core.json.int
import app.toque.core.json.list
import app.toque.core.json.obj
import app.toque.core.json.str
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeCodec
import app.toque.core.recipes.RecipeSource

/** Un repas prévu dans Foyer (jour ISO, heure « 20:00 » ou rien). */
data class FoyerPlan(val day: String, val time: String?, val title: String, val recipeId: String?)

/** Ce que Foyer envoie au robot : les recettes partagées du foyer et les repas prévus. */
data class FoyerFeed(
    val household: String,
    val robotName: String,
    val recipes: List<Recipe>,
    val plans: List<FoyerPlan>,
    /** Recettes dont les réglages robot sont encore en préparation côté Foyer. */
    val pendingSteps: Int,
    /** Entrées ignorées (illisibles, ou qui ne sont pas des recettes du foyer). */
    val skipped: Int,
    /** Écran de veille choisi dans Foyer (null : Foyer n'en dit rien, l'horloge par défaut). */
    val screensaver: ScreensaverConfig? = null,
) {
    companion object {
        const val ID_PREFIX = "foyer-"

        /**
         * Lit la réponse de /api/robot/sync. Seules les recettes « foyer-… » sont gardées :
         * Foyer ne peut ni remplacer ni effacer une recette Lidl.
         */
        fun parse(text: String): FoyerFeed {
            val root = Json.parse(text).obj()
            var skipped = 0
            val recipes = ArrayList<Recipe>()
            for (entry in root["recipes"].list()) {
                val r = try {
                    RecipeCodec.fromMap(entry.obj(), RecipeSource.FOYER)
                } catch (_: Exception) {
                    null
                }
                if (r == null || !r.id.startsWith(ID_PREFIX)) skipped++ else recipes += r.copy(source = RecipeSource.FOYER)
            }
            val plans = root["plans"].list().mapNotNull { pAny ->
                val p = pAny.obj()
                val day = p.str("day")?.takeIf { Regex("""\d{4}-\d{2}-\d{2}""").matches(it) } ?: return@mapNotNull null
                val title = p.str("title")?.trim().orEmpty()
                if (title.isEmpty()) return@mapNotNull null
                FoyerPlan(day, p.str("time")?.takeIf { Regex("""\d{2}:\d{2}""").matches(it) }, title, p.str("recipeId")?.takeIf { it.startsWith(ID_PREFIX) })
            }
            return FoyerFeed(
                household = root.str("household").orEmpty(),
                robotName = root["robot"].obj().str("name").orEmpty(),
                recipes = recipes,
                plans = plans,
                pendingSteps = (root.int("pendingSteps") ?: 0).coerceAtLeast(0),
                skipped = skipped,
                screensaver = (root["screensaver"] as? Map<*, *>)?.let { ScreensaverConfig.fromMap(root["screensaver"].obj()) },
            )
        }

        /** Le prochain repas prévu à partir de maintenant ([today] ISO, [nowHm] « HH:mm »). */
        fun nextPlan(plans: List<FoyerPlan>, today: String, nowHm: String): FoyerPlan? =
            plans.filter { it.day > today || (it.day == today && (it.time == null || it.time >= nowHm.take(5))) }
                .minWithOrNull(compareBy<FoyerPlan>({ it.day }, { it.time ?: "99:99" }))

        fun encodePlans(plans: List<FoyerPlan>): String = Json.write(plans.map {
            linkedMapOf("day" to it.day, "time" to it.time, "title" to it.title, "recipeId" to it.recipeId)
        })

        fun decodePlans(text: String): List<FoyerPlan> = try {
            Json.parse(text).list().mapNotNull { pAny ->
                val p = pAny.obj()
                val day = p.str("day") ?: return@mapNotNull null
                FoyerPlan(day, p.str("time"), p.str("title").orEmpty(), p.str("recipeId"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
