// Bayer CFA conventions shared by RAW-domain shaders.
//
// pattern: 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR (top-left 2x2 cell, row-major).
// Channel labels: 0 R, 1 G1 (green in the red row), 2 G2 (green in the blue
// row), 3 B. For Camera2 lens-shading lookups pass 1 for either green; see
// lens_shading.glsl, which selects G_even/G_odd by sensor-row parity.

// Sensor coordinates of each colour site in the 2x2 cell `cell`.
void rawrCfaCellCoordinates(uint pattern, ivec2 cell, out ivec2 qr, out ivec2 qg1, out ivec2 qg2, out ivec2 qb) {
    ivec2 b = cell << 1;
    if (pattern == 0u) {
        qr = b;
        qg1 = b + ivec2(1, 0);
        qg2 = b + ivec2(0, 1);
        qb = b + ivec2(1, 1);
    } else if (pattern == 1u) {
        qg1 = b;
        qr = b + ivec2(1, 0);
        qb = b + ivec2(0, 1);
        qg2 = b + ivec2(1, 1);
    } else if (pattern == 2u) {
        qg2 = b;
        qb = b + ivec2(1, 0);
        qr = b + ivec2(0, 1);
        qg1 = b + ivec2(1, 1);
    } else {
        qb = b;
        qg2 = b + ivec2(1, 0);
        qg1 = b + ivec2(0, 1);
        qr = b + ivec2(1, 1);
    }
}

// Channel label of sensor site q.
int rawrCfaChannelAt(uint pattern, ivec2 q) {
    int phase = (q.x & 1) | ((q.y & 1) << 1);
    if (pattern == 0u) return int[4](0, 1, 2, 3)[phase];
    if (pattern == 1u) return int[4](1, 0, 3, 2)[phase];
    if (pattern == 2u) return int[4](2, 3, 0, 1)[phase];
    return int[4](3, 2, 1, 0)[phase];
}
