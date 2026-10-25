# Camera-maker harvest — everything we can take

> Status 2026-10-23: survey only, nothing implemented from this doc yet.
> Adopted into `docs/refactor/release-2.0-checklist.md` on 2026-10-23:
> ML rechecks as F14/F15, Nikon as section H, Sony DirectLog as
> section I, the shared/other-maker builds as section J.
> Scope: Nikon, Canon, Sony, Pentax/Ricoh, Fujifilm, Olympus/OM System,
> Panasonic Lumix, Leica/Hasselblad/Sigma (checked, mostly nothing).
> Companion docs: `docs/upstream-harvest.md` (rawr scopes/wavelet),
> `references/README.md` (PhotonCamera).

## Rules

Every item is one of four take types. Do not blur them.

| Type | Meaning | Legal basis |
|---|---|---|
| `CODE` | Reuse/port open-source code | Licence compatible with GPL-3.0-or-later; keep notices, record in `NOTICE.md` |
| `DATA` | Free file the **user** downloads and imports | Free download ≠ redistribution licence. Never bundle, never scrape libraries |
| `MIMIC` | Clean-room reimplementation of documented behaviour | Behaviour/UI concepts are not code. Build our own curves/params; avoid maker trademarks as product names where a generic name works |
| `PARK` / `SKIP` | Out of scope now / not portable | Recorded so we do not re-litigate |

Status: `HAVE` (shipped), `PARTIAL` (machinery exists, feature missing),
`TAKE` (candidate), `PARK`, `SKIP`.
Effort: `S` ≤ ~1 day, `M` days, `L` week+ / new subsystem.
Licences marked `check` must be verified at port time, per repo precedent.

## What RawLens already has (do not re-pitch)

Evidence in `app/src/main/java/com/matthew/rawlens/`:

- Magic Lantern Auto ETTR — already ported: `MagicLanternEttr.kt`, `EttrLoop.kt`, `RawEttr.kt`
- Pre-capture buffers (= Nikon Pre-Release Capture, Olympus Pro Capture, Fuji Pre-Shot): `RawZslBuffer.kt`
- Focus peaking: `FocusPeaking.kt`, `FocusPeakingOverlayView.kt`
- Histogram / waveform / scopes (harvest in progress): `HistogramView.kt`, `RawWaveform.kt`, `LiveScopes.kt`, `docs/upstream-harvest.md`
- HDR bracketing + HDR+ merge: `HdrBracketConfig.kt`, `HdrPlusMerge.kt`
- Burst merge / multi-frame SR (offline): `BurstMergeRunner.kt`, `MfsrMerge.kt`, `MergesActivity.kt`
- Log video: `DirectLog*.kt`, `VfLogGrade.kt`; RAW video: `CrsWriter.kt`
- AgX looks (Base/Golden/Punchy), wavelet + RawNIND denoise, face-priority AF/AE, per-lens DNG calibration

---

## 1. Canon

Open code: **Magic Lantern** (EOS, GPL) and **CHDK** (PowerShot, GPL) —
the only true open firmware codebases from any maker. Both licence-compatible.

