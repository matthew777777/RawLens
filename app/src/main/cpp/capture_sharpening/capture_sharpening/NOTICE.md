# capture_sharpening — NOTICE

Part of Rawr and distributed with it under **GPL-3.0-only** (see the repository
root `LICENSE` and `NOTICE`).

This module is a GPU port of **RawTherapee's** Capture Sharpening
(<https://github.com/RawTherapee/RawTherapee>, `rtengine/capturesharpening.cc`,
`rt_algo.cc`, `boxblur.cc`, `gauss.cc`; Copyright (c) 2019 Ingo Weyrich and the
RawTherapee contributors), GPL-3.0-or-later. Copyright remains with the
RawTherapee contributors.

`tools/capsharp_reference.py` is a NumPy port of the same code. `tools/rt_harness`
and `tools/rt_amaze` compile RawTherapee source extracted at build time from a
local checkout; no RawTherapee source is stored here.

Full third-party notices are in the repository root `THIRD_PARTY_NOTICES.md`.
