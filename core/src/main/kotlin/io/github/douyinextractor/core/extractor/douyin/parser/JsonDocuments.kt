package io.github.douyinextractor.core.extractor.douyin.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder

/** Parses data only. Page JavaScript is never evaluated. */
internal object JsonDocuments {
    const val MAX_HTML_CHARS = 2 * 1024 * 1024
    private const val MAX_DEPTH = 64
    private val json = Json { isLenient = false }

    fun document(html: String, pageUrl: String): Document {
        require(html.length <= MAX_HTML_CHARS) { "Page exceeds parser limit" }
        return Jsoup.parse(html, pageUrl)
    }

    fun parse(text: String): JsonElement {
        require(text.length <= MAX_HTML_CHARS) { "JSON exceeds parser limit" }
        val value = text.trim().removePrefix("\uFEFF").trim()
        val end = balancedEnd(value, 0)
        require(end == value.length) { "Invalid JSON document" }
        return json.parseToJsonElement(value)
    }

    fun percentDecoded(text: String): String = URLDecoder.decode(text.trim().replace("+", "%2B"), "UTF-8")

    /** A small lexer avoids recognizing an assignment inside a quoted string or JS comment. */
    fun assignments(script: String, names: Set<String>): List<String> {
        require(script.length <= MAX_HTML_CHARS)
        val found = mutableListOf<String>()
        var index = 0
        while (index < script.length && found.size < 32) {
            val ch = script[index]
            when {
                ch == '\'' || ch == '"' || ch == '`' -> index = skipQuoted(script, index, ch)
                ch == '/' && script.getOrNull(index + 1) == '/' -> {
                    index = script.indexOf('\n', index + 2).takeIf { it >= 0 } ?: script.length
                }
                ch == '/' && script.getOrNull(index + 1) == '*' -> {
                    index = script.indexOf("*/", index + 2).takeIf { it >= 0 }?.plus(2) ?: script.length
                }
                ch == '/' -> index = skipRegularExpression(script, index)
                ch.isLetter() || ch == '_' || ch == '$' -> {
                    val start = index++
                    while (index < script.length && (script[index].isLetterOrDigit() || script[index] == '_' || script[index] == '$')) index++
                    if (script.substring(start, index) in names) {
                        var value = index
                        while (script.getOrNull(value)?.isWhitespace() == true) value++
                        if (script.getOrNull(value) == '=' && script.getOrNull(value + 1) != '=') {
                            value++
                            while (script.getOrNull(value)?.isWhitespace() == true) value++
                            if (script.getOrNull(value) == '{' || script.getOrNull(value) == '[') {
                                try {
                                    val end = balancedEnd(script, value)
                                    found += script.substring(value, end)
                                    index = end
                                } catch (_: IllegalArgumentException) {
                                    // Preserve a structural failure for the parser, without page values in diagnostics.
                                    found += "{invalid-json}"
                                }
                            }
                        }
                    }
                }
                else -> index++
            }
        }
        return found
    }

    private fun skipQuoted(text: String, start: Int, quote: Char): Int {
        var index = start + 1
        while (index < text.length) {
            if (text[index] == '\\') index += 2
            else if (text[index++] == quote) return index
        }
        return text.length
    }

    private fun skipRegularExpression(text: String, start: Int): Int {
        var index = start + 1
        var inClass = false
        while (index < text.length) {
            when (text[index]) {
                '\\' -> index++
                '[' -> inClass = true
                ']' -> inClass = false
                '/' -> if (!inClass) return index + 1
                '\n', '\r' -> return index
            }
            index++
        }
        return text.length
    }

    private fun balancedEnd(text: String, start: Int): Int {
        require(text.getOrNull(start) == '{' || text.getOrNull(start) == '[') { "Expected JSON container" }
        val stack = CharArray(MAX_DEPTH)
        var depth = 0
        var index = start
        while (index < text.length) {
            when (val ch = text[index]) {
                '"' -> {
                    index = skipQuoted(text, index, '"')
                    continue
                }
                '{', '[' -> {
                    require(depth < MAX_DEPTH) { "JSON nesting exceeds limit" }
                    stack[depth++] = ch
                }
                '}', ']' -> {
                    require(depth > 0 && stack[depth - 1] == if (ch == '}') '{' else '[') { "Unbalanced JSON" }
                    depth--
                    if (depth == 0) return index + 1
                }
            }
            index++
        }
        throw IllegalArgumentException("Incomplete JSON")
    }
}
