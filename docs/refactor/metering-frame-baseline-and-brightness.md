# Metering frame baseline and scene brightness tuning

> Repository context: [current source state](../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

## Frame metadata

PROGRAM and ETTR now pair metering RAW images with repeating capture results using
`Image.timestamp == SENSOR_TIMESTAMP`. A 16-result history handles metadata arriving
first. At most one image waits for metadata, for up to 250 ms. Missing metadata or
invalid exposure skips that sample; the latest frame is never substituted. Pending
images are released on timeout and teardown before reader retirement.

The matched result supplies ISO, exposure time, dynamic black/white levels, scene
flicker and the noise profile used for ETTR dynamic range. Physical result metadata
is selected for a routed physical camera when available. Static sensor levels remain
the fallback when the matched metadata has no dynamic levels. Sampling preserves
fractional dynamic black levels. The existing lifecycle generation guard rejects
worker results from old sessions.

Enable DEBUG logging for RawLensCamera to inspect `Metering timestamp=...` records.
They include the matched result frame, sampled ISO/exposure and latest ISO/exposure.
A device trace across abrupt brightness changes is needed to establish whether the
previous mismatch caused visible pumping; unit tests cannot establish that.

## Per-lens brightness profiles

PROGRAM's limits editor now offers Fixed (existing default), Natural and Protect
highlights. Selection is stored in the per-lens profile. Old profiles retain Fixed.

Adaptive modes use `green mean / ((ISO / 100) * exposureSeconds)` as a scene brightness
proxy, not lux. Interpolation is linear in log scene brightness:

| Scene proxy | Target |
| --- | --- |
| 0.1 | 0.06 |
| 1 | 0.10 |
| 16 | 0.16 |
| 256 | 0.18 |

Values clamp beyond the endpoints. Histogram constraint targets scale with the
brightness target so the lower highlight constraint does not cancel dark-scene
placement. Protect highlights also selects AGC's upper histogram constraint.
Existing EV bias, axis locks, exposure limits and flicker handling still apply.

These are initial creative curves, **not phone-calibrated profiles**. Validate
scene transitions, warm/saturated scenes, clipping and noise on real devices before
changing defaults. Saturated RAW and non-linear vendor sensor modes can bias the
scene estimate.

## References

- Raspberry Pi libcamera delayed controls:
  https://github.com/raspberrypi/libcamera/blob/c682979b50695b93526adb1e66db33743484b63b/src/libcamera/delayed_controls.cpp
- Existing RawLens ProgramAgc port and its BSD attribution remain unchanged except
  for configurable brightness and histogram constraint targets.
