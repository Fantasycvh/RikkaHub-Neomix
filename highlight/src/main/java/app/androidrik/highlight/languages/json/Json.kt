package app.androidrik.highlight.languages.json

import app.androidrik.highlight.core.C_BLOCK_COMMENT_MODE
import app.androidrik.highlight.core.C_LINE_COMMENT_MODE
import app.androidrik.highlight.core.C_NUMBER_MODE
import app.androidrik.highlight.core.Language
import app.androidrik.highlight.core.QUOTE_STRING_MODE
import app.androidrik.highlight.core.keywords
import app.androidrik.highlight.core.mode

/** JSON, ported from `lib/languages/json.js` of `highlight.js` 11.11.1. */
internal fun json(): Language {
    val attribute = mode {
        scope = "attr"
        begin = """"(\\.|[^\\"\r\n])*"(?=\s*:)"""
        relevance = 1.01
    }
    val punctuation = mode {
        match = """[{}[\],:]"""
        scope = "punctuation"
        relevance = 0.0
    }
    val literals = listOf("true", "false", "null")

    // Upstream would normally rely on `keywords` here, but matching the literals with a mode is
    // what makes the very tight `illegal: \S` rule below workable: anything else is then flagged,
    // which keeps JSON from claiming every JSON-shaped snippet during auto detection.
    val literalsMode = mode {
        scope = "literal"
        beginKeywords = literals.joinToString(separator = " ")
    }

    return Language(
        name = "JSON",
        aliases = setOf("json", "jsonc", "json5"),
        root = mode {
            keywords = keywords { literal(literals) }
            contains = listOf(
                attribute,
                punctuation,
                QUOTE_STRING_MODE,
                literalsMode,
                C_NUMBER_MODE,
                C_LINE_COMMENT_MODE,
                C_BLOCK_COMMENT_MODE,
            )
            illegal = """\S"""
        },
    )
}
