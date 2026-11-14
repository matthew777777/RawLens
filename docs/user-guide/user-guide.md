# RawLens user guide

RawLens captures and develops RAW photos, combines bursts, and records RAW or encoded video. This guide covers the current app controls. Available lenses, sensor modes and recording options depend on your phone.

For a printable copy, open [the HTML guide](user-guide.html) in a browser and choose Print → Save as PDF.

## 1. First setup

1. Allow camera access when asked.
2. Open **Settings → Lenses** and choose **Recommended**, or select the RAW-capable lenses you want. Save the selection. If a route fails, try another route for that lens.
3. Choose a photo format. **JPEG** gives a developed image ready to share; **JPEG+DNG** also keeps a RAW master; **DNG** saves RAW for later editing. The developed format can be changed to JPEG XL in Photo settings.
4. Start in **AUTO**, select a lens, tap your subject to focus, and press the photo shutter.
5. Open the gallery thumbnail to check the result.

Microphone permission is requested for video sound. Denying it allows silent recording. Location is optional. The photo-folder grant lets burst motion sidecars sit next to your DNGs; choose `DCIM/RawLens` when asked.

## 2. Viewfinder and quick controls

Use the mode control to select AUTO, PROGRAM, ZSL or MANUAL. Lens buttons switch cameras; only selected lenses appear. The capture-format control chooses which files to save. The preview control chooses how the screen displays the image. Changing the preview does not change the saved file format.

Tap the ISO, shutter, white-balance or focus chip to open its controls. The quick panel provides shortcuts for capture, merge and monitoring options. Some controls are unavailable during a save, a recording or on cameras without the required hardware capability.

Tap the subject to focus. Move the exposure target to meter another part of the scene. Face-priority AF/AE can follow a face when enabled; an explicit tap target takes priority. Fixed-focus lenses do not offer a focus-distance slider.

Use the histogram or waveform to judge brightness. The SCOPE quick control toggles scopes; hold it to change the graph. RAW zebras, false colour and the RAW exposure meter are enabled under **General**. These measure sensor data before the display look. They can differ from the brightness of the developed image, and sparse measurements may miss tiny clipped highlights.

Grid, level, timer, torch and OIS controls help with framing and capture. Focus peaking highlights detail during manual or tap focus; use it as an aid alongside the preview.

## 3. Exposure modes

| Mode | What it does | Typical use |
|---|---|---|
| AUTO | The camera chooses exposure. EV adjusts exposure compensation. | Everyday photos and a starting point on an unfamiliar lens. |
| PROGRAM | RawLens meters RAW and balances shutter speed against ISO using a per-lens profile. | Control over motion blur, noise and highlights without setting both axes manually. |
| ZSL | Keeps recent RAW frames ready before the shutter press. | Catching a moment or feeding a burst merge. |
| MANUAL | You set ISO and shutter speed. | Consistent exposure, tripod work or deliberate motion rendering. |

### PROGRAM controls

Tap ISO or shutter to adjust that axis. Hold a chip to lock its value while the other remains automatic. Release locks with Auto. Holding the second axis when one is already locked enters MANUAL.

The PROGRAM editor is available through **Settings → Exposure → PROGRAM custom AE**. It includes shutter/ISO priority, axis locks, sensor bounds, an automatic handheld shutter limit, metering and brightness bias. Profiles are saved per lens. Use a faster shutter limit for moving subjects; allowing a slower shutter can reduce ISO but increases blur risk.

Scene-brightness controls choose Natural, Fixed or Protect highlights behavior. Experimental curves expose brightness targets for different scene levels. Reset the targets if you want to return to the supplied curve.

Cameras without manual sensor control use Android AE and cannot apply the full PROGRAM profile.

### ETTR and Active D-Lighting

**ETTR single exposure** under Exposure measures RAW highlights and tries to raise exposure while retaining the chosen headroom. It respects its exposure/noise limits; once converged it can hold exposure. If PROGRAM appears held, check ETTR and axis locks. Movement or changing light can make a simpler automatic exposure more useful.

