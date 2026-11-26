#import amaze
// AMaZE pass 7: area interpolation in nyquist regions (C++ lines 931-967).
// The bounding-box test is disabled: processing is gated per site by the
// dilated nyquist flags only.
uniform sampler2D u_nyq2;
uniform sampler2D u_hvwt;

// R/B-only planes are stored at half width: column x >> 1 (the C++ half grid).
float n2(ivec2 q) { return texelFetch(u_nyq2, ivec2(q.x >> 1, q.y), 0).r; }
float Cf(ivec2 q) { return texelFetch(u_cfa, q, 0).r; }

layout(binding = 0, r32f) writeonly uniform highp image2D img_out;
void emit(ivec2 p, float v) { imageStore(img_out, p, vec4(v)); }
void main() {
    ivec2 hp = ivec2(gl_GlobalInvocationID.xy);
    ivec2 p = ivec2(hp.x << 1, hp.y);
    if (p.x >= u_size.x || p.y >= u_size.y) return;
    ivec2 s = ivec2(site_x(p), p.y);
    if (n2(s) < 0.5) { emit(hp, texelFetch(u_hvwt, hp, 0).r); return; }
    float sumcfa = 0.0, sumh = 0.0, sumv = 0.0, sumsqh = 0.0, sumsqv = 0.0, areawt = 0.0;
    for (int j = -6; j <= 6; j += 2) {
        for (int k = -6; k <= 6; k += 2) {
            // RawTherapee accumulates rows outside columns (float order matters).
            ivec2 q = s + ivec2(k, j);
            if (n2(q) > 0.5) {
                float cf = Cf(q);
                sumcfa += cf;
                sumh += Cf(q + ivec2(-1, 0)) + Cf(q + ivec2(1, 0));
                sumv += Cf(q + ivec2(0, -1)) + Cf(q + ivec2(0, 1));
                sumsqh += sq(cf - Cf(q + ivec2(-1, 0))) + sq(cf - Cf(q + ivec2(1, 0)));
                sumsqv += sq(cf - Cf(q + ivec2(0, -1))) + sq(cf - Cf(q + ivec2(0, 1)));
                areawt += 1.0;
            }
        }
    }
    sumh = sumcfa - 0.5 * sumh;
    sumv = sumcfa - 0.5 * sumv;
    areawt = 0.5 * areawt;
    float hcdvar = EPSSQ + abs(areawt * sumsqh - sumh * sumh);
    float vcdvar = EPSSQ + abs(areawt * sumsqv - sumv * sumv);
    emit(hp, hcdvar / (vcdvar + hcdvar));
}
