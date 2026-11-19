package app.toque.core

import app.toque.core.recipes.DishKind
import org.junit.Assert.assertEquals
import org.junit.Test

class DishKindTest {
    @Test
    fun householdRecipes() {
        assertEquals(DishKind.POT, DishKind.of("Capellini au beurre et au citron"))
        assertEquals(DishKind.COOKIE, DishKind.of("Tarte-cookie façon churro fourrée au chocolat"))
        assertEquals(DishKind.EGG, DishKind.of("Œuf poché, crème de butternut et lard"))
    }

    @Test
    fun lidlCatalogue() {
        assertEquals(DishKind.MEAT, DishKind.of("Agneau de Pâques en petits pains farcis", "Snacks"))
        assertEquals(DishKind.FISH, DishKind.of("Aïoli de cabillaud", "Plats principaux"))
        assertEquals(DishKind.VEGETABLE, DishKind.of("Aligot", "Garnitures"))
        assertEquals(DishKind.DESSERT, DishKind.of("Amandes caramélisées", "Snacks"))
        assertEquals(DishKind.VEGETABLE, DishKind.of("Asperges à la sauce hollandaise", "Garnitures"))
        assertEquals(DishKind.BREAD, DishKind.of("Anneaux de pain au sésame", "Snacks"))
        assertEquals(DishKind.DEFAULT, DishKind.of("Antipasti", "Snacks"))
    }

    @Test
    fun orderPicksTheMostTellingWord() {
        assertEquals(DishKind.POULTRY, DishKind.of("Coq au vin"))
        assertEquals(DishKind.WINE, DishKind.of("Vin chaud"))
        assertEquals(DishKind.MEAT, DishKind.of("Cake au jambon et aux olives"))
        assertEquals(DishKind.DESSERT, DishKind.of("Riz au lait"))
        assertEquals(DishKind.POT, DishKind.of("Risotto aux champignons"))
        assertEquals(DishKind.FISH, DishKind.of("Coquilles Saint-Jacques gratinées"))
        assertEquals(DishKind.SOUP, DishKind.of("Velouté de potimarron"))
        assertEquals(DishKind.DRINK, DishKind.of("Smoothie banane-fraise"))
        assertEquals(DishKind.COFFEE, DishKind.of("Chocolat chaud"))
        assertEquals(DishKind.CAKE, DishKind.of("Gâteaux au yaourt"))
        assertEquals(DishKind.FRUIT, DishKind.of("Confiture de fraises"))
    }

    @Test
    fun categoryWhenTheTitleSaysNothing() {
        assertEquals(DishKind.DRINK, DishKind.of("Hugo", "Boissons"))
        assertEquals(DishKind.FRUIT, DishKind.of("Mélange de la forêt", "Confitures & gelées"))
        assertEquals(DishKind.POT, DishKind.of("Königsberger Klopse", "Plats principaux"))
        assertEquals(DishKind.BREAD, DishKind.of("Bretzels bavarois maison", "Cuisine allemande"))
        assertEquals(DishKind.DEFAULT, DishKind.of("Leberkäse", "Cuisine allemande"))
        assertEquals(DishKind.DEFAULT, DishKind.of(""))
    }

    @Test
    fun wordsNotSubstrings() {
        // « coq » n'est pas « coquillettes », « bar » n'est pas « barbecue », « pâte » n'est pas « pâtes ».
        assertEquals(DishKind.MEAT, DishKind.of("Coquillettes au jambon"))
        assertEquals(DishKind.POT, DishKind.of("Coquillettes à la crème"))
        assertEquals(DishKind.DEFAULT, DishKind.of("Sauce barbecue maison"))
        assertEquals(DishKind.DEFAULT, DishKind.of("Pâté en croûte"))
        assertEquals("oeuf poche creme", DishKind.normalize("Œuf poché, crème !"))
    }
}
