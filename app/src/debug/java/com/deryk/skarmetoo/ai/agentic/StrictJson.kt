package com.deryk.skarmetoo.ai.agentic

/** Syntax-only RFC 8259 check before Android's deliberately lenient JSONTokener. */
internal object StrictJson {
  fun check(text: String) {
    var pos = 0
    fun ws() {
      while (pos < text.length && text[pos] in " \r\n\t") pos++
    }
    fun take(c: Char) {
      ws()
      require(pos < text.length && text[pos] == c) { "Invalid JSON at $pos" }
      pos++
    }
    fun string(): String {
      take('"')
      val start = pos
      while (pos < text.length) {
        val c = text[pos++]
        if (c == '"') return text.substring(start, pos - 1)
        require(c.code >= 32)
        if (c == '\\') {
          require(pos < text.length)
          val e = text[pos++]
          require(e in "\"\\/bfnrtu")
          if (e == 'u') {
            repeat(4) { require(pos < text.length && text[pos++] in "0123456789abcdefABCDEF") }
          }
        }
      }
      error("Unterminated JSON string")
    }
    fun value(depth: Int) {
      require(depth <= 32) { "JSON nesting exceeds limit" }
      ws()
      require(pos < text.length)
      when (text[pos]) {
        '{' -> {
          pos++
          ws()
          val keys = mutableSetOf<String>()
          if (pos < text.length && text[pos] == '}') {
            pos++
            return
          }
          while (true) {
            val raw = string()
            val decoded = org.json.JSONTokener("\"$raw\"").nextValue().toString()
            require(keys.add(decoded)) { "Duplicate JSON property" }
            take(':')
            value(depth + 1)
            ws()
            require(pos < text.length)
            if (text[pos] == '}') {
              pos++
              break
            }
            take(',')
          }
        }
        '[' -> {
          pos++
          ws()
          if (pos < text.length && text[pos] == ']') {
            pos++
            return
          }
          var count = 0
          while (true) {
            require(++count <= 2048)
            value(depth + 1)
            ws()
            require(pos < text.length)
            if (text[pos] == ']') {
              pos++
              break
            }
            take(',')
          }
        }
        '"' -> string()
        't',
        'f',
        'n' -> {
          val literal =
              when (text[pos]) {
                't' -> "true"
                'f' -> "false"
                else -> "null"
              }
          require(text.startsWith(literal, pos))
          pos += literal.length
        }
        else -> {
          val match = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").find(text, pos)
          require(match != null && match.range.first == pos)
          pos = match.range.last + 1
        }
      }
    }
    value(0)
    ws()
    require(pos == text.length) { "Trailing JSON data" }
  }
}
