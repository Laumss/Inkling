package com.supernote_quicktoolbar.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.supernote_quicktoolbar.NativeLocale
import com.supernote_quicktoolbar.relay.core.MarkdownAlignment
import com.supernote_quicktoolbar.relay.core.MarkdownBlock
import com.supernote_quicktoolbar.relay.core.MarkdownDocument
import com.supernote_quicktoolbar.relay.core.MarkdownDocumentParser

// ═══════════════════════════════════════════════════════════════════════════
// Zero-dependency Markdown + GFM-table + LaTeX-subset renderer for the relay
// inbox detail page. Colors stay parameterized for the shared e-ink palette.
// ═══════════════════════════════════════════════════════════════════════════

data class MdPalette(
    val ink: Color,
    val muted: Color,
    val codeBg: Color,
    /** Headings & bold: slightly stronger than body ink so hierarchy reads on e-ink. */
    val strong: Color = ink,
)

/** CJK punctuation hanging + no mid-word break for English/numbers. */
private val EinkLineBreak = LineBreak(
    strategy = LineBreak.Strategy.HighQuality,
    strictness = LineBreak.Strictness.Strict,
    wordBreak = LineBreak.WordBreak.Default,
)

private val EinkTextStyle = TextStyle(lineBreak = EinkLineBreak)

// ── LaTeX subset → styled text ──────────────────────────────────────────────

private val TEX_SYMBOLS = mapOf(
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
    "zeta" to "ζ", "eta" to "η", "theta" to "θ", "iota" to "ι", "kappa" to "κ",
    "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π",
    "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "phi" to "φ", "chi" to "χ",
    "psi" to "ψ", "omega" to "ω",
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ",
    "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
    "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓",
    "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠",
    "approx" to "≈", "equiv" to "≡", "sim" to "∼", "propto" to "∝",
    "infty" to "∞", "partial" to "∂", "nabla" to "∇",
    "sum" to "∑", "prod" to "∏", "int" to "∫", "oint" to "∮",
    "rightarrow" to "→", "to" to "→", "leftarrow" to "←", "Rightarrow" to "⇒",
    "Leftarrow" to "⇐", "leftrightarrow" to "↔", "Leftrightarrow" to "⇔",
    "in" to "∈", "notin" to "∉", "subset" to "⊂", "subseteq" to "⊆",
    "cup" to "∪", "cap" to "∩", "emptyset" to "∅", "forall" to "∀", "exists" to "∃",
    "land" to "∧", "lor" to "∨", "neg" to "¬", "oplus" to "⊕", "otimes" to "⊗",
    "angle" to "∠", "perp" to "⊥", "parallel" to "∥", "degree" to "°",
    "ldots" to "…", "cdots" to "⋯", "dots" to "…", "prime" to "′",
    "hbar" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ",
    "quad" to "  ", "qquad" to "    ", ";" to " ", "," to " ", " " to " ",
    "left" to "", "right" to "", "displaystyle" to "", "limits" to "",
)

/** Reads a {...} group or a single token starting at [start]; returns (content, nextIndex). */
private fun readGroup(s: String, start: Int): Pair<String, Int> {
    if (start >= s.length) return "" to start
    if (s[start] == '{') {
        var depth = 0
        var j = start
        while (j < s.length) {
            when (s[j]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) return s.substring(start + 1, j) to j + 1 } }
            j++
        }
        return s.substring(start + 1) to s.length
    }
    if (s[start] == '\\') {
        var j = start + 1
        while (j < s.length && s[j].isLetter()) j++
        return s.substring(start, if (j == start + 1) j + 1 else j) to (if (j == start + 1) j + 1 else j)
    }
    return s[start].toString() to start + 1
}

