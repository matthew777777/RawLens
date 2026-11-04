// Post-demosaic chroma cleanup, fused into the AMaZE / RCD final passes so it
// costs no extra texture, pass, or readback.
//
//  * False-color suppression (RawTherapee / dcraw "median filter passes"):
//    one 3x3 median of the color differences (R-G, B-G). Removes demosaic
//    maze / zipper moire on fine repeating detail (palm fronds, fabric)
//    without touching luminance (G is kept).
//  * Purple defringe: at strong local contrast edges only, strips the
//    positive (violet/magenta) part of the color difference, the signature
//    of lateral CA + blooming around dark detail against a bright sky.
//    Flat purple objects keep their color (no edge -> no effect).
//
// Works in the white-balanced camera basis (neutral = 1,1,1). The includer
// defines, BEFORE this import:
//   highp vec3 fetch_bal(ivec2 off)  // balanced RGB (>= 0) at pixel + off, |off| <= 2
uniform int u_fc_median;        // 1 = one 3x3 color-difference median pass
uniform highp float u_defringe; // 0 = off .. 1 = full purple-fringe removal

#define CC_SORT(a, b) { highp vec2 t_ = min(a, b); b = max(a, b); a = t_; }

highp vec2 cc_diff(ivec2 off) {
    highp vec3 s = fetch_bal(off);
    return s.rb - s.g;
}

highp vec3 chroma_cleanup(highp vec3 c) {
    if (u_fc_median == 0 && u_defringe <= 0.0) return c;
    highp vec2 d = c.rb - c.g;
    if (u_fc_median != 0) {
        // Paeth's 19-exchange median-of-9 network, vectorized over (R-G, B-G).
        highp vec2 p0 = cc_diff(ivec2(-1, -1)), p1 = cc_diff(ivec2(0, -1)), p2 = cc_diff(ivec2(1, -1));
        highp vec2 p3 = cc_diff(ivec2(-1, 0)), p4 = d, p5 = cc_diff(ivec2(1, 0));
        highp vec2 p6 = cc_diff(ivec2(-1, 1)), p7 = cc_diff(ivec2(0, 1)), p8 = cc_diff(ivec2(1, 1));
        CC_SORT(p1, p2) CC_SORT(p4, p5) CC_SORT(p7, p8) CC_SORT(p0, p1) CC_SORT(p3, p4)
        CC_SORT(p6, p7) CC_SORT(p1, p2) CC_SORT(p4, p5) CC_SORT(p7, p8) CC_SORT(p0, p3)
        CC_SORT(p5, p8) CC_SORT(p4, p7) CC_SORT(p3, p6) CC_SORT(p1, p4) CC_SORT(p2, p5)
        CC_SORT(p4, p7) CC_SORT(p4, p2) CC_SORT(p6, p4) CC_SORT(p4, p2)
        d = p4;
    }
    if (u_defringe > 0.0 && d.y > 0.0) {
        // Local green contrast over a 5x5 footprint (9 taps, stride 2).
        highp float hi = 0.0;
        highp float lo = 1e30;
        for (int y = -2; y <= 2; y += 2) {
            for (int x = -2; x <= 2; x += 2) {
                highp float g = fetch_bal(ivec2(x, y)).g;
                hi = max(hi, g);
                lo = min(lo, g);
            }
        }
        // ~3x step -> starts, ~7x step (twig against sky) -> full.
        highp float edge = 1.0 - smoothstep(0.15, 0.35, lo / max(hi, 1e-6));
        // Violet/magenta hue: B above G, R not far below G.
        highp float violet = smoothstep(-0.6, -0.1, d.x / d.y);
        highp float k = clamp(u_defringe, 0.0, 1.0) * edge * violet;
        d = mix(d, min(d, vec2(0.0)), k);
    }
    return max(vec3(c.g + d.x, c.g, c.g + d.y), vec3(0.0));
}
