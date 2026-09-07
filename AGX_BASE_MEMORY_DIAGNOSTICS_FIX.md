# AgX Base-only + memory diagnostics fix

## Why Punchy/Golden were removed

Google Filament applies the optional AgX creative looks *after* the AgX sigmoid. Its current
`ToneMapper.cpp` uses `power = 1.35` and `saturation = 1.4` for Punchy, while Golden uses a warm
channel slope, `power = 0.8`, and `saturation = 1.3`. RawLens matched those values; the problem was
not a mistranscribed constant.

When RawLens's adjustable AgX shadow log range is extended from the Filament default 10 EV to 12 EV,
more very-low-level sensor variation becomes visible above the AgX floor. The Punchy post-sigmoid
power/saturation operation then exaggerates that chroma variation. Because AgX Base does not show
the defect on the same DNG, RawLens now exposes only the Base transform. The custom shadow-range
control remains available, including 12 EV.

The GPU shader and CPU reference no longer contain or dispatch Golden/Punchy code, and the UI no
longer offers an AgX look selector. Old stored look preferences are harmless and ignored.

## Logcat memory/resource diagnostics

A new `RawLensMemory` logger samples:

- total/dalvik/native/other PSS
- Java heap used/max
- native heap allocation
- `/proc/self/status` RSS and anonymous RSS
- live GL texture count and estimated bytes
- live GL program and framebuffer counts
- live AMaZE EGL context count

It also keeps a short per-checkpoint history and emits `POTENTIAL_MEMORY_LEAK` if repeated identical
checkpoints show strong monotonic growth. `development-coordinator-closed` additionally emits
`POTENTIAL_GL_LEAK` if tracked GL/EGL resources are still alive after shutdown.

Useful command:

```bash
adb logcat -v threadtime -s RawLensMemory RawLensDevelop
```

For a clean repeatability test, capture the same scene 8-12 times and compare the repeated
`MEM[amaze-frame-complete]` / `MEM[jpeg-frame-complete]` lines. Stable plateaus are expected because
AMaZE intentionally retains tile scratch, shader programs, an EGL context and readback buffers until
the development coordinator closes. Continuous growth is not expected.