**Active D-Lighting** under Photo protects highlights through PROGRAM/ETTR and lifts developed shadows and midtones. It can change capture exposure as well as the developed look. EV is unavailable in MANUAL.

### White balance and focus

Tap WB for the current control. Hold WB to switch between Kelvin and the camera's advertised presets. Kelvin adjustment requires usable camera calibration; automatic Kelvin readouts are estimates, particularly under mixed or strongly coloured light. White balance also applies to video and can be changed during a take.

Manual focus uses the focus slider. For video, save A/B marks and pull between them as described in the video section.

## 4. Photo settings

Open **Settings → Photo** for output format and the shared RAW development look.

| Setting | Purpose |
|---|---|
| Developed format | Choose JPEG or JPEG XL for developed output. The capture-format control separately decides whether to save DNG, developed output or both. |
| JPEG quality and chroma subsampling | Balance file size and compression quality. Ultra HDR uses the platform encoder, which controls its chroma subsampling. |
| JPEG XL options | Set quality, effort, decoding speed, bit depth and pixel type. Higher effort can take longer to save. |
| HDR transfer | JPEG XL can use SDR or HDR PQ. PQ needs compatible HDR viewing software and a suitable display. |
| Ultra HDR JPEG | Adds an HDR gain map on supported Android versions. An editor or viewer may display only the SDR base or discard the gain map. |
| Display P3 | Writes wider-gamut developed output; use colour-managed software when viewing or editing it. |
| Demosaic | AMaZE or RCD for still development. Quad-Bayer follows its supported AMaZE path. |
| Highlight reconstruction | Attempts to reconstruct clipped colour before demosaic; it cannot recover every fully clipped highlight. |
| False-color suppression | Reduces colour speckles and moiré using colour-difference cleanup. |
| Purple defringe | Reduces purple/magenta fringes at high-contrast edges. |
| AgX look and adjustments | Choose the supplied look and adjust contrast, saturation, purity, hue preservation, highlight/shadow range and gamut compression. |
| Adaptive development exposure | Adjusts development brightness from scene statistics. Separate PROGRAM strength controls how much adaptation it receives. |

Headroom, sky protection and shoulder controls shape how bright areas enter the tone mapping. **Reset official AgX Base** restores the supplied base settings.

Photo look adjustments affect developed files. DNG compression and calibration are separate controls under General; AI RAW denoise is an exception that can write modified RAW data.

### Device-specific sensor modes

On supported Redmi Note 15 Pro 5G hardware, Photo may show **Redmi sensor modes**. This requires root access granted to RawLens and compatible firmware. The lens switcher then exposes the supported resolution/crop and DCG14 routes. DCG14 requires automatic exposure; manual ISO/shutter and flash are unavailable. Use the capability notes shown in the app, rather than assuming another phone supports these routes.

## 5. ZSL and burst capture

Open **Settings → Burst** to enable **RAW zero shutter lag** and set the shared burst/merge frame count. The default is **16**; the selectable range is 1–59. Device buffer capacity, RAW resolution and the chosen capture method can reduce the usable count. Larger buffers consume more memory, and very long exposures slow the available history.

ZSL buffers full-resolution RAW with matching capture metadata. Wait for an active/ready status before relying on pre-shutter frames. A warming status means the buffer is filling; unavailable/fallback means ordinary capture is being used. The camera can also fall back when a stream configuration is refused.

**Top-up: complete a thin ring with fresh frames** allows new frames to fill a short buffer. It can increase the delay after pressing the shutter. Developed unmerged ZSL output has tighter memory limits than DNG-only output. The separate six-frame burst gesture produces individual captures rather than a single merged photo.

### Save now, merge later

Enable **Save ZSL bursts to .craw for offline merge** to stage a take as `IMG_<timestamp>_BURST.craw`. This saves the buffered take for later processing instead of immediately merging it.

