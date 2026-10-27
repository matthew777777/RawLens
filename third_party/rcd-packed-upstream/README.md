# Packed-ratio RCD source

Original shader files from the user-provided source archive are retained here. The project is licensed under GNU GPL v3; see LICENSE.txt. No original shader copyright or license notices were removed.

The adapted runtime shaders are in app/src/main/assets/shaders/rcd_packed.

Direct Log's RCD option uses this backend, with fused P010 output for full-size and scaled recording. Scaling retains the separate grade's source-coordinate mapping and half-float rounding. Full-size and scaled output have separate specialized pipelines. Workgroups are tuned per stage, with a smaller-workgroup fallback for devices with lower limits. Still photos retain the existing RawLens RCD implementation. If Packed RCD cannot initialize, video uses the existing MHC fallback; it does not select the old RCD video backend.

Runtime changes preserve the tested source pixels: the weighted median exploits the duplicated center sample, shared arrays match the actual workgroup, and the fused output retains the source half-float rounding before the unchanged grade. `tools/build_packed_rcd_shaders.sh` rebuilds and validates the shaders and checks the weighted-median network. Device tests compare source RGB pixels, separate/fused P010 across all profiles, and asynchronous submissions with distinct per-frame grades.
