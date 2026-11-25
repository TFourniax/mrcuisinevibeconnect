package app.toque.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr

/**
 * Carte de réglage : libellé, grande valeur, ligne d'aide, une jauge facultative ([extra]) et les
 * boutons − / +. Au bouton rotatif, un appui sur la carte entre dans le réglage, tourner fait
 * − / +, un second appui en sort.
 */
class ValueCard(c: Context, label: String, icon: Icon.Kind) : LinearLayout(c), KnobAdjustable {
    private val valueView = Ui.tabular(Ui.text(c, "", 72f, Ui.TEXT, Ui.LIGHT)).apply { includeFontPadding = false }
    private val unitView = Ui.text(c, "", 32f, Ui.TEXT, Ui.LIGHT)
    private val subView = Ui.ellipsize(Ui.text(c, "", 14f, Ui.TEXT2).apply { gravity = Gravity.CENTER })
    private val iconView = Icon(c, icon, Ui.TEXT2)
    private val labelView = Ui.text(c, label, 15f, Ui.TEXT2, Ui.SEMIBOLD)
    /** Jauge ou raccourcis propres à la carte (posés sous la valeur). */
    val extra: FrameLayout = FrameLayout(c)
    private val minus: View
    private val plus: View
    var onMinus: () -> Unit = {}
    var onPlus: () -> Unit = {}
    var onSelect: () -> Unit = {}
    /** Répétition à l'appui long (désactivée pendant une cuisson pour vitesse et température). */
    var repeatEnabled = true
    private var isSel = false
    private var editable = true

    init {
        orientation = VERTICAL
        setGravity(Gravity.CENTER_HORIZONTAL)
        setPadding(Ui.u(18), Ui.u(16), Ui.u(18), Ui.u(18))
        val head = Ui.horizontal(c)
        head.addView(iconView, LayoutParams(Ui.u(18), Ui.u(18)).apply { rightMargin = Ui.u(8) })
        head.addView(labelView)
        addView(head, Ui.lp(Ui.MATCH, Ui.WRAP))
        val row = Ui.horizontal(c, Gravity.BOTTOM)
        row.addView(valueView)
        row.addView(unitView, Ui.lp(Ui.WRAP, Ui.WRAP, l = 4, b = 8))
        addView(row, Ui.lp(Ui.WRAP, Ui.WRAP, t = 10))
        addView(subView, Ui.lp(Ui.MATCH, Ui.WRAP, t = 6))
        addView(extra, Ui.lp(Ui.MATCH, Ui.WRAP, t = 8))
        addView(Ui.spacer(c, h = 0, weight = 1f))
        val buttons = Ui.horizontal(c)
        minus = Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.MINUS, height = 56, textSize = 20f) { onSelect(); onMinus() }
        plus = Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.PLUS, height = 56, textSize = 20f) { onSelect(); onPlus() }
        minus.contentDescription = tr("Moins")
        plus.contentDescription = tr("Plus")
        repeatOnHold(minus, { repeatEnabled }) { onMinus() }
        repeatOnHold(plus, { repeatEnabled }) { onPlus() }
        buttons.addView(minus, LayoutParams(Ui.u(68), Ui.u(56)))
        buttons.addView(Ui.spacer(c, weight = 1f), LayoutParams(0, 1, 1f))
        buttons.addView(plus, LayoutParams(Ui.u(68), Ui.u(56)))
        addView(buttons, Ui.lp(Ui.MATCH, Ui.WRAP, t = 10))
        isClickable = true
        setOnClickListener { onSelect() }
        refreshBg()
    }

    fun set(value: String, unit: String = "", sub: String = "", color: Int = Ui.TEXT) {
        valueView.shown = value
        valueView.setTextColor(color)
        unitView.shown = unit
        unitView.setTextColor(color)
        subView.shown = sub
    }

    fun select(sel: Boolean) {
        if (sel == isSel) return
        isSel = sel
        refreshBg()
    }

    fun setEditable(on: Boolean) {
        editable = on
        Ui.setButtonEnabled(minus, on)
        Ui.setButtonEnabled(plus, on)
    }

    override fun knobBegin() = onSelect()

    /** Les mêmes gestes que − et + : les limites et règles de l'écran s'appliquent. */
    override fun knobAdjust(delta: Int) {
        if (!editable) return
        onSelect()
        if (delta > 0) onPlus() else onMinus()
    }

    private fun refreshBg() {
        background = Ui.round(Ui.SURFACE, 24f, if (isSel) Ui.ACCENT else Ui.STROKE, if (isSel) 2.5f else 1f)
        Ui.lift(this)
        iconView.color = if (isSel) Ui.ACCENT else Ui.TEXT2
        labelView.setTextColor(if (isSel) Ui.ACCENT else Ui.TEXT2)
    }

    companion object {
        /**
         * Répète l'action tant que le bouton reste appuyé (après 450 ms). S'arrête de lui-même si
         * le bouton n'est plus enfoncé ou après 40 répétitions, même si le relâchement est perdu.
         */
        fun repeatOnHold(v: View, enabled: () -> Boolean = { true }, action: () -> Unit) {
            var count = 0
            val r = object : Runnable {
                override fun run() {
                    if (!v.isPressed || !enabled() || count >= 40) return
                    count++
                    action()
                    v.postDelayed(this, 110)
                }
            }
            v.setOnTouchListener { view, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        count = 0
                        view.removeCallbacks(r)
                        view.postDelayed(r, 450)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.removeCallbacks(r)
                }
                false
            }
        }
    }
}

