package app.toque.ui

import android.content.Context
import android.content.res.AssetManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.toque.R
import app.toque.core.recipes.DishKind

/**
 * Texte d'une vue rafraîchie à chaque instantané de la machine (une dizaine par seconde en
 * cuisson) : posé seulement s'il change, car chaque setText relance une mise en page.
 */
var TextView.shown: CharSequence
    get() = text
    set(v) {
        if (!TextUtils.equals(text, v)) text = v
    }

/**
 * Thèmes (clair le jour, sombre le soir : les couleurs de Foyer), police Montserrat et
 * utilitaires de mise en page, à l'échelle d'un écran de référence 1024×600.
 */
object Ui {
    /** Une palette complète ; l'interface est reconstruite quand elle change (MainActivity.applyTheme). */
    class Palette(
        val dark: Boolean,
        val bg: Int, val surface: Int, val surface2: Int, val surface3: Int, val stroke: Int,
        val text: Int, val text2: Int, val text3: Int,
        val accent: Int, val accentDim: Int, val onAccent: Int,
        val cool: Int, val ok: Int, val warn: Int, val warnDim: Int, val danger: Int, val dangerDim: Int,
        val scrim: Int, val glass: Int, val ripple: Int,
        /** Dégradés des vignettes sans photo (deux couleurs par plat). */
        val placeholders: IntArray,
    )

    /** Jour : crème et terre cuite, comme Foyer en clair. Contrastes vérifiés (AA). */
    val LIGHT_PALETTE = Palette(
        dark = false,
        bg = 0xFFF5F0E8.toInt(), surface = 0xFFFFFFFF.toInt(), surface2 = 0xFFFAF6F0.toInt(), surface3 = 0xFFEEE7DD.toInt(),
        stroke = 0xFFE5DDD2.toInt(),
        text = 0xFF241B14.toInt(), text2 = 0xFF645850.toInt(), text3 = 0xFF7D7168.toInt(),
        accent = 0xFFA54A24.toInt(), accentDim = 0xFFF5E4D6.toInt(), onAccent = 0xFFFFFFFF.toInt(),
        cool = 0xFF2C6A8E.toInt(), ok = 0xFF267B4C.toInt(), warn = 0xFF9A6212.toInt(), warnDim = 0xFFF6E8CC.toInt(),
        danger = 0xFFB83A30.toInt(), dangerDim = 0xFFF8E1DC.toInt(),
        scrim = 0x66241B14, glass = 0xF0FFFFFF.toInt(), ripple = 0x1F241B14,
        placeholders = intArrayOf(
            0xFFF6D29B.toInt(), 0xFFC7713D.toInt(), 0xFFF5CF78.toInt(), 0xFFD48A2C.toInt(),
            0xFFD3E0AE.toInt(), 0xFF7E9A55.toInt(), 0xFFF2B48E.toInt(), 0xFFC4603A.toInt(),
            0xFFE6D9BB.toInt(), 0xFFA8915F.toInt(), 0xFFE8B5C4.toInt(), 0xFFB46A86.toInt(),
            0xFFB9D4DE.toInt(), 0xFF5E8EA3.toInt(), 0xFFEDA48C.toInt(), 0xFFB8452F.toInt(),
        ),
    )

    /** Soir : la palette sombre de Foyer (celle de Toque jusqu'à 1.0.11). */
    val DARK_PALETTE = Palette(
        dark = true,
        bg = 0xFF130E0B.toInt(), surface = 0xFF1F1915.toInt(), surface2 = 0xFF2B2521.toInt(), surface3 = 0xFF362B24.toInt(),
        stroke = 0xFF38312D.toInt(),
        text = 0xFFF1EEE7.toInt(), text2 = 0xFFB0AAA1.toInt(), text3 = 0xFF8E867D.toInt(),
        accent = 0xFFE79363.toInt(), accentDim = 0x29E79363, onAccent = 0xFF1E130E.toInt(),
        cool = 0xFF7FB8D9.toInt(), ok = 0xFF60BB83.toInt(), warn = 0xFFE8B85C.toInt(), warnDim = 0x33E8B85C,
        danger = 0xFFEA6B60.toInt(), dangerDim = 0x2EEA6B60,
        scrim = 0xCC000000.toInt(), glass = 0xEE1F1915.toInt(), ripple = 0x33FFFFFF,
        placeholders = intArrayOf(
            0xFF3A2416.toInt(), 0xFF6B3A1E.toInt(), 0xFF16302A.toInt(), 0xFF2A5246.toInt(),
            0xFF2B2140.toInt(), 0xFF4A3A6B.toInt(), 0xFF3B1E26.toInt(), 0xFF6A3341.toInt(),
            0xFF1C2B3E.toInt(), 0xFF35506E.toInt(), 0xFF35301A.toInt(), 0xFF5E5530.toInt(),
            0xFF1E3320.toInt(), 0xFF3A5C3C.toInt(), 0xFF3A2A1C.toInt(), 0xFF6A4B30.toInt(),
        ),
    )