**Burst gyro sidecars** stores motion information alongside a DNG burst. Use **Save sidecars next to DNGs (choose photo folder)** to grant or restore the folder permission. Keep `burst.json` and the `gyro/` directory with the DNGs when copying a burst to a desktop tool.

## 6. Merge settings

Use the **KB-SR**, **LK-SR** or **HDR+** quick control to choose a merge, then configure it under **Settings → Merge**. AUTO, PROGRAM and MANUAL capture fresh frames after the shutter; ZSL uses buffered frames, with top-up/reference behavior where enabled. Flash capture uses its supported single-frame path.

Keep the camera steady while frames are being collected. More frames can reduce noise but extend processing and do not guarantee more detail when the subject is moving.

### KB-SR

KB-SR means Kernel-Based Super-Resolution; older documents call this engine MFSR. Choose **KernelNet** or **Wronski** reconstruction kernels. **S.3 artefact rejection** helps reject inconsistent high-frequency detail. **Zipper fix (colour-difference merge)** targets coloured edge patterns. **Learned noise model** controls the noise-model option used by the engine.

### LK-SR

LK-SR uses Lucas–Kanade alignment and reconstruction. Its viewfinder zoom control cycles **1×, 1.5× and 2×**. The separate resolution button selects sensor-size or 2× reconstruction output and displays the resulting megapixel count. Zoom controls framing; resolution controls output dimensions.

The Merge tab provides:

- **ISP preview during LK-SR merge:** temporarily replaces the RAW viewfinder with the camera preview, pausing RAW scopes and peaking to reduce competition for the GPU.
- **LK-SR denoise: sliding DCT:** optional denoise during reconstruction; off by default.
- **LK-SR reference: middle of the burst:** on by default, collects around the shutter press and chooses a reference near the middle. Turning it off uses pre-press frames and a recent reference.
- **Show LK-SR Lab (expert tuning):** exposes advanced reconstruction settings. These apply to subsequent live and offline LK-SR merges. Use **Reset LK-SR Lab to defaults** to remove overrides.

The standalone LK-SR Lite research tool is separate from the app's LK-SR engine.

### HDR+

HDR+ combines aligned RAW frames to reduce noise. **Auto strength** selects strength from measured noise and motion; it ignores the manual slider. With Auto off, higher strength can clean up a static scene but increase ghosting on moving subjects.

HDR+ burst merging and HDR exposure bracketing are separate features.

## 7. Offline Merges

1. Open **Settings → Burst → Open merges**.
2. Select a staged burst. Ordinary RAW Video recordings are excluded from this list.
3. Choose **KB-SR**, **LK-SR** or **HDR+**, then the available zoom and source-frame options.
4. Choose DNG, developed output, or both. JPEG is the initial output choice; choose JPEG or JXL as the developed format and set its quality.
5. Use **Fine-tune method** if needed, then start the merge and watch the progress/status display.

**Process all** queues the listed bursts. During batch processing, the stop action finishes the current job before stopping the queue. Burst sharing lets you transfer staged source data for later processing. Keep source bursts until you have checked the result. Staged orientation is retained when the take is loaded.

## 8. HDR brackets

Under **Settings → Exposure**, HDR bracketing offers ±2 EV or ±4 EV sequences. Save a merged DNG or choose to keep all three brackets separately. Sequential trio uses 0 / −2 / 0 EV to combine repeated base exposures with a highlight-protecting frame.

**Experimental two-exposure RAW HDR** is a separate, camera-0-only option. Sequential captures can show motion differences; use a steady scene. **Debug → Save debug HDR frames** retains extra source/output files for investigation and uses additional storage.

## 9. Denoise and capture sharpening

Open **Settings → Denoise**.

**Capture sharpening** restores detail in developed photos. Choose its strength preset here; it does not sharpen the saved sensor DNG.

**Profiled wavelet (Vulkan)** processes camera-linear developed output. Strength controls overall reduction; Detail changes texture preservation; Luma controls luminance smoothing. The default algorithm is RAWR. **RawLens Wiener algorithm** switches to the alternative rule; its Grain control adjusts how much fine grain remains. These controls do not denoise the sensor DNG.