private fun AnnotatedString.Builder.appendTex(tex: String, color: Color) {
    var i = 0
    val sup = SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.72.em, color = color)
    val sub = SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.72.em, color = color)
    while (i < tex.length) {
        val c = tex[i]
        when {
            c == '\\' -> {
                var j = i + 1
                while (j < tex.length && tex[j].isLetter()) j++
                val cmd = if (j == i + 1 && j < tex.length) tex.substring(i + 1, j + 1) else tex.substring(i + 1, j)
                if (j == i + 1 && j < tex.length) j++
                when (cmd) {
                    "frac" -> {
                        val (num, n1) = readGroup(tex, j)
                        val (den, n2) = readGroup(tex, n1)
                        val simple = num.length <= 2 && den.length <= 2 &&
                            num.none { it == '\\' } && den.none { it == '\\' }
                        if (simple) {
                            withStyle(sup) { appendTex(num, color) }
                            append('⁄')
                            withStyle(sub) { appendTex(den, color) }
                        } else {
                            append('(')
                            appendTex(num, color)
                            append(")/(")
                            appendTex(den, color)
                            append(')')
                        }
                        i = n2; continue
                    }
                    "sqrt" -> {
                        val (arg, n1) = readGroup(tex, j)
                        append("√(")
                        appendTex(arg, color)
                        append(')')
                        i = n1; continue
                    }
                    "vec", "hat", "widehat", "bar", "overline", "dot", "ddot",
                    "tilde", "widetilde", "check", "breve" -> {
                        val (arg, n1) = readGroup(tex, j)
                        val mark = when (cmd) {
                            "vec" -> '\u20D7'
                            "hat", "widehat" -> '\u0302'
                            "bar" -> '\u0304'
                            "overline" -> '\u0305'
                            "dot" -> '\u0307'
                            "ddot" -> '\u0308'
                            "check" -> '\u030C'
                            "breve" -> '\u0306'
                            else -> '\u0303'
                        }
                        if (arg.none { it == '\\' }) {
                            for (ch in arg) if (ch != '{' && ch != '}') { append(ch); append(mark) }
                        } else {
                            appendTex(arg, color)
                            append(mark)
                        }
                        i = n1; continue
                    }
                    "text", "mathrm", "mathbf", "mathit", "operatorname" -> {
                        val (arg, n1) = readGroup(tex, j)
                        append(arg)
                        i = n1; continue
                    }
                    else -> {
                        append(TEX_SYMBOLS[cmd] ?: cmd)
                        i = j; continue
                    }
                }
            }
            c == '^' -> {
                val (arg, n1) = readGroup(tex, i + 1)
                withStyle(sup) { appendTex(arg, color) }
                i = n1
            }
            c == '_' -> {
                val (arg, n1) = readGroup(tex, i + 1)
                withStyle(sub) { appendTex(arg, color) }
                i = n1
            }
            c == '{' || c == '}' -> i++
            else -> { append(c); i++ }
        }
    }
}

// ── Inline markdown → AnnotatedString ───────────────────────────────────────

