package app.androidrik.rikkahub.tikz

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 测试 B（函数图像+网格）设备端 xdvipdfmx 崩溃（ttbc_output_close SIGABRT）二分定位：
 * 逐组件编译，找出设备 so 触发崩溃的最小组合。POC 宿主 so 全组合可编译（9051B），
 * 设备 so 崩 → 设备 libtex_poc.so 的内容相关 bug。
 */
@RunWith(AndroidJUnit4::class)
class BisectTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun compile(code: String): Boolean = runBlocking {
        withContext(Dispatchers.IO) {
            // 注意：崩溃发生在隔离进程，主进程只会收到 failed（进程重启）
            TectonicNative.compileIsolated(context, code) != null
        }
    }

    private fun tryCase(name: String, body: String) {
        val wrapper = "\\begin{tikzpicture}\n$body\n\\end{tikzpicture}"
        val ok = compile(wrapper)
        android.util.Log.i("BisectTest", "CASE $name => ${if (ok) "OK" else "FAIL"}")
    }

    @Test
    fun bisectComponents() {
        assumeTrue(TectonicNative.available)
        tryCase("grid-only", "\\draw[gray!30, very thin] (-3,-3) grid (3,3);")
        tryCase("axis-only", "\\draw[thick, ->] (-3.5,0) -- (3.5,0) node[right]{\$x\$};")
        tryCase("foreach-labels-only",
            "\\foreach \\x in {-2,-1,1,2} \\draw (\\x,0.1) -- (\\x,-0.1) node[below]{\$\\x\$};")
        tryCase("foreach-footnotesize-math",
            "\\foreach \\x in {-2,-1,1,2} \\draw (\\x,0.1) -- (\\x,-0.1) node[below]{\\footnotesize \$\\x\$};\n" +
                "\\foreach \\y in {-2,-1,1,2} \\draw (0.1,\\y) -- (-0.1,\\y) node[left]{\\footnotesize \$\\y\$};")
        // 精确定界：footnotesize 是否需要 foreach/数学才崩
        tryCase("footnotesize-plain-node", "\\node at (0,0) {\\footnotesize ab};")
        tryCase("footnotesize-math-node", "\\node at (0,0) {\\footnotesize \$x\$};")
        tryCase("footnotesize-foreach-plain", "\\foreach \\x in {1,2} \\node at (\\x,0) {\\footnotesize n};")
        tryCase("small-math-node", "\\node at (0,0) {\\small \$x\$};")
        tryCase("scriptsize-math-node", "\\node at (0,0) {\\scriptsize \$x\$};")
        tryCase("plot-only-samples50",
            "\\draw[blue, thick, smooth] plot[domain=-3:3, samples=50] (\\x, {0.5*\\x*\\x - 1});")
        tryCase("plot-only-samples20",
            "\\draw[blue, thick, smooth] plot[domain=-3:3, samples=20] (\\x, {0.5*\\x*\\x - 1});")
        tryCase("plot-nosmooth",
            "\\draw[blue, thick] plot[domain=-3:3, samples=50] (\\x, {0.5*\\x*\\x - 1});")
        tryCase("circle-only", "\\draw[red, thick] (0,0) circle (2);")
        tryCase("math-node-only", "\\node[blue] at (2.5,2.5) {\$y=\\frac{x^2}{2}-1\$};")
        tryCase("grid-plus-plot",
            "\\draw[gray!30, very thin] (-3,-3) grid (3,3);\n\\draw[blue, thick, smooth] plot[domain=-3:3, samples=50] (\\x, {0.5*\\x*\\x - 1});")
        tryCase("full-B",
            "\\draw[gray!30, very thin] (-3,-3) grid (3,3);\n" +
                "\\draw[thick, ->] (-3.5,0) -- (3.5,0) node[right]{\$x\$};\n" +
                "\\draw[thick, ->] (0,-3.5) -- (0,3.5) node[above]{\$y\$};\n" +
                "\\foreach \\x in {-2,-1,1,2} \\draw (\\x,0.1) -- (\\x,-0.1) node[below]{\$\\x\$};\n" +
                "\\foreach \\y in {-2,-1,1,2} \\draw (0.1,\\y) -- (-0.1,\\y) node[left]{\$\\y\$};\n" +
                "\\draw[blue, thick, smooth] plot[domain=-3:3, samples=50] (\\x, {0.5*\\x*\\x - 1});\n" +
                "\\draw[red, thick] (0,0) circle (2);\n" +
                "\\node[blue] at (2.5,2.5) {\$y=\\frac{x^2}{2}-1\$};\n" +
                "\\node[red] at (-2.5,-1.5) {\$x^2+y^2=4\$};")
        // 用户原始 B（含 \footnotesize，从消息库提取的精确文本）
        tryCase("user-exact-B",
            "\\draw[gray!30, very thin] (-3,-3) grid (3,3);\n" +
                "\\draw[thick, ->] (-3.5,0) -- (3.5,0) node[right]{\$x\$};\n" +
                "\\draw[thick, ->] (0,-3.5) -- (0,3.5) node[above]{\$y\$};\n" +
                "\\foreach \\x in {-2,-1,1,2} \\draw (\\x,0.1) -- (\\x,-0.1) node[below]{\\footnotesize \$\\x\$};\n" +
                "\\foreach \\y in {-2,-1,1,2} \\draw (0.1,\\y) -- (-0.1,\\y) node[left]{\\footnotesize \$\\y\$};\n" +
                "\\draw[blue, thick, smooth] plot[domain=-3:3, samples=50] (\\x, {0.5*\\x*\\x - 1});\n" +
                "\\draw[red, thick] (0,0) circle (2);\n" +
                "\\node[blue] at (2.5,2.5) {\$y=\\frac{x^2}{2}-1\$};\n" +
                "\\node[red] at (-2.5,-1.5) {\$x^2+y^2=4\$};")
        assertTrue("bisect ran", true)
    }
}
