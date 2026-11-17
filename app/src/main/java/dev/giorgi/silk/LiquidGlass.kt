package dev.giorgi.silk

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi

/**
 * Liquid Glass background for any view.
 *
 * On Android 13+ a small GPU shader (AGSL) per panel:
 *   - shows the blurred wallpaper that lies under the panel
 *   - bends it near the edges like a thick lens (refraction)
 *   - splits colour slightly at the rim (chromatic aberration)
 *   - adds a thin specular highlight lit from the top-left
 * Without the wallpaper image it falls back to a plain translucent panel.
 *
 * [followsPager]: true for panels on home pages, which slide over the still
 * wallpaper and so must redraw while paging. The dock and menus don't.
 */
class LiquidGlass(
    private val host: View,
    private val radius: Float,
    private val darkness: Float = 0.08f,
    val followsPager: Boolean = true,
) : Drawable() {

    val isHostAttached: Boolean get() = host.isAttachedToWindow

    private val loc = IntArray(2)
    private val rect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = host.context.dpf(1f)
        color = 0x40FFFFFF
    }
    private var blurShader: BitmapShader? = null
    private var blurFor: Bitmap? = null
    private val matrix = Matrix()

    init {
        WallpaperSource.register(this)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        rect.set(0f, 0f, b.width().toFloat(), b.height().toFloat())
        host.getLocationOnScreen(loc)
        // getLocationOnScreen includes the host's own scale/translation (menu spring-in,
        // dock zoom). Remove it, so the glass samples what is behind the panel at rest.
        val ox = loc[0] - host.pivotX * (1f - host.scaleX) - host.translationX + b.left
        val oy = loc[1] - host.pivotY * (1f - host.scaleY) - host.translationY + b.top

        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        val wall = WallpaperSource.glassShader
        val drewLiquid = wall != null && Build.VERSION.SDK_INT >= 33 && drawLiquid(canvas, wall, ox, oy)
        if (!drewLiquid) {
            if (wall != null) drawBlurred(canvas, ox, oy)
            tint.color = 0x2EFFFFFF
            canvas.drawRoundRect(rect, radius, radius, tint)
            val half = stroke.strokeWidth / 2
            rect.inset(half, half)
            canvas.drawRoundRect(rect, radius, radius, stroke)
        }
        canvas.restore()
    }

    @RequiresApi(33)
    private fun drawLiquid(canvas: Canvas, wall: Shader, ox: Float, oy: Float): Boolean {
        val rs = sharedShader() ?: return false
        rs.setInputShader("wall", wall)
        rs.setFloatUniform("origin", ox, oy)
        rs.setFloatUniform("size", rect.width(), rect.height())
        rs.setFloatUniform("radius", radius)
        rs.setFloatUniform("dark", darkness)
        paint.shader = rs
        canvas.drawRect(rect, paint)
        return true
    }

    private fun drawBlurred(canvas: Canvas, ox: Float, oy: Float) {
        val bmp = WallpaperSource.glassBitmap ?: return
        if (blurFor !== bmp) {
            blurShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            blurFor = bmp
        }
        val shader = blurShader ?: return
        matrix.setScale(WallpaperSource.GLASS_SCALE, WallpaperSource.GLASS_SCALE)
        matrix.postTranslate(-ox, -oy)
        shader.setLocalMatrix(matrix)
        paint.shader = shader
        canvas.drawRoundRect(rect, radius, radius, paint)
        tint.color = (darkness * 255).toInt() shl 24
        canvas.drawRoundRect(rect, radius, radius, tint)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radius)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private var shaderBroken = false
        private var shared: RuntimeShader? = null

        /**
         * Compiled once per process, on first use. Uniforms are set per draw;
         * each recorded draw keeps its own copy, so sharing is safe.
         */
        @RequiresApi(33)
        private fun sharedShader(): RuntimeShader? {
            if (shaderBroken) return null
            shared?.let { return it }
            return try {
                RuntimeShader(SHADER).also { shared = it }
            } catch (e: Exception) {
                Log.w("Silk", "liquid glass shader failed to compile", e)
                shaderBroken = true
                null
            }
        }

        private const val SHADER = """
            uniform shader wall;
            uniform float2 origin;
            uniform float2 size;
            uniform float radius;
            uniform float dark;

            float sdf(float2 p) {
                float2 c = size * 0.5;
                float2 q = abs(p - c) - (c - float2(radius));
                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
            }

            half4 main(float2 p) {
                float d = sdf(p);
                if (d > 1.0) { return half4(0.0); }

                // k: 0 in the flat middle, rising to 1 at the rim.
                float band = min(min(size.x, size.y) * 0.25, 44.0);
                float k = clamp(1.0 + d / band, 0.0, 1.0);

                float3 c = float3(0.0);
                float2 g = float2(0.0);
                if (k <= 0.0) {
                    // Flat middle: no bending, no colour split, no rim -> one sample.
                    c = float3(wall.eval(origin + p).rgb);
                } else {
                    // Outward normal, analytic.
                    float2 cc = size * 0.5;
                    float2 v = p - cc;
                    float2 qq = abs(v) - (cc - float2(radius));
                    float2 qp = max(qq, float2(0.0));
                    float ql = length(qp);
                    g = ql > 0.0001 ? qp / ql : (qq.x > qq.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
                    g *= float2(v.x < 0.0 ? -1.0 : 1.0, v.y < 0.0 ? -1.0 : 1.0);

                    k = k * k;
                    // Refraction: near the rim, show what lies a little further out.
                    float2 sp = origin + p + g * (k * band * 0.6);
                    float ca = k * 3.0;
                    c.r = float(wall.eval(sp + g * ca).r);
                    c.g = float(wall.eval(sp).g);
                    c.b = float(wall.eval(sp - g * ca).b);
                }

                c = mix(c, float3(1.0), 0.08);
                c = c * (1.0 - dark);

                // Thin specular rim, brightest where it faces the light.
                float rim = clamp(1.0 - abs(d + 1.2) / 1.4, 0.0, 1.0);
                float facing = abs(dot(g, normalize(float2(-0.45, -0.9))));
                c += float3(rim * (0.15 + 0.55 * facing));
                c += float3(k * 0.06);

                c = clamp(c, 0.0, 1.0);
                float a = clamp(0.5 - d, 0.0, 1.0);
                return half4(half3(c * a), half(a));
            }
        """
    }
}