| Item | Type | Status | Effort | Notes |
|---|---|---|---|---|
| Auto ETTR (`ettr.mo`) | CODE | HAVE | — | Ported, see above |
| Focus peaking / zebra / cropmarks | CODE | HAVE/PARTIAL | S | Peaking shipped; zebra in scopes harvest |
| Motion-detection trigger (CHDK/ML) | MIMIC | TAKE | M | Fire shutter when VF frame changes past threshold (lightning, wildlife). Needs cheap frame-diff on VF tap; no ML code needed |
| Trap focus (ML) | MIMIC | TAKE | M | Fire when AF point reaches peak sharpness while half-pressed / in MF. Reuse `FocusPeaking` sharpness measure |
| Focus stacking sequence (ML focus stack) | MIMIC | TAKE | M | Step focus distance through N planes, capture each. Merge stays desktop/offline; capture side is Camera2 focus-distance steps |
| Advanced bracketing sequencer | MIMIC | PARTIAL | S | We have HDR brackets; ML adds ISO/aperture/flash/focus bracket axes. Extend `HdrBracketConfig` pattern |
| Intervalometer sequencer | MIMIC | PARTIAL | S | Only 2/5 s self-timer today. ML/CHDK-style interval + count + start delay is trivial on existing timer path |
| Rack focus / focus pull (video) | MIMIC | TAKE | M | A/B focus points, timed pull during record. Camera2 lens-distance ramp |
| Lua/uBASIC scripting (CHDK) | MIMIC | PARK | L | Programmable capture scripts. Powerful, big surface; revisit only if sequencer demand appears |
| MLV RAW video | CODE | HAVE | — | Own CRS path exists; MLV format itself of no value |
| Dual ISO | MIMIC | SKIP | — | Alternating per-row sensor gain; Camera2 exposes no such control |
| Canon Picture Styles (.pf2/.pf3) | DATA/MIMIC | TAKE | M | Same slot as Nikon Picture Controls (§7). Import is a parser; styles themselves are MIMIC |
| Canon Log → 709 LUTs | DATA | TAKE | S | Free Canon `.cube` downloads; covered by generic LUT import (§8) |

## 2. Nikon

No official open code. NEF SDK is closed/licensed. NikonHacker is reverse engineering.

| Item | Type | Status | Effort | Notes |
|---|---|---|---|---|
| Picture Controls (Standard…Flat, Monochrome + filters/toning) | MIMIC | TAKE | M | Parameter set: sharpening/clarity/contrast/brightness/saturation/hue. Slot beside AgX looks in `JpegOutputSettings` |
| Flexible Colour Picture Control (HSL colour blender + grading) | MIMIC | TAKE | M | Z6III-era; an HSL mixer in the develop look |
| `.NCP`/`.NP2`/`.NP3` import | CODE/DATA | TAKE | M | Format decoded by `dumpncp.pl` in `simeonpilgrim/nikon-firmware-tools` (licence `check`) and `@ssssota/flexible-color-picture-control` (JS, licence `check`). User supplies files (e.g. from nikonpc.com); never bundle that library |
| Highlight-weighted metering | MIMIC | TAKE | S | Weights by highlight brightness × area. Fourth weight in `RawProgramMeter` beside centre/average/spot |
| Active D-Lighting | MIMIC | PARTIAL | S | Capture-side highlight bias + develop-side shadow/midtone lift as one Off/Low/Normal/High/ExtraHigh control. Both halves exist separately today |
| N-Log / Nikon×RED LUTs | DATA | TAKE | S | Free `.cube` from Nikon; user-import only via §8 |
| Imaging Cloud recipes (`.NP3`) | DATA | TAKE | — | Same import path as `.NP3` above; Nikon account files, user-supplied |
| Pre-Release Capture | MIMIC | HAVE | — | = RAW ZSL buffer |
| Airnef PTP/IP Wi-Fi transfer | CODE | PARK | L | Open-source Nikon Wi-Fi import incl. `GetPartialObject` stall workaround. RawLens has no gallery — product decision, not engineering |
| NikonHacker firmware patches | — | SKIP | — | Wrong platform, legal risk |

## 3. Sony

Open code: `ma1co/Sony-PMCA-RE`, OpenMemories Framework/Tweak (MIT) —
Android apps that ran inside Sony bodies. Nothing sensor-portable.

