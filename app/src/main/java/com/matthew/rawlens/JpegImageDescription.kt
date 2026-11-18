// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.util.Locale

/** Frozen shutter settings and actual encode result, appended to existing merge provenance.
 * A configured control is identified as such; unrecorded backend/timing facts are not invented. */
internal fun jpegImageDescription(
    settings: JpegOutputSettings,
    typeSuffix: String?,
    description: String?,
    metadata: RawFrameMetadata? = null,
    outputWidth: Int? = null,
    outputHeight: Int? = null,
    ultraHdrEncoded: Boolean = settings.ultraHdr,
    encodeMs: Long? = null,
    monochromeMode: MonoMode? = null
): String = buildString {
    fun number(value: Number): String = String.format(Locale.US, "%.3f", value.toDouble())
    val rgbMerge = typeSuffix == CaptureFileNames.TYPE_MFSR || typeSuffix == CaptureFileNames.TYPE_LKR
    val mono = monochromeMode?.takeIf { it != MonoMode.OFF }
    val input = if (mono != null) "B&W ${mono.label} photosite luminance" else if (rgbMerge) "Merged linear camera RGB" else "RAW_SENSOR"
    val tone = if (settings.toneMapper == PhotoToneMapper.ACES_2) "ACES 2.0" else if (settings.toneMapper == PhotoToneMapper.SIGMOID) "Sigmoid" else if (settings.toneMapper == PhotoToneMapper.BASIC) "Basic" else "AgX ${settings.agxLook.label}"
    val gamut = if (settings.displayP3) "Display P3" else "sRGB"
    if (!description.isNullOrBlank()) append(description.trimEnd()).append("\n\nJPEG DEVELOPMENT\n")
    else append("Captured with RawLens\nJPEG DEVELOPMENT\n")
    append("$input -> ACEScg -> $tone -> $gamut")
    if (ultraHdrEncoded) append(" -> Ultra HDR gainmap (JPEG_R)")
    append("\n\nPARAMETERS\n")
    append("- Tone mapper: ${settings.toneMapper.label}\n")
    if (settings.toneMapper == PhotoToneMapper.BASIC) {
        append("- Basic response: sRGB transfer, brightness gamma 1.100, cubic contrast blend 0.750\n")
        append("- Highlight compression: ratio-preserving rational shoulder; knee 0.600, white limit 1.000, strength ${number(settings.highlightShoulder)}\n")
    } else if (settings.toneMapper == PhotoToneMapper.ACES_2) {
        append("- ACES 2.0: perceptual tone/chroma/gamut mapping, 100 nit SDR output, reference LUT\n")
    } else if (settings.toneMapper == PhotoToneMapper.SIGMOID) {
        append("- Sigmoid: contrast 1.5, skew 0, preserve hue 100%, Rec.2020 primaries\n")
    } else {
        append("- AgX look: ${settings.agxLook.label}\n")
        append("- AgX contrast: ${number(settings.agxContrast)}\n")
        append("- AgX saturation: ${number(settings.agxSaturation)}\n")
        append("- AgX purity boost: ${number(settings.agxPurityBoost)}\n")
        append("- AgX hue preservation: ${number(settings.agxHuePreservation)}\n")
        append("- AgX shadow range: ${number(settings.agxShadowEv)} EV; effective ${number(settings.effectiveAgxShadowEv)} EV\n")
        append("- AgX highlight range: ${number(settings.agxHighlightEv)} EV\n")
        append("- AgX gamut compression: ${number(settings.agxGamutCompression)}\n")
        append("- Highlight compression: exponential shoulder; knee 0.900, strength ${number(settings.highlightShoulder)}\n")
    }
    append("- Working space: scene-linear ACEScg (AP1, D60)\n")
    append(if (settings.chromaCleanup.rapidRawActive)
        "- JPEG preprocessing: RapidRAW 1.7 develop ON; color NR ${number(settings.chromaCleanup.rapidRawColorNoiseReduction)}, " +
            "sharpening ${number(settings.chromaCleanup.rapidRawSharpening)} (linear sRGB)\n"
    else "- JPEG preprocessing: RapidRAW OFF\n")
    append("- Output: $gamut, 8-bit SDR base, sRGB transfer\n")
    if (outputWidth != null && outputHeight != null) append("- Dimensions: ${outputWidth}x${outputHeight}\n")
    if (mono != null) append("- B&W mode: ${mono.label}; photosite monochrome, no demosaic\n")
    else if (rgbMerge) append("- Input: reconstructed camera RGB; no additional demosaic\n")
    else append("- Demosaic preference: ${settings.demosaic.label}; actual backend is not recorded (AMaZE fallback supported)\n")
    append("- JPEG quality: ${settings.jpegQuality}\n")
    append("- Chroma subsampling: ${settings.chromaSubsampling.label}\n")
    append("- Encoding: ${if (ultraHdrEncoded) "JPEG/R SDR base + HDR gainmap" else "libjpeg-turbo SDR JPEG"}\n")
    if (settings.ultraHdr && !ultraHdrEncoded) append("- Ultra HDR: requested; SDR fallback written\n")
    if (ultraHdrEncoded) {
        append("- Gainmap quality: ${settings.gainmapQuality}; channels: ${if (settings.multiChannelMap) "RGB" else "luma"}\n")
        append("- Gainmap configured log2 limits: ${number(settings.gainMapMinLog2)}..${number(settings.gainMapMaxLog2)}; gamma ${number(settings.gainmapGamma)}\n")
    }
    append("\nDEVELOPMENT SETTINGS\n")
    append("- Adaptive exposure AUTO/ZSL: ${settings.adaptiveExposureAuto}; PROGRAM strength ${number(settings.adaptiveExposureProgramStrength)}\n")
    append("- Highlight headroom: ${number(settings.highlightHeadroom)}; sky protection ${number(settings.highlightSoftHeadroom)}\n")
    append("- Meter center weight: ${number(settings.centerWeight)}\n")
    append("- Active D-Lighting: ${settings.activeDLighting.label}\n")
    append("- Bayer highlight reconstruction requested: ${settings.hlReconstruction} (inpaint opposed)\n")
    append("- False-color suppression: ${settings.chromaCleanup.falseColorSuppression}\n")
    append("- Purple defringe strength: ${number(settings.chromaCleanup.defringeStrength)}\n")
    encodeMs?.let { append("\nTIMINGS\n- JPEG encode: $it ms\n") }
    metadata?.let {
        append("\nSource:\n- Camera: ${it.cameraId}\n- Dimensions: ${it.imageWidth}x${it.imageHeight}\n")
        append("- Reference timestamp: ${it.timestampNanos} ns\n- Reference frame: ${it.frameNumber}\n")
        append("- ISO: ${it.sensitivityIso ?: "?"}\n")
        append("- Exposure: ${it.exposureTimeNanos?.let { ns -> number(ns / 1e6) + " ms" } ?: "?"}\n")
    }
}.trimEnd()
