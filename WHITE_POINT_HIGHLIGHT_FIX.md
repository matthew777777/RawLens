# Camera-space white-point highlight fix

JPEG highlight neutralization now runs in demosaiced camera RGB before white balance / calibration / camera-to-ACEScg conversion.

Pipeline:

`AMaZE camera RGB -> clamp negative reconstruction -> 70%..99% clipping proximity -> blend toward normalized camera neutral white -> camera-to-ACEScg -> AgX/output`

The camera neutral is derived from the resolved AsShotNeutral (or the existing Camera2-gain/unity fallback) and normalized by its maximum component.

Core shader operation used in both `amaze/final.glsl` and `amaze/final_display.glsl`:

```glsl
highp vec3 whiteBlendRgb = smoothstep(vec3(0.70), vec3(0.99), cameraRgb);
highp float whiteBlend = max(whiteBlendRgb.r, max(whiteBlendRgb.g, whiteBlendRgb.b));
cameraRgb = mix(cameraRgb, u_camera_white_normalized, whiteBlend);
```

This protects clipped highlights from becoming magenta/pink when white balance or the color matrix amplifies unequal clipped camera channels.
