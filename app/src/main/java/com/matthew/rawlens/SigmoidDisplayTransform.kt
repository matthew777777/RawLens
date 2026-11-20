// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.max
import kotlin.math.pow

/** darktable 5.6 sigmoid (sigmoid.c, Jakob Andrén and contributors), fully parametric.
 * Generalized log-logistic per channel in working Rec.2020 with darktable's
 * "preserve hue" RGB-ratio processing. Default parameters equal darktable's defaults.
 * Colorfulness is a RawLens post-scale (darktable leaves this to color balance rgb); 1 = off.
 */
internal object SigmoidDisplayTransform {
    private const val GREY = 0.1845

    data class Params(
        val magnitude: Float, val paperExposure: Float, val filmFog: Float,
        val filmPower: Float, val paperPower: Float
    ) {
        companion object {
            private fun gll(x: Double, mag: Double, pexp: Double, fog: Double, fp: Double, pp: Double): Double {
                val film = (fog + max(x, 0.0)).pow(fp)
                return mag * (film / (pexp + film)).pow(pp)
            }

            /** sigmoid.c commit_params: grey slope fixed by contrast, ends fixed by targets. */
            fun of(t: ToneLookSettings): Params {
                val b = t.bounded()
                val contrast = b.sigmoidContrast.toDouble()
                val white = b.sigmoidWhite.toDouble()
                val black = b.sigmoidBlack.toDouble().coerceAtLeast(1e-7)
                val d = 1e-6
                val refExp = GREY.pow(contrast) * (1.0 / GREY - 1.0)
                val refSlope = (gll(GREY + d, 1.0, refExp, 0.0, contrast, 1.0) -
                    gll(GREY - d, 1.0, refExp, 0.0, contrast, 1.0)) / (2 * d)
                val pp = 5.0.pow(-b.sigmoidSkew.toDouble())
                val wg = (white / GREY).pow(1.0 / pp) - 1.0
                val tExp = GREY * wg
                val tSlope = (gll(GREY + d, white, tExp, 0.0, 1.0, pp) -
                    gll(GREY - d, white, tExp, 0.0, 1.0, pp)) / (2 * d)
                val fp = refSlope / tSlope
                val wb = (black / white).pow(-1.0 / pp) - 1.0
                val fog = GREY * wg.pow(1.0 / fp) / (wb.pow(1.0 / fp) - wg.pow(1.0 / fp))
                val pexp = (fog + GREY).pow(fp) * wg
                return Params(white.toFloat(), pexp.toFloat(), fog.toFloat(), fp.toFloat(), pp.toFloat())
            }
        }
    }

    fun curve(value: Float, p: Params = Params.of(ToneLookSettings())): Float {
        val film = (value.coerceAtLeast(0f) + p.filmFog).toDouble().pow(p.filmPower.toDouble())
        return (p.magnitude * (film / (p.paperExposure + film)).pow(p.paperPower.toDouble())).toFloat()
    }

    fun map(input: FloatArray, look: ToneLookSettings = ToneLookSettings()): FloatArray {
        val t = look.bounded()
        return map(input, t, Params.of(t))
    }

    /** [map] with bounded [t] and its [params] precomputed (hot loops such as the VF LUT bake). */
    fun map(input: FloatArray, t: ToneLookSettings, params: Params): FloatArray {
        val hue = t.sigmoidHue
        val average = (input.sum() / 3f).coerceAtLeast(0f)
        val minimum = input.min()
        val saturation = if (minimum < 0f) -average / (minimum - average) else 1f
        val rgb = FloatArray(3) { average + saturation * (input[it] - average) }
        val order = (0..2).sortedBy { rgb[it] }
        val lo = order[0]; val mid = order[1]; val hi = order[2]
        val p = FloatArray(3) { curve(rgb[it], params) }
        val out = p.copyOf()
        val chroma = rgb[hi] - rgb[lo]
        if (chroma != 0f) {
            val scale = (rgb[mid] - rgb[lo]) / chroma
            val full = p[lo] + (p[hi] - p[lo]) * scale
            val naive = (1f - hue) * p[mid] + hue * full
            val sum = rgb[lo] + rgb[mid]
            val blend = if (sum != 0f) 2f * rgb[lo] / sum else 0f
            val energy = blend * p.sum() + (1f - blend) * (p[lo] + naive + p[hi])
            if (naive <= p[mid]) {
                val m = ((1f - hue) * p[mid] + hue * (scale * p[hi] + (1f - scale) * (energy - p[hi]))) /
                    (1f + hue * (1f - scale))
                out[lo] = energy - p[hi] - m; out[mid] = m
            } else {
                val m = ((1f - hue) * p[mid] + hue * (p[lo] * (1f - scale) + scale * (energy - p[lo]))) /
                    (1f + hue * scale)
                out[mid] = m; out[hi] = energy - p[lo] - m
            }
        }
        return colorful(out, t.sigmoidColorfulness)
    }