    @Volatile var palette: Palette = LIGHT_PALETTE
        private set

    val isDark: Boolean get() = palette.dark

    fun usePalette(p: Palette) {
        palette = p
    }

    val BG: Int get() = palette.bg
    val SURFACE: Int get() = palette.surface
    val SURFACE2: Int get() = palette.surface2
    val SURFACE3: Int get() = palette.surface3
    val STROKE: Int get() = palette.stroke
    val TEXT: Int get() = palette.text
    val TEXT2: Int get() = palette.text2
    val TEXT3: Int get() = palette.text3
    val ACCENT: Int get() = palette.accent
    val ACCENT_DIM: Int get() = palette.accentDim
    /** Texte posé sur l'accent. */
    val ON_ACCENT: Int get() = palette.onAccent
    val COOL: Int get() = palette.cool
    val OK: Int get() = palette.ok
    val WARN: Int get() = palette.warn
    val WARN_DIM: Int get() = palette.warnDim
    val DANGER: Int get() = palette.danger
    val DANGER_DIM: Int get() = palette.dangerDim
    val SCRIM: Int get() = palette.scrim
    /** Bandeau posé sur une photo (texte lisible quelle que soit l'image). */
    val GLASS: Int get() = palette.glass

    // ------------------------------------------------------------------ police

    // Avant le chargement de Montserrat (et dans le service du bouton de retour) : celles du système.
    var THIN: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        private set
    var LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        private set
    var REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        private set
    var MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        private set
    var SEMIBOLD: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        private set
    var BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
        private set

    /** Montserrat (licence OFL, assets/fonts), embarquée : l'écran du robot ressemble à Foyer. */
    fun initFonts(assets: AssetManager) {
        fun load(name: String, fallback: Typeface): Typeface = try {
            Typeface.createFromAsset(assets, "fonts/Montserrat-$name.ttf")
        } catch (_: RuntimeException) {
            fallback
        }
        THIN = load("ExtraLight", THIN)
        LIGHT = load("Light", LIGHT)
        REGULAR = load("Regular", REGULAR)
        MEDIUM = load("Medium", MEDIUM)
        SEMIBOLD = load("SemiBold", SEMIBOLD)
        BOLD = load("Bold", BOLD)
    }

    // ------------------------------------------------------------------ échelle

    /** Facteur d'échelle : 1 unité = 1 px sur un écran 1024×600. */
    var scale = 1f

    fun init(widthPx: Int, heightPx: Int) {
        val w = maxOf(widthPx, heightPx)
        val h = minOf(widthPx, heightPx)
        scale = minOf(w / 1024f, h / 600f)
    }

    fun u(v: Float): Int = Math.round(v * scale)
    fun u(v: Int): Int = Math.round(v * scale)
    fun uf(v: Float): Float = v * scale

    // ------------------------------------------------------------------ fonds

