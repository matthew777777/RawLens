# C9 reference-parity harness (checklist §M C9, docs/refactor/mfsr-c9-reference-parity.md)

Python side, run with the CPU reference's venv (matthew777777/Handheld-Multi-Frame-Super-Resolution):

    python c9_export.py <reference repo> <burst dir> <out dir> <x> <y> <size>   # burst.f32, cfg.json, out_ref.f32, flows/rob/cov
    python c9_levels.py <reference repo> <out dir>                               # per-level flows, frames 1 and 6
    python c9_l2tie.py  <reference repo> <out dir>                               # float64 SSD of the diverging L2 tiles
    python cmpf32.py <a.f32> <b.f32> <height> [border]                           # PSNR / max / pixel counts

RawLens side (writes out_rawlens*.f32, flows_rawlens.f32, cov_rawlens.f32, ... into the same dir):

    ./gradlew :tools:sr-pipeline:test --tests '*C9ReferenceE2eTest*' -PsrpDiag=1 -PsrpHeap=12g -PsrpBurstDir=<out dir>