| Item | Type | Status | Effort | Notes |
|---|---|---|---|---|
| User LUT in Picture Profile (`.cube` import) | MIMIC/DATA | TAKE | M | ZV-E1-era behaviour; same generic LUT library as §8 |
| S-Cinetone look | MIMIC | TAKE | S | One more develop look, own approximation |
| Creative Look set (VV/FL/IN/SH…) | MIMIC | TAKE | S | Fold into §7 look pack programme, generic names |
| S-Log3/S-Log2 official LUTs | DATA | TAKE | S | Free Sony `.cube`; user-import via §8 |
| Dynamic Range Optimizer (DRO) | MIMIC | HAVE | — | Same behaviour as Active D-Lighting (§2); implement once, once only |
| Zebra with custom IRE range | MIMIC | PARTIAL | S | Scopes harvest has zebra; add user-set range (Sony lets you set e.g. 95+ only) |
| Gamma Display Assist | MIMIC | TAKE | S | = LUT view assist for DirectLog preview (§8) |
| Pixel Shift Multi Shooting | MIMIC | PARTIAL | M | See §6 pixel-shift row |
| Camera Remote API (HTTP/JSON) | CODE | PARK | L | Deprecated by Sony; only for a camera-companion feature we do not have |
| PMCA/OpenMemories code | CODE | SKIP | — | MIT but targets Sony body Android; reference reading only |

## 4. Fujifilm

No open code. RAF support exists in LibRaw/darktable/RawTherapee (already our ecosystem).

| Item | Type | Status | Effort | Notes |
|---|---|---|---|---|
| Film Simulations (Provia/Velvia/Astia/Classic Chrome/Classic Neg/Pro Neg/Eterna/Acros…) | MIMIC | TAKE | M | Build own approximations. Open LUT packs exist (Sowerby LUTs, `bastibe/Darktable-Film-Simulation-Panel`) — licences `check` per pack; default to user-import (§8), not bundling |
| Recipe parameter system | MIMIC | TAKE | M | Highlight/Shadow tone, Color, Sharpness, Clarity as look parameters on top of §7 |
| Grain Effect (strength × size, ISO-dependent Acros grain) | MIMIC | TAKE | M | Post-develop grain stage; strength/size/ISO curve. No grain stage exists today |
| Colour Chrome Effect / FX Blue | MIMIC | TAKE | S | Saturation compression in deep reds (and blues); a targeted saturation rolloff in develop |
| White Balance Shift (R/B grid) | MIMIC | TAKE | S | Recipe-level WB offset stored with the look, applied on top of capture WB. Fuji-signature, tiny |
| DR100/200/400 | MIMIC | HAVE | — | Same as Active D-Lighting/DRO. Do not implement a second copy |
| Acros mono filters (Ye/R/G) | MIMIC | TAKE | S | Part of mono filter set in §7 (shared with Nikon mono) |
| F-Log/F-Log2 official LUTs | DATA | TAKE | S | Free Fujifilm `.cube`; user-import via §8 |
| Pre-Shot | MIMIC | HAVE | — | = RAW ZSL buffer |
| X-Trans demosaic | CODE | SKIP | — | Phone sensors are Bayer/Quad-Bayer; irrelevant |

## 5. Olympus / OM System

No living open code (Olympus Air open-platform SDK discontinued).

| Item | Type | Status | Effort | Notes |
|---|---|---|---|---|
| **Live Composite** | MIMIC | TAKE | M | Base exposure, then only add brighter pixels per frame (star trails, fireworks, light painting). Directly on `BurstMergeRunner` + ZSL frames. Standout add |
| **Live ND** (ND2–ND64 simulation) | MIMIC | TAKE | M | Temporal average of burst frames = long-exposure blur. CHDK RAW Average is the same idea (GPL reference) |
| Live GND | MIMIC | TAKE | M | Graduated variant of Live ND (OM-1 II); masked average, second step after Live ND |
| Live Bulb / Live Time progressive preview | MIMIC | PARTIAL | M | Show composite building during capture; falls out of Live Composite implementation |
| High Res Shot (pixel shift) | MIMIC | PARTIAL | M | See §6 |
| Art Filters (Grainy Film, Pin Hole, Diorama, Dramatic Tone…) | MIMIC | TAKE | S | Cheap look pack entries in §7; Dramatic Tone = aggressive local-contrast preset |
| Pro Capture | MIMIC | HAVE | — | = RAW ZSL buffer |