/** Jauge de température : un dégradé du froid au chaud, et un repère sur la consigne (0 : sans chauffe). */
class TempGauge(c: Context) : View(c) {
    /** Position du repère, de 0 (sans chauffe) à 1 (130 °C). */
    var position = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        val bar = Ui.uf(10f)
        val knob = Ui.uf(12f)
        val cy = height / 2f
        r.set(knob, cy - bar / 2, width - knob, cy + bar / 2)
        paint.shader = android.graphics.LinearGradient(r.left, 0f, r.right, 0f,
            intArrayOf(Ui.COOL, Ui.WARN, Ui.ACCENT, Ui.DANGER), floatArrayOf(0f, 0.45f, 0.75f, 1f), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRoundRect(r, bar / 2, bar / 2, paint)
        paint.shader = null
        val x = r.left + (r.right - r.left) * position
        paint.color = Ui.SURFACE
        canvas.drawCircle(x, cy, knob, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Ui.uf(3f)
        paint.color = if (position > 0f) Ui.ACCENT else Ui.TEXT3
        canvas.drawCircle(x, cy, knob - Ui.uf(1.5f), paint)
        paint.style = Paint.Style.FILL
    }
}

/** Les dix vitesses en barres montantes : remplies jusqu'à la vitesse, pâles au-delà de la limite permise. */
class SpeedBars(c: Context) : View(c) {
    private var speed = 0
    private var max = 10
    /** Vitesse réelle du moteur pendant une cuisson (-1 : à l'arrêt, seule la consigne compte). */
    private var actual = -1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    fun set(speed: Int, max: Int) {
        if (speed == this.speed && max == this.max) return
        this.speed = speed
        this.max = max
        invalidate()
    }

    /** En marche : les barres pleines suivent le moteur, la consigne pas encore atteinte reste pâle. */
    fun setActual(level: Int) {
        if (level == actual) return
        actual = level
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val gap = Ui.uf(5f)
        val w = (width - 9 * gap) / 10f
        for (i in 1..10) {
            val h = height * (0.3f + 0.07f * (i - 1))
            val x = (i - 1) * (w + gap)
            r.set(x, height - h, x + w, height.toFloat())
            // Consigne pas encore atteinte : l'accent à moitié transparent (même rendu dans les deux thèmes).
            val pending = actual >= 0 && i > actual && i <= speed
            paint.color = if (i <= speed) Ui.ACCENT else Ui.SURFACE3
            paint.alpha = when {
                pending -> 90
                i > max -> 110
                else -> 255
            }
            canvas.drawRoundRect(r, Ui.uf(3f), Ui.uf(3f), paint)
        }
        paint.alpha = 255
    }
}

/** Bouton « maintenir pour Turbo » : bordé au repos, ambre tant qu'on appuie. */
class HoldButton(c: Context, label: String, private val onDown: () -> Unit, private val onUp: () -> Unit) : LinearLayout(c) {
    private val text: TextView
    private val icon: Icon

    init {
        orientation = HORIZONTAL
        setGravity(Gravity.CENTER)
        setPadding(Ui.u(20), 0, Ui.u(20), 0)
        minimumHeight = Ui.u(48)
        icon = Icon(c, Icon.Kind.BOLT, Ui.WARN)
        addView(icon, LayoutParams(Ui.u(20), Ui.u(20)).apply { rightMargin = Ui.u(8) })
        text = Ui.fitWords(Ui.text(c, label, 16f, Ui.TEXT, Ui.MEDIUM), 12f)
        addView(text)
        isClickable = true
        rest()
    }

    private fun rest() {
        background = Ui.round(Ui.SURFACE, 30f, Ui.STROKE, 1.5f)
        text.setTextColor(Ui.TEXT)
        icon.color = Ui.WARN
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                background = Ui.round(Ui.WARN, 30f)
                val on = if (Ui.isDark) 0xFF1A1405.toInt() else android.graphics.Color.WHITE
                text.setTextColor(on)
                icon.color = on
                onDown()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                rest()
                onUp()
            }
        }
        return true
    }
}
