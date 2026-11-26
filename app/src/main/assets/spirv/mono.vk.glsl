#version 450
#extension GL_EXT_control_flow_attributes : require
// RawLens B&W from photosites (no demosaic). GPL-3.0-or-later.
// Idea after rwmono (github.com/mristuccia/rwmono): every output pixel comes
// from measured photosites only.
//  DETAIL (default build): full resolution. Each balanced photosite v is
//    rescaled by the local luma/own-channel ratio Y/C (5x5 binomial class
//    means). Neutral areas keep v untouched (real per-photosite detail);
//    coloured areas lose the CFA checkerboard. Noise e(C) (measured on the
//    frame, CPU) regularizes the ratio so weak channels fall back to Y
//    instead of amplifying noise. Same-phase class means make the ratio
//    cancel Gr/Gb imbalance by construction.
//  CLEAN (-DCLEAN): rwmono "bin luma", (R'+G1+G2+B')/4 per 2x2 quad.
// Outputs (flags): bit0 mono r32f (DNG), bit1 camera-linear RGBA16F packed as
// two r32ui texels per pixel (JPEG/JXL tail: Y * camera white = neutral grey).
#ifdef CLEAN
layout(local_size_x=16, local_size_y=8) in;
#else
layout(local_size_x=16, local_size_y=16) in;
#endif
layout(binding=4) uniform sampler2D srcTex; // CFA, read via textureGather
layout(binding=1, r32f) uniform writeonly image2D monoOut;
layout(binding=2, r32ui) uniform writeonly uimage2D packedOut;
layout(std140, binding=3) uniform Params {
    ivec4 size;    // in w, in h, out flags, unused
    ivec4 cells;   // colour (0 R, 1 G, 2 B) at (x&1)+2*(y&1)
    vec4 gain;     // WB balance R,G,B; raw clip
    vec4 neutral;  // camera white RGB (packed output), unused
    vec4 noise;    // balanced variance = a*level + b, unused, unused
} p;

float gainOf(int c) { return c == 0 ? p.gain.x : (c == 1 ? p.gain.y : p.gain.z); }

void store(ivec2 o, float y) {
    if ((p.size.z & 1) != 0) imageStore(monoOut, o, vec4(y));
    if ((p.size.z & 2) != 0) {
        imageStore(packedOut, ivec2(2 * o.x, o.y), uvec4(packHalf2x16(vec2(y * p.neutral.x, y * p.neutral.y))));
        imageStore(packedOut, ivec2(2 * o.x + 1, o.y), uvec4(packHalf2x16(vec2(y * p.neutral.z, 1.0))));
    }
}

#if defined(QUINCUNX)
// rwmono quincunx (flat output would keep every photosite; this keeps greens only).
// Output (x, y) -> J = x + jMin, i = y; the photosite is r = i - J, c = i + J + c0,
// with c0 = column of the first green site in row 0 (p.size.w). Greens lie on the
// lattice r + c - c0 even, so every in-range lattice site is a real green. Gb sites
// (green on a row with blue) are scaled by p.noise.z = mean(Gr) / mean(Gb), as rwmono.
// Out-of-frame lattice points stay 0. Output is roughly W*H/2 pixels, rotated 45 degrees.
void main() {
    int w = p.size.x, h = p.size.y, c0 = p.size.w;
    int jMin = -((h - 1 + c0) / 2);
    int jMax = (w - 1 - c0) / 2;
    int iMax = (h - 1 + w - 1 - c0) / 2;
    ivec2 o = ivec2(gl_GlobalInvocationID.xy);
    if (o.x > jMax - jMin || o.y > iMax) return;
    int J = o.x + jMin;
    int r = o.y - J;
    int c = o.y + J + c0;
    float y = 0.0;
    if (r >= 0 && r < h && c >= 0 && c < w) {
        int col = p.cells[(c & 1) + 2 * (r & 1)];
        if (col == 1) {
            // Green on a row that also holds red is Gr; green beside blue is Gb.
            int beside = p.cells[((c ^ 1) & 1) + 2 * (r & 1)];
            float gb = beside == 2 ? p.noise.z : 1.0;
            float raw = texelFetch(srcTex, ivec2(c, r), 0).r;
            y = min(raw * gainOf(1) * gb, p.gain.w * p.gain.y);
        }
    }
    store(o, y);
}
#elif defined(CLEAN)
void main() {
    ivec2 o = ivec2(gl_GlobalInvocationID.xy);
    if (o.x >= p.size.x / 2 || o.y >= p.size.y / 2) return;
    // One gather = the whole quad. Components x=(0,1) y=(1,1) z=(1,0) w=(0,0).
    vec4 g = vec4(gainOf(p.cells[2]), gainOf(p.cells[3]), gainOf(p.cells[1]), gainOf(p.cells[0]));
    vec4 t = textureGather(srcTex, (vec2(2 * o) + 1.0) / vec2(p.size.xy), 0);
    // rwmono bin luma: R', B' clipped at green saturation.
    vec4 b = min(t * g, vec4(p.gain.w * p.gain.y));
    store(o, 0.25 * (b.x + b.y + b.z + b.w));
}
#else