    fun round(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Float = 0f): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            setCornerRadius(uf(radius))
            if (strokeWidth > 0) setStroke(maxOf(1, u(strokeWidth)), strokeColor)
        }

    fun pressable(base: Drawable, radius: Float, ripple: Int = palette.ripple): Drawable =
        RippleDrawable(ColorStateList.valueOf(ripple), base, round(Color.WHITE, radius))

    /** Fond de carte : blanc bordé le jour, brun sombre le soir. */
    fun cardBg(radius: Float = 24f, color: Int = SURFACE): GradientDrawable = round(color, radius, STROKE, 1f)

    /** Ombre douce (thème clair seulement : le soir, la bordure suffit). */
    fun lift(v: View, elevation: Float = 3f): View {
        if (!isDark) {
            v.elevation = uf(elevation)
            v.outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        return v
    }

    /** Coins arrondis qui découpent le contenu (photo d'une carte). */
    fun clip(v: View, radius: Float): View {
        v.clipToOutline = true
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, uf(radius))
        }
        return v
    }

    // ------------------------------------------------------------------ textes

    fun text(c: Context, s: CharSequence = "", size: Float = 18f, color: Int = TEXT, face: Typeface = REGULAR): TextView =
        TextView(c).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_PX, uf(size))
            setTextColor(color)
            typeface = face
            includeFontPadding = false
        }

    /** Petit titre en capitales espacées (« CE SOIR · 19 H 30 », « À SAVOIR »). */
    fun overline(c: Context, s: String, color: Int = TEXT2, size: Float = 13f): TextView =
        text(c, s.uppercase(app.toque.core.i18n.I18n.locale), size, color, BOLD).apply { letterSpacing = 0.08f }

    /** Chiffres de même largeur (minuteurs, poids) : rien ne bouge quand ils changent. */
    fun tabular(t: TextView): TextView {
        t.fontFeatureSettings = "tnum"
        return t
    }

    /**
     * Les mots ne sont jamais coupés en deux : si le plus long ne tient pas dans la largeur
     * disponible, la taille du texte baisse (jusqu'à [minSize], unités de l'interface) ; elle
     * revient à sa taille d'origine dès qu'il y a la place. Le retour à la ligne se fait entre
     * les mots.
     */
    fun fitWords(t: TextView, minSize: Float = 12f): TextView {
        val maxPx = t.textSize
        val minPx = uf(minSize)
        t.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val tv = v as TextView
            val avail = (r - l) - tv.totalPaddingLeft - tv.totalPaddingRight
            val words = tv.text.toString().split(' ', '\n').filter { it.isNotEmpty() }
            if (avail <= 0 || words.isEmpty()) return@addOnLayoutChangeListener
            val paint = android.text.TextPaint(tv.paint)
            var size = maxPx
            while (size > minPx) {
                paint.textSize = size
                if (words.all { paint.measureText(it) <= avail }) break
                size = maxOf(minPx, size - uf(0.5f))
            }
            if (Math.abs(size - tv.textSize) > 0.25f) tv.post { tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size) }
        }
        return t
    }

    fun ellipsize(t: TextView, lines: Int = 1): TextView {
        t.maxLines = lines
        t.ellipsize = TextUtils.TruncateAt.END
        return t
    }

    // ------------------------------------------------------------------ mise en page

    fun vertical(c: Context, gravity: Int = Gravity.START): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setGravity(gravity)
    }

    fun horizontal(c: Context, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        setGravity(gravity)
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f,
           l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(u(l), u(t), u(r), u(b)) }

    fun flp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.MATCH_PARENT,
            gravity: Int = Gravity.NO_GRAVITY): FrameLayout.LayoutParams = FrameLayout.LayoutParams(w, h, gravity)

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun pad(v: View, all: Int): View {
        v.setPadding(u(all), u(all), u(all), u(all))
        return v
    }

    fun pad(v: View, h: Int, vert: Int): View {
        v.setPadding(u(h), u(vert), u(h), u(vert))
        return v
    }

    fun scroll(c: Context, child: View): ScrollView = ScrollView(c).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(child)
    }

    fun spacer(c: Context, w: Int = 0, h: Int = 0, weight: Float = 0f): View =
        View(c).apply { layoutParams = LinearLayout.LayoutParams(if (w == 0 && weight > 0) 0 else u(w), u(h), weight) }

    fun divider(c: Context): View = View(c).apply {
        background = ColorDrawable(STROKE)
        layoutParams = LinearLayout.LayoutParams(MATCH, maxOf(1, u(1)))
    }

    // ------------------------------------------------------------------ boutons et pastilles

    enum class Style { PRIMARY, SECONDARY, DANGER, DANGER_SOFT, ACCENT_SOFT, GHOST, COOL }

    /** Bouton arrondi avec icône facultative. */
    fun button(c: Context, label: String, style: Style = Style.SECONDARY, icon: Icon.Kind? = null,
               height: Int = 64, textSize: Float = 18f, onClick: (View) -> Unit): LinearLayout {
        val (bg, fg) = when (style) {
            Style.PRIMARY -> ACCENT to ON_ACCENT
            Style.SECONDARY -> SURFACE3 to TEXT
            Style.DANGER -> DANGER to Color.WHITE
            Style.DANGER_SOFT -> DANGER_DIM to DANGER
            Style.ACCENT_SOFT -> ACCENT_DIM to ACCENT
            Style.GHOST -> Color.TRANSPARENT to TEXT
            Style.COOL -> COOL to (if (isDark) 0xFF04131C.toInt() else Color.WHITE)
        }
        val row = horizontal(c, Gravity.CENTER)
        row.minimumHeight = u(height)
        row.setPadding(u(if (label.isEmpty()) 16 else 22), 0, u(if (label.isEmpty()) 16 else 24), 0)
        val base = if (style == Style.GHOST) round(Color.TRANSPARENT, height / 2f, STROKE, 1.5f) else round(bg, height / 2f)
        row.background = pressable(base, height / 2f)
        if (icon != null) {
            row.addView(Icon(c, icon, fg), LinearLayout.LayoutParams(u(textSize * 1.2f), u(textSize * 1.2f)).apply {
                if (label.isNotEmpty()) rightMargin = u(10)
            })
        }
        if (label.isNotEmpty()) row.addView(text(c, label, textSize, fg, SEMIBOLD))
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener { onClick(it) }
        return row
    }

    /** Bouton rond à icône seule (pause, arrêt, fermer), [size] de côté. */
    fun iconButton(c: Context, icon: Icon.Kind, label: String, style: Style = Style.SECONDARY, size: Int = 48, iconSize: Int = 22,
                   onClick: (View) -> Unit): FrameLayout {
        val (bg, fg) = when (style) {
            Style.PRIMARY -> ACCENT to ON_ACCENT
            Style.DANGER -> DANGER to Color.WHITE
            Style.DANGER_SOFT -> DANGER_DIM to DANGER
            Style.ACCENT_SOFT -> ACCENT_DIM to ACCENT
            Style.GHOST -> Color.TRANSPARENT to TEXT
            Style.COOL -> COOL to Color.WHITE
            Style.SECONDARY -> SURFACE3 to TEXT
        }
        val f = FrameLayout(c)
        f.background = pressable(round(bg, size / 2f), size / 2f)
        f.addView(Icon(c, icon, fg), flp(u(iconSize), u(iconSize), Gravity.CENTER))
        f.contentDescription = label
        f.isClickable = true
        f.isFocusable = true
        f.setOnClickListener { onClick(it) }
        f.minimumWidth = u(size)
        f.minimumHeight = u(size)
        return f
    }

    fun setButtonEnabled(b: View, enabled: Boolean) {
        b.isEnabled = enabled
        b.alpha = if (enabled) 1f else 0.35f
    }

    /** Pastille d'information (icône + texte). */
    fun chip(c: Context, label: String, icon: Icon.Kind? = null, color: Int = TEXT2, bg: Int = SURFACE3, size: Float = 15f): LinearLayout {
        val row = horizontal(c, Gravity.CENTER_VERTICAL)
        row.setPadding(u(12), u(7), u(14), u(7))
        row.background = round(bg, 40f)
        if (icon != null) row.addView(Icon(c, icon, color), LinearLayout.LayoutParams(u(size * 1.15f), u(size * 1.15f)).apply { rightMargin = u(6) })
        // Une puce ne se coupe jamais au milieu d'un mot : trop étroite, elle se termine par « … ».
        row.addView(ellipsize(text(c, label, size, color, MEDIUM)))
        return row
    }

    fun chipText(chip: LinearLayout): TextView = chip.getChildAt(chip.childCount - 1) as TextView

    /** Puce sélectionnable (filtres, choix). */
    fun toggleChip(c: Context, label: String, selected: Boolean, onClick: () -> Unit): TextView {
        val t = text(c, label, 16f, if (selected) ON_ACCENT else TEXT, if (selected) SEMIBOLD else MEDIUM)
        t.setPadding(u(18), u(11), u(18), u(11))
        t.background = pressable(if (selected) round(ACCENT, 40f) else round(SURFACE, 40f, STROKE, 1f), 40f)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    /**
     * Choix en ligne dans un rail (« Normal | Inverse », « Lame | Fouet ») : le choix courant est
     * une pastille claire posée dans le rail.
     */
    fun segmented(c: Context, options: List<String>, selected: Int, height: Int = 48, onPick: (Int) -> Unit): LinearLayout {
        val rail = horizontal(c)
        rail.setPadding(u(4), u(4), u(4), u(4))
        rail.background = round(SURFACE3, height / 2f)
        for ((i, label) in options.withIndex()) {
            val sel = i == selected
            val t = text(c, label, 16f, if (sel) TEXT else TEXT2, if (sel) SEMIBOLD else MEDIUM).apply { gravity = Gravity.CENTER }
            val r = (height - 8) / 2f
            t.background = pressable(if (sel) round(SURFACE, r) else round(Color.TRANSPARENT, r), r)
            if (sel) lift(t, 1.5f)
            t.isClickable = true
            t.setOnClickListener { onPick(i) }
            rail.addView(t, LinearLayout.LayoutParams(0, u(height - 8), 1f).apply { if (i < options.size - 1) rightMargin = u(4) })
        }
        return rail
    }

    /** Pastille ronde d'icône (accent pâle par défaut). */
    fun iconCircle(c: Context, kind: Icon.Kind, size: Int = 42, iconSize: Int = 20, bg: Int = ACCENT_DIM, fg: Int = ACCENT): FrameLayout {
        val f = FrameLayout(c)
        f.background = round(bg, size / 2f)
        f.addView(Icon(c, kind, fg), flp(u(iconSize), u(iconSize), Gravity.CENTER))
        return f
    }

    fun card(c: Context, radius: Float = 24f, color: Int = SURFACE): LinearLayout = vertical(c).apply {
        background = if (color == SURFACE) cardBg(radius) else round(color, radius)
        setPadding(u(20), u(18), u(20), u(18))
    }

    // ------------------------------------------------------------------ valeurs

    fun clock(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format("%02d:%02d", s / 60, s % 60)
    }

    fun duration(seconds: Int): String = when {
        seconds < 60 -> "$seconds s"
        seconds % 60 == 0 -> "${seconds / 60} min"
        else -> "${seconds / 60} min ${seconds % 60} s"
    }

    /** Couleur liée à une température : froid -> bleu, tiède -> ambre, chaud -> accent, très chaud -> rouge. */
    fun tempColor(c: Int): Int = when {
        c >= 100 -> DANGER
        c >= 60 -> ACCENT
        c >= 40 -> WARN
        else -> COOL
    }

    /** Couleurs de repli stables pour une vignette de recette sans photo. */
    fun placeholderColors(seed: String): IntArray {
        val p = palette.placeholders
        val i = Math.abs(seed.hashCode() % (p.size / 2))
        return intArrayOf(p[2 * i], p[2 * i + 1])
    }

    /** Icône du genre de plat (Lucide), blanche, pour une recette sans photo ; à poser sur [placeholderColors]. */
    fun dishIcon(c: Context, title: String = "", category: String = ""): ImageView = ImageView(c).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        imageAlpha = if (isDark) 140 else 190
        setDish(this, title, category)
    }

    fun setDish(icon: ImageView, title: String, category: String = "") = icon.setImageResource(dishDrawable(DishKind.of(title, category)))

    fun dishDrawable(kind: DishKind): Int = when (kind) {
        DishKind.SOUP -> R.drawable.dish_soup
        DishKind.SALAD -> R.drawable.dish_salad
        DishKind.PIZZA -> R.drawable.dish_pizza
        DishKind.SANDWICH -> R.drawable.dish_sandwich
        DishKind.EGG -> R.drawable.dish_egg_fried
        DishKind.COOKIE -> R.drawable.dish_cookie
        DishKind.CAKE -> R.drawable.dish_cake_slice
        DishKind.DESSERT -> R.drawable.dish_ice_cream_cone
        DishKind.COFFEE -> R.drawable.dish_coffee
        DishKind.WINE -> R.drawable.dish_wine
        DishKind.DRINK -> R.drawable.dish_cup_soda
        DishKind.FISH -> R.drawable.dish_fish
        DishKind.POULTRY -> R.drawable.dish_drumstick
        DishKind.MEAT -> R.drawable.dish_beef
        DishKind.POT -> R.drawable.dish_cooking_pot
        DishKind.BREAD -> R.drawable.dish_croissant
        DishKind.VEGETABLE -> R.drawable.dish_carrot
        DishKind.FRUIT -> R.drawable.dish_citrus
        DishKind.DEFAULT -> R.drawable.dish_chef_hat
    }

    fun gradient(colors: IntArray, radius: Float): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { setCornerRadius(uf(radius)) }
}
