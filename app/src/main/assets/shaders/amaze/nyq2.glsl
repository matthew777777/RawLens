#import amaze
// AMaZE pass 6: nyquist flag dilate/erode (C++ lines 893-927).
// The bounding-box test is disabled: every tile processes all of its sites
// (the per-site flags themselves still gate the downstream math).
uniform sampler2D u_nyqtest;

// R/B-only planes are stored at half width: column x >> 1 (the C++ half grid).
bool flag(ivec2 q) { return texelFetch(u_nyqtest, ivec2(q.x >> 1, q.y), 0).r > 0.0; }

layout(binding = 0, r32f) writeonly uniform highp image2D img_out;
void emit(ivec2 p, float v) { imageStore(img_out, p, vec4(v)); }
void main() {
    ivec2 hp = ivec2(gl_GlobalInvocationID.xy);
    ivec2 p = ivec2(hp.x << 1, hp.y);
    if (p.x >= u_size.x || p.y >= u_size.y) return;
    ivec2 s = ivec2(site_x(p), p.y);
    // RawTherapee restricts nyquist2 to the eight-pixel interior. Besides
    // matching that domain, this prevents negative half-grid shared reads.
    if (!inside(s, 8)) { emit(hp, 0.0); return; }
    float nb = (float(flag(s + ivec2(0, -2))) + float(flag(s + ivec2(-1, -1))) + float(flag(s + ivec2(1, -1)))
                + float(flag(s + ivec2(-2, 0))) + float(flag(s + ivec2(2, 0)))
                + float(flag(s + ivec2(-1, 1))) + float(flag(s + ivec2(1, 1))) + float(flag(s + ivec2(0, 2))));
    emit(hp, nb > 4.0 ? 1.0 : (nb < 4.0 ? 0.0 : (flag(s) ? 1.0 : 0.0)));
}
