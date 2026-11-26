#import amaze
// AMaZE pass 8b: nyquist refinement using G curvatures (C++ lines 994-1012).
// The bounding-box test is disabled: refinement runs wherever the dilated
// per-site flags say so.
uniform sampler2D u_gd;      // rgbgreen, dgrb0, dgrb2h, dgrb2v
uniform sampler2D u_cd2;
uniform sampler2D u_nyq2;

// R/B-only planes are stored at half width: column x >> 1 (the C++ half grid).
float n2(ivec2 q) { return texelFetch(u_nyq2, ivec2(q.x >> 1, q.y), 0).r; }
vec2 d2(ivec2 q) { return texelFetch(u_gd, ivec2(q.x & ~1, q.y), 0).ba; }   // dgrb2h, dgrb2v
float Cf(ivec2 q) { return texelFetch(u_cfa, q, 0).r; }

layout(binding = 0, rgba32f) writeonly uniform highp image2D img_out;
void emit(ivec2 p, vec4 v) { imageStore(img_out, p, v); }
float d0ref(ivec2 s) {   // refined colour difference at a nyquist R/B site
    vec2 gvar = EPSSQ + (GQUINC[0] * d2(s)
        + GQUINC[1] * (d2(s + ivec2(1, 1)) + d2(s + ivec2(1, -1)) + d2(s + ivec2(-1, -1)) + d2(s + ivec2(-1, 1)))
        + GQUINC[2] * (d2(s + ivec2(0, -2)) + d2(s + ivec2(-2, 0)) + d2(s + ivec2(2, 0)) + d2(s + ivec2(0, 2)))
        + GQUINC[3] * (d2(s + ivec2(2, 2)) + d2(s + ivec2(2, -2)) + d2(s + ivec2(-2, -2)) + d2(s + ivec2(-2, 2))));
    vec2 vh = texelFetch(u_cd2, s, 0).rg;
    return (vh.g * gvar.y + vh.r * gvar.x) / (gvar.x + gvar.y);
}
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= u_size.x || p.y >= u_size.y) return;
    vec4 base = texelFetch(u_gd, p, 0);
    vec4 o = vec4(base.r, base.g, 0.0, 0.0);
    if (!isG(p.y, p.x) && n2(p) > 0.5) {
        o.r = Cf(p) + d0ref(p);            // per-pixel green
    }
    if ((p.x & 1) == 0) {
        ivec2 s = ivec2(site_x(p), p.y);   // anchored site's half-grid d0
        if (n2(s) > 0.5) {
            o.g = d0ref(s);
        }
    }
    emit(p, o);
}
