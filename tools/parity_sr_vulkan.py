#!/usr/bin/env python3
"""Mechanical 1:1 enforcement for tools/sr-vulkan.

Run by :tools:sr-vulkan:parityCheck (part of `check`) and standalone:
    python3 tools/parity_sr_vulkan.py

Rules:
  IDENTICAL  - file must be byte-identical to the phone/burstrecon original.
  LIFTS      - the LIFT-BEGIN..LIFT-END body must equal the original range.
  RANGES     - explicit original line ranges must appear verbatim in the copy.
  SUBSEQUENCE- every original line must survive in order in the copy, except
               allowlisted modified lines (additive deviations).
  DIRS       - directory trees must be identical (minus .DS_Store).
  FILES      - single files must be byte-identical (models).
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/matthew/rawlens"
ML = ROOT / "app/src/main/java/com/particlesdevs/photoncamera/processing/ml"
CORE = ROOT / "tools/sr-vulkan/src/main/kotlin/com/matthew/rawlens"

RAWLENS_IDENTICAL = [
    # Pure core (no host APIs at all).
    "RawSrAlignment.kt", "RawSrBayerMerge.kt", "RawSrBurstPlanner.kt",
    "RawSrCovarianceGuide.kt", "RawSrFrameNoiseMeter.kt", "RawSrGpuScheduling.kt",
    "RawSrHighlights.kt", "RawSrHotPixel.kt", "RawSrKernelCovariance.kt",
    "RawSrMergedNoise.kt", "RawSrNoiseLut.kt", "RawSrPackedFrame.kt",
    "RawSrPackedKernelInput.kt", "RawSrRobustness.kt", "RawSrTuning.kt",
    "RawSrUnblocker.kt", "RawSrWorkers.kt", "RawSensorUnpacker.kt",
    "DngNoiseProfile.kt", "DenoiseSettings.kt", "RawSuperResolutionSettings.kt",
    "QuadBayerPreparation.kt", "CaptureFileNames.kt", "RawBayerLayout.kt",
    # Host-coupled only through the sr-vulkan host shims (android.util.Log,
    # android.os.SystemClock/Build, android.opengl.GLES30, android.content,
    # android.location, androidx.exifinterface) — every line identical.
    "RawSrMergeJob.kt", "RawSrKernelNetAniso.kt", "MosaicSrReconstructor.kt",
    "MosaicSrDngWriter.kt", "LinearRgbDngWriter.kt", "RawPreDemosaicPipeline.kt",
    "GpsLocation.kt", "AmazePipelineContract.kt",
    # Unified Vulkan backend (shared phone/desktop bytes; the GLES
    # orchestrator is retired — see check_retirement).
    "VkRawSrProcessor.kt", "VkCompute.kt", "SrVulkan.kt",
    "RawSrGpuOutput.kt", "UploadBuffers.kt", "GpuRawAmazeInput.kt",
]

LIFTS = [
    # (original, lo, hi, lift file); 1-based inclusive.
    (APP / "RawNindDenoiser.kt", 13, 137, CORE / "RawNindPack.kt"),
]

# Unified-backend files (both trees) that must stay free of GL host tokens
# (GLES30 *constants* are format tokens and stay; everything that compiles,
# binds, dispatches, or reads back through GL is gone).
VK_BACKEND_FILES = [
    "VkRawSrProcessor.kt", "VkCompute.kt", "SrVulkan.kt",
    "RawSrGpuOutput.kt", "UploadBuffers.kt", "GpuRawAmazeInput.kt",
]
RETIRED_GL_TOKENS = ("Gles31", "EglCompute", "GlTexture", "GLES31.",
                     "glReadPixels", "glGenFramebuffers", "glTexImage",
                     "glDispatchCompute", "glMemoryBarrier", "glFenceSync")

failures = []


def fail(message):
    failures.append(message)
    print(f"PARITY-FAIL: {message}")


def check_identical(src: Path, dst: Path):
    a, b = src.read_bytes(), dst.read_bytes()
    if a != b:
        out = subprocess.run(
            ["diff", "-u", str(src), str(dst)], capture_output=True, text=True
        ).stdout
        lines = out.splitlines()
        shown = "\n".join(lines[:60])
        fail(f"{dst.relative_to(ROOT)} differs from {src.relative_to(ROOT)}:\n{shown}")


def check_lift(src: Path, lo: int, hi: int, lift: Path):
    want = src.read_text().splitlines(keepends=True)[lo - 1:hi]
    text = lift.read_text().splitlines(keepends=True)
    begin = next(i for i, l in enumerate(text) if "LIFT-BEGIN" in l) + 1
    end = next(i for i, l in enumerate(text) if "LIFT-END" in l)
    got = text[begin:end]
    if got != want:
        fail(f"{lift.relative_to(ROOT)} LIFT body != {src.relative_to(ROOT)}:{lo}-{hi}")


def check_metadata_range():
    """RawFrameMetadata: lines 16-104 (all pure declarations) verbatim."""
    orig = (APP / "RawFrameMetadata.kt").read_text().splitlines()
    want = [l for l in orig[15:104]]
    copy = (CORE / "RawFrameMetadata.kt").read_text().splitlines()
    start = next(i for i, l in enumerate(copy) if l.startswith("/** A defensive immutable wrapper"))
    stop = next(i for i, l in enumerate(copy) if l.startswith("// DESKTOP COUNTERPART"))
    got = copy[start:stop]
    while got and not got[-1].strip():
        got.pop()
    if got != want:
        fail("RawFrameMetadata.kt pure region (orig 16-104) drifted")


def check_dir(src: Path, dst: Path):
    out = subprocess.run(
        ["diff", "-r", "-x", ".DS_Store", str(src), str(dst)],
        capture_output=True, text=True,
    ).stdout
    if out.strip():
        fail(f"{dst.relative_to(ROOT)} differs:\n" + "\n".join(out.splitlines()[:20]))


def check_retirement():
    """The GLES SR orchestrator is retired: its file is gone, no source may
    name it, and no GL host token may survive in the unified backend."""
    if (APP / "Gles31RawSrProcessor.kt").exists():
        fail("Gles31RawSrProcessor.kt still exists (retired by the Vulkan cutover)")
    if (ROOT / "tools/srvk_port_processor.py").exists():
        fail("tools/srvk_port_processor.py still exists (generator retired with its source)")
    for root in (ROOT / "app/src/main", ROOT / "app/src/androidTest", ROOT / "tools/sr-vulkan/src"):
        for path in sorted(root.rglob("*.kt")) + sorted(root.rglob("*.java")):
            if "build/" in str(path):
                continue
            try:
                text = path.read_text()
            except OSError:
                continue
            if "Gles31RawSrProcessor" in text:
                fail(f"{path.relative_to(ROOT)} still names the retired orchestrator")
    for name in VK_BACKEND_FILES:
        for path in (APP / name, CORE / name):
            text = path.read_text()
            for token in RETIRED_GL_TOKENS:
                if token in text:
                    fail(f"{path.relative_to(ROOT)} leaks retired GL token: {token}")


def main():
    for name in RAWLENS_IDENTICAL:
        check_identical(APP / name, CORE / name)
    check_identical(
        ML / "KernelNetNcnnProcessor.java",
        ROOT / "tools/sr-vulkan/src/main/kotlin/com/particlesdevs/photoncamera/processing/ml/KernelNetNcnnProcessor.java",
    )
    for src, lo, hi, lift in LIFTS:
        check_lift(src, lo, hi, lift)
    check_metadata_range()
    check_retirement()
    check_dir(ROOT / "app/src/main/assets/shaders", ROOT / "tools/sr-vulkan/src/main/resources/shaders")
    for model in ("kernelnet_aniso_v2_2_params.ncnn.param", "kernelnet_aniso_v2_2_params.ncnn.bin"):
        check_identical(ROOT / f"app/src/main/assets/models/{model}",
                         ROOT / f"tools/sr-vulkan/src/main/resources/{model}")
    if failures:
        print(f"\nparity_sr_vulkan: {len(failures)} FAILURE(S)")
        return 1
    print("parity_sr_vulkan: all 1:1 checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
