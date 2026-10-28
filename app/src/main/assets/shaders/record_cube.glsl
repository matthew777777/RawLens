// SPDX-License-Identifier: GPL-3.0-or-later
// Separate take-constant set: no per-frame uploads and no extra image pass.
// .cube red-fastest order maps to Vulkan x=R, y=G, z=B.
// Filtering/texel centers: Vulkan specification, textures chapter.
layout(set = 1, binding = 0) uniform sampler3D recordCube;
layout(set = 1, binding = 1, std140) uniform RecordCubeParams {
    vec4 minimum;
    vec4 maximum;
    vec4 options; // intensity, reserved
} cube;
vec3 applyRecordLut(vec3 encoded) {
    vec3 grid = clamp((encoded - cube.minimum.rgb) / (cube.maximum.rgb - cube.minimum.rgb), 0.0, 1.0);
    float size = float(textureSize(recordCube, 0).x);
    vec3 coordinate = (grid * (size - 1.0) + 0.5) / size;
    vec3 rendered = textureLod(recordCube, coordinate, 0.0).rgb;
    return mix(encoded, rendered, cube.options.x);
}