private fun inlineAnnotated(text: String, pal: MdPalette): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    while (i < n) {
        val rest = text.substring(i)
        when {
            rest.startsWith("**") -> {
                val end = text.indexOf("**", i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = pal.strong)) {
                        append(inlineAnnotated(text.substring(i + 2, end), pal))
                    }
                    i = end + 2
                } else { append("**"); i += 2 }
            }
            rest.startsWith("~~") -> {
                val end = text.indexOf("~~", i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = pal.ink)) {
                        append(inlineAnnotated(text.substring(i + 2, end), pal))
                    }
                    i = end + 2
                } else { append("~~"); i += 2 }
            }
            text[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = pal.ink)) {
                        append(inlineAnnotated(text.substring(i + 1, end), pal))
                    }
                    i = end + 1
                } else { append('*'); i++ }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = pal.codeBg, color = pal.ink)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append('`'); i++ }
            }
            text[i] == '$' -> {
                val end = text.indexOf('$', i + 1)
                if (end > 0) {
                    appendTex(text.substring(i + 1, end), pal.ink)
                    i = end + 1
                } else { append('$'); i++ }
            }
            rest.startsWith("![") -> {
                val mid = text.indexOf("](", i)
                val end = if (mid > 0) text.indexOf(')', mid) else -1
                if (mid > 0 && end > 0) {
                    withStyle(SpanStyle(color = pal.muted)) { append(NativeLocale.t("relay_image_alt", text.substring(i + 2, mid))) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text[i] == '[' -> {
                val mid = text.indexOf("](", i)
                val end = if (mid > 0) text.indexOf(')', mid) else -1
                if (mid > 0 && end > 0) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = pal.ink)) {
                        append(text.substring(i + 1, mid))
                    }
                    i = end + 1
                } else { append('['); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

// ── Composable renderer ─────────────────────────────────────────────────────

@Composable
fun MarkdownDocumentView(
    document: MarkdownDocument,
    palette: MdPalette,
    modifier: Modifier = Modifier,
    baseFontSize: Int = 16,
    boldBody: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        document.blocks.forEach { block ->
            MarkdownBlockView(
                block = block,
                palette = palette,
                baseFontSize = baseFontSize,
                boldBody = boldBody,
            )
        }
    }
}

@Composable
fun MarkdownBlockView(
    block: MarkdownBlock,
    palette: MdPalette,
    modifier: Modifier = Modifier,
    baseFontSize: Int = 16,
    boldBody: Boolean = false,
) {
    val bodyWeight = if (boldBody) FontWeight.Bold else null
    val headWeight = if (boldBody) FontWeight.ExtraBold else FontWeight.Bold

    when (block) {
        is MarkdownBlock.Heading -> {
            val size = when (block.level) {
                1 -> baseFontSize + 10
                2 -> baseFontSize + 6
                3 -> baseFontSize + 3
                else -> baseFontSize + 1
            }
            val role = MarkdownDocumentParser.roleHeading(block.text)
            if (role != null) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(
                            fontSize = size.sp,
                            fontWeight = headWeight,
                            color = palette.strong,
                        )) { append(role.first + ":") }
                        if (role.second.isNotBlank()) {
                            append(" ")
                            withStyle(SpanStyle(
                                fontSize = baseFontSize.sp,
                                fontWeight = if (boldBody) FontWeight.Bold else FontWeight.Normal,
                                color = palette.ink,
                            )) { append(inlineAnnotated(role.second, palette)) }
                        }
                    },
                    lineHeight = (size * 1.3).sp,
                    style = EinkTextStyle,
                    modifier = modifier.padding(top = (baseFontSize * 0.8).dp),
                )
            } else {
                Text(
                    inlineAnnotated(block.text, palette),
                    fontSize = size.sp,
                    fontWeight = headWeight,
                    color = palette.strong,
                    lineHeight = (size * 1.3).sp,
                    style = EinkTextStyle,
                    modifier = modifier,
                )
            }
        }

        is MarkdownBlock.Paragraph -> Text(
            inlineAnnotated(block.text, palette),
            fontSize = baseFontSize.sp,
            fontWeight = bodyWeight,
            color = palette.ink,
            lineHeight = (baseFontSize * 1.55).sp,
            style = EinkTextStyle,
            modifier = modifier,
        )

        is MarkdownBlock.Quote -> Row(modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(palette.muted, RectangleShape))
            Text(
                inlineAnnotated(block.text, palette),
                fontSize = baseFontSize.sp,
                fontWeight = bodyWeight,
                color = palette.muted,
                lineHeight = (baseFontSize * 1.5).sp,
                style = EinkTextStyle,
                modifier = Modifier.padding(start = 10.dp),
            )
        }

        is MarkdownBlock.CodeFence -> Text(
            block.code,
            fontSize = (baseFontSize - 3).sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = bodyWeight,
            color = palette.ink,
            lineHeight = (baseFontSize * 1.35).sp,
            modifier = modifier
                .fillMaxWidth()
                .background(palette.codeBg, RectangleShape)
                .border(1.dp, palette.muted, RectangleShape)
                .padding(10.dp),
        )

        is MarkdownBlock.ListItem -> Row(modifier.padding(start = (block.indent * 18).dp)) {
            Text(
                block.marker,
                fontSize = baseFontSize.sp,
                fontWeight = bodyWeight,
                color = palette.ink,
                modifier = Modifier.width(26.dp),
            )
            Text(
                inlineAnnotated(block.text, palette),
                fontSize = baseFontSize.sp,
                fontWeight = bodyWeight,
                color = palette.ink,
                lineHeight = (baseFontSize * 1.5).sp,
                style = EinkTextStyle,
            )
        }

        is MarkdownBlock.Rule -> Box(
            modifier.fillMaxWidth().height(1.dp).background(palette.muted, RectangleShape),
        )

        is MarkdownBlock.Math -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                buildAnnotatedString { appendTex(block.tex.replace("\n", " "), palette.ink) },
                fontSize = (baseFontSize + 2).sp,
                fontWeight = bodyWeight,
                color = palette.ink,
                textAlign = TextAlign.Center,
                lineHeight = ((baseFontSize + 2) * 1.6).sp,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        is MarkdownBlock.Table -> MarkdownTable(
            table = block,
            palette = palette,
            baseFontSize = baseFontSize,
            boldBody = boldBody,
            modifier = modifier,
        )
    }
}

