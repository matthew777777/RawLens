uint lumaQ16(uvec3 rgb) { return (13933u * rgb.r + 46871u * rgb.g + 4732u * rgb.b + 32768u) >> 16u; }
uint hash2(uvec2 p) {
    uint h = p.x * 0x9E3779B9u ^ p.y * 0x85EBCA6Bu ^ 0xC2B2AE35u;
    h ^= h >> 16u;
    h *= 0x7FEB352Du;
    h ^= h >> 15u;
    h *= 0x846CA68Bu;
    h ^= h >> 16u;
    return h;
}
bool sampleSelected(uvec2 p, uint mode) {
    if (mode == 0u) return true;
    uvec2 b = p >> 1u;
    uint lane = (p.y & 1u) * 2u + (p.x & 1u);
    uint h = hash2(b);
    if (mode == 2u) return lane == (h & 3u);
    bool d = (h & 1u) != 0u;
    return d ? (lane == 0u || lane == 3u) : (lane == 1u || lane == 2u);
}
ivec2 cbcrQ16(uvec3 rgb) {
    int cb = -7509 * int(rgb.r) - 25259 * int(rgb.g) + 32768 * int(rgb.b);
    int cr = 32768 * int(rgb.r) - 29763 * int(rgb.g) - 3005 * int(rgb.b);
    int cbi = cb >= 0 ? (cb + 32768) / 65536 : -((-cb + 32768) / 65536);
    int cri = cr >= 0 ? (cr + 32768) / 65536 : -((-cr + 32768) / 65536);
    return clamp(ivec2(128 + cbi, 128 + cri), ivec2(0), ivec2(255));
}

ivec2 cbcrFineQ16(uvec3 rgb) {
    const int Q = 65536;
    const int HALF = 128 * Q;
    const int MAXV = 255 * Q;
    int cb = HALF - 7509 * int(rgb.r) - 25259 * int(rgb.g) + 32768 * int(rgb.b);
    int cr = HALF + 32768 * int(rgb.r) - 29763 * int(rgb.g) - 3005 * int(rgb.b);
    return clamp(ivec2(cb, cr), ivec2(0), ivec2(MAXV));
}
