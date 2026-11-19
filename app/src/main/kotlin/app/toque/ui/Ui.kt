package app.toque.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.toque.R
import app.toque.core.recipes.DishKind

/** Thème sombre (couleurs de Foyer) et utilitaires de mise en page, à l'échelle d'un écran de référence 1024×600. */
object Ui {
    // Palette de Foyer en mode sombre (tons chauds, accent terre cuite) : le robot et le
    // téléphone se ressemblent.
    const val BG = 0xFF130E0B.toInt()
    const val SURFACE = 0xFF1F1915.toInt()
    const val SURFACE2 = 0xFF2B2521.toInt()
    const val SURFACE3 = 0xFF362B24.toInt()
    const val STROKE = 0xFF38312D.toInt()
    const val TEXT = 0xFFF1EEE7.toInt()
    const val TEXT2 = 0xFFB0AAA1.toInt()
    const val TEXT3 = 0xFF7E766E.toInt()
    const val ACCENT = 0xFFE79363.toInt()
    const val ACCENT_DIM = 0x29E79363
    /** Texte posé sur l'accent. */
    const val ON_ACCENT = 0xFF1E130E.toInt()
    const val COOL = 0xFF7FB8D9.toInt()
    const val OK = 0xFF60BB83.toInt()
    const val WARN = 0xFFE8B85C.toInt()
    const val WARN_DIM = 0x33E8B85C
    const val DANGER = 0xFFEA6B60.toInt()
    const val SCRIM = 0xCC000000.toInt()

    val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val THIN: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

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

    fun round(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Float = 0f): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            setCornerRadius(uf(radius))
            if (strokeWidth > 0) setStroke(u(strokeWidth), strokeColor)
        }

    fun pressable(base: Drawable, radius: Float, ripple: Int = 0x33FFFFFF): Drawable =
        RippleDrawable(ColorStateList.valueOf(ripple), base, round(Color.WHITE, radius))

    fun text(c: Context, s: CharSequence = "", size: Float = 18f, color: Int = TEXT, face: Typeface = REGULAR): TextView =
        TextView(c).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_PX, uf(size))
            setTextColor(color)
            typeface = face
            includeFontPadding = false
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

    enum class Style { PRIMARY, SECONDARY, DANGER, GHOST, COOL }

    /** Bouton arrondi avec icône facultative. */
    fun button(c: Context, label: String, style: Style = Style.SECONDARY, icon: Icon.Kind? = null,
               height: Int = 64, textSize: Float = 20f, onClick: (View) -> Unit): LinearLayout {
        val (bg, fg) = when (style) {
            Style.PRIMARY -> ACCENT to ON_ACCENT
            Style.SECONDARY -> SURFACE3 to TEXT
            Style.DANGER -> DANGER to Color.WHITE
            Style.GHOST -> Color.TRANSPARENT to TEXT2
            Style.COOL -> COOL to 0xFF04131C.toInt()
        }
        val row = horizontal(c, Gravity.CENTER)
        row.minimumHeight = u(height)
        row.setPadding(u(22), 0, u(22), 0)
        val base = if (style == Style.GHOST) round(Color.TRANSPARENT, height / 2f, STROKE, 1.5f) else round(bg, height / 2f)
        row.background = pressable(base, height / 2f)
        if (icon != null) {
            row.addView(Icon(c, icon, fg), LinearLayout.LayoutParams(u(textSize * 1.3f), u(textSize * 1.3f)).apply {
                if (label.isNotEmpty()) rightMargin = u(10)
            })
        }
        if (label.isNotEmpty()) row.addView(text(c, label, textSize, fg, MEDIUM))
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener { onClick(it) }
        return row
    }

    fun setButtonEnabled(b: View, enabled: Boolean) {
        b.isEnabled = enabled
        b.alpha = if (enabled) 1f else 0.35f
    }

    /** Pastille d'information (icône + texte). */
    fun chip(c: Context, label: String, icon: Icon.Kind? = null, color: Int = TEXT2, bg: Int = SURFACE2, size: Float = 16f): LinearLayout {
        val row = horizontal(c, Gravity.CENTER_VERTICAL)
        row.setPadding(u(12), u(6), u(14), u(6))
        row.background = round(bg, 40f)
        if (icon != null) row.addView(Icon(c, icon, color), LinearLayout.LayoutParams(u(size * 1.2f), u(size * 1.2f)).apply { rightMargin = u(6) })
        row.addView(text(c, label, size, color, MEDIUM))
        return row
    }

    fun chipText(chip: LinearLayout): TextView = chip.getChildAt(chip.childCount - 1) as TextView

    /** Puce sélectionnable (filtres, choix). */
    fun toggleChip(c: Context, label: String, selected: Boolean, onClick: () -> Unit): TextView {
        val t = text(c, label, 17f, if (selected) ON_ACCENT else TEXT, MEDIUM)
        t.setPadding(u(18), u(10), u(18), u(10))
        t.background = pressable(if (selected) round(ACCENT, 40f) else round(SURFACE2, 40f, STROKE, 1f), 40f)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    fun card(c: Context, radius: Float = 24f, color: Int = SURFACE): LinearLayout = vertical(c).apply {
        background = round(color, radius)
        setPadding(u(20), u(18), u(20), u(18))
    }

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

    /** Couleur liée à une température : froid -> bleu, tiède -> orange, chaud -> rouge. */
    fun tempColor(c: Int): Int = when {
        c >= 90 -> DANGER
        c >= 60 -> ACCENT
        c >= 40 -> WARN
        else -> COOL
    }

    /** Couleur de repli stable pour une vignette de recette sans photo. */
    fun placeholderColors(seed: String): IntArray {
        val palette = intArrayOf(
            0xFF3A2416.toInt(), 0xFF16302A.toInt(), 0xFF2B2140.toInt(), 0xFF3B1E26.toInt(),
            0xFF1C2B3E.toInt(), 0xFF35301A.toInt(), 0xFF1E3320.toInt(), 0xFF3A2A1C.toInt(),
        )
        val i = Math.abs(seed.hashCode()) % palette.size
        val a = palette[i]
        val b = palette[(i + 3) % palette.size]
        return intArrayOf(a, b)
    }

    /** Icône du genre de plat (Lucide), blanche à 55 %, pour une recette sans photo ; à poser sur [placeholderColors]. */
    fun dishIcon(c: Context, title: String = "", category: String = ""): ImageView = ImageView(c).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        imageAlpha = 140
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