**AI RAW denoise (RawNIND Bayer)** requires the model files supplied by a compatible build. It writes a denoised DNG and develops from that data. **Save original DNG alongside** also retains the untouched capture. AI strength blends the source and denoised result, from untouched at 0% to full model output at 100%.

LK-SR sliding DCT and RAW Video DCT have separate switches. Changing photo wavelet settings does not enable them.

## 10. Video recording

Switch to video mode and choose **Encoded Video** or RAW Video under **Settings → Video**. Entering video starts with automatic exposure; manual video ISO/shutter controls remain available. Photo PROGRAM, ZSL, ETTR and burst processing are suspended, and the previous photo mode is restored when you leave video.

Choose your recording folder and format before starting. Changes marked for the next take require stopping and starting a new recording. Watch the health display for frame drops, heat, low space or stalled output. Recording performance depends on sensor readout, exposure, resolution and processing load.

### Encoded Video

Encoded Video writes **10-bit HEVC MP4**. Choose output profile, frame rate, resolution, crop and demosaic:

- **Rec.709:** conventional developed video.
- **ACES fitted:** a baked filmic approximation in Rec.709, suitable for normal viewing; it is not ACES scene data.
- **S-Log3:** S-Gamut3.Cine / S-Log3 for grading. Interpret the recording as video/limited range.
- **HLG:** HDR output for a compatible editing and display workflow.

**MHC** is the default demosaic. **Super pixel** reduces processing and detail by combining each Bayer quad. Digital gain brightens the encoded image without changing sensor exposure. Saturation and vibrance adjust colour; these grading controls can change during a take.

### LUT monitoring and baking

Import extracted `.cube` files through **Import LUT (.cube)**, select a LUT and adjust its intensity. The library accepts up to 16 imports. Use a LUT intended for S-Gamut3.Cine/S-Log3.

With baking off, the take monitor uses the selected assist/LUT while the recording retains S-Log3. Enable **Bake selected LUT into S-Log3 recordings** to save the imported look as SDR Rec.709 in a `_LUT709` file. The built-in assist is monitor-only; other output profiles ignore the baking option.

### RAW Video

RAW Video writes `.craw` files. **CRAW Live** is the default codec, **CRAW Balanced** trades CPU work for potentially smaller files, and **CRAW Live v1 — Legacy** remains available for compatibility. Compression preserves the input samples; file size and speed vary with the scene.

Select the advertised RAW frame-rate range, crop and optional half-resolution binning. **RGB** binning saves demosaiced colour; **CFA** saves a smaller half-resolution mosaic for later demosaic.

**DCT denoise (RAW Video)** applies temporal/spatial denoise before writing the file. It is off by default, is baked into the saved samples, and applies to the next take. Turn it off when you want an untouched sensor-sample recording. Motion metadata is stored for later stabilization.

### Audio, locks and focus pulls

**Video sound** enables microphone capture during recording. Current capture uses PCM16 audio; microphone availability and permission determine whether sound is present. The on-screen meters show recording levels.

**Lock exposure**, **Lock white balance** and **Lock focus on record** freeze the automatic values at recording start. Leave them off for continuous automatic adjustment. Manual controls remain available during a take.

For an A/B focus pull, set a manual focus distance and tap an empty mark to store it. Tap a stored mark to pull to it; hold a mark to replace it. Cycle the duration through 0.5, 1.2, 2.5 and 5 seconds; hold the duration control to clear the marks. Lens changes clear marks. Actual lens response depends on the camera.

### Preview sleep

During a take, **Preview** in quick controls pauses monitoring. Tap the black preview or Wake to resume. Hold Preview, or use Video settings, to set an idle timeout of Never, 5, 10, 30 or 60 seconds. Video and audio keep recording while monitoring and assist analysis sleep.

### Stabilization

