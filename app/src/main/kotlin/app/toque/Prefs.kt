package app.toque

import android.content.Context
import android.content.SharedPreferences
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.i18n.trKey

/**
 * Réglages persistants, lisibles par les deux processus (interface et machine).
 * Écriture synchrone (commit) : le processus machine relit le fichier juste après.
 */
class Prefs(context: Context) {
    @Suppress("DEPRECATION")
    private val p: SharedPreferences = context.getSharedPreferences("toque", Context.MODE_MULTI_PROCESS)

    enum class Mode(val key: String, private val fr: String) {
        DEMO("demo", trKey("Démo (base simulée)")),
        TWIN("twin", trKey("Jumeau numérique (PC)")),
        HARDWARE("hardware", trKey("Machine réelle"));

        val label: String get() = tr(fr)

        companion object {
            fun of(k: String?) = values().firstOrNull { it.key == k } ?: DEMO
        }
    }

    var mode: Mode
        get() = Mode.of(p.getString("mode", null))
        set(v) {
            p.edit().putString("mode", v.key).commit()
        }

    /** Langue de l'interface : « auto » (celle du système), « fr » ou « en ». */
    var language: String
        get() = p.getString("language", I18n.AUTO) ?: I18n.AUTO
        set(v) {
            p.edit().putString("language", v).commit()
        }

    var twinHost: String
        get() = p.getString("twinHost", "127.0.0.1") ?: "127.0.0.1"
        set(v) {
            p.edit().putString("twinHost", v).commit()
        }

    var twinPort: Int
        get() = p.getInt("twinPort", 8765)
        set(v) {
            p.edit().putInt("twinPort", v).commit()
        }

    var serialDevice: String
        get() = p.getString("serialDevice", "/dev/ttyMT0") ?: "/dev/ttyMT0"
        set(v) {
            p.edit().putString("serialDevice", v).commit()
        }

    var vendorPackage: String
        get() = p.getString("vendorPackage", "de.silpion.mc2") ?: "de.silpion.mc2"
        set(v) {
            p.edit().putString("vendorPackage", v).commit()
        }

    var requireBowl: Boolean
        get() = p.getBoolean("requireBowl", false)
        set(v) {
            p.edit().putBoolean("requireBowl", v).commit()
        }

    /** Montée progressive de la vitesse quand le contenu est chaud (≥ 60 °C). */
    var hotRamp: Boolean
        get() = p.getBoolean("hotRamp", true)
        set(v) {
            p.edit().putBoolean("hotRamp", v).commit()
        }

    /** Recettes : enchaîner sans appui les étapes machine qui se suivent sans geste à faire. */
    var chainSteps: Boolean
        get() = p.getBoolean("chainSteps", true)
        set(v) {
            p.edit().putBoolean("chainSteps", v).commit()
        }

    var sound: Boolean
        get() = p.getBoolean("sound", true)
        set(v) {
            p.edit().putBoolean("sound", v).commit()
        }

    /** Fuseau horaire : « auto » (d'après la connexion Internet) ou un identifiant IANA choisi à la main. */
    var timeZone: String
        get() = p.getString("timeZone", "auto") ?: "auto"
        set(v) {
            p.edit().putString("timeZone", v).commit()
        }

    /** Dernier fuseau appliqué avec succès (affiché dans les réglages). */
    var lastZone: String
        get() = p.getString("lastZone", "") ?: ""
        set(v) {
            p.edit().putString("lastZone", v).commit()
        }

    var autoSync: Boolean
        get() = p.getBoolean("autoSync", true)
        set(v) {
            p.edit().putBoolean("autoSync", v).commit()
        }

    var lastSyncMs: Long
        get() = p.getLong("lastSyncMs", 0)
        set(v) {
            p.edit().putLong("lastSyncMs", v).commit()
        }

    var lastSyncResult: String
        get() = p.getString("lastSyncResult", "") ?: ""
        set(v) {
            p.edit().putString("lastSyncResult", v).commit()
        }

    var vendorSession: Boolean
        get() = p.getBoolean("vendorSession", false)
        set(v) {
            p.edit().putBoolean("vendorSession", v).commit()
        }

    /** Bouton « Retour à Toque » à afficher : Toque a ouvert une autre appli (robot sans bouton Accueil). */
    var returnBubble: Boolean
        get() = p.getBoolean("returnBubble", false)
        set(v) {
            p.edit().putBoolean("returnBubble", v).commit()
        }

    /** Ouvrir Toque au démarrage du robot (désactivé par « Revenir à l'appli d'origine »). */
    var bootLaunch: Boolean
        get() = p.getBoolean("bootLaunch", true)
        set(v) {
            p.edit().putBoolean("bootLaunch", v).commit()
        }

    var knobLeft: Int
        get() = p.getInt("knobLeft", -1)
        set(v) {
            p.edit().putInt("knobLeft", v).commit()
        }

    var knobRight: Int
        get() = p.getInt("knobRight", -1)
        set(v) {
            p.edit().putInt("knobRight", v).commit()
        }

