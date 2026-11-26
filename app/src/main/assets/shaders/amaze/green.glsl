#import amaze
// AMaZE pass 8: populate G at R/B sites + hvwt firming (C++ lines 971-988)
uniform sampler2D u_hvwt;    // pre-firming hvwt (with area updates)
uniform sampler2D u_cd2;     // vcd', hcd'
uniform sampler2D u_nyq2;

// R/B-only planes are stored at half width: column x >> 1 (the C++ half grid).
float hv(ivec2 q) { return texelFetch(u_hvwt, ivec2(q.x >> 1, q.y), 0).r; }
float n2(ivec2 q) { return texelFetch(u_nyq2, ivec2(q.x >> 1, q.y), 0).r; }
float Cf(ivec2 q) { return texelFetch(u_cfa, q, 0).r; }

layout(binding = 0, rgba32f) writeonly uniform highp image2D img_gd;
layout(binding = 1, r32f) writeonly uniform highp image2D img_hv;
void emit_gd(ivec2 p, vec4 v) { imageStore(img_gd, p, v); }
void emit_hv(ivec2 p, float v) { imageStore(img_hv, ivec2(p.x >> 1, p.y), vec4(v)); }

float firm(ivec2 s) {   // firming of hvwt at R/B site s
    float own = hv(s);
    float alt = 0.25 * (hv(s + ivec2(1, 1)) + hv(s + ivec2(1, -1)) + hv(s + ivec2(-1, -1)) + hv(s + ivec2(-1, 1)));
    return abs(0.5 - own) < abs(0.5 - alt) ? alt : own;
}
float d0_at(ivec2 s) {  // colour difference G-R/G-B at R/B site s
    float hvf = firm(s);
    vec2 vh = texelFetch(u_cd2, s, 0).rg;
    return hvf * vh.r + (1.0 - hvf) * vh.g;
}
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= u_size.x || p.y >= u_size.y) return;
    float ohv = 0.0;
    if ((p.x & 1) == 1) {
        // odd column: only the per-pixel green channel is meaningful
        vec4 ogd = (!isG(p.y, p.x) && inside(p, 8)) ? vec4(Cf(p) + d0_at(p), 0.0, 0.0, 0.0)
                                                    : vec4(Cf(p), 0.0, 0.0, 0.0);
        emit_gd(p, ogd);
        return;
    }
    ivec2 s = ivec2(site_x(p), p.y);   // R/B site anchored at this even texel
    vec4 ogd;
    if (inside(s, 8)) {
        float d0 = d0_at(s);
        float green = Cf(s) + d0;
        float d2h = 0.0, d2v = 0.0;
        if (n2(s) > 0.5) {
            // neighbouring G values are the cfa samples
            d2h = sq(green - 0.5 * (Cf(s + ivec2(-1, 0)) + Cf(s + ivec2(1, 0))));
            d2v = sq(green - 0.5 * (Cf(s + ivec2(0, -1)) + Cf(s + ivec2(0, 1))));
        }
        ohv = firm(s);
        // .r is the green of THIS pixel; site values go to the half-grid channels
        ogd = (s == p) ? vec4(green, d0, d2h, d2v) : vec4(Cf(p), d0, d2h, d2v);
    } else {
        ogd = vec4(Cf(p), 0.0, 0.0, 0.0);
    }
    emit_hv(p, ohv);
    emit_gd(p, ogd);
}
