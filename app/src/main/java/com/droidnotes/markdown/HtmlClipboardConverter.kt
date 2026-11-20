package com.droidnotes.markdown

object HtmlClipboardConverter {
    private val STYLE_REGEX = Regex("""(?is)<style[^>]*>.*?</style>""")
    private val SCRIPT_REGEX = Regex("""(?is)<script[^>]*>.*?</script>""")
    private val HEADING_REGEXES = (1..6).map { level ->
        "#".repeat(level) to Regex("""(?is)<h$level[^>]*>(.*?)</h$level>""")
    }
    private val PRE_REGEX = Regex("""(?is)<pre[^>]*>(.*?)</pre>""")
    private val CODE_REGEX = Regex("""(?is)<code[^>]*>(.*?)</code>""")
    private val BOLD_REGEX = Regex("""(?is)<(strong|b)[^>]*>(.*?)</\1>""")
    private val ITALIC_REGEX = Regex("""(?is)<(em|i)[^>]*>(.*?)</\1>""")
    private val STRIKE_REGEX = Regex("""(?is)<(del|s|strike)[^>]*>(.*?)</\1>""")
    private val LINK_REGEX = Regex("""(?is)<a[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""")
    private val IMG_WITH_ALT_REGEX = Regex("""(?is)<img[^>]*src=["']([^"']+)["'][^>]*alt=["']([^"']*)["'][^>]*/?>""")
    private val IMG_REGEX = Regex("""(?is)<img[^>]*src=["']([^"']+)["'][^>]*/?>""")
    private val BLOCKQUOTE_REGEX = Regex("""(?is)<blockquote[^>]*>(.*?)</blockquote>""")
    private val LIST_ITEM_REGEX = Regex("""(?is)<li[^>]*>(.*?)</li>""")
    private val HR_REGEX = Regex("""(?is)<hr\s*/?>""")
    private val BR_REGEX = Regex("""(?is)<br\s*/?>""")
    private val BLOCK_END_REGEX = Regex("""(?is)</(p|div|tr)>""")
    private val MULTI_NEWLINE_REGEX = Regex("""\n{3,}""")
    private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
    private val NUMERIC_ENTITY_REGEX = Regex("""&#(x?[0-9a-fA-F]+);""")

    fun htmlToMarkdown(html: String): String {
        var md = html
            .replace("\r\n", "\n")
            .replace(STYLE_REGEX, "")
            .replace(SCRIPT_REGEX, "")

        // Headings
        for ((prefix, regex) in HEADING_REGEXES) {
            md = md.replace(regex) {
                "\n$prefix ${stripTags(it.groupValues[1]).trim()}\n"
            }
        }

        // Preformatted code blocks
        md = md.replace(PRE_REGEX) {
            "\n```\n${unescapeHtml(stripTags(it.groupValues[1])).trim()}\n```\n"
        }
        md = md.replace(CODE_REGEX) {
            "`${unescapeHtml(stripTags(it.groupValues[1])).trim()}`"
        }

        // Bold, italic, strikethrough
        md = md.replace(BOLD_REGEX) {
            "**${it.groupValues[2].trim()}**"
        }
        md = md.replace(ITALIC_REGEX) {
            "*${it.groupValues[2].trim()}*"
        }
        md = md.replace(STRIKE_REGEX) {
            "~~${it.groupValues[2].trim()}~~"
        }

        // Links and images
        md = md.replace(LINK_REGEX) {
            "[${stripTags(it.groupValues[2]).trim()}](${it.groupValues[1]})"
        }
        md = md.replace(IMG_WITH_ALT_REGEX) {
            "![${it.groupValues[2]}](${it.groupValues[1]})"
        }
        md = md.replace(IMG_REGEX) {
            "![image](${it.groupValues[1]})"
        }

        // Blockquotes & lists
        md = md.replace(BLOCKQUOTE_REGEX) {
            stripTags(it.groupValues[1]).lines().joinToString("\n") { line -> "> ${line.trim()}" } + "\n"
        }
        md = md.replace(LIST_ITEM_REGEX) {
            "- ${stripTags(it.groupValues[1]).trim()}\n"
        }
        md = md.replace(HR_REGEX, "\n---\n")
        md = md.replace(BR_REGEX, "\n")
        md = md.replace(BLOCK_END_REGEX, "\n\n")

        md = stripTags(md)
        md = unescapeHtml(md)
        return md.replace(MULTI_NEWLINE_REGEX, "\n\n").trim()
    }

