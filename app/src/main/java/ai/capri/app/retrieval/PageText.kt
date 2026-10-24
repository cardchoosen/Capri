package ai.capri.app.retrieval

import net.dankito.readability4j.Readability4J
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

internal data class PageText(val title: String, val text: String, val method: String)
internal object PageExtractor {
    fun extract(page: HttpPage): PageText {
        if (page.status !in 200..299) throw RetrievalFailure("http_${page.status}")
        val mime = page.mime.substringBefore(';').trim().lowercase()
        if (mime in setOf("text/plain", "text/markdown", "application/json")) return PageText(page.url, page.text(), "plain_text")
        if (mime.isNotBlank() && mime !in setOf("text/html", "application/xhtml+xml")) throw RetrievalFailure("unsupported_content_type")
        val document = page.document()
        if (document.getAllElements().size > 50000) throw RetrievalFailure("page_too_complex")
        org.jsoup.select.NodeTraversor.traverse(object : org.jsoup.select.NodeVisitor {
            override fun head(node: org.jsoup.nodes.Node, depth: Int) { if (depth > 100) throw RetrievalFailure("page_too_complex") }
            override fun tail(node: org.jsoup.nodes.Node, depth: Int) {}
        }, document)
        if (document.title().contains(Regex("(?i)captcha|安全验证|人机验证|access denied|just a moment|attention required"))) throw RetrievalFailure("access_restricted")
        document.select("script,style,noscript,template,nav,footer,form,iframe,svg,canvas").remove()
        // Prefer semantically marked content, preserving code/tables in technical documentation.
        val marked = document.select("article,main,[role=main]").maxByOrNull { it.text().length }
        val article = if (marked != null && marked.text().length >= 160) marked else runCatching {
            Readability4J(page.url, document.outerHtml()).parse().content?.let { org.jsoup.Jsoup.parseBodyFragment(it, page.url).body() }
        }.getOrNull()
        val root = article ?: document.body()
        val output = StringBuilder()
        fun walk(element: Element) {
            when (element.normalName()) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> { output.append("\n\n").append("#".repeat(element.normalName().last().digitToInt())).append(' ') }
                "p", "div", "section", "blockquote", "table", "tr" -> output.append('\n')
                "br" -> output.append('\n')
                "li" -> output.append("\n- ")
                "pre" -> { output.append("\n```\n").append(element.wholeText()).append("\n```\n"); return }
            }
            element.childNodes().forEach { node -> when (node) {
                is TextNode -> output.append(node.text())
                is Element -> walk(node)
            } }
            when (element.normalName()) {
                "p", "div", "section", "li", "blockquote", "table", "tr", "h1", "h2", "h3", "h4", "h5", "h6" -> output.append('\n')
                "td", "th" -> output.append(" | ")
            }
        }
        walk(root)
        val text = output.toString().replace(Regex("\\n{3,}"), "\n\n").trim()
        if (text.length < 80) throw RetrievalFailure("no_readable_content")
        if (article == null && document.select("p").sumOf { it.text().length } < 80) throw RetrievalFailure("requires_rendering_or_no_content")
        return PageText(document.title().take(400), text, if (marked === article) "semantic_html" else if (article != null) "readability" else "clean_html")
    }
}
