#import amaze
// AMaZE pass 14: fancy chroma smoothing (C++ lines 1426-1435)
uniform sampler2D u_gd3;   // rgbgreen'', D0, D1

layout(binding = 0, rgba32f) writeonly uniform highp image2D img_out;
void emit(ivec2 p, vec4 v) { imageStore(img_out, p, v); }
float DC(ivec2 q, bool cb) { vec2 d = texelFetch(u_gd3, ivec2(q.x & ~1, q.y), 0).gb; return cb ? d.x : d.y; }
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= u_size.x || p.y >= u_size.y) return;
    vec4 base = texelFetch(u_gd3, p, 0);
    vec4 o = vec4(base.g, base.b, base.r, 0.0);
    if ((p.x & 1) == 1) { emit(p, o); return; }
    ivec2 s = ivec2(site_x(p), p.y);
    if (!inside(s, 14)) { emit(p, o); return; }
    bool cb = FC(s.y, s.x) == 2;   // c = 0 at B sites (smooth D0), c = 1 at R sites (smooth D1)
    float dm1m1 = DC(s + ivec2(-1, -1), cb);
    float dp1p1 = DC(s + ivec2(1, 1), cb);
    float dm3m3 = DC(s + ivec2(-3, -3), cb);
    float dp1m1 = DC(s + ivec2(1, -1), cb);
    float dm1p1 = DC(s + ivec2(-1, 1), cb);
    float dp3m3 = DC(s + ivec2(3, -3), cb);
    float dp3p3 = DC(s + ivec2(3, 3), cb);
    float dm3p3 = DC(s + ivec2(-3, 3), cb);
    float dm3m1 = DC(s + ivec2(-3, -1), cb);
    float dm1m3 = DC(s + ivec2(-1, -3), cb);
    float dp3m1 = DC(s + ivec2(3, -1), cb);
    float dm3p1 = DC(s + ivec2(-3, 1), cb);
    float dp3p1 = DC(s + ivec2(3, 1), cb);
    float dp1p3 = DC(s + ivec2(1, 3), cb);
    float wtnw = 1.0 / (EPS + abs(dm1m1 - dp1p1) + abs(dm1m1 - dm3m3) + abs(dp1p1 - dm3m3));
    // RawTherapee pointer offsets are p1=(-rowStride+1), m1=(rowStride+1):
    // +p1 is NE, -p1 is SW, +m1 is SE, and -m1 is NW.
    float wtne = 1.0 / (EPS + abs(dp1m1 - dm1p1) + abs(dp1m1 - dp3m3) + abs(dm1p1 - dp3m3));
    float wtsw = 1.0 / (EPS + abs(dm1p1 - dp1m1) + abs(dm1p1 - dp3p3) + abs(dp1m1 - dm3p3));
    float wtse = 1.0 / (EPS + abs(dp1p1 - dm1m1) + abs(dp1p1 - dm3p3) + abs(dm1m1 - dp3p3));
    float val = (wtnw * (1.325 * dm1m1 - 0.175 * dm3m3 - 0.075 * dm3m1 - 0.075 * dm1m3)
               + wtne * (1.325 * dp1m1 - 0.175 * dp3m3 - 0.075 * dp3m1 - 0.075 * dp1p1)
               + wtsw * (1.325 * dm1p1 - 0.175 * dm3p3 - 0.075 * dm3p1 - 0.075 * dm1m1)
               + wtse * (1.325 * dp1p1 - 0.175 * dp3p3 - 0.075 * dp3p1 - 0.075 * dp1p3)) / (wtnw + wtne + wtsw + wtse);
    if (cb) {
        o.r = val;   // at B sites c==0 -> D0 smoothed
    } else {
        o.g = val;   // at R sites c==1 -> D1 smoothed
    }
    emit(p, o);
}
