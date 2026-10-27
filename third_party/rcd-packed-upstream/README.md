# Packed-ratio RCD source

Original shader files from the user-provided source archive are retained here. The project is licensed under GNU GPL v3; see LICENSE.txt. No original shader copyright or license notices were removed.

The adapted runtime shaders are in app/src/main/assets/shaders/rcd_packed.

Direct Log's RCD option uses this backend (16×16 workgroups), with fused P010 output when crop and output dimensions match. Still photos retain the existing RawLens RCD implementation. If Packed RCD cannot initialize, video uses the existing MHC fallback; it does not select the old RCD video backend.
