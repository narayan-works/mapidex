package com.narayan.mapidex.companion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Playdate-vibe chrome for the companion: 1-bit black/white, pixel headings (Silkscreen, OFL),
 * chunky monospace body, sharp bordered cards with a hard drop shadow. One accent: Playdate yellow.
 */
object Pd {
    val Ink = Color(0xFF000000)
    val Paper = Color(0xFFFFFFFF)
    /** Only "grey" allowed: quiet meta text. */
    val Quiet = Color(0xFF5A5A5A)
    /** Faint rule / disabled border. */
    val Rule = Color(0xFFD6D6D6)
    /** Classic Playdate yellow — the ONLY accent: primary buttons, selected tab, card title strips. */
    val Accent = Color(0xFFFFC500)
    val Pixel = FontFamily(
        Font(R.font.silkscreen_regular, FontWeight.Normal),
        Font(R.font.silkscreen_bold, FontWeight.Bold),
    )
    val Mono = FontFamily.Monospace
    val Square = RoundedCornerShape(0.dp)
}

@Composable
fun PdTheme(content: @Composable () -> Unit) {
    val ink = Pd.Ink; val paper = Pd.Paper
    val scheme = lightColorScheme(
        primary = ink, onPrimary = paper, primaryContainer = ink, onPrimaryContainer = paper,
        secondary = ink, onSecondary = paper, secondaryContainer = paper, onSecondaryContainer = ink,
        tertiary = ink, onTertiary = paper, background = paper, onBackground = ink,
        surface = paper, onSurface = ink, surfaceVariant = paper, onSurfaceVariant = ink,
        surfaceTint = paper, outline = ink, outlineVariant = ink, error = ink, onError = paper,
    )
    val base = TextStyle(fontFamily = Pd.Mono, color = ink)
    val t = Typography()
    val typo = Typography(
        bodyLarge = t.bodyLarge.merge(base), bodyMedium = t.bodyMedium.merge(base), bodySmall = t.bodySmall.merge(base),
        labelLarge = t.labelLarge.merge(base).copy(fontWeight = FontWeight.Bold), labelMedium = t.labelMedium.merge(base),
        labelSmall = t.labelSmall.merge(base), titleMedium = t.titleMedium.merge(base), titleLarge = t.titleLarge.merge(base),
    )
    val sq = Pd.Square
    MaterialTheme(colorScheme = scheme, typography = typo,
        shapes = Shapes(extraSmall = sq, small = sq, medium = sq, large = sq, extraLarge = sq)) {
        CompositionLocalProvider(LocalContentColor provides ink) { content() }
    }
}

/** Pixel-font label (Playdate menu style). Uppercased. */
@Composable
fun PxText(text: String, size: TextUnit = 12.sp, color: Color = Color.Unspecified, bold: Boolean = false, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier = modifier, fontFamily = Pd.Pixel, fontSize = size, color = color,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Clip)

/** Chunky mono body text. */
@Composable
fun MonoText(text: String, size: TextUnit = 14.sp, bold: Boolean = false, color: Color = Pd.Ink, maxLines: Int = Int.MAX_VALUE,
             modifier: Modifier = Modifier) =
    Text(text, modifier = modifier, fontFamily = Pd.Mono, fontSize = size, color = color,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

@Composable
fun QuietText(text: String, size: TextUnit = 12.sp, maxLines: Int = Int.MAX_VALUE) =
    MonoText(text, size = size, color = Pd.Quiet, maxLines = maxLines)

/** Bento card: sharp 2dp border, yellow title strip (black type + rule), hard 1-bit shadow. */
@Composable
fun PdCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shadow = 3.dp
    Column(
        modifier
            .fillMaxWidth()
            .padding(end = shadow, bottom = shadow)
            .drawBehind { drawRect(Pd.Ink, topLeft = Offset(shadow.toPx(), shadow.toPx()), size = Size(size.width, size.height)) }
            .background(Pd.Paper)
            .border(2.dp, Pd.Ink)
            .let { if (onClick != null) it.clickable { onClick() } else it }
    ) {
        if (title != null) {
            Row(Modifier.fillMaxWidth().background(Pd.Accent)
                    .drawBehind { val t = 2.dp.toPx(); drawRect(Pd.Ink, Offset(0f, size.height - t), Size(size.width, t)) }
                    .padding(start = 10.dp, end = 10.dp, top = 5.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                PxText(title, 11.sp, Pd.Ink, bold = true, modifier = Modifier.weight(1f))
                trailing?.let { CompositionLocalProvider(LocalContentColor provides Pd.Ink) { it() } }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

/** Bordered pixel button. inverted = primary: Playdate-yellow fill, black border/type. Otherwise white. */
@Composable
fun PdButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, inverted: Boolean = false) {
    Box(
        modifier
            .background(if (inverted && enabled) Pd.Accent else Pd.Paper)
            .border(2.dp, if (enabled) Pd.Ink else Pd.Rule)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) { PxText(text, 11.sp, if (enabled) Pd.Ink else Pd.Quiet, bold = true) }
}

/** Quiet secondary link (debug toggles, refresh, etc.) — pixel grey, no chrome. */
@Composable
fun PdLink(text: String, onClick: () -> Unit) {
    Box(Modifier.clickable { onClick() }.padding(vertical = 4.dp)) { PxText(text, 9.sp, Pd.Quiet) }
}

/** 1dp faint hairline — calmer than dither inside cards. */
@Composable
fun Hairline(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(Pd.Rule))

/** Little tag, e.g. vibe or status. inverted = Playdate-yellow fill (black type), else white. */
@Composable
fun PdTag(text: String, inverted: Boolean = true) {
    Box(Modifier.background(if (inverted) Pd.Accent else Pd.Paper).border(1.5.dp, Pd.Ink).padding(horizontal = 6.dp, vertical = 2.dp)) {
        PxText(text, 9.sp, Pd.Ink)
    }
}

/** 2px checkerboard dither strip (Playdate menu divider). */
@Composable
fun Dither(height: Dp = 6.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val c = 2.dp.toPx()
        var y = 0f; var row = 0
        while (y < size.height) {
            var x = if (row % 2 == 0) 0f else c
            while (x < size.width) { drawRect(Pd.Ink, Offset(x, y), Size(c, c)); x += c * 2 }
            y += c; row++
        }
    }
}

/** Playdate-ish busy indicator: small black ring. */
@Composable
fun PdSpinner(size: Dp = 16.dp, color: Color = Pd.Ink) =
    CircularProgressIndicator(Modifier.size(size), color = color, strokeWidth = 3.dp, trackColor = Color.Transparent)

/** Label/value row: pixel label left, mono value right. */
@Composable
fun KV(label: String, value: String, bold: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PxText(label, 10.sp, Pd.Quiet, modifier = Modifier.width(72.dp))
        MonoText(value, 13.sp, bold = bold, maxLines = 2, modifier = Modifier.weight(1f))
    }
}
