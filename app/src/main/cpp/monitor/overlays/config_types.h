#pragma once
#include <array>
#include <cstdint>

namespace monitoring_overlays {

struct ColorRgba {
    float r = 0.0f;
    float g = 0.0f;
    float b = 0.0f;
    float a = 1.0f;
};

struct RawStateOverlayParams {
    bool highlightEnabled = true;
    // Deprecated: RAW shadow warnings are superseded by TonemapShadowParams
    // (display-referred zebra). Fields remain for ABI compatibility and are
    // still honored by the standalone RAW pass, but application policy should
    // leave them disabled and use Tonemap Shadows instead.
    bool shadowWarningEnabled = true;
    bool shadowClippedEnabled = true;

    std::array<ColorRgba, 3> highlightSeverity{{
        {0.0f, 1.0f, 0.0f, 0.70f},
        {1.0f, 1.0f, 0.0f, 0.78f},
        {1.0f, 0.0f, 0.0f, 0.88f},
    }};
    std::array<ColorRgba, 3> shadowWarningSeverity{{
        {0.20f, 0.45f, 1.0f, 0.40f},
        {0.25f, 0.20f, 1.0f, 0.52f},
        {0.50f, 0.10f, 1.0f, 0.64f},
    }};
    std::array<ColorRgba, 3> shadowClippedSeverity{{
        {0.15f, 0.15f, 0.35f, 0.62f},
        {0.12f, 0.08f, 0.45f, 0.74f},
        {0.20f, 0.00f, 0.55f, 0.86f},
    }};
};

struct FocusPeakingParams {
    bool enabled = true;
    // 0 = conservative, 1 = sensitive. Internally maps to a stable score threshold.
    // Operates on display-referred (tonemapped) luma. Scores are Sobel magnitude
    // plus Laplacian assist, lightly contrast-normalized; expect thresholds far
    // lower than the legacy scene-linear detector.
    float sensitivity = 0.55f;
    ColorRgba color{0.30f, 1.0f, 0.10f, 1.0f};
    // Absolute display-domain high-frequency floor used to suppress flat-area
    // quantization/noise. Scaled by (1 + 0.5*sqrt(mean)) like before.
    float absoluteNoiseFloor = 0.012f;
    // Stabilizes contrast normalization; display means are ~0.1..0.9 so this is
    // larger than the legacy linear value.
    float normalizationFloor = 0.15f;
};

struct TonemapShadowParams {
    bool enabled = true;
    // Current photographic controls, conventional -100..+100. Informational:
    // the overlay reads the already-tonemapped image, so these sliders act
    // through the pixels themselves (darkening grows the zebra, lifting
    // shrinks it). Retained for API compat and future slider-aware features.
    float shadowsUI = 0.0f;
    float blacksUI = 0.0f;
    // Crushed-black threshold in IRE (0..100). Pixels below this always zebra.
    float thresholdIre = 10.0f;
    // Diagonal stripe period in pixels. Must be >= 2.
    float stripePeriod = 8.0f;
    ColorRgba stripeColorA{0.0f, 0.0f, 0.0f, 1.0f};
    ColorRgba stripeColorB{1.0f, 1.0f, 1.0f, 1.0f};
};

struct FalseColorRange {
    // Inclusive lower bound, exclusive upper bound, except the final active range
    // may include 100 IRE by setting highIre >= 100.
    float lowIre = 0.0f;
    float highIre = 0.0f;
    ColorRgba color{};
};

constexpr uint32_t kMaxFalseColorRanges = 16;

struct FalseColorParams {
    bool enabled = true;
    uint32_t rangeCount = 0;
    std::array<FalseColorRange, kMaxFalseColorRanges> ranges{};

    static FalseColorParams defaultPreset();
};

}  // namespace monitoring_overlays
