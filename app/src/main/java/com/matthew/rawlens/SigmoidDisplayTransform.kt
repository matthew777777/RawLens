// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.pow

/** darktable 5.6.2 sigmoid defaults (sigmoid.c, Jakob Andrén and contributors).
 * Per-channel log-logistic in working Rec.2020, with full hue/energy preservation.
 * This ports the display transform, not darktable's exposure or color-calibration modules.
 */
internal object SigmoidDisplayTransform {
    private const val GREY = .1845
    private const val POWER = 1.5
    private val relation = 1.0 / GREY - 1.0
    private val fog = GREY * relation.pow(1.0 / POWER) /
        ((1.0 / .000152 - 1.0).pow(1.0 / POWER) - relation.pow(1.0 / POWER))
    private val exposure = (fog + GREY).pow(POWER) * relation

    fun curve(value: Float): Float {
        val film = (value.coerceAtLeast(0f) + fog).pow(POWER)
        return (film / (exposure + film)).toFloat()
    }
    fun map(input: FloatArray): FloatArray {
        val average = (input.sum() / 3f).coerceAtLeast(0f)
        val minimum = input.min()
        val saturation = if(minimum < 0f) -average / (minimum - average) else 1f
        val rgb = FloatArray(3) { average + saturation * (input[it] - average) }
        val order = (0..2).sortedBy { rgb[it] }
        val lo=order[0];val mid=order[1];val hi=order[2]
        val p=FloatArray(3) { curve(rgb[it]) }
        val chroma=rgb[hi]-rgb[lo]
        val scale=if(chroma!=0f) (rgb[mid]-rgb[lo])/chroma else 0f
        val corrected=p[lo]+(p[hi]-p[lo])*scale
        val sum=rgb[lo]+rgb[mid]
        val blend=if(sum!=0f) 2f*rgb[lo]/sum else 0f
        val energy=blend*p.sum()+(1f-blend)*(p[lo]+corrected+p[hi])
        return p.copyOf().apply {
            if(corrected<=p[mid]) {
                this[mid]=(scale*p[hi]+(1f-scale)*(energy-p[hi]))/(2f-scale)
                this[lo]=energy-p[hi]-this[mid]
            } else {
                this[mid]=(p[lo]*(1f-scale)+scale*(energy-p[lo]))/(1f+scale)
                this[hi]=energy-p[lo]-this[mid]
            }
        }
    }

    // Shared by still export and both GLES viewfinders.
    val glsl: String = """
float dt_sigmoid_curve(float x) {
    float film = pow(max(x,0.0) + ${fog}, 1.5);
    return film / (${exposure} + film);
}
vec3 dt_sigmoid(vec3 value) {
    float avg=max((value.r+value.g+value.b)/3.0,0.0);
    float low=min(value.r,min(value.g,value.b));
    float sat=low<0.0 ? -avg/(low-avg) : 1.0;
    vec3 rgb=vec3(avg)+sat*(value-vec3(avg));
    int lo=0;int hi=0;
    if(rgb.g<rgb[lo])lo=1;if(rgb.b<rgb[lo])lo=2;
    if(rgb.g>rgb[hi])hi=1;if(rgb.b>rgb[hi])hi=2;
    if(lo==hi){return vec3(dt_sigmoid_curve(rgb.r));}
    int mid=3-lo-hi;
    vec3 p=vec3(dt_sigmoid_curve(rgb.r),dt_sigmoid_curve(rgb.g),dt_sigmoid_curve(rgb.b));
    float scale=(rgb[mid]-rgb[lo])/(rgb[hi]-rgb[lo]);
    float corrected=p[lo]+(p[hi]-p[lo])*scale;
    float sum=rgb[lo]+rgb[mid];float blend=sum!=0.0 ? 2.0*rgb[lo]/sum : 0.0;
    float energy=blend*(p.r+p.g+p.b)+(1.0-blend)*(p[lo]+corrected+p[hi]);
    vec3 result=p;
    if(corrected<=p[mid]){
        result[mid]=(scale*p[hi]+(1.0-scale)*(energy-p[hi]))/(2.0-scale);
        result[lo]=energy-p[hi]-result[mid];
    }else{
        result[mid]=(p[lo]*(1.0-scale)+scale*(energy-p[lo]))/(1.0+scale);
        result[hi]=energy-p[lo]-result[mid];
    }
    return result;
}
""".trimIndent()
}