## 6. Pentax / Ricoh (+ shared pixel-shift row)

Open layer is thin: PK_Tether (unofficial Pentax tethering, licence `check`),
community Wi-Fi tether. Ricoh's genuinely open API is Theta 360 — irrelevant.

| Item | Type | Status | Effort | Notes |
|---|---|---|---|---|
| Pixel Shift Resolution (full RGB per pixel) | MIMIC | PARTIAL | L | Pentax 4-frame exact shift; Sony/Olympus variants. Phone cannot shift sensor, but handheld burst has natural sub-pixel shift: ship as **full-colour merge** (less demosaic error), not a resolution claim. Builds on `MfsrMerge`/offline merges; motion-masking is the hard part |
| Custom Images (Bleach Bypass, Reversal Film, Cross Processing, Radiant…) | MIMIC | TAKE | S | Look-pack entries we lack; Bleach Bypass/Cross Process are one curve + saturation move each |
| Monochrome-dedicated rendering (K-3 III Mono) | MIMIC | PARTIAL | S | True mono develop path (no colour filter array interpolation artefacts beyond our demosaic); mono looks in §7 cover most of it |
| PK_Tether / Pentax tether protocols | CODE | PARK | L | Camera-companion only; no gallery product surface |

## 7. Shared: look / recipe system (all makers, one build)

Nikon Picture Controls, Canon Picture Styles, Sony Creative Look,
Fuji Film Simulations + Recipes, Pentax Custom Images, Olympus Picture
Modes/Art Filters, Panasonic Photo Styles and Leica Film Styles are the
**same feature** wearing different badges. Build it once:

| Piece | Makers | Status | Effort |
|---|---|---|---|
| Parameterised look (contrast/saturation/sharpness/clarity/hue + custom tone curve) | Nikon, Canon, Pentax, Lumix | TAKE | M |
| Named look packs (own approximations, generic names) | All | TAKE | M |
| Mono + colour filter effects (Y/O/R/G) + toning | Nikon, Fuji, Pentax, Lumix | TAKE | S |
| HSL colour blender on the look | Nikon Flexible Colour | TAKE | M |
| Grain stage (strength/size/ISO-linked) | Fuji, Lumix, Olympus | TAKE | M |
| Recipe = look + WB shift + DR/ADL strength, saved as one preset | Fuji recipes, Nikon Imaging Cloud | TAKE | S |
| User look import: `.cube`, `.NCP`/`.NP3`, HaldCLUT PNG | Nikon, Fuji packs, Lumix | TAKE | M |

Where it lands: `JpegOutputSettings` beside AgX Base/Golden/Punchy, rendered
through the existing AgX/output path — looks must not fork the develop pipeline.

## 8. Shared: LUT import + view assist (one build, four makers' data)

Panasonic Real Time LUT, Sony user-LUT Picture Profiles, Nikon Zr LUT
loading and Canon log workflows all reduce to: **user imports `.cube`,
camera applies it to preview and/or output, with an intensity slider**.

| Piece | Status | Effort | Notes |
|---|---|---|---|
| `.cube` parser (LUT_3D_SIZE 16–65, domain, title) | TAKE | S | Plain text format; trivial parser, host-testable |
| 3D texture in VfLogGrade/DirectLog preview (view assist) | TAKE | M | Preview-only first: zero stills-pipeline risk |
| LUT applied to recorded video grade | TAKE | M | Second step, after preview proves out |
| LUT on JPEG develop output | PARK | M | Conflicts with AgX look ownership; decide after §7 |
| LUT library (slots, rename, delete, intensity per LUT) | TAKE | S | Panasonic's 10-slot library is the UX model (MIMIC) |
| Free maker LUT packs (Nikon RED, Sony S-Log, Fuji F-Log, Canon Log, Panasonic VariCam/creator) | DATA | TAKE | Document download links in About/Help; user imports. Never bundle |

