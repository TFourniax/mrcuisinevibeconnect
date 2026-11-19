package app.toque.core.recipes

import java.text.Normalizer
import java.util.Locale

/**
 * Le genre d'un plat, pour l'illustrer quand sa recette n'a pas de photo (une icône plutôt que
 * des initiales). Trouvé d'après le titre, puis la catégorie (celles du catalogue Lidl comprises),
 * par mots-clés français et anglais, sans accents : gratuit, hors ligne, pour toutes les recettes.
 * Le premier genre dont un mot-clé apparaît dans le titre l'emporte ; l'ordre va du plus
 * précis (« tarte-cookie » est un cookie, « œuf poché, crème de butternut » un œuf, « coq au
 * vin » une volaille, « cake au jambon » de la viande) au plus vague (légumes, fruits).
 */
enum class DishKind(val icon: String) {
    SOUP("soup"),
    SALAD("salad"),
    PIZZA("pizza"),
    SANDWICH("sandwich"),
    EGG("egg_fried"),
    COOKIE("cookie"),
    CAKE("cake_slice"),
    DESSERT("ice_cream_cone"),
    COFFEE("coffee"),
    WINE("wine"),
    DRINK("cup_soda"),
    FISH("fish"),
    POULTRY("drumstick"),
    MEAT("beef"),
    POT("cooking_pot"),
    BREAD("croissant"),
    VEGETABLE("carrot"),
    FRUIT("citrus"),
    DEFAULT("chef_hat");