    fun markdownToHtml(title: String, document: MarkdownDocument): String {
        val body = buildString {
            document.blocks.forEach { block ->
                append(blockToHtml(block))
                append("\n")
            }
        }
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>${escapeHtml(title)}</title>
              <style>
                body { font-family: -apple-system, BlinkMacSystemFont, "Inter", sans-serif; background: #000; color: #f5f5f5; max-width: 780px; margin: 40px auto; padding: 0 20px; line-height: 1.6; }
                h1, h2, h3, h4 { color: #fff; margin-top: 1.5em; border-bottom: 1px solid #262626; padding-bottom: 4px; }
                code { font-family: "JetBrains Mono", monospace; background: #141414; padding: 2px 6px; border-radius: 4px; }
                pre { background: #0a0a0a; border: 1px solid #262626; padding: 14px; border-radius: 6px; overflow-x: auto; }
                blockquote { border-left: 3px solid #fff; margin: 16px 0; padding: 4px 14px; background: #0a0a0a; color: #d4d4d4; }
                table { border-collapse: collapse; width: 100%; margin: 16px 0; }
                th, td { border: 1px solid #262626; padding: 8px 12px; }
                th { background: #141414; color: #fff; }
                .math-block { background: #0a0a0a; border: 1px solid #262626; padding: 14px; text-align: center; font-family: "JetBrains Mono", monospace; border-radius: 6px; margin: 14px 0; }
                .chart-box { background: #0a0a0a; border: 1px solid #262626; padding: 14px; border-radius: 6px; margin: 14px 0; }
                .cite { font-size: 0.9em; color: #d4d4d4; border: 1px solid #262626; padding: 8px 12px; border-radius: 4px; margin: 8px 0; }
                a { color: #fff; text-decoration: underline; }
                mark { background: #e5e5e5; color: #000; padding: 0 3px; }
              </style>
            </head>
            <body>
            $body
            </body>
            </html>
        """.trimIndent()
    }

    private fun blockToHtml(block: MarkdownBlock): String = when (block) {
        is MarkdownBlock.Heading -> "<h${block.level}>${inlinesToHtml(block.inlines)}</h${block.level}>"
        is MarkdownBlock.Paragraph -> "<p>${inlinesToHtml(block.inlines)}</p>"
        is MarkdownBlock.CodeBlock -> "<pre><code>${escapeHtml(block.code)}</code></pre>"
        is MarkdownBlock.LatexBlock -> "<div class=\"math-block\">${escapeHtml(LatexEngine.toReadableString(block.expression))}</div>"
        is MarkdownBlock.ChartBlock -> {
            val rows = block.spec.points.joinToString("") { "<li>${escapeHtml(it.label)}: ${it.value}</li>" }
            "<div class=\"chart-box\"><strong>${escapeHtml(block.spec.title.ifBlank { "Chart (${block.spec.type.name})" })}</strong><ul>$rows</ul></div>"
        }
        is MarkdownBlock.DiagramBlock -> {
            val rows = block.edges.joinToString("") {
                val lbl = if (it.label.isNotBlank()) " (${escapeHtml(it.label)})" else ""
                "<li>${escapeHtml(it.from)} → ${escapeHtml(it.to)}$lbl</li>"
            }
            "<div class=\"chart-box\"><strong>Diagram</strong><ul>$rows</ul></div>"
        }
        is MarkdownBlock.TableBlock -> {
            val head = block.headers.joinToString("") { "<th>${inlinesToHtml(it)}</th>" }
            val body = block.rows.joinToString("") { r ->
                "<tr>" + r.joinToString("") { c -> "<td>${inlinesToHtml(c)}</td>" } + "</tr>"
            }
            "<table><thead><tr>$head</tr></thead><tbody>$body</tbody></table>"
        }
        is MarkdownBlock.Blockquote -> "<blockquote>${block.blocks.joinToString("") { blockToHtml(it) }}</blockquote>"
        is MarkdownBlock.BulletList -> "<ul>${block.items.joinToString("") { "<li>${inlinesToHtml(it)}</li>" }}</ul>"
        is MarkdownBlock.OrderedList -> "<ol start=\"${block.startNumber}\">${block.items.joinToString("") { "<li>${inlinesToHtml(it)}</li>" }}</ol>"
        is MarkdownBlock.TaskList -> "<ul style=\"list-style:none;padding-left:0;\">${
            block.items.joinToString("") {
                val box = if (it.checked) "☑" else "☐"
                "<li>$box ${inlinesToHtml(it.inlines)}</li>"
            }
        }</ul>"
        is MarkdownBlock.ImageBlock -> "<figure><img src=\"${escapeHtml(block.uri)}\" alt=\"${escapeHtml(block.alt)}\" style=\"max-width:100%;\"/><figcaption>${escapeHtml(block.alt)}</figcaption></figure>"
        is MarkdownBlock.HorizontalRule -> "<hr />"
        is MarkdownBlock.CitationDefinition -> "<div class=\"cite\" id=\"cite-${escapeHtml(block.id)}\"><strong>[${escapeHtml(block.id)}]</strong> ${inlinesToHtml(block.inlines)}</div>"
    }

    private fun inlinesToHtml(inlines: List<MarkdownInline>): String = inlines.joinToString("") { inline ->
        when (inline) {
            is MarkdownInline.PlainText -> escapeHtml(inline.text)
            is MarkdownInline.Bold -> "<strong>${inlinesToHtml(inline.children)}</strong>"
            is MarkdownInline.Italic -> "<em>${inlinesToHtml(inline.children)}</em>"
            is MarkdownInline.BoldItalic -> "<strong><em>${inlinesToHtml(inline.children)}</em></strong>"
            is MarkdownInline.Strikethrough -> "<del>${inlinesToHtml(inline.children)}</del>"
            is MarkdownInline.Highlight -> "<mark>${inlinesToHtml(inline.children)}</mark>"
            is MarkdownInline.InlineCode -> "<code>${escapeHtml(inline.code)}</code>"
            is MarkdownInline.InlineLatex -> "<code>${escapeHtml(LatexEngine.toReadableString(inline.expression))}</code>"
            is MarkdownInline.Link -> "<a href=\"${escapeHtml(inline.url)}\">${escapeHtml(inline.label)}</a>"
            is MarkdownInline.WikiLink -> "<span class=\"wikilink\">[[${escapeHtml(inline.display)}]]</span>"
            is MarkdownInline.CitationRef -> "<sup><a href=\"#cite-${escapeHtml(inline.id)}\">[${escapeHtml(inline.id)}]</a></sup>"
            is MarkdownInline.TagToken -> "<code>${escapeHtml(inline.tag)}</code>"
            is MarkdownInline.InlineImage -> "<img src=\"${escapeHtml(inline.uri)}\" alt=\"${escapeHtml(inline.alt)}\"/>"
        }
    }

    private fun stripTags(input: String): String = input.replace(HTML_TAG_REGEX, "")

    private fun unescapeHtml(input: String): String {
        val named = input
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        return NUMERIC_ENTITY_REGEX.replace(named) { m ->
            val raw = m.groupValues[1]
            val code = if (raw.startsWith("x", ignoreCase = true)) {
                raw.drop(1).toIntOrNull(16)
            } else {
                raw.toIntOrNull(10)
            }
            code?.toChar()?.toString() ?: m.value
        }
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
