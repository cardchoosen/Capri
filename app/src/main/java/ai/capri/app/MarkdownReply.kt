package ai.capri.app

import android.graphics.Typeface
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import org.commonmark.node.*
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell

private val MarkdownWhite = Color(0xFFF4F6F3)
private val MarkdownGreen = Color(0xFFA5FF75)
private val MarkdownPanel = Color(0xFF111612)
private val MarkdownLine = Color(0xFF29322B)

internal sealed interface ReplyBlock {
    data class Prose(val text: Spanned) : ReplyBlock
    data class Code(val language: String, val text: String) : ReplyBlock
    data class Table(val rows: List<List<Spanned>>) : ReplyBlock
}

/** Top-level code and tables use native Compose containers; prose keeps CommonMark spans. */
internal fun replyBlocks(markdown: String, engine: Markwon): List<ReplyBlock> {
    val document = engine.parse(markdown)
    val result = mutableListOf<ReplyBlock>()
    var prose = Document()
    fun flush() {
        if (prose.firstChild != null) { result.add(ReplyBlock.Prose(engine.render(prose))); prose = Document() }
    }
    fun renderCell(cell: Node): Spanned {
        val paragraph = Paragraph()
        var child = cell.firstChild
        while (child != null) { val next = child.next; paragraph.appendChild(child); child = next }
        return engine.render(Document().apply { appendChild(paragraph) })
    }
    fun rows(node: Node): List<List<Spanned>> {
        val output = mutableListOf<List<Spanned>>()
        var child = node.firstChild
        while (child != null) {
            if (child.firstChild is TableCell) {
                val cells = mutableListOf<Spanned>()
                var cell = child.firstChild
                while (cell != null) { cells.add(renderCell(cell)); cell = cell.next }
                output.add(cells)
            } else output.addAll(rows(child))
            child = child.next
        }
        return output
    }
    var node = document.firstChild
    while (node != null) {
        val next = node.next
        when (node) {
            is FencedCodeBlock -> { flush(); result.add(ReplyBlock.Code(node.info.orEmpty().trim().substringBefore(' ').take(24), node.literal.orEmpty())) }
            is IndentedCodeBlock -> { flush(); result.add(ReplyBlock.Code("", node.literal.orEmpty())) }
            is TableBlock -> { flush(); result.add(ReplyBlock.Table(rows(node))) }
            else -> prose.appendChild(node)
        }
        node = next
    }
    flush()
    return result
}

@Composable internal fun MarkdownReply(markdown: String) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val engine = remember(context, density.density, density.fontScale) {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder.linkColor(0xFFA5FF75.toInt())
                        .codeTextColor(0xFFA5FF75.toInt()).codeBackgroundColor(0xFF18231A.toInt())
                        .codeBlockTextColor(0xFFF4F6F3.toInt()).codeBlockBackgroundColor(0xFF111612.toInt())
                        .codeTypeface(Typeface.MONOSPACE)
                        .blockQuoteColor(0xFFA5FF75.toInt()).blockQuoteWidth(with(density) { 2.dp.roundToPx() })
                        .headingBreakHeight(0).thematicBreakColor(0xFF29322B.toInt())
                }
            }).build()
    }
    val blocks = remember(markdown, engine) { replyBlocks(markdown, engine) }
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        blocks.forEach { block ->
            when (block) {
                is ReplyBlock.Prose -> RichText(block.text)
                is ReplyBlock.Code -> CodeCard(block)
                is ReplyBlock.Table -> MarkdownTable(block.rows)
            }
        }
        TextButton(onClick = { clipboard.setText(AnnotatedString(markdown)) }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
            Icon(Icons.Outlined.ContentCopy, null, Modifier.size(14.dp), tint = Color(0xFF939E94))
            Spacer(Modifier.width(6.dp)); Text("复制回复", color = Color(0xFF939E94), fontSize = 11.sp)
        }
    }
}

@Composable private fun RichText(text: Spanned, modifier: Modifier = Modifier, header: Boolean = false, selectable: Boolean = true) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    AndroidView(modifier = modifier.fillMaxWidth(), factory = { context ->
        TextView(context).apply {
            setTextColor(0xFFF4F6F3.toInt()); setLinkTextColor(0xFFA5FF75.toInt())
            textSize = 15f; includeFontPadding = false
            setTextIsSelectable(selectable)
            movementMethod = LinkMovementMethod.getInstance()
            if (!selectable) { isFocusable = false; isFocusableInTouchMode = false }
        }
    }, update = { view ->
        view.textSize = if (header) 13f else 15f
        view.setTypeface(Typeface.DEFAULT, if (header) Typeface.BOLD else Typeface.NORMAL)
        view.setLineSpacing(with(density) { 5.sp.toPx() }, 1f)
        if (view.text != text) view.text = text
    })
}

@Composable private fun CodeCard(code: ReplyBlock.Code) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code.text) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().border(1.dp, MarkdownLine, RoundedCornerShape(2.dp)).background(MarkdownPanel, RoundedCornerShape(2.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(code.language.ifEmpty { "CODE" }, color = MarkdownGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { clipboard.setText(AnnotatedString(code.text)); copied = true }) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text(if (copied) "已复制" else "复制", fontSize = 11.sp)
            }
        }
        HorizontalDivider(color = MarkdownLine)
        SelectionContainer {
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp)) {
                Text(code.text.trimEnd('\n'), color = MarkdownWhite, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp, softWrap = false)
            }
        }
    }
}

@Composable private fun MarkdownTable(rows: List<List<Spanned>>) {
    val columns = rows.maxOfOrNull { it.size } ?: return
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        rows.forEachIndexed { index, row ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                repeat(columns) { column ->
                    Box(Modifier.width(180.dp).fillMaxHeight().border(0.5.dp, MarkdownLine).background(if (index == 0) MarkdownPanel else Color.Transparent).padding(8.dp)) {
                        row.getOrNull(column)?.let { RichText(it, header = index == 0, selectable = false) }
                    }
                }
            }
        }
    }
}