    private val LUM = floatArrayOf(.2627f, .6780f, .0593f)
    private fun colorful(rgb: FloatArray, amount: Float): FloatArray {
        if (amount == 1f) return rgb
        val y = rgb[0] * LUM[0] + rgb[1] * LUM[1] + rgb[2] * LUM[2]
        val s = maxOf(kotlin.math.abs(rgb[0] - y), kotlin.math.abs(rgb[1] - y), kotlin.math.abs(rgb[2] - y)) /
            y.coerceAtLeast(1e-4f)
        val k = 1f + (amount - 1f) / (1f + s)
        return FloatArray(3) { (y + (rgb[it] - y) * k).coerceAtLeast(0f) }
    }

    // Shared by still export and both GLES viewfinders; parameters from ToneLook (u_tone_look).
    val glsl: String = """
float dt_sigmoid_curve(float x) {
    float film = pow(max(x,0.0) + u_tone_look[0].z, u_tone_look[0].w);
    return u_tone_look[0].x * pow(film / (u_tone_look[0].y + film), u_tone_look[1].x);
}
vec3 dt_sigmoid(vec3 value) {
    float avg=max((value.r+value.g+value.b)/3.0,0.0);
    float low=min(value.r,min(value.g,value.b));
    float sat=low<0.0 ? -avg/(low-avg) : 1.0;
    vec3 rgb=vec3(avg)+sat*(value-vec3(avg));
    int lo=0;int hi=0;
    if(rgb.g<rgb[lo])lo=1;if(rgb.b<rgb[lo])lo=2;
    if(rgb.g>rgb[hi])hi=1;if(rgb.b>rgb[hi])hi=2;
    vec3 result;
    if(lo==hi){result=vec3(dt_sigmoid_curve(rgb.r));}
    else{
    int mid=3-lo-hi;
    float hue=u_tone_look[1].y;
    vec3 p=vec3(dt_sigmoid_curve(rgb.r),dt_sigmoid_curve(rgb.g),dt_sigmoid_curve(rgb.b));
    float scale=(rgb[mid]-rgb[lo])/(rgb[hi]-rgb[lo]);
    float full=p[lo]+(p[hi]-p[lo])*scale;
    float naive=(1.0-hue)*p[mid]+hue*full;
    float sum=rgb[lo]+rgb[mid];float blend=sum!=0.0 ? 2.0*rgb[lo]/sum : 0.0;
    float energy=blend*(p.r+p.g+p.b)+(1.0-blend)*(p[lo]+naive+p[hi]);
    result=p;
    if(naive<=p[mid]){
        result[mid]=((1.0-hue)*p[mid]+hue*(scale*p[hi]+(1.0-scale)*(energy-p[hi])))/(1.0+hue*(1.0-scale));
        result[lo]=energy-p[hi]-result[mid];
    }else{
        result[mid]=((1.0-hue)*p[mid]+hue*(p[lo]*(1.0-scale)+scale*(energy-p[lo])))/(1.0+hue*scale);
        result[hi]=energy-p[lo]-result[mid];
    }
    }
    float amount=u_tone_look[1].z;
    if(amount!=1.0){
        float y=dot(result,vec3(0.2627,0.6780,0.0593));
        vec3 c=result-vec3(y);
        float s=max(abs(c.r),max(abs(c.g),abs(c.b)))/max(y,1e-4);
        result=max(vec3(y)+c*(1.0+(amount-1.0)/(1.0+s)),vec3(0.0));
    }
    return result;
}
""".trimIndent()
}
