package com.kyronapp.kyron.ui

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.kyronapp.kyron.R
import com.kyronapp.kyron.data.NationalCoverage
import com.kyronapp.kyron.data.Place
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared UI helpers for the placard design system: tone colors,
 *  motion gating, formatting, and small physics. */
object Ui {

    fun dp(ctx: Context, d: Int): Int =
        (d * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun reduceMotion(ctx: Context): Boolean =
        Settings.Global.getFloat(
            ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f

    /** 0 neutral, 1 good, 2 fair/warn, 3 bad — matches numericTone/resultTone. */
    fun toneColor(ctx: Context, tone: Int): Int = ContextCompat.getColor(
        ctx, when (tone) {
            1 -> R.color.tone_good
            2 -> R.color.tone_ok
            3 -> R.color.tone_red
            else -> R.color.tone_gray
        }
    )

    /** One score color everywhere: model-aware for numerics, native tone otherwise. */
    fun scoreColor(ctx: Context, p: Place, model: String): Int =
        if (p.hasNumericScore) toneColor(ctx, NationalCoverage.numericTone(model, p.score))
        else toneColor(ctx, p.resultTone)

    private val KEEP_UPPER = setOf(
        "N", "S", "E", "W", "NE", "NW", "SE", "SW", "US", "II", "III", "IV"
    )

    /** Title-case for display only (addresses; never applied to names or data). */
    fun titleCase(s: String): String {
        if (s.isBlank()) return s
        return s.split(Regex("\\s+")).joinToString(" ") { raw ->
            val t = raw.trim()
            when {
                t.isEmpty() -> t
                t.all { it.isDigit() || it == '-' || it == '.' } -> t
                KEEP_UPPER.contains(t.uppercase(Locale.US)) -> t.uppercase(Locale.US)
                t.any { it.isDigit() } -> t.lowercase(Locale.US)
                else -> t.lowercase(Locale.US).replaceFirstChar { it.uppercase(Locale.US) }
                    .replace(Regex("'(\\w)")) { "'${it.groupValues[1].uppercase(Locale.US)}" }
            }
        }
    }

    private val ISO = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val OUT = SimpleDateFormat("MMM d, yyyy", Locale.US)

    /** "2026-11-12" → "Nov 12, 2026"; unparseable input returns as-is. */
    fun fmtDate(s: String): String {
        if (s.isBlank()) return s
        return runCatching { OUT.format(ISO.parse(s.trim()) as Date) }.getOrElse { s }
    }

    /** Spring-driven animate-to on a view property (no-op target snap when reduced). */
    fun springTo(
        v: View,
        prop: DynamicAnimation.ViewProperty,
        target: Float,
        stiffness: Float = 500f,
        damping: Float = 0.78f
    ) {
        if (reduceMotion(v.context)) {
            prop.setValue(v, target)
            return
        }
        SpringAnimation(v, prop, target).apply {
            spring.stiffness = stiffness
            spring.dampingRatio = damping
        }.start()
    }

    /** Fade + gentle rise used for first-show entrances. */
    fun animateIn(v: View, delayMs: Long = 0, risePx: Float = 28f) {
        if (reduceMotion(v.context)) {
            v.alpha = 1f; v.translationY = 0f
            return
        }
        v.alpha = 0f
        v.translationY = risePx
        v.animate().alpha(1f).translationY(0f)
            .setStartDelay(delayMs)
            .setDuration(340)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
            .start()
    }

    /** Subtle confirm haptic (CONFIRM is API 30+; falls back to a tap). */
    fun haptic(v: View) {
        val c = if (Build.VERSION.SDK_INT >= 30)
            HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP
        v.performHapticFeedback(c)
    }
}
