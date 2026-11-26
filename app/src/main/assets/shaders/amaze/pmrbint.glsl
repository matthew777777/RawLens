#import amaze
// AMaZE pass 11: pmwt firming + R+B interpolation (C++ lines 1241-1251)
uniform sampler2D u_rbpm;   // rbm, rbp, pmwt

// R/B-only planes are stored at half width: column x >> 1 (the C++ half grid).
float pm(ivec2 q) { return texelFetch(u_rbpm, ivec2(q.x >> 1, q.y), 0).b; }
float Cf(ivec2 q) { return texelFetch(u_cfa, q, 0).r; }

layout(binding = 0, rgba32f) writeonly uniform highp image2D img_out;
void emit(ivec2 p, vec4 v) { imageStore(img_out, p, v); }
void main() {
    ivec2 hp = ivec2(gl_GlobalInvocationID.xy);
    ivec2 p = ivec2(hp.x << 1, hp.y);
    if (p.x >= u_size.x || p.y >= u_size.y) return;
    ivec2 s = ivec2(site_x(p), p.y);
    if (!inside(s, 10)) { emit(hp, vec4(0.0)); return; }
    vec3 here = texelFetch(u_rbpm, hp, 0).rgb;
    float own = here.b;
    float alt = 0.25 * (pm(s + ivec2(1, 1)) + pm(s + ivec2(1, -1)) + pm(s + ivec2(-1, -1)) + pm(s + ivec2(-1, 1)));
    float pmf = abs(0.5 - own) < abs(0.5 - alt) ? alt : own;
    float rbm = here.r, rbp = here.g;
    float rbint = 0.5 * (Cf(s) + rbm * (1.0 - pmf) + rbp * pmf);
    emit(hp, vec4(pmf, rbint, 0.0, 0.0));
}