    companion object {
        /**
         * Mots-clés par genre, dans l'ordre d'essai. Un mot simple vaut aussi au pluriel (s, x) ;
         * « caramel* » vaut pour tout mot qui commence ainsi ; plusieurs mots se suivent tels quels.
         */
        private val KEYWORDS: List<Pair<DishKind, List<String>>> = listOf(
            SOUP to listOf("soupe", "veloute", "potage", "bouillon", "gaspacho", "gazpacho", "minestrone", "bisque", "consomme",
                "garbure", "chorba", "harira", "pho", "ramen", "soup", "broth", "chowder"),
            SALAD to listOf("salade", "taboule", "coleslaw", "poke", "salad"),
            PIZZA to listOf("pizza", "calzone", "flammekueche", "tarte flambee"),
            SANDWICH to listOf("sandwich", "burger", "hamburger", "wrap", "panini", "croque", "hot dog", "bagel", "club"),
            EGG to listOf("oeuf", "omelette", "frittata", "quiche", "shakshuka", "brouillade", "egg", "omelet"),
            FISH to listOf("poisson", "saumon", "cabillaud", "thon", "truite", "sardine", "maquereau", "colin", "merlu", "lieu",
                "dorade", "daurade", "bar", "sole", "crevette", "gambas", "moule", "saint jacques", "fruits de mer", "calamar",
                "encornet", "poulpe", "homard", "crabe", "anchois", "haddock", "morue", "brandade", "bouillabaisse", "surimi",
                "fish", "salmon", "tuna", "cod", "shrimp", "prawn", "seafood"),
            POULTRY to listOf("poulet", "coq", "volaille", "dinde", "canard", "magret", "pintade", "chapon", "coquelet", "nugget",
                "chicken", "turkey", "duck"),
            MEAT to listOf("boeuf", "veau", "porc", "agneau", "mouton", "steak", "viande", "saucisse", "jambon", "lardon", "chorizo",
                "bacon", "roti", "boulette", "hachis", "bourguignon", "blanquette", "pot au feu", "cote", "filet mignon", "merguez",
                "kebab", "chipolata", "carpaccio", "tartare", "beef", "pork", "lamb", "meat", "ham", "sausage", "meatball"),
            COOKIE to listOf("cookie", "biscuit", "sable", "macaron", "financier", "madeleine", "palmier", "tuile", "speculoos"),
            CAKE to listOf("gateau", "cake", "tarte", "tartelette", "clafoutis", "fondant", "moelleux", "cheesecake", "charlotte",
                "buche", "brownie", "muffin", "cupcake", "quatre quarts", "kouign", "far", "savarin", "baba", "galette des rois",
                "kougelhopf", "stollen", "pie"),
            DESSERT to listOf("dessert", "glace", "sorbet", "creme brulee", "creme dessert", "mousse", "panna cotta", "tiramisu",
                "flan", "compote", "crumble", "riz au lait", "ile flottante", "crepe", "gaufre", "pancake", "beignet", "churro",
                "caramel*", "praline", "nougat", "truffe", "pudding", "ice cream", "fudge", "confiserie", "bonbon"),
            COFFEE to listOf("cafe", "latte", "cappuccino", "matcha", "chai", "infusion", "tisane", "chocolat chaud", "coffee", "tea"),
            WINE to listOf("vin", "sangria", "cocktail", "mojito", "punch", "spritz", "liqueur", "champagne", "biere", "aperitif",
                "wine", "beer", "eggnog", "lait de poule"),
            DRINK to listOf("boisson", "smoothie", "jus", "limonade", "citronnade", "milkshake", "sirop", "orgeat", "lassi",
                "drink", "juice", "lemonade", "shake", "frappe"),
            POT to listOf("pates", "pasta", "spaghetti", "tagliatelle", "penne", "lasagne", "risotto", "riz", "paella", "couscous",
                "curry", "chili", "gratin", "ragout", "mijote", "tajine", "cassoulet", "choucroute", "wok", "nouille", "gnocchi",
                "ravioli", "capellini", "linguine", "macaroni", "coquillette", "fusilli", "farfalle", "orzo", "quinoa", "boulgour",
                "lentille", "dal", "dahl", "tartiflette", "raclette", "fondue", "pesto", "bolognaise", "carbonara",
                "noodles", "rice", "stew", "casserole"),
            BREAD to listOf("pain", "brioche", "baguette", "croissant", "viennoiserie", "focaccia", "naan", "bun", "petit pain",
                "pate brisee", "pate feuilletee", "pate a pizza", "bretzel", "fougasse", "chausson", "pain perdu", "scone",
                "bread", "roll", "loaf"),
            VEGETABLE to listOf("legume", "carotte", "courgette", "aubergine", "poireau", "potiron", "potimarron", "butternut", "courge",
                "brocoli", "chou", "epinard", "haricot", "petits pois", "champignon", "asperge", "ratatouille", "poivron",
                "tomate", "pomme de terre", "patate", "puree", "frite", "aligot", "endive", "artichaut", "betterave", "fenouil",
                "celeri", "navet", "panais", "houmous", "falafel", "vegetable", "veggie", "potato", "mushroom", "asparagus"),
            FRUIT to listOf("fruit", "pomme", "poire", "fraise", "framboise", "citron", "orange", "banane", "mangue", "ananas", "peche",
                "abricot", "cerise", "myrtille", "prune", "figue", "rhubarbe", "confiture", "gelee", "marmelade", "coulis",
                "lemon", "apple", "berry", "jam"),
        )

        /** Catégories (Lidl et autres) quand le titre n'a rien dit. */
        private val CATEGORIES: List<Pair<DishKind, List<String>>> = listOf(
            DRINK to listOf("boisson", "drink", "beverage"),
            SOUP to listOf("soupe", "soup"),
            SALAD to listOf("salade", "salad"),
            BREAD to listOf("pain", "boulangerie", "bread", "baking"),
            CAKE to listOf("patisserie", "gateau", "cake", "pastry"),
            DESSERT to listOf("dessert", "sucre", "sweet"),
            FRUIT to listOf("confiture", "gelee", "jam"),
            VEGETABLE to listOf("garniture", "accompagnement", "legume", "side", "vegetable"),
            FISH to listOf("poisson", "fish"),
            MEAT to listOf("viande", "meat"),
            POT to listOf("plat", "sauce", "main"),
        )

        fun of(title: String, category: String = ""): DishKind {
            val t = Text(title)
            for ((kind, words) in KEYWORDS) if (words.any { t.has(it) }) return kind
            val c = Text(category)
            for ((kind, words) in CATEGORIES) if (words.any { c.has(it) }) return kind
            return DEFAULT
        }

        private val MARKS = Regex("\\p{M}+")
        private val OTHER = Regex("[^a-z0-9]+")

        /** Minuscules sans accents ni ponctuation (« Œuf poché » → « oeuf poche »). */
        fun normalize(s: String): String {
            val lower = s.lowercase(Locale.ROOT).replace("œ", "oe").replace("æ", "ae")
            return Normalizer.normalize(lower, Normalizer.Form.NFD).replace(MARKS, "").replace(OTHER, " ").trim()
        }
    }

    private class Text(raw: String) {
        private val padded = " " + normalize(raw) + " "
        private val words = padded.trim().split(' ').filter { it.isNotEmpty() }.toHashSet()

        fun has(keyword: String): Boolean = when {
            keyword.endsWith("*") -> keyword.dropLast(1).let { stem -> words.any { it.startsWith(stem) } }
            ' ' in keyword -> " $keyword " in padded || " ${keyword}s " in padded
            else -> keyword in words || "${keyword}s" in words || "${keyword}x" in words
        }
    }
}
