#import amaze
// AMaZE pass 11: pmwt firming + R+B interpolation (C++ lines 1241-1251)
uniform sampler2D u_rbpm;   // rbm, rbp, pmwt
uniform sampler2D u_grad;   // cfa rides in .a (written by gradcd)

const int HALO = 2;
const int TW = LW + 2 * HALO;
const int TH = LH + 2 * HALO;
const int TN = TW * TH;

ivec2 T0() { return ivec2(gl_WorkGroupID.xy * uvec2(LW, LH)) - HALO; }
int SIDX(ivec2 q) { ivec2 l = q - T0(); return l.y * TW + l.x; }
int SIDX_H(ivec2 q) { ivec2 l = ivec2(q.x & ~1, q.y) - T0(); return l.y * TW + l.x; }

shared vec4 s_rbpm[TN];
shared float s_cfa[TN];
float pm(ivec2 q) { return s_rbpm[SIDX_H(q)].b; }
float Cf(ivec2 q) { return s_cfa[SIDX(q)]; }

void load_tile() {
    ivec2 t0 = T0();
    for (int i = int(gl_LocalInvocationIndex); i < TN; i += LW * LH) {
        ivec2 q = clamp(t0 + ivec2(i % TW, i / TW), ivec2(0), u_size - 1);
        s_rbpm[i] = texelFetch(u_rbpm, q, 0);
        s_cfa[i] = texelFetch(u_grad, q, 0).a;
    }
    memoryBarrierShared();
    barrier();
}

layout(binding = 0, rgba16f) writeonly uniform highp image2D img_out;
void emit(ivec2 p, vec4 v) { imageStore(img_out, p, v); }
void main() {
    load_tile();
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= u_size.x || p.y >= u_size.y) return;
    emit(p, vec4(0.0));
    if ((p.x & 1) == 1) return;
    ivec2 s = ivec2(site_x(p), p.y);
    if (!inside(s, 10)) return;
    float own = pm(s);
    float alt = 0.25 * (pm(s + ivec2(1, 1)) + pm(s + ivec2(1, -1)) + pm(s + ivec2(-1, -1)) + pm(s + ivec2(-1, 1)));
    float pmf = abs(0.5 - own) < abs(0.5 - alt) ? alt : own;
    float rbm = s_rbpm[SIDX(p)].r;
    float rbp = s_rbpm[SIDX(p)].g;
    float rbint = 0.5 * (Cf(s) + rbm * (1.0 - pmf) + rbp * pmf);
    emit(p, vec4(pmf, rbint, 0.0, 0.0));
}
