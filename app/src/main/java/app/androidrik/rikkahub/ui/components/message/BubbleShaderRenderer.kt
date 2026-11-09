package app.androidrik.rikkahub.ui.components.message

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import app.androidrik.rikkahub.data.model.BubbleShaderStyle
import app.androidrik.rikkahub.data.model.BubbleShaderTemplate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** One instance per mounted layer. Slider edits update uniforms without recompiling the program. */
internal class BubbleShaderRenderer {
    private var runtime: Shader? = null
    private var runtimeFailed = false
    private var applied: Pair<BubbleShaderStyle, Float>? = null
    private var rasterStyle: BubbleShaderStyle? = null
    private var raster: BitmapShader? = null
    private val matrix = Matrix()

    fun prepare(style: BubbleShaderStyle, density: Float, offset: Offset, hardware: Boolean): Shader {
        if (hardware && Build.VERSION.SDK_INT >= 33 && !runtimeFailed) {
            try {
                val shader = runtime ?: BubbleRuntimeShader.create().also { runtime = it }
                if (applied?.first != style || applied?.second != density) {
                    BubbleRuntimeShader.update(shader as RuntimeShader, style, density)
                    applied = style to density
                }
                matrix.setTranslate(-offset.x, -offset.y)
                shader.setLocalMatrix(matrix)
                return shader
            } catch (failure: RuntimeException) {
                runtimeFailed = true
                runtime = null
                Log.w("BubbleShader", "Built-in shader unavailable; using raster material", failure)
            }
        }
        // Transform-independent tiles are shared across messages, exports and slider edits.
        val key = style.copy(scaleDp = 128f, angleDegrees = 0f)
        if (rasterStyle != key) {
            rasterStyle = key
            raster = BitmapShader(BubbleShaderTiles.get(key), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        matrix.setScale(style.scaleDp * density / BUBBLE_SHADER_TILE_SIDE, style.scaleDp * density / BUBBLE_SHADER_TILE_SIDE)
        matrix.postRotate(style.angleDegrees)
        matrix.postTranslate(-offset.x, -offset.y)
        return requireNotNull(raster).also { it.setLocalMatrix(matrix) }
    }
}

private object BubbleShaderTiles {
    // 16 * 128 * 128 * 4 = 1 MiB; never recycle bitmaps still held by a mounted painter.
    private val cache = LruCache<BubbleShaderStyle, Bitmap>(16)
    @Synchronized fun get(style: BubbleShaderStyle): Bitmap = cache.get(style) ?: run {
        val field = BubbleShaderField(style)
        val side = BUBBLE_SHADER_TILE_SIDE
        val pixels = IntArray(side * side) { index ->
            field.color((index % side + 0.5f) / side, (index / side + 0.5f) / side)
        }
        Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888).also { cache.put(style, it) }
    }
}

@RequiresApi(33)
internal object BubbleRuntimeShader {
    fun create() = RuntimeShader(SOURCE)

    fun update(shader: RuntimeShader, style: BubbleShaderStyle, density: Float) {
        shader.setIntUniform("uTemplate", when (style.template) {
            BubbleShaderTemplate.AURORA -> 0
            BubbleShaderTemplate.MARBLE -> 1
            BubbleShaderTemplate.IRIDESCENT -> 2
            BubbleShaderTemplate.RIPPLES -> 3
        })
        shader.setFloatUniform("uScale", style.scaleDp * density)
        val angle = style.angleDegrees * PI.toFloat() / 180f
        shader.setFloatUniform("uRotation", cos(angle), sin(angle))
        shader.setFloatUniform("uPhase", style.phase * (2f * PI.toFloat()))
        shader.setFloatUniform("uWarp", style.warp)
        shader.setFloatUniform("uContrast", style.contrast)
        style.colors.forEachIndexed { index, color ->
            shader.setColorUniform("uColor$index", (0xFF000000L or color.substring(1).toLong(16)).toInt())
        }
    }

    // All spatial fields are periodic. CPU fallback samples the same equations once per tile.
    private const val SOURCE = """
        uniform int uTemplate;
        uniform float uScale;
        uniform float2 uRotation;
        uniform float uPhase;
        uniform float uWarp;
        uniform float uContrast;
        layout(color) uniform half4 uColor0;
        layout(color) uniform half4 uColor1;
        layout(color) uniform half4 uColor2;
        half4 main(float2 coord) {
            float2 p = float2(uRotation.x * coord.x + uRotation.y * coord.y,
                             -uRotation.y * coord.x + uRotation.x * coord.y) * 6.28318530718 / uScale;
            float f;
            if (uTemplate == 0) {
                f = 0.5 + 0.25 * sin(p.x + uWarp * sin(p.y + uPhase))
                        + 0.25 * cos(p.y - uPhase + uWarp * sin(p.x));
            } else if (uTemplate == 1) {
                f = 0.5 + 0.5 * sin(2.0 * p.x + uPhase + 2.0 * uWarp * sin(p.y + sin(p.x - uPhase)));
            } else if (uTemplate == 2) {
                f = 0.5 + (sin(p.x + uPhase + uWarp * cos(p.y))
                        + sin(p.y - uPhase + uWarp * cos(p.x)) + cos(p.x + p.y + uPhase)) / 6.0;
            } else {
                float d = sqrt(max(0.0, 2.0 - cos(p.x) - cos(p.y)));
                f = 0.5 + 0.5 * cos(6.0 * d - uPhase + uWarp * sin(p.x + p.y));
            }
            float t = clamp((f - 0.5) * uContrast + 0.5, 0.0, 1.0) * 2.0;
            return t < 1.0 ? mix(uColor0, uColor1, half(t)) : mix(uColor1, uColor2, half(t - 1.0));
        }
    """
}
