package ir.yadino.domain.json

/**
 * A small, dependency free JSON implementation used for backups and settings export.
 * It supports the full JSON grammar (RFC 8259) including unicode escapes and keeps the
 * insertion order of object members, which makes outputs deterministic.
 */
sealed interface JsonValue

data class JsonObject(val members: Map<String, JsonValue>) : JsonValue {
    operator fun get(key: String): JsonValue? = members[key]
    fun string(key: String): String? = (members[key] as? JsonString)?.value
    fun long(key: String): Long? = (members[key] as? JsonNumber)?.toLongExact()
    fun int(key: String): Int? = long(key)?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else null }
    fun bool(key: String): Boolean? = (members[key] as? JsonBool)?.value
    fun obj(key: String): JsonObject? = members[key] as? JsonObject
    fun array(key: String): JsonArray? = members[key] as? JsonArray
    fun isNull(key: String): Boolean = members[key] == null || members[key] == JsonNull
}

data class JsonArray(val items: List<JsonValue>) : JsonValue

data class JsonString(val value: String) : JsonValue

/** Numbers are kept as their textual representation to avoid precision loss. */
data class JsonNumber(val raw: String) : JsonValue {
    fun toLongExact(): Long? = raw.toLongOrNull()
    fun toDouble(): Double = raw.toDouble()
}

data class JsonBool(val value: Boolean) : JsonValue

data object JsonNull : JsonValue

class JsonParseException(message: String, val position: Int) : Exception("$message at position $position")

/** Builder helpers. */
fun jsonObject(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(linkedMapOf<String, JsonValue>().apply {
    for ((k, v) in pairs) put(k, Json.toJsonValue(v))
})

object Json {

    /** Maximum nesting depth accepted by the parser (protects against malicious input). */
    private const val MAX_DEPTH = 64

    fun parse(text: String): JsonValue {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue(0)
        p.skipWs()
        if (p.pos != text.length) throw JsonParseException("Unexpected trailing content", p.pos)
        return v
    }

    fun stringify(value: JsonValue, pretty: Boolean = false): String {
        val sb = StringBuilder()
        write(sb, value, if (pretty) 0 else -1)
        return sb.toString()
    }

    fun toJsonValue(v: Any?): JsonValue = when (v) {
        null -> JsonNull
        is JsonValue -> v
        is String -> JsonString(v)
        is Boolean -> JsonBool(v)
        is Int, is Long, is Short, is Byte -> JsonNumber(v.toString())
        is Float -> JsonNumber(v.toString())
        is Double -> JsonNumber(if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString())
        is Map<*, *> -> JsonObject(linkedMapOf<String, JsonValue>().apply { v.forEach { (k, value) -> put(k.toString(), toJsonValue(value)) } })
        is Iterable<*> -> JsonArray(v.map { toJsonValue(it) })
        is Enum<*> -> JsonString(v.name)
        else -> JsonString(v.toString())
    }

    private fun write(sb: StringBuilder, v: JsonValue, indent: Int) {
        when (v) {
            is JsonNull -> sb.append("null")
            is JsonBool -> sb.append(if (v.value) "true" else "false")
            is JsonNumber -> sb.append(v.raw)
            is JsonString -> quote(sb, v.value)
            is JsonArray -> {
                if (v.items.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                v.items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    newline(sb, if (indent < 0) -1 else indent + 1)
                    write(sb, item, if (indent < 0) -1 else indent + 1)
                }
                newline(sb, indent)
                sb.append(']')
            }
            is JsonObject -> {
                if (v.members.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, item) in v.members) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, if (indent < 0) -1 else indent + 1)
                    quote(sb, k)
                    sb.append(if (indent >= 0) ": " else ":")
                    write(sb, item, if (indent < 0) -1 else indent + 1)
                }
                newline(sb, indent)
                sb.append('}')
            }
        }
    }

    private fun newline(sb: StringBuilder, indent: Int) {
        if (indent < 0) return
        sb.append('\n')
        repeat(indent) { sb.append("  ") }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == '\u2028' || c == '\u2029') {
                    sb.append("\\u").append(String.format("%04x", c.code))
                } else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        fun readValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) throw JsonParseException("Nesting too deep", pos)
            if (pos >= s.length) throw JsonParseException("Unexpected end of input", pos)
            return when (val c = s[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> JsonString(readString())
                't' -> literal("true", JsonBool(true))
                'f' -> literal("false", JsonBool(false))
                'n' -> literal("null", JsonNull)
                else -> if (c == '-' || c in '0'..'9') readNumber() else throw JsonParseException("Unexpected character '$c'", pos)
            }
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!s.startsWith(word, pos)) throw JsonParseException("Invalid literal", pos)
            pos += word.length
            return value
        }

        private fun readObject(depth: Int): JsonObject {
            pos++ // {
            val map = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return JsonObject(map) }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') throw JsonParseException("Expected member name", pos)
                val key = readString()
                skipWs()
                if (pos >= s.length || s[pos] != ':') throw JsonParseException("Expected ':'", pos)
                pos++
                skipWs()
                map[key] = readValue(depth + 1)
                skipWs()
                if (pos >= s.length) throw JsonParseException("Unterminated object", pos)
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return JsonObject(map) }
                    else -> throw JsonParseException("Expected ',' or '}'", pos)
                }
            }
        }

        private fun readArray(depth: Int): JsonArray {
            pos++ // [
            val list = ArrayList<JsonValue>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return JsonArray(list) }
            while (true) {
                skipWs()
                list += readValue(depth + 1)
                skipWs()
                if (pos >= s.length) throw JsonParseException("Unterminated array", pos)
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return JsonArray(list) }
                    else -> throw JsonParseException("Expected ',' or ']'", pos)
                }
            }
        }

        private fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw JsonParseException("Unterminated string", pos)
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) throw JsonParseException("Bad escape", pos)
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonParseException("Bad unicode escape", pos)
                                val hex = s.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: throw JsonParseException("Bad unicode escape", pos)
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw JsonParseException("Bad escape '\\$e'", pos)
                        }
                    }
                    c < ' ' -> throw JsonParseException("Control character in string", pos)
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): JsonNumber {
            val start = pos
            if (s[pos] == '-') pos++
            if (pos >= s.length) throw JsonParseException("Bad number", pos)
            if (s[pos] == '0') pos++ else {
                if (s[pos] !in '1'..'9') throw JsonParseException("Bad number", pos)
                while (pos < s.length && s[pos].isAsciiDigit()) pos++
            }
            if (pos < s.length && s[pos] == '.') {
                pos++
                if (pos >= s.length || !s[pos].isAsciiDigit()) throw JsonParseException("Bad fraction", pos)
                while (pos < s.length && s[pos].isAsciiDigit()) pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                if (pos >= s.length || !s[pos].isAsciiDigit()) throw JsonParseException("Bad exponent", pos)
                while (pos < s.length && s[pos].isAsciiDigit()) pos++
            }
            return JsonNumber(s.substring(start, pos))
        }

        private fun Char.isAsciiDigit() = this in '0'..'9'
    }
}