/**
 * Splits a wide table into multiple sub-tables that each fit within
 * the available width. When all columns fit, renders a single table.
 */
@Composable
private fun MarkdownTable(
    table: MarkdownBlock.Table,
    palette: MdPalette,
    baseFontSize: Int,
    boldBody: Boolean,
    modifier: Modifier = Modifier,
) {
    val columnWidths = remember(table) {
        List(table.headers.size) { col ->
            val maxLen = maxOf(
                table.headers[col].length,
                table.rows.maxOfOrNull { it.getOrElse(col) { "" }.length } ?: 0,
            )
            (maxLen * 8).coerceIn(80, 480).dp
        }
    }

    // Filter out columns that are entirely blank (header + all rows)
    val nonEmptyCols = remember(table) {
        (0 until table.headers.size).filter { col ->
            table.headers[col].isNotBlank() ||
                table.rows.any { it.getOrElse(col) { "" }.isNotBlank() }
        }
    }
    val filteredWidths = remember(nonEmptyCols, columnWidths) {
        nonEmptyCols.map { columnWidths[it] }
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val available = maxWidth
        // Even distribution: calculate rows needed, then spread columns uniformly
        val groups = remember(filteredWidths, available) {
            val n = filteredWidths.size
            if (n == 0) return@remember emptyList<IntRange>()

            val totalWidth = filteredWidths.fold(0f) { acc, w -> acc + w.value }
            val avail = available.value.coerceAtLeast(1f)
            val numRows = kotlin.math.ceil(totalWidth / avail)
                .toInt().coerceIn(1, n)

            val basePerRow = n / numRows
            val remainder = n % numRows
            val result = mutableListOf<IntRange>()
            var start = 0
            for (row in 0 until numRows) {
                val count = basePerRow + if (row < remainder) 1 else 0
                result += start until (start + count)
                start += count
            }
            result
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            groups.forEach { groupRange ->
                // Map group indices back to original column indices
                val cols = groupRange.map { nonEmptyCols[it] }
                val subWidths = groupRange.map { filteredWidths[it] }
                val subAlignments = cols.map { table.alignments.getOrElse(it) { MarkdownAlignment.LEFT } }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, palette.ink, RectangleShape),
                ) {
                    MarkdownTableRow(
                        cells = cols.map { table.headers.getOrElse(it) { "" } },
                        alignments = subAlignments,
                        palette = palette,
                        baseFontSize = baseFontSize,
                        fontWeight = FontWeight.ExtraBold,
                        background = palette.codeBg,
                        columnWidths = subWidths,
                    )
                    table.rows.forEach { row ->
                        MarkdownTableRow(
                            cells = cols.map { row.getOrElse(it) { "" } },
                            alignments = subAlignments,
                            palette = palette,
                            baseFontSize = baseFontSize,
                            fontWeight = if (boldBody) FontWeight.Bold else FontWeight.Normal,
                            background = Color.Transparent,
                            columnWidths = subWidths,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownTableRow(
    cells: List<String>,
    alignments: List<MarkdownAlignment>,
    palette: MdPalette,
    baseFontSize: Int,
    fontWeight: FontWeight,
    background: Color,
    columnWidths: List<androidx.compose.ui.unit.Dp>,
) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        cells.forEachIndexed { index, cell ->
            val textAlign = when (alignments.getOrElse(index) { MarkdownAlignment.LEFT }) {
                MarkdownAlignment.LEFT -> TextAlign.Start
                MarkdownAlignment.CENTER -> TextAlign.Center
                MarkdownAlignment.RIGHT -> TextAlign.End
            }
            Text(
                text = inlineAnnotated(cell, palette),
                modifier = Modifier
                    .width(columnWidths.getOrElse(index) { 200.dp })
                    .fillMaxHeight()
                    .background(background, RectangleShape)
                    .border(0.5.dp, palette.muted, RectangleShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                fontSize = baseFontSize.sp,
                fontWeight = fontWeight,
                color = palette.ink,
                lineHeight = (baseFontSize * 1.4).sp,
                textAlign = textAlign,
                style = EinkTextStyle,
            )
        }
    }
}
