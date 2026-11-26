// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
// Portrait bokeh: shared helpers. Prepended after "#version 310 es".
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler2DArray;
precision highp image2D;
precision highp image2DArray;

// ACEScg (AP1, D60) -> linear Rec.709 (D65, Bradford). Column-major.
const mat3 AP1_TO_709 = mat3(
    1.70505, -0.13026, -0.02400,
   -0.62179,  1.14080, -0.12897,
   -0.08326, -0.01055,  1.15297);

// Display-ish encoding the networks see.
vec3 portraitDisplay(vec3 acescg) {
    vec3 c = max(AP1_TO_709 * acescg, vec3(0.0));
    c = c / (c + 0.18) * 1.18;
    return pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2));
}
// Edge-fit guide: the display curve applied once to linear Rec.709 luminance
// (scalar, one pow). thumb.glsl writes it to alpha for the CPU side, so both
// ends of the guided filter see the same quantity.
float portraitGuide(vec3 acescg) {
    float y = max(dot(AP1_TO_709 * acescg, vec3(0.2126, 0.7152, 0.0722)), 0.0);
    y = y / (y + 0.18) * 1.18;
    return pow(clamp(y, 0.0, 1.0), 1.0 / 2.2);
}
uint portraitHash(uint x) { x ^= x >> 16; x *= 0x7feb352du; x ^= x >> 15; x *= 0x846ca68bu; x ^= x >> 16; return x; }
