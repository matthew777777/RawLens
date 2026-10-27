# F2 extension: whole-frame RAW average and clipping estimate

Implemented 2026-10-26. Host and real-RAW accuracy checks passed. The phone
disconnected from ADB during the UI test; UI and recording acceptance remain
pending. This extends the previously closed spot-meter feature.

## Behavior

Settings → General → RAW exposure meter enables/disables the readout. The
adjacent RAW meter button selects Spot or Average. Spot remains the default;
existing enable preferences are retained. The main round button continues
to cycle exposure overlays only.

Average displays a compact readout such as `AVG −3.2EV 0.4%` in the existing
HUD slot beside AUTO/status. Percentage means the estimated area of sampled
Bayer cells containing at least one RAW channel at sensor white. Any detected
clipping makes the readout red. Nonzero estimates below 0.1% show `<0.1%`,
not a rounded zero. Accessibility text identifies the sampled estimate and
sample count. The average mode draws no spot-meter box.

The mean uses black-subtracted, white-normalized native CFA samples, including
both greens, and averages linear levels before converting to RAW EV. This is
not processed luminance, an AE control, or highlight headroom: a low average
can coexist with clipped highlights. Calibration and fresh/stale-frame rules
are shared with the existing spot meter.

Photo mode covers the RAW frame. DirectLog measures the recorded sensor crop;
CRS passes its resolved crop separately from preview geometry, but its existing
unimplemented backend still prevents a CRS recording validation.

## Reuse and bounded cost

This is a whole-frame adaptation of the existing RAW spot arithmetic and
Magic Lantern `raw_to_ev` port, with the sparse Bayer-grid approach already
used by `RawHistogramSampler`. See the pinned source table in
[the exposure-assist audit](raw-exposure-assist-audit.md).

The meter uses a fixed, aspect-preserving grid of at most 128×128 complete
Bayer quads, independent of overlay resolution. A 4080×3060 frame uses
12,288 cells / 49,152 metered channel values rather than a 12 MP census.
Average-only creates no histogram, overlay bitmap, or image-sized array.
The worker remains a background-priority, one-borrow worker at 250 ms
meter-only cadence (125 ms while an overlay is active).

Packed RAW10/12 use the existing unpacker with two row caches, one per Bayer
row parity. This avoids decoding an entire packed row repeatedly as each
quad switches between its two rows. No unpacking formulas were changed.

Sparse sampling can miss tiny highlights or alias repeating scene patterns;
the clipped percentage is an estimate, not proof that every pixel is safe.

## Verification

25 focused host tests passed, including new coverage for linear mean versus
mean-of-logs, all CFA patterns, either green clipping, channel-union area,
per-phase calibration, crop alignment/exclusion, invalid crops, bounded sample
count, overlay independence, rare-clipping formatting and padded/nonzero-offset
RAW10/12 equality with RAW_SENSOR.

Six real RAW captures on Xiaomi 25080RABDG / Android 16 were checked against
an independent dense scan of every complete Bayer cell, with source-buffer
hashes unchanged. The ADB report was read before the phone disconnected:

| ISO | Exposure (ns) | Sampled EV | Dense EV | Sampled clipped % | Dense clipped % | Median average-only ms |
| --- | --- | --- | --- | --- | --- | --- |
| 50 | 1000000 | −9.905387 | −9.905387 | 0 | 0 | 1.528 |
| 50 | 16000000 | −9.119594 | −9.125169 | 0 | 0 | 1.090 |
| 50 | 50000000 | −7.480647 | −7.478499 | 0 | 0 | 1.862 |
| 3200 | 1000000 | −7.184101 | −7.179726 | 0 | 0 | 1.857 |
| 3200 | 16000000 | −3.148847 | −3.149381 | 0.008138 | 0.005863 | 1.776 |
| 3200 | 50000000 | −1.630782 | −1.632257 | 10.538737 | 10.512559 | 1.815 |

For these six frames, maximum mean disagreement was below 0.006 EV and maximum
clipping disagreement below 0.03 percentage points. These are measured scene
results, not universal sampling error bounds. The RAW/render device tests
reported passes; the combined runner was interrupted during MainActivity UI
testing by the ADB disconnect and is not counted as an overall pass.

[Host results and interrupted device runner](results/f2-average-2026-10-26/).

## Resume after reconnect

Matching app and test APKs are saved locally as
`/tmp/rawlens-average-pending-arm64.apk` and
`/tmp/rawlens-average-pending-test.apk`. The latest app build includes the
packed-row-cache improvement; it still needs installation after reconnect.

Run `RawExposureAssistUiTest` with `rawlensF12Audit=true`, `f12UiRecord=true`
to check average HUD layout across photo/LOG/CRS, actual recording monitor,
mode switching and return to photo. Then run `RawExposureRecordingDeviceTest`
at 3840×2160/30 HLG with `rawlensF12Audit=true`, `f12Width=3840`,
`f12Height=2160`, `f12Fps=30`, `f12Profile=HLG`. It now compares spot, average,
average plus zebras/false colour, and surrounding disabled baselines, including
crop delivery, clipping counts, zero stale callbacks and lease cleanup.
Retrieve the on-device RAW accuracy report and final screenshots/reports,
then record the results here. Do not claim recording acceptance until it passes.
