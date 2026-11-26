// SPDX-License-Identifier: Apache-2.0
// Catmull-Rom weights of the 4 taps x0 - 1 .. x0 + 2 at fraction f, and their derivative by f. Bilinear stays the
// default forward model; this is the LKRL_CUBIC experiment (one camera-RGB plane of z per Bayer sample).
vec4 crW(float f) {
  float g = f * f, h = g * f;
  return vec4(-0.5 * h + g - 0.5 * f, 1.5 * h - 2.5 * g + 1.0, -1.5 * h + 2.0 * g + 0.5 * f, 0.5 * h - 0.5 * g);
}
vec4 crD(float f) {
  float g = f * f;
  return vec4(-1.5 * g + 2.0 * f - 0.5, 4.5 * g - 5.0 * f, -4.5 * g + 4.0 * f + 0.5, 1.5 * g - f);
}
int cpos(int x, int n) { return clamp(x, 0, n - 1); }   // taps beyond the border repeat it, in U and in U^T
vec4 crRow(int base, ivec2 x0, int dy) {
  int r = base + cpos(x0.y + dy, pc.H) * pc.W;
  return vec4(z[r + cpos(x0.x - 1, pc.W)], z[r + x0.x], z[r + x0.x + 1], z[r + cpos(x0.x + 2, pc.W)]);
}
float crSample(int base, ivec2 x0, vec2 f) {
  vec4 wx = crW(f.x), wy = crW(f.y);
  return dot(wy, vec4(dot(wx, crRow(base, x0, -1)), dot(wx, crRow(base, x0, 0)), dot(wx, crRow(base, x0, 1)), dot(wx, crRow(base, x0, 2))));
}
// (value, d/dx, d/dy) in HR px
vec3 crSampleGrad(int base, ivec2 x0, vec2 f) {
  vec4 wx = crW(f.x), wy = crW(f.y), dx = crD(f.x), dy = crD(f.y);
  vec4 a = crRow(base, x0, -1), b = crRow(base, x0, 0), c = crRow(base, x0, 1), d = crRow(base, x0, 2);
  vec4 v = vec4(dot(wx, a), dot(wx, b), dot(wx, c), dot(wx, d));
  return vec3(dot(wy, v), dot(wy, vec4(dot(dx, a), dot(dx, b), dot(dx, c), dot(dx, d))), dot(dy, v));
}
