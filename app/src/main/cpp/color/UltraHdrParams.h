// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Full Ultra HDR encode parameters. Ported from RAWR
// (https://github.com/adityawarmanfw/rawr app/src/main/cpp/color/UltraHdrParams.h).
// Single shared struct so the GLES gain-map pass, the CPU map fit, and the
// JPEG mux can never drift apart field-by-field. Vulkan-free: RawLens
// generates the map on GLES (see agx_srgb8.glsl / final_display.glsl).
#pragma once

#include <algorithm>

#include "gainmap/UltrahdrMux.h"

namespace rawlens::color {

struct UltraHdrParams {
    bool enabled = false;
    // Map JPEG quality. Fixed at 92 (was 95): full-res RGB maps at q92
    // hold smooth gradients without banding; q90 showed 8x8 blocking risk
    // in sky, q95+ buys nothing visible. Base quality is separate.
    int gainmapQuality = 92;
    // Identity floor (1x): the file must never darken the base rendition.
    float gainMapMinLog2 = 0.0f;
    float gainMapMaxLog2 = 4.7090998f;
    float gamma = 1.0f;
    float offsetSdr = 0.015625f;
    float offsetHdr = 0.015625f;
    float hdrCapacityMinLog2 = 0.0f;
    float hdrCapacityMaxLog2 = 4.7090998f;
    // Linear gain matching the SDR base's exposure domain (tonemap:
    // aePostGain * 2^EV). The gain-map HDR tap is pre-exposure scene linear;
    // without this, highlights clipped at 1.0 in both domains ratio to ~1x
    // and HDR highlights render flat. Encode-only; the mux never sees it.
    float hdrExposure = 1.0f;
    // Linear gain for sensor-clipped texels. Default 0 (pure measured ratio:
    // smooth natural rolloff). Positive values add specular dazzle beyond the
    // measured headroom but flatten large clipped washes into hard-edged
    // discs. Encode-only; the mux never sees it.
    float clipBoost = 0.0f;
    // Chroma protection: keeps saturated brights (warm lamps, neon) from
    // clipping white under the multiplicative single-channel gain. Default 0.6.
    float satProtect = 0.6f;
    // Multi-channel (RGB) gain map: per-channel gains preserve hue through a
    // white SDR base (warm lamp stays yellow). RGB JPEG components share one
    // ISO/XMP metadata parameter set. Default on (Photos verdict).
    bool multiChannelMap = true;
    // Post-encode map blur sigma in map texels. Default 0 (off: blur passes
    // skipped, sharper maps, no edge halo). Encode-only; the mux never sees it.
    float mapBlurSigma = 0.0f;
    // Full-resolution RGB map grid. Default true (validated A/B: sharper, no
    // extra halo). RawLens keeps the legacy quarter-res grid (1 map texel per
    // 4x4 base block) until the GLES passes grow a full-res mode; the flag is
    // carried for parity and gates the mux hint only.
    bool fullResMap = false;
    // Single-pass adaptive ceiling (applied on the CPU wide-range map, no
    // second render): p99.9 of per-pixel mean log-gain + fitMargin, rounded up
    // to fitStep, clamped to [fitFloor, min(displayCap, wideMax)].
    // displayCap 1.45 (2.8x) is the on-device ladder verdict: higher caps
    // push mids/shadows dark next to the brighter highlights on this panel.
    float gainMapFitMargin = 0.25f;
    float gainMapFitStep = 0.25f;
    float gainMapFitFloor = 1.0f;
    float gainMapDisplayCap = 1.45f;
    // Soft shoulder above the fitted max (same tanh family as the chroma knee
    // below): over-max content compresses into the top shoulderWidth EV
    // starting shoulderKneeBelowMax under max instead of clipping to flat
    // white. Encode-only like satProtect.
    float shoulderWidth = 0.5f;
    float shoulderKneeBelowMax = 0.5f;
    // Luma baseline floor (applied before the shoulder): every texel gets at
    // least fitted * baseShare * Y^baseExponent EV, Y = linear Rec.709 luma
    // of the SDR base. Relative to the fitted max so the floor scales with
    // display headroom like the highlights. baseShare 0 disables.
    float baseShare = 0.75f;
    float baseExponent = 0.4f;
    // Soft-knee chroma compression around the per-pixel channel mean
    // (d' = T*tanh(d/T)): slope 1 at neutral, asymptote +/-T. Kills hue
    // fringing (magenta windows) without the contour a hard clip prints.
    float chromaKnee = 0.05f;
    // Film glow factor (scales the post/pre scatter quotient toward the HDR
    // numerator). Unused in RawLens (no film-glow tap); carried for parity.
    // 0 = pure scene tap.
    float glowStrength = 0.0f;
    float glowMax = 2.0f;

    [[nodiscard]] gainmap::UltrahdrMuxParams toMuxParams() const {
        gainmap::UltrahdrMuxParams p;
        p.gainMapMinLog2 = gainMapMinLog2;
        p.gainMapMaxLog2 = gainMapMaxLog2;
        p.gamma = gamma;
        p.offsetSdr = offsetSdr;
        p.offsetHdr = offsetHdr;
        p.hdrCapacityMinLog2 = hdrCapacityMinLog2;
        p.hdrCapacityMaxLog2 = hdrCapacityMaxLog2;
        p.multiChannel = multiChannelMap;
        return p;
    }

    [[nodiscard]] bool validForEncode(const char** reason = nullptr) const {
        gainmap::UltrahdrMuxParams mux = toMuxParams();
        if (!gainmap::UltrahdrMux::validateParams(mux, reason)) return false;
        auto fail = [&](const char* msg) {
            if (reason) *reason = msg;
            return false;
        };
        if (!(hdrExposure > 0.0f) || hdrExposure != hdrExposure) return fail("ultrahdr: hdrExposure must be > 0");
        if (gainmapQuality < 1 || gainmapQuality > 100) return fail("ultrahdr: gainmapQuality must be 1..100");
        if (!(satProtect >= 0.0f) || !(satProtect <= 1.0f)) return fail("ultrahdr: satProtect must be 0..1");
        if (!(gainMapDisplayCap >= gainMapFitFloor)) return fail("ultrahdr: displayCap must cover fitFloor");
        return true;
    }
};

}  // namespace rawlens::color
