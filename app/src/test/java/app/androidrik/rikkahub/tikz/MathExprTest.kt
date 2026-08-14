package app.androidrik.rikkahub.tikz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MathExpr 表达式求值器测试：TikZ plot 的 {expr} 采样用。
 * 优先级：^ > 一元负号 > * / > + -；^ 右结合；支持 x/pi/e 与 sin/cos/tan/ln/log/sqrt/abs。
 */
class MathExprTest {

    private fun assertNear(expected: Double, actual: Double) {
        assertEquals(expected, actual, 1e-9)
    }

    @Test
    fun `basic arithmetic respects precedence`() {
        assertNear(14.0, MathExpr.evaluate("2+3*4", 0.0))
        assertNear(20.0, MathExpr.evaluate("(2+3)*4", 0.0))
        assertNear(-1.0, MathExpr.evaluate("2-3", 0.0))
        assertNear(6.0, MathExpr.evaluate("12/2", 0.0))
        assertNear(7.0, MathExpr.evaluate("1+2*3", 0.0))
    }

    @Test
    fun `power is right associative`() {
        assertNear(8.0, MathExpr.evaluate("2^3", 0.0))
        assertNear(512.0, MathExpr.evaluate("2^3^2", 0.0))
    }

    @Test
    fun `unary minus binds after power`() {
        assertNear(-9.0, MathExpr.evaluate("-x^2", 3.0))
        assertNear(-5.0, MathExpr.evaluate("-5", 0.0))
        assertNear(5.0, MathExpr.evaluate("--5", 0.0))
        assertNear(0.125, MathExpr.evaluate("2^-3", 0.0))
    }

    @Test
    fun `variable x and constants`() {
        assertNear(25.0, MathExpr.evaluate("x*x", 5.0))
        assertNear(Math.PI, MathExpr.evaluate("pi", 0.0))
        assertNear(Math.E, MathExpr.evaluate("e", 0.0))
        // 左结合：(x/pi)*pi = x
        assertNear(Math.PI, MathExpr.evaluate("x/pi*pi", Math.PI))
    }

    @Test
    fun `functions evaluate`() {
        assertNear(1.0, MathExpr.evaluate("sin(pi/2)", 0.0))
        assertNear(0.0, MathExpr.evaluate("cos(pi/2)", 0.0))
        assertNear(1.0, MathExpr.evaluate("tan(pi/4)", 0.0))
        assertNear(3.0, MathExpr.evaluate("sqrt(9)", 0.0))
        assertNear(2.0, MathExpr.evaluate("log(100)", 0.0))
        assertNear(1.0, MathExpr.evaluate("ln(e)", 0.0))
        assertNear(5.0, MathExpr.evaluate("abs(-5)", 0.0))
    }

    @Test
    fun `nested and mixed expressions`() {
        assertNear(0.0, MathExpr.evaluate("sin(x-x)", 3.0))
        assertNear(3.0, MathExpr.evaluate("sqrt(1+2*4)", 0.0))
        assertNear(0.5, MathExpr.evaluate("x*x - x - 1.5", 2.0))
    }

    @Test
    fun `sqrt of negative is nan`() {
        assertTrue(MathExpr.evaluate("sqrt(-1)", 0.0).isNaN())
    }

    @Test
    fun `division by zero is infinite`() {
        assertTrue(MathExpr.evaluate("1/0", 0.0).isInfinite())
    }

    @Test
    fun `unknown function or variable is nan`() {
        assertTrue(MathExpr.evaluate("foo(3)", 0.0).isNaN())
        assertTrue(MathExpr.evaluate("y+1", 2.0).isNaN())
        assertTrue(MathExpr.evaluate("2+*3", 0.0).isNaN())
    }
}
