/*
 * YunX Desktop - AGPL-3.0.
 * 主题配色：在 Material3 默认方案之上，按用户自由调节的「主题色 / 背景色」重新派生整套配色。
 * 关键点是**对比度保护**：用户可以选任意颜色，正文/图标颜色必须始终可读，不能只改 primary 了事。
 */
package com.yunx.desktop.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 亮度（近似 Rec.709），用于判断该配深色字还是浅色字 */
private fun luminance(c: Color): Float = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

/** 在给定底色上取可读的前景色 */
private fun readableOn(bg: Color): Color = if (luminance(bg) > 0.55f) Color(0xFF141218) else Color(0xFFF5F3F7)

private fun mix(a: Color, b: Color, t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * k,
        green = a.green + (b.green - a.green) * k,
        blue = a.blue + (b.blue - a.blue) * k,
        alpha = 1f,
    )
}

/** RGB → HSV（h: 0..360, s/v: 0..1），供设置页三根滑杆使用 */
fun rgbToHsv(c: Color): FloatArray {
    val r = c.red; val g = c.green; val b = c.blue
    val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
    val h = when {
        d == 0f -> 0f
        mx == r -> 60f * (((g - b) / d) % 6f)
        mx == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    return floatArrayOf(if (h < 0) h + 360f else h, if (mx == 0f) 0f else d / mx, mx)
}

/** HSV → Color（h: 0..360, s/v: 0..1） */
fun hsvToColor(h: Float, s: Float, v: Float): Color {
    val hh = ((h % 360f) + 360f) % 360f / 60f
    val ss = s.coerceIn(0f, 1f); val vv = v.coerceIn(0f, 1f)
    val i = hh.toInt()
    val f = hh - i
    val p = vv * (1 - ss); val q = vv * (1 - ss * f); val t = vv * (1 - ss * (1 - f))
    return when (i) {
        0 -> Color(vv, t, p)
        1 -> Color(q, vv, p)
        2 -> Color(p, vv, t)
        3 -> Color(p, q, vv)
        4 -> Color(t, p, vv)
        else -> Color(vv, p, q)
    }
}

/** 色相偏移（保持饱和度/明度），用来派生 secondary / tertiary */
private fun hueShift(c: Color, degrees: Float, satScale: Float = 1f): Color {
    val hsv = rgbToHsv(c)
    return hsvToColor(hsv[0] + degrees, hsv[1] * satScale, hsv[2])
}

/** 颜色是否“几乎无彩色”（饱和度极低）——纯灰背景时不要硬套色相派生 */
private fun isGray(c: Color): Boolean = rgbToHsv(c)[1] < 0.06f

/**
 * 依据用户设置生成配色方案。
 * @param accentArgb 主题色（null = 用 Material3 默认）
 * @param baseArgb 背景色（null = 用 Material3 默认）
 */
fun yunxScheme(dark: Boolean, accentArgb: Int?, baseArgb: Int?): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    if (accentArgb == null && baseArgb == null) return base
    var s = base

    // 背景色：连带派生所有 surface 层级与前景色，保证任何底色都读得清
    if (baseArgb != null) {
        val bg = Color(baseArgb)
        val fg = readableOn(bg)
        val step = if (dark) 0.06f else 0.04f
        s = s.copy(
            background = bg,
            onBackground = fg,
            surface = bg,
            onSurface = fg,
            surfaceVariant = mix(bg, fg, 0.12f),
            onSurfaceVariant = mix(fg, bg, 0.35f),
            surfaceContainerLowest = mix(bg, fg, step * 0.5f),
            surfaceContainerLow = mix(bg, fg, step),
            surfaceContainer = mix(bg, fg, step * 1.5f),
            surfaceContainerHigh = mix(bg, fg, step * 2.2f),
            surfaceContainerHighest = mix(bg, fg, step * 3f),
            surfaceTint = s.primary,
            outline = mix(bg, fg, 0.5f),
            outlineVariant = mix(bg, fg, 0.25f),
            inverseSurface = fg,
            inverseOnSurface = bg,
        )
    }

    // 主题色：primary 及其容器、次级/三级色一起派生，界面才不会“只有按钮变色”
    if (accentArgb != null) {
        val a0 = Color(accentArgb)
        // 深色模式下把过暗的主题色提亮，浅色模式下把过亮的压暗，保证在各自底上有足够辨识度
        val a = if (dark) hsvToColor(rgbToHsv(a0)[0], rgbToHsv(a0)[1], max(rgbToHsv(a0)[2], 0.62f))
                else hsvToColor(rgbToHsv(a0)[0], rgbToHsv(a0)[1], min(rgbToHsv(a0)[2], 0.72f))
        val onA = readableOn(a)
        val bg = s.background
        s = s.copy(
            primary = a,
            onPrimary = onA,
            primaryContainer = mix(a, bg, if (dark) 0.72f else 0.82f),
            onPrimaryContainer = if (dark) mix(a, Color.White, 0.55f) else mix(a, Color.Black, 0.55f),
            inversePrimary = mix(a, Color.White, 0.3f),
            secondary = if (isGray(a)) mix(a, bg, 0.25f) else hueShift(a, 28f, 0.75f),
            onSecondary = onA,
            secondaryContainer = mix(if (isGray(a)) a else hueShift(a, 28f, 0.75f), bg, if (dark) 0.72f else 0.82f),
            onSecondaryContainer = s.onSurface,
            tertiary = if (isGray(a)) mix(a, bg, 0.45f) else hueShift(a, -40f, 0.7f),
            onTertiary = onA,
            tertiaryContainer = mix(if (isGray(a)) a else hueShift(a, -40f, 0.7f), bg, if (dark) 0.72f else 0.82f),
            onTertiaryContainer = s.onSurface,
            surfaceTint = a,
        )
    }
    return s
}

/** 主题色的默认值（Material3 默认紫），供设置页「重置」按钮显示当前基准 */
fun defaultPrimary(dark: Boolean): Color = (if (dark) darkColorScheme() else lightColorScheme()).primary

/** 背景色默认值 */
fun defaultBackground(dark: Boolean): Color = (if (dark) darkColorScheme() else lightColorScheme()).background

/** 供设置页展示的十六进制文本 */
fun hexOf(c: Color): String = "#%06X".format(0xFFFFFF and c.toArgb())

/** 颜色差异是否明显（用于判断用户是否真的改过） */
fun roughlyEqual(a: Color, b: Color): Boolean =
    abs(a.red - b.red) < 0.004f && abs(a.green - b.green) < 0.004f && abs(a.blue - b.blue) < 0.004f
