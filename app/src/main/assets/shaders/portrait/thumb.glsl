// Area-averaged, display-encoded thumbnail for the depth and person networks.
layout(local_size_x = 16, local_size_y = 16) in;
layout(rgba16f, binding = 1) readonly uniform image2D u_scene;
layout(rgba8, binding = 0) writeonly uniform image2D u_thumb;
uniform ivec2 u_size;
uniform ivec2 u_tsize;
void main() {
    ivec2 g = ivec2(gl_GlobalInvocationID.xy);
    if (g.x >= u_tsize.x || g.y >= u_tsize.y) return;
    ivec2 x0 = g * u_size / u_tsize;
    ivec2 x1 = max((g + 1) * u_size / u_tsize, x0 + 1);
    ivec2 st = max((x1 - x0) / 8, ivec2(1));
    vec3 acc = vec3(0.0);
    float n = 0.0;
    for (int y = x0.y; y < x1.y; y += st.y) {
        for (int x = x0.x; x < x1.x; x += st.x) {
            acc += imageLoad(u_scene, ivec2(x, y)).rgb;
            n += 1.0;
        }
    }
    imageStore(u_thumb, g, vec4(portraitDisplay(acc / n), portraitGuide(acc / n)));
}
