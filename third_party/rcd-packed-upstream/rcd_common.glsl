#extension GL_GOOGLE_include_directive : require

#include "raw_input.glsl"

layout(constant_id = 7) const int RCD_BAYER_OFFSET_X = 0;
layout(constant_id = 14) const int RCD_BAYER_OFFSET_Y = 0;
layout(constant_id = 8) const int RCD_WIDTH = 1;
layout(constant_id = 9) const int RCD_HEIGHT = 1;

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
