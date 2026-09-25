// AMaZE pass 0: fetch one tile of the CFA into its padded staging window.
//
// Match RawTherapee's tile initialization, including its distinct corner
// rules. For the first tile top/left are -16, so 32-j+top is 16-j,
// whereas the corner loops explicitly address 32-j without that offset.
//
// Tiled processing: u_off places the window anywhere in the full image.
// Window texels are addressed in whole-image padded coordinates and clamped
// to that domain before mirroring, so a tile reaching past the image edge
// sees exactly the values the whole-image pad plus the staging edge clamp
// would have produced.
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;

uniform sampler2D u_in;       // RawLens full-resolution R32F CFA; scene-linear, unbounded
uniform ivec2 u_insize;       // full (W, H)
uniform ivec2 u_off;          // window origin in full-image coords (ox - B - 16, oy - B - 16)
uniform ivec2 u_size;         // padded window size (tile + 2*B + 32)
uniform ivec4 u_fc;           // cropped-image CFA phase: 0=R, 1=G, 2=B
uniform highp vec3 u_demosaic_balance; // RT-style pre-demosaic channel balance, max gain = 1
layout(binding = 0, r32f) writeonly uniform highp image2D img_out;

int FC(int y, int x) {
    return (y & 1) == 0 ? ((x & 1) == 0 ? u_fc.x : u_fc.y)
                        : ((x & 1) == 0 ? u_fc.z : u_fc.w);
}

int srcRow(int r, int n, bool sideBorder) {
    if (r < 16) return (sideBorder ? 32 : 16) - r;
    if (r < n + 16) return r - 16;
    return 2 * n + 14 - r;
}

int srcCol(int c, int n, bool rowBorder) {
    if (c < 16) return (rowBorder ? 32 : 16) - c;
    if (c < n + 16) return c - 16;
    return 2 * n + 14 - c;
}

// RawTherapee's fixed border assumes a sufficiently large image. RawLens
// also accepts 4x4 crops: reflect otherwise unavailable taps without changing
// their Bayer parity (a plain clamp can fetch a different color).
int reflectSmallImage(int p, int n) {
    int period = 2 * (n - 1);
    int q = ((p % period) + period) % period;
    return q < n ? q : period - q;
}

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= u_size.x || p.y >= u_size.y) return;
    ivec2 fp = clamp(p + u_off + ivec2(16), ivec2(0), u_insize + ivec2(31));
    bool sideBorder = fp.x < 16 || fp.x >= u_insize.x + 16;
    bool rowBorder = fp.y < 16 || fp.y >= u_insize.y + 16;
    ivec2 s = ivec2(srcCol(fp.x, u_insize.x, rowBorder), srcRow(fp.y, u_insize.y, sideBorder));
    s = ivec2(reflectSmallImage(s.x, u_insize.x), reflectSmallImage(s.y, u_insize.y));
    float raw = texelFetch(u_in, s, 0).r;
    int c = FC(s.y, s.x);
    float balance = c == 0 ? u_demosaic_balance.r : (c == 2 ? u_demosaic_balance.b : u_demosaic_balance.g);
    // RawTherapee's AMaZE works on CFA values after white-balance multipliers
    // have been applied.  Use an equivalent normalized balance (largest gain=1)
    // here, then compensate the final camera matrix so overall scene-linear
    // colorimetry is unchanged.  This is important for AMaZE's colour-ratio,
    // variance and saturation tests and avoids treating ordinary sensor-channel
    // imbalance as chroma edges/noise.
    imageStore(img_out, p, vec4(raw * balance));
}