**Stabilized output** selects **OG only**, **Stabilized only** or **Both** for Encoded Video. Stabilization runs on the phone after recording stops and writes a `_STAB.mp4` copy. The implementation uses gyro motion, path smoothing and self-calibration. If processing fails, the original is retained. Allow processing to finish and inspect the result before deleting originals.

### Recording folder

Use **Choose recording folder** to record directly to a permitted phone folder or attached USB drive. Without a selected folder, the destination is `Downloads/RawLens`. Reconnect an unavailable drive before recording. **Recover older RAW recordings** is available for recordings from the older storage workflow.

## 11. General settings

| Control | What to know |
|---|---|
| DNG sensor calibration | Per-camera black/white levels, noise and colour metadata overrides. Reset restores the device declarations. Use measured values when overriding calibration. |
| Vendor keys | Per-lens request/session options exposed by the manufacturer. Availability and accepted values depend on firmware. |
| DNG writer backend | Choose the platform writer or the available TinyDNG/fallback option. |
| DNG compression | Optional lossless JPEG XL mosaic compression on supported output paths; older RAW editors may lack DNG 1.7 support. |
| Pause camera while in Settings | Saves power by closing the camera behind Settings. Active recordings continue. |
| ISP preview | Uses the camera's preview instead of the RAW-developed viewfinder. Its appearance can differ from the saved RAW development. |
| Preview resolution and mode | Adjust monitoring cost and choose RAW, developed or format-following preview. |
| Exposure overlay and RAW meter | Choose zebras/false colour and spot/average RAW readings. These aids do not change exposure. |
| Focus peaking | Enable it and choose its overlay colour. |
| Save GPS location | Adds a fresh location to supported photos and MP4 recordings after permission is granted. |
| Settings backup | Export a JSON backup. Import replaces settings and asks for a restart. |

## 12. Lenses, Debug and About

**Lenses** groups routes that reach the same lens and recommends a route using capabilities and previous use. Rear and front camera routes are grouped separately. A lens can advertise RAW but refuse a particular stream combination. Try another route or RAW size if needed. RAW size/format choices are per lens; unsupported choices can fall back. **Copy lens report** provides route and capability information for support.

**Debug** contains HDR source-frame saving, Camera2/RAW viewfinder overlays and app logging. Enable only the diagnostics you need, reproduce the issue, then use **Share latest log**. Check logs before sharing, since device and capture details can be included.

**About** provides installed build information, licences and notices. GitHub builds also offer a manual update check and an optional startup check. F-Droid builds disable the updater.

## 13. Files and common problems

Photos normally appear under `DCIM/RawLens`. Burst sidecars use the granted photo folder, with a Download fallback when the grant is unavailable. Video uses the recording destination selected in Video settings. Changing preview mode does not select DNG output; use the capture-format control.

| Problem | Try |
|---|---|
| A lens will not open | Select its recommended route, try another RAW size, or return vendor keys to known working values. |
| ZSL is warming or falls back | Wait for fresh frames; reduce buffer depth or RAW resolution if the device cannot sustain the stream. |
| Exposure seems held | Check MANUAL, PROGRAM axis locks, ETTR and video record locks. |
| Preview is black | Wake a sleeping recording preview; otherwise check exposure, light and camera status. |
| RAW and developed previews look different | Check preview mode, white balance, AgX/adaptive exposure and whether ISP preview is active. |
| Merge ghosts or loses detail | Try less subject motion, fewer frames, HDR+ auto strength or reset LK-SR Lab overrides. |
| Recording drops frames | Reduce resolution/frame rate or processing load, check free space and the destination, and let an overheated device cool. |
| No audio | Check Video sound, microphone permission and microphone availability. |
| Missing burst motion files | Restore the photo-folder grant and keep sidecars with the DNGs. |
| JXL or AI output is unavailable | Check whether this build includes the required encoder or model assets. |

For a device report, include the phone model, Android version, affected lens/Camera2 ID, selected mode and steps to reproduce. See [Contributing](../../CONTRIBUTING.md). For engineering details, see [current repository state](../CURRENT_STATE.md).
