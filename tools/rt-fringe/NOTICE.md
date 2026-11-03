# RawTherapee references and derivative code

Upstream: https://github.com/RawTherapee/RawTherapee
Pinned revision: `b2ee504c47e5e6d5e054395aa12bf1fe43bd1c3c`.

- `upstream/CA_correct_RT.cc`: Emil Martinec (2008–2010), improvements by
  Ingo Weyrich (2018). Original copyright/license header retained.
- `upstream/PF_correct_RT.cc`: Emil Martinec (2008–2010), optimization by
  Ingo Weyrich (2013, 2018). Original copyright/license header retained.
- `defringe.h` adapts the residual chroma, threshold and inverse-residual
  weighted neighbourhood equations in `PF_correct_RT` into bounded row rings.
  Implementation changes and numerical differences are described in README.md.
- The harness and sRGB adapter were written for RawLens in 2026.

These sources and derivatives are GPL-3.0-or-later. The upstream license is
included in `upstream/LICENSE`. RawLens is independent of RawTherapee.
