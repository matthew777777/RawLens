// Coarse-to-fine compositing. Back chain (far->near, "over") and front chain
// (near->far, "under") come up from the coarser level by bilinear sampling;
// this level's layers are merged in. FINAL resolves front over back, adds the
// near-focus band (per-pixel gather, radius <= u_rnear) and matching grain.
layout(local_size_x = 16, local_size_y = 16) in;
#define MAXL 24
uniform sampler2DArray u_prev;     // layer 0 back, layer 1 front (linear)
uniform sampler2DArray u_layers;   // this level's blurred layers
uniform int u_has_prev;
uniform int u_n;
uniform ivec2 u_kind[MAXL];        // x: 0 back, 1 front, 2 near band; y: array layer
uniform vec4 u_meta[MAXL];         // near band: centre, lo, hi, half-width of the flat top
uniform ivec2 u_size;
#ifdef FINAL
uniform sampler2D u_img;
layout(rgba16f, binding = 0) writeonly uniform image2D u_out;
uniform float u_df;
uniform float u_k;
uniform float u_rnear;
uniform float u_grain;
uniform uint u_seed;
#else
layout(rgba16f, binding = 0) writeonly uniform image2DArray u_acc;
#endif

void main() {
    ivec2 g = ivec2(gl_GlobalInvocationID.xy);
    if (g.x >= u_size.x || g.y >= u_size.y) return;
    vec4 back = vec4(0.0), front = vec4(0.0);
    if (u_has_prev != 0) {
        vec2 uv = (vec2(g) + 0.5) / vec2(u_size);
        back = textureLod(u_prev, vec3(uv, 0.0), 0.0);
        front = textureLod(u_prev, vec3(uv, 1.0), 0.0);
    }
#ifdef FINAL
    vec4 me = texelFetch(u_img, g, 0);
#endif
    for (int k = 0; k < u_n; ++k) {
        vec4 L;
        if (u_kind[k].x == 2) {
#ifdef FINAL
            vec4 f = u_meta[k];
            float rc = min(abs(me.a - u_df) * u_k, u_rnear);
            vec4 a = vec4(0.0);
            float ws = 0.0;
            for (int dy = -2; dy <= 2; ++dy) {
                for (int dx = -2; dx <= 2; ++dx) {
                    float dk = clamp(rc + 0.5 - length(vec2(dx, dy)), 0.0, 1.0);
                    if (dk > 0.0) {
                        ivec2 q = clamp(g + ivec2(dx, dy), ivec2(0), u_size - 1);
                        vec4 v = (dx == 0 && dy == 0) ? me : texelFetch(u_img, q, 0);
                        float w;
                        if (abs(v.a - f.x) <= f.w) w = 1.0;
                        else if (v.a < f.x) w = clamp((v.a - f.y) / max(f.x - f.w - f.y, 1e-6), 0.0, 1.0);
                        else w = clamp((f.z - v.a) / max(f.z - f.x - f.w, 1e-6), 0.0, 1.0);
                        a += dk * vec4(v.rgb * w, w);
                        ws += dk;
                    }
                }
            }
            L = a / ws;
#else
            L = vec4(0.0);
#endif
        } else {
            L = texelFetch(u_layers, ivec3(g, u_kind[k].y), 0);
        }
        if (u_kind[k].x == 1) front += (1.0 - front.a) * L;
        else back = L + (1.0 - L.a) * back;   // L (nearer) over the far chain
    }
#ifdef FINAL
    vec4 c = front + (1.0 - front.a) * back;
    vec3 rgb = c.a > 1e-4 ? c.rgb / c.a : me.rgb;
    // Grain back into the blurred areas so they do not look pasted on.
    float blur = clamp(abs(me.a - u_df) * u_k / max(u_rnear, 1.0), 0.0, 1.0);
    uint h = portraitHash(uint(g.y * u_size.x + g.x) * 747796405u + u_seed);
    float nz = (float(h & 0xffffu) / 65535.0 - 0.5) + (float(h >> 16) / 65535.0 - 0.5);
    rgb += nz * u_grain * blur * sqrt(max(rgb, vec3(0.0)));
    imageStore(u_out, g, vec4(max(rgb, vec3(0.0)), 1.0));
#else
    imageStore(u_acc, ivec3(g, 0), back);
    imageStore(u_acc, ivec3(g, 1), front);
#endif
}