    var knobPress: Int
        get() = p.getInt("knobPress", -1)
        set(v) {
            p.edit().putInt("knobPress", v).commit()
        }

    var lastCrashMs: Long
        get() = p.getLong("lastCrashMs", 0)
        set(v) {
            p.edit().putLong("lastCrashMs", v).commit()
        }

    var welcomeDone: Boolean
        get() = p.getBoolean("welcomeDone", false)
        set(v) {
            p.edit().putBoolean("welcomeDone", v).commit()
        }

    var builtinVersion: Int
        get() = p.getInt("builtinVersion", 0)
        set(v) {
            p.edit().putInt("builtinVersion", v).commit()
        }

    /** Recette guidée en cours (id + étape), pour reprendre après un redémarrage. */
    var guidedRecipe: String
        get() = p.getString("guidedRecipe", "") ?: ""
        set(v) {
            p.edit().putString("guidedRecipe", v).commit()
        }

    var guidedStep: Int
        get() = p.getInt("guidedStep", 0)
        set(v) {
            p.edit().putInt("guidedStep", v).commit()
        }

    // ---- Foyer (appli du foyer) : liaison durable et dernière synchro des recettes partagées.

    var foyerEnabled: Boolean
        get() = p.getBoolean("foyerEnabled", true)
        set(v) {
            p.edit().putBoolean("foyerEnabled", v).commit()
        }

    /** Nom du foyer auquel le robot est relié ("" : pas relié). */
    var foyerHousehold: String
        get() = p.getString("foyerHousehold", "") ?: ""
        set(v) {
            p.edit().putString("foyerHousehold", v).commit()
        }

    var foyerRobotName: String
        get() = p.getString("foyerRobotName", "") ?: ""
        set(v) {
            p.edit().putString("foyerRobotName", v).commit()
        }

    /** Code de liaison affiché tant que le robot n'est pas relié, et sa fin de validité. */
    var foyerCode: String
        get() = p.getString("foyerCode", "") ?: ""
        set(v) {
            p.edit().putString("foyerCode", v).commit()
        }

    var foyerCodeUntil: Long
        get() = p.getLong("foyerCodeUntil", 0)
        set(v) {
            p.edit().putLong("foyerCodeUntil", v).commit()
        }

    var foyerEtag: String
        get() = p.getString("foyerEtag", "") ?: ""
        set(v) {
            p.edit().putString("foyerEtag", v).commit()
        }

    var foyerLastSyncMs: Long
        get() = p.getLong("foyerLastSyncMs", 0)
        set(v) {
            p.edit().putLong("foyerLastSyncMs", v).commit()
        }

    var foyerLastResult: String
        get() = p.getString("foyerLastResult", "") ?: ""
        set(v) {
            p.edit().putString("foyerLastResult", v).commit()
        }

    /** Repas prévus dans Foyer (JSON), pour l'accueil. */
    var foyerPlans: String
        get() = p.getString("foyerPlans", "[]") ?: "[]"
        set(v) {
            p.edit().putString("foyerPlans", v).commit()
        }

    /**
     * Mises à jour installées toutes seules la nuit (robot rooté, machine à l'arrêt, version
     * publiée depuis 2 jours). Coupé par défaut : une nouvelle version s'installe d'un geste,
     * devant le robot, quand on a le temps de vérifier qu'elle marche.
     */
    var autoUpdate: Boolean
        get() = p.getBoolean("autoUpdate", false)
        set(v) {
            p.edit().putBoolean("autoUpdate", v).commit()
        }

    /** Empreinte du dernier contenu reçu de Foyer : rien à ranger s'il n'a pas changé. */
    var foyerFeedHash: String
        get() = p.getString("foyerFeedHash", "") ?: ""
        set(v) {
            p.edit().putString("foyerFeedHash", v).commit()
        }

    /** Photos des recettes du foyer : nom du fichier → adresse (JSON), pour reprendre celles qui manquent. */
    var foyerPhotos: String
        get() = p.getString("foyerPhotos", "{}") ?: "{}"
        set(v) {
            p.edit().putString("foyerPhotos", v).commit()
        }

    /** Version de Toque qui a rangé le dernier contenu de Foyer (une nouvelle version le reprend en entier). */
    var foyerAppliedVersion: String
        get() = p.getString("foyerAppliedVersion", "") ?: ""
        set(v) {
            p.edit().putString("foyerAppliedVersion", v).commit()
        }

    /** Écran de veille choisi dans Foyer (JSON de ScreensaverConfig ; vide : l'horloge après 5 min). */
    var screensaver: String
        get() = p.getString("screensaver", "") ?: ""
        set(v) {
            p.edit().putString("screensaver", v).commit()
        }

    /** Statut des agents : dernière liste reçue de Foyer (JSON d'Agents.encode). */
    var agentsCache: String
        get() = p.getString("agentsCache", "") ?: ""
        set(v) {
            p.edit().putString("agentsCache", v).commit()
        }

