package app.androidrik.rikkahub.tikz

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 数学表达式求值器：供 TikZ `plot (\x, {expr})` 采样使用。
 *
 * 递归下降语法（优先级从低到高）：
 *   expression := term (('+' | '-') term)*
 *   term       := unary (('*' | '/') unary)*
 *   unary      := ('-' | '+')* power
 *   power      := primary ('^' unary)?          // 右结合
 *   primary    := NUMBER | IDENT '(' expression ')' | IDENT | '(' expression ')'
 *
 * 变量/常量：x、pi、e；函数：sin cos tan ln log sqrt abs exp asin acos。
 * 非法输入/未知函数/未知变量返回 NaN（调用方断开采样段）；1/0 自然产生 Infinity。
 */
object MathExpr {

    private val FUNCTIONS = mapOf(
        "sin" to { v: Double -> sin(v) },
        "cos" to { v: Double -> cos(v) },
        "tan" to { v: Double -> tan(v) },
        "ln" to { v: Double -> ln(v) },
        "log" to { v: Double -> log10(v) },
        "sqrt" to { v: Double -> sqrt(v) },
        "abs" to { v: Double -> abs(v) },
        "exp" to { v: Double -> exp(v) },
        "asin" to { v: Double -> asin(v) },
        "acos" to { v: Double -> acos(v) },
    )

    /** 求值表达式；任何解析/求值失败返回 NaN（不抛异常） */
    fun evaluate(expr: String, x: Double): Double = try {
        Parser(Tokenizer(expr), x).parseExpression()
    } catch (e: Exception) {
        Double.NaN
    }

    private sealed interface Token {
        data class Num(val v: Double) : Token
        data class Ident(val s: String) : Token
        object Plus : Token
        object Minus : Token
        object Star : Token
        object Slash : Token
        object Caret : Token
        object LParen : Token
        object RParen : Token
        object End : Token
    }

    private class Tokenizer(private val src: String) {
        private var pos = 0

        fun next(): Token {
            while (pos < src.length && src[pos].isWhitespace()) pos++
            if (pos >= src.length) return Token.End
            val c = src[pos]
            if (c.isDigit() || (c == '.' && pos + 1 < src.length && src[pos + 1].isDigit())) {
                val start = pos
                while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
                return Token.Num(src.substring(start, pos).toDoubleOrNull() ?: 0.0)
            }
            if (c.isLetter() || c == '_') {
                val start = pos
                while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
                return Token.Ident(src.substring(start, pos))
            }
            pos++
            return when (c) {
                '+' -> Token.Plus
                '-' -> Token.Minus
                '*' -> Token.Star
                '/' -> Token.Slash
                '^' -> Token.Caret
                '(' -> Token.LParen
                ')' -> Token.RParen
                else -> Token.End
            }
        }
    }

    private class Parser(private val tk: Tokenizer, private val x: Double) {
        private var cur: Token = tk.next()

        private fun advance(): Token {
            val t = cur
            cur = tk.next()
            return t
        }

        fun parseExpression(): Double {
            var v = parseTerm()
            while (true) {
                when (cur) {
                    is Token.Plus -> { advance(); v += parseTerm() }
                    is Token.Minus -> { advance(); v -= parseTerm() }
                    else -> return v
                }
            }
        }

        private fun parseTerm(): Double {
            var v = parseUnary()
            while (true) {
                when (cur) {
                    is Token.Star -> { advance(); v *= parseUnary() }
                    is Token.Slash -> { advance(); v /= parseUnary() }
                    else -> return v
                }
            }
        }

        private fun parseUnary(): Double {
            when (cur) {
                is Token.Minus -> { advance(); return -parseUnary() }
                is Token.Plus -> { advance(); return parseUnary() }
                else -> return parsePower()
            }
        }

        private fun parsePower(): Double {
            val base = parsePrimary()
            if (cur is Token.Caret) {
                advance()
                val exp = parseUnary()  // 右结合
                return base.pow(exp)
            }
            return base
        }

        private fun parsePrimary(): Double = when (val t = advance()) {
            is Token.Num -> t.v
            is Token.Ident -> {
                if (cur is Token.LParen) {
                    advance()
                    val arg = parseExpression()
                    if (cur is Token.RParen) advance()
                    FUNCTIONS[t.s.lowercase()]?.invoke(arg) ?: Double.NaN
                } else {
                    when (t.s.lowercase()) {
                        "x" -> x
                        "pi" -> Math.PI
                        "e" -> Math.E
                        else -> Double.NaN
                    }
                }
            }
            is Token.LParen -> {
                val v = parseExpression()
                if (cur is Token.RParen) advance()
                v
            }
            else -> Double.NaN
        }
    }
}
