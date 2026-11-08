package com.topz.stitchdraw.core

/**
 * Tiny dependency-free JSON reader/writer used for workspace layouts now and
 * for editable project data later. Plain JVM so it is unit-testable off-device.
 *
 * Values map to: Map<String, Any?>, List<Any?>, String, Double/Long, Boolean, null.
 */
object MiniJson {

    class JsonException(message: String) : RuntimeException(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.atEnd()) throw JsonException("Trailing data at ${p.pos}")
        return v
    }

    fun write(value: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        writeValue(sb, value, pretty, 0)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, v: Any?, pretty: Boolean, depth: Int) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float -> sb.append(if (v.isFinite()) v.toString() else "0")
            is Double -> sb.append(if (v.isFinite()) v.toString() else "0")
            is String -> writeString(sb, v)
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, pretty, depth + 1)
                    writeString(sb, k.toString())
                    sb.append(if (pretty) ": " else ":")
                    writeValue(sb, value, pretty, depth + 1)
                }
                newline(sb, pretty, depth)
                sb.append('}')
            }
            is Iterable<*> -> {
                val items = v.toList()
                if (items.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    newline(sb, pretty, depth + 1)
                    writeValue(sb, item, pretty, depth + 1)
                }
                newline(sb, pretty, depth)
                sb.append(']')
            }
            else -> writeString(sb, v.toString())
        }
    }

    private fun newline(sb: StringBuilder, pretty: Boolean, depth: Int) {
        if (!pretty) return
        sb.append('\n')
        repeat(depth) { sb.append("  ") }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0
        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            if (atEnd()) throw JsonException("Unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw JsonException("Unexpected '$c' at $pos")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw JsonException("Bad literal at $pos")
            pos += word.length
            return v
        }

        private fun num(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val t = s.substring(start, pos)
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) {
                t.toDoubleOrNull() ?: throw JsonException("Bad number '$t'")
            } else {
                t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw JsonException("Bad number '$t'")
            }
        }

        private fun str(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("Unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonException("Bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonException("Bad unicode escape")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw JsonException("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return out }
            while (true) {
                skipWs()
                out.add(value())
                skipWs()
                if (atEnd()) throw JsonException("Unterminated array")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw JsonException("Expected , or ] at ${pos - 1}")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return out }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') throw JsonException("Expected key at $pos")
                val k = str()
                skipWs()
                if (atEnd() || s[pos++] != ':') throw JsonException("Expected : at ${pos - 1}")
                skipWs()
                out[k] = value()
                skipWs()
                if (atEnd()) throw JsonException("Unterminated object")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw JsonException("Expected , or } at ${pos - 1}")
                }
            }
        }
    }
}

/** Typed, forgiving accessors so loaders can tolerate missing/extra fields (backward compatibility). */
@Suppress("UNCHECKED_CAST")
fun Any?.asMap(): Map<String, Any?> = (this as? Map<String, Any?>) ?: emptyMap()
fun Any?.asList(): List<Any?> = (this as? List<Any?>) ?: emptyList()
fun Any?.asString(default: String = ""): String = (this as? String) ?: default
fun Any?.asBool(default: Boolean = false): Boolean = (this as? Boolean) ?: default
fun Any?.asDouble(default: Double = 0.0): Double = (this as? Number)?.toDouble() ?: default
fun Any?.asInt(default: Int = 0): Int = (this as? Number)?.toInt() ?: default