    /** Dernière réponse de Foyer pour les agents (ms). */
    var agentsLastOkMs: Long
        get() = p.getLong("agentsLastOkMs", 0L)
        set(v) {
            p.edit().putLong("agentsLastOkMs", v).apply()
        }

    /** Dernière ouverture de la carte des agents (ms) : un « terminé » vu ne colore plus l'icône. */
    var agentsSeenMs: Long
        get() = p.getLong("agentsSeenMs", 0L)
        set(v) {
            p.edit().putLong("agentsSeenMs", v).commit()
        }

    /** Voyant de /sys/class/leds désigné comme l'anneau du bouton rotatif ("" : pas encore trouvé). */
    var ringLed: String
        get() = p.getString("ringLed", "") ?: ""
        set(v) {
            p.edit().putString("ringLed", v).commit()
        }

    /** Anneau lumineux : « auto » (quand on se sert du robot), « on » ou « off » (RingPolicy). */
    var ringMode: String
        get() = p.getString("ringMode", "auto") ?: "auto"
        set(v) {
            p.edit().putString("ringMode", v).commit()
        }

    /** Les trois voyants de l'anneau RVB, trouvés tout seuls (« red,green,blue » ; "" : pas de jeu RVB). */
    var ringRgb: String
        get() = p.getString("ringRgb", "") ?: ""
        set(v) {
            p.edit().putString("ringRgb", v).commit()
        }

    /** Couleur de l'anneau (RingColors : « white » par défaut, comme l'appli d'origine). */
    var ringColor: String
        get() = p.getString("ringColor", "white") ?: "white"
        set(v) {
            p.edit().putString("ringColor", v).commit()
        }

    /** Force de l'anneau : « low », « medium » ou « high » (par défaut, comme l'appli d'origine). */
    var ringIntensity: String
        get() = p.getString("ringIntensity", "high") ?: "high"
        set(v) {
            p.edit().putString("ringIntensity", v).commit()
        }

    /** Couleurs selon l'état (cuisson orange, étape bleue, prêt vert, alerte rouge clignotant). */
    var ringByState: Boolean
        get() = p.getBoolean("ringByState", false)
        set(v) {
            p.edit().putBoolean("ringByState", v).commit()
        }

    /** État des voyants avant que Toque 1.0.9 y touche (Leds.encodeOriginal), pour « Réinitialiser l'anneau ». */
    var ringOriginal: String
        get() = p.getString("ringOriginal", "") ?: ""
        set(v) {
            p.edit().putString("ringOriginal", v).commit()
        }

    /** Dernière version publiée plus récente que celle installée ("" : aucune), vue la nuit. */
    var updateAvailable: String
        get() = p.getString("updateAvailable", "") ?: ""
        set(v) {
            p.edit().putString("updateAvailable", v).commit()
        }

    // ------------------------------------------------------------------ apparence (1.0.12)

    /** Thème : « auto » (clair de 7 h à 20 h, sombre ensuite), « light » ou « dark ». */
    var themeMode: String
        get() = p.getString("themeMode", "auto") ?: "auto"
        set(v) {
            p.edit().putString("themeMode", v).commit()
        }

    /** Choix fait à la lune ou au soleil en automatique : « dark », « light » ou "" (aucun). */
    var themeOverride: String
        get() = p.getString("themeOverride", "") ?: ""
        set(v) {
            p.edit().putString("themeOverride", v).commit()
        }

    /** Jusqu'à quand le choix fait à la lune tient (heure murale, ms). */
    var themeOverrideUntil: Long
        get() = p.getLong("themeOverrideUntil", 0)
        set(v) {
            p.edit().putLong("themeOverrideUntil", v).commit()
        }

    // ------------------------------------------------------------------ « À savoir » (1.0.12)

    /** Évènements signalés sous la cloche (JSON, NoticeLog). */
    var noticeLog: String
        get() = p.getString("noticeLog", "") ?: ""
        set(v) {
            p.edit().putString("noticeLog", v).commit()
        }

    /** Clés des messages d'état déjà vus (mise à jour proposée…), séparées par des retours à la ligne. */
    var noticeRead: String
        get() = p.getString("noticeRead", "") ?: ""
        set(v) {
            p.edit().putString("noticeRead", v).commit()
        }

    /** Recettes du foyer déjà connues (identifiants, un par ligne) : les autres sont « nouvelles ». */
    var noticeRecipes: String
        get() = p.getString("noticeRecipes", "") ?: ""
        set(v) {
            p.edit().putString("noticeRecipes", v).commit()
        }

    /** Repas prévus déjà vus (même format que foyerPlans). */
    var noticePlans: String
        get() = p.getString("noticePlans", "") ?: ""
        set(v) {
            p.edit().putString("noticePlans", v).commit()
        }

    /** Nombre d'animations de l'écran de veille déjà vues. */
    var noticeSaverItems: Int
        get() = p.getInt("noticeSaverItems", -1)
        set(v) {
            p.edit().putInt("noticeSaverItems", v).commit()
        }
}
