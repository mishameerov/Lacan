package org.lacan.core

/** Removes narrowly recognized hesitation sounds without rewriting surrounding text. */
object HesitationFilter {
    private const val HYPHENS = "-‐‑‒–—"
    private val filler = Regex(
        "(?<![\\p{L}\\p{N}_])(?:([аэы])\\1{2,}|([аэы])(?:[$HYPHENS]+\\2)+|(м)\\3{2,}|(м)(?:[$HYPHENS]+\\4)+)(?![\\p{L}\\p{N}_])",
        RegexOption.IGNORE_CASE,
    )
    fun clean(text: String): String {
        val matches = filler.findAll(text).toList()
        if (matches.isEmpty()) return text

        val spans = mutableListOf<IntRange>()
        for (match in matches) {
            var start = match.range.first
            var end = match.range.last + 1
            while (start > 0 && (text[start - 1] == ' ' || text[start - 1] == '\t')) start--
            while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
            if (end < text.length && text[end] == ',') {
                end++
                while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
            }
            val exclusiveEnd = end
            val previous = spans.lastOrNull()
            if (previous != null && start <= previous.last + 1) {
                spans[spans.lastIndex] = previous.first until maxOf(previous.last + 1, exclusiveEnd)
            } else spans += start until exclusiveEnd
        }

        val result = StringBuilder()
        var cursor = 0
        for (span in spans) {
            val end = span.last + 1
            result.append(text, cursor, span.first)
            val left = result.lastOrNull()
            val right = text.substring(end).firstOrNull()
            if (left != null && right != null && !left.isWhitespace() && !right.isWhitespace() &&
                right !in ".,;:!?…" && text.substring(span.first, end).none { it == '\n' || it == '\r' }) {
                result.append(' ')
            }
            cursor = end
        }
        result.append(text, cursor, text.length)
        var cleaned = result.toString()
        if (cleaned.none(Char::isLetterOrDigit)) return ""
        // Remove only edge separators left behind by removing a filler at that edge.
        if (spans.first().first == 0) cleaned = cleaned.trimStart(' ', '\t', ',', ';', ':')
        if (spans.last().last + 1 == text.length ||
            text.substring(spans.last().last + 1).all { it == ' ' || it == '\t' }) {
            cleaned = cleaned.trimEnd(' ', '\t', ',', ';', ':')
        }
        return if (cleaned.none(Char::isLetterOrDigit)) "" else cleaned
    }
}
