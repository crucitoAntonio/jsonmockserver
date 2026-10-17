package com.dertoo.jsonmock.model

/**
 * Validador JSON mínimo (RFC 8259) sin dependencias: dice si el texto es JSON válido y,
 * si no, en qué línea/columna falla. No construye ningún objeto.
 */
object JsonCheck {

    data class Error(val line: Int, val column: Int, val message: String) {
        override fun toString() = "line $line, column $column: $message"
    }

    /** null si es válido. */
    fun validate(text: String): Error? = try {
        Parser(text).run { ws(); value(); ws(); if (i < s.length) fail("unexpected content after the JSON") }
        null
    } catch (e: ParseError) {
        e.error
    }

    fun isValid(text: String) = text.isNotBlank() && validate(text) == null

    private class ParseError(val error: Error) : Exception()

    private class Parser(val s: String) {
        var i = 0

        fun fail(msg: String): Nothing {
            val before = s.substring(0, minOf(i, s.length))
            val line = before.count { it == '\n' } + 1
            val col = i - (before.lastIndexOf('\n') + 1) + 1
            throw ParseError(Error(line, col, msg))
        }

        fun ws() { while (i < s.length && s[i] in " \t\r\n") i++ }

        fun value() {
            if (i >= s.length) fail("expected a value")
            when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true")
                'f' -> lit("false")
                'n' -> lit("null")
                else -> if (s[i] == '-' || s[i].isDigit()) num() else fail("unexpected character '${s[i]}'")
            }
        }

        fun obj() {
            i++; ws()
            if (peek('}')) { i++; return }
            while (true) {
                ws(); if (!peek('"')) fail("expected a quoted key")
                str(); ws(); expect(':'); ws(); value(); ws()
                if (peek(',')) { i++; continue }
                expect('}'); return
            }
        }

        fun arr() {
            i++; ws()
            if (peek(']')) { i++; return }
            while (true) {
                ws(); value(); ws()
                if (peek(',')) { i++; continue }
                expect(']'); return
            }
        }

        fun str() {
            i++
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> { i++; return }
                    c == '\\' -> {
                        i++
                        if (i >= s.length) fail("incomplete escape")
                        when (s[i]) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> i++
                            'u' -> {
                                if (i + 4 >= s.length || !s.substring(i + 1, i + 5).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) fail("invalid \\u escape")
                                i += 5
                            }
                            else -> fail("invalid escape '\\${s[i]}'")
                        }
                    }
                    c < ' ' -> fail("control character inside a string (missing closing quote?)")
                    else -> i++
                }
            }
            fail("unterminated string (missing \")")
        }

        fun num() {
            if (peek('-')) i++
            if (peek('0')) i++ else digits()
            if (peek('.')) { i++; digits() }
            if (peek('e') || peek('E')) { i++; if (peek('+') || peek('-')) i++; digits() }
        }

        fun digits() {
            val start = i
            while (i < s.length && s[i].isDigit()) i++
            if (i == start) fail("expected a digit")
        }

        fun lit(word: String) {
            if (!s.startsWith(word, i)) fail("expected '$word'")
            i += word.length
        }

        fun peek(c: Char) = i < s.length && s[i] == c

        fun expect(c: Char) { if (!peek(c)) fail("expected '$c'"); i++ }
    }
}
