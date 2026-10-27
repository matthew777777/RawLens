// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from the user-provided Packed-ratio archive; provenance and
// unchanged upstream source: third_party/rcd-packed-upstream/SOURCE.md.
layout(binding = 0) readonly buffer Bayer { uint codes[]; };
layout(push_constant) uniform Params {
    ivec2 u_dims; ivec2 u_origin; ivec4 u_chans;
    vec4 u_black; vec4 u_invRange; int u_pitch; int u_mode;
} p;
#define RCD_WIDTH p.u_dims.x
#define RCD_HEIGHT p.u_dims.y
#define RCD_BAYER_OFFSET_X ((p.u_chans.x ^ p.u_origin.x) & 1)
#define RCD_BAYER_OFFSET_Y (((p.u_chans.x >> 1) ^ p.u_origin.y) & 1)
const int CFA_RED = 0; const int CFA_GREEN = 1; const int CFA_BLUE = 2;
highp float rawNormalizedClamped(ivec2 position) {
    ivec2 sensor = position + p.u_origin;
    uint index = uint(sensor.y) * uint(p.u_pitch) + uint(sensor.x);
    uint word = codes[index >> 1u];
    uint value = (word >> ((index & 1u) * 16u)) & 65535u;
    int q = ((sensor.y & 1) << 1) | (sensor.x & 1);
    return clamp((float(value) - p.u_black[q]) * p.u_invRange[q], 0.0, 1.0);
}
const highp float RCD_EPS = 1e-5;
const highp float RCD_EPSSQ = 1e-10;

highp float rcdSqr(highp float value)
{
    return value * value;
}

highp float getRaw(ivec2 position)
{
    position = clamp(position, ivec2(0), ivec2(RCD_WIDTH - 1, RCD_HEIGHT - 1));
    return rawNormalizedClamped(position);
}

ivec2 rcdRedOffset()
{
    return ivec2(RCD_BAYER_OFFSET_X, RCD_BAYER_OFFSET_Y);
}

ivec2 rcdBlueOffset()
{
    return ivec2(1) - rcdRedOffset();
}

int rcdFc(ivec2 position)
{
    int x = position.x & 1;
    int y = position.y & 1;
    if (x == RCD_BAYER_OFFSET_X && y == RCD_BAYER_OFFSET_Y)
        return CFA_RED;
    if (x == 1 - RCD_BAYER_OFFSET_X && y == 1 - RCD_BAYER_OFFSET_Y)
        return CFA_BLUE;
    return CFA_GREEN;
}

bool rcdIsGreen(ivec2 position)
{
    return rcdFc(position) == CFA_GREEN;
}