// 5x5 binomial (1 4 6 4 1) per parity class from 9 gathers over the 6x6
// block at (x-2..x+3, y-2..y+3); the +3 row/column has weight 0. Gather
// component order: x=(0,1) y=(1,1) z=(1,0) w=(0,0) -> eo, oo, oe, ee
// relative to the centre photosite. No shared memory, no barriers: the
// reads ride the texture pipe and leave LS for the stores (malioc G615).
void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    if (xy.x >= p.size.x || xy.y >= p.size.y) return;
    vec2 inv = 1.0 / vec2(p.size.xy);
    vec2 uv0 = (vec2(xy) - 1.0) * inv; // gather centre of block (x-2, y-2)
    int ce = (xy.x & 1) | ((xy.y & 1) << 1);
    // Class colours: ee self, oe x-neighbour, eo y-neighbour, oo diagonal.
    ivec4 cls = ivec4(p.cells[ce ^ 2], p.cells[ce ^ 3], p.cells[ce ^ 1], p.cells[ce]);
    vec4 g = vec4(gainOf(cls.x), gainOf(cls.y), gainOf(cls.z), gainOf(cls.w));
    float clipBal = p.gain.w * p.gain.y;
    vec4 clipRaw = clipBal / g; // rwmono: clip at green saturation
    vec4 acc = vec4(0.0);
    float centre = 0.0;
    [[unroll]] for (int j = 0; j < 3; ++j) {
        // even-row weight (dy = -2,0,2) and odd-row weight (dy = -1,1,3)
        float wye = j == 1 ? 6.0 : 1.0, wyo = j == 2 ? 0.0 : 4.0;
        [[unroll]] for (int i = 0; i < 3; ++i) {
            float wxe = i == 1 ? 6.0 : 1.0, wxo = i == 2 ? 0.0 : 4.0;
            vec4 t = min(textureGather(srcTex, uv0 + vec2(2 * i, 2 * j) * inv, 0), clipRaw);
            if (i == 1 && j == 1) centre = t.w;
            acc += t * vec4(wxe * wyo, wxo * wyo, wxo * wye, wxe * wye);
        }
    }
    vec4 m = acc * g * (1.0 / 64.0); // balanced class means (eo, oo, oe, ee)
    float v = centre * g.w;
    // Luma (R + 2G + B)/4 = mean of the four parity classes for any phase.
    // Own channel = same-phase class: cancels Gr/Gb imbalance.
    float y = 0.25 * (m.x + m.y + m.z + m.w);
    float own = max(m.w, 0.0);
    float sigma = sqrt(max(p.noise.x * own + p.noise.y, 1e-12));
    float out_ = y * (max(v, 0.0) + sigma) / (own + sigma);
    if (v >= clipBal) out_ = max(y, v); // clipped site: ratio meaningless
    store(xy, out_);
}
#endif