## 9. Shared: metering / exposure behaviours (one build each)

| Behaviour | Makers | Status | Effort |
|---|---|---|---|
| Highlight-weighted metering | Nikon | TAKE | S (§2) |
| ADL / DRO / DR / i.Dynamic / Highlight Tone Priority (capture bias + shadow lift) | Nikon, Sony, Fuji, Panasonic, Canon | PARTIAL | S — implement **once** |
| Zebra with custom range, false colour | Sony, Panasonic, ML | PARTIAL | S — scopes harvest |

## 10. Shared: capture sequencers (Canon-led, all mimic)

| Behaviour | Source | Status | Effort |
|---|---|---|---|
| Motion-detection trigger | CHDK/ML | TAKE | M |
| Trap focus | ML | TAKE | M |
| Focus-bracket capture sequence | ML, Canon bodies, Lumix Post Focus | TAKE | M |
| Intervalometer (interval/count/delay) | ML/CHDK, all makers' bodies | TAKE | S |
| Live Composite / Live ND / Live GND | Olympus, newer Lumix Live View Composite | TAKE | M (§5) |
| Full-colour pixel-shift-style burst merge | Pentax, Sony, Olympus | PARTIAL | L (§6) |

## 11. Cross-maker open infrastructure (reference, not features)

| Project | Licence | Use to RawLens |
|---|---|---|
| libgphoto2 / gphoto2 | LGPL (`check`) | PTP/PTP-IP behaviour reference if a camera-companion ever ships |
| digiCamControl | GPL | Nikon/Canon tether UX + protocol reference (C#/Windows; study only) |
| Entangle | GPL | Linux tether reference |
| Airnef | `check` | Nikon Wi-Fi transfer + firmware-stall workaround (§2) |
| LibRaw / darktable / RawTherapee | LGPL/GPL | Already our algorithm ecosystem (AMaZE etc.) |
| lensfun | LGPL | Only if imported-maker-lens files ever matter; phone lenses are our own calibration |
| ExifTool / exiv2 | GPL/Artistic, GPL | MakerNote decoding reference for any future import surface |

## 12. Checked, nothing to take

| Maker | Verdict |
|---|---|
| Leica | Looks/FOTOS closed, trademark-heavy. SKIP beyond generic look-pack inspiration |
| Hasselblad | HNCS colour science closed, no open data. SKIP |
| Sigma | fp LUT/teal-and-orange era features are generic LUT/look items already in §7–§8. Nothing distinct |
| DJI / GoPro | Action-cam colour (D-Log M, HLG) reduces to §8 DATA rows; no distinct stills behaviour |

---

## Priority shortlist

**P0 — one build, many makers**
1. `.cube` LUT import + library + DirectLog view assist (§8) — unlocks Nikon/Sony/Fuji/Canon/Panasonic free LUT data
2. Parameterised look/recipe system (§7) — Nikon/Canon/Fuji/Pentax/Lumix looks in one slot
3. Highlight-weighted metering (§9) — S effort, Nikon-signature

**P1 — distinctive capture modes**
4. Live Composite + Live ND (§5, Olympus) — on existing burst/ZSL merge
5. ADL-once control (§9) — packaging of existing halves
6. Intervalometer + motion trigger + trap focus (§10, Canon) — sequencer batch

**P2 — bigger bets**
7. `.NCP`/`.NP3` look import (§2) — after §7 exists to land in
8. Full-colour burst merge (§6, Pentax) — after motion-masking research
9. Focus-bracket capture (§10) — merge side stays offline

**Parked (product decision required)**
- Any maker-camera companion/import (Airnef, digiCamControl, PK_Tether, Sony Remote API) — RawLens has no gallery by design
- CHDK-style capture scripting — only if sequencer demand appears

