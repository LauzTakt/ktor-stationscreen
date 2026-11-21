// Small JSON parser for KTVM (no reflection): objects become JsonObject, arrays JsonArray,
// numbers Long or Double, plus String, Boolean and null.
package esp32.json

class JsonException(message: String) : Exception(message)

class JsonObject(val map: Map<String, Any?>) {
    operator fun get(key: String): Any? = map[key]
    fun has(key: String) = map.containsKey(key) && map[key] != null
    fun str(key: String, default: String = ""): String = (map[key] as? String) ?: default
    fun strOrNull(key: String): String? = map[key] as? String
    fun int(key: String, default: Int = 0): Int = num(map[key])?.toInt() ?: default
    fun long(key: String, default: Long = 0L): Long = num(map[key]) ?: default
    fun bool(key: String, default: Boolean = false): Boolean = (map[key] as? Boolean) ?: default
    fun obj(key: String): JsonObject? = map[key] as? JsonObject
    fun arr(key: String): JsonArray = (map[key] as? JsonArray) ?: JsonArray(emptyList())
    val keys: Set<String> get() = map.keys
    override fun toString() = Json.stringify(this)
}

class JsonArray(val list: List<Any?>) {
    val size: Int get() = list.size
    operator fun get(i: Int): Any? = list[i]
    fun objects(): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        for (v in list) if (v is JsonObject) out.add(v)
        return out
    }
    override fun toString() = Json.stringify(this)
}

private fun num(v: Any?): Long? = when (v) {
    is Long -> v
    is Double -> v.toLong()
    is String -> v.toLongOrNull()
    else -> null
}

object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.ws()
        if (p.i < text.length) p.fail("unexpected trailing data")
        return v
    }

    fun parseObject(text: String): JsonObject =
        parse(text) as? JsonObject ?: throw JsonException("expected a JSON object")

    fun parseArray(text: String): JsonArray =
        parse(text) as? JsonArray ?: throw JsonException("expected a JSON array")

    fun stringify(v: Any?): String {
        val sb = StringBuilder()
        write(sb, v)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is JsonObject -> {
                sb.append('{')
                var first = true
                for (k in v.map.keys) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k); sb.append(':'); write(sb, v.map[k])
                }
                sb.append('}')
            }
            is JsonArray -> {
                sb.append('[')
                for (i in 0 until v.list.size) { if (i > 0) sb.append(','); write(sb, v.list[i]) }
                sb.append(']')
            }
            else -> sb.append(v.toString())
        }
    }

    // Copies runs of plain characters with substring (on KTVM strings are UTF-8 bytes; appending them one by
    // one as Char would re-encode every byte >= 0x80).
    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        var run = 0
        for (k in 0 until s.length) {
            val esc = when (s[k]) {
                '"' -> "\\\""
                '\\' -> "\\\\"
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> null
            }
            if (esc != null) {
                if (k > run) sb.append(s.substring(run, k))
                sb.append(esc)
                run = k + 1
            }
        }
        if (s.length > run) sb.append(s.substring(run))
        sb.append('"')
    }
}

private class Parser(val s: String) {
    var i = 0

    fun fail(msg: String): Nothing = throw JsonException("JSON: $msg at $i")

    fun ws() {
        while (i < s.length) {
            val c = s[i]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++ else break
        }
    }

    fun value(): Any? {
        ws()
        if (i >= s.length) fail("unexpected end")
        val c = s[i]
        return when {
            c == '{' -> obj()
            c == '[' -> arr()
            c == '"' -> str()
            c == 't' -> lit("true", true)
            c == 'f' -> lit("false", false)
            c == 'n' -> lit("null", null)
            c == '-' || (c >= '0' && c <= '9') -> number()
            else -> fail("unexpected '$c'")
        }
    }

    private fun lit(word: String, v: Any?): Any? {
        if (i + word.length > s.length || s.substring(i, i + word.length) != word) fail("bad literal")
        i += word.length
        return v
    }

    private fun obj(): JsonObject {
        i++
        val m = LinkedHashMap<String, Any?>()
        ws()
        if (i < s.length && s[i] == '}') { i++; return JsonObject(m) }
        while (true) {
            ws()
            if (i >= s.length || s[i] != '"') fail("expected a key")
            val k = str()
            ws()
            if (i >= s.length || s[i] != ':') fail("expected ':'")
            i++
            m[k] = value()
            ws()
            if (i >= s.length) fail("unterminated object")
            val c = s[i++]
            if (c == '}') return JsonObject(m)
            if (c != ',') fail("expected ',' or '}'")
        }
    }

    private fun arr(): JsonArray {
        i++
        val l = ArrayList<Any?>()
        ws()
        if (i < s.length && s[i] == ']') { i++; return JsonArray(l) }
        while (true) {
            l.add(value())
            ws()
            if (i >= s.length) fail("unterminated array")
            val c = s[i++]
            if (c == ']') return JsonArray(l)
            if (c != ',') fail("expected ',' or ']'")
        }
    }

    private fun str(): String {
        i++
        val sb = StringBuilder()
        var run = i
        while (true) {
            if (i >= s.length) fail("unterminated string")
            val c = s[i]
            if (c == '"') {
                if (i > run) sb.append(s.substring(run, i))
                i++
                return sb.toString()
            }
            if (c != '\\') { i++; continue }
            if (i > run) sb.append(s.substring(run, i))
            i++
            if (i >= s.length) fail("bad escape")
            when (val e = s[i++]) {
                'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                'u' -> { if (i + 4 > s.length) fail("bad \\u escape"); sb.append(hex4(i).toChar()); i += 4 }
                else -> sb.append(e)
            }
            run = i
        }
    }

    private fun hex4(at: Int): Int {
        var v = 0
        for (k in at until at + 4) {
            val c = s[k]
            v = v * 16 + when {
                c >= '0' && c <= '9' -> c - '0'
                c >= 'a' && c <= 'f' -> c - 'a' + 10
                c >= 'A' && c <= 'F' -> c - 'A' + 10
                else -> fail("bad hex digit")
            }
        }
        return v
    }

    private fun number(): Any {
        val start = i
        if (s[i] == '-') i++
        var frac = false
        while (i < s.length) {
            val c = s[i]
            if (c >= '0' && c <= '9') i++
            else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { frac = true; i++ }
            else break
        }
        val t = s.substring(start, i)
        return if (frac) (t.toDoubleOrNull() ?: fail("bad number")) else (t.toLongOrNull() ?: fail("bad number"))
    }
}
