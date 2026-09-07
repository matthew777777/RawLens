# RawLens DNG GainMap compile/interoperability fix v7

## Compile error fixed

`NativeDngWriter.kt` no longer reads or assigns `Parameters.hasGainMap`.
GainMap presence is represented by the actual payload and grid dimensions:

- real HAL map -> non-empty `gainMap`, positive `mapSize`
- no HAL map / shading already applied -> empty `gainMap`, `mapSize=(0,0)`

`DngCreator.setParameters()` validates `gainMap.length == width * height * 4`
before calling native `setGainMap()`. This removes the Kotlin/Java field dependency
that caused `Unresolved reference 'hasGainMap'` while retaining the rule that RawLens
must not embed a fake identity GainMap.

## GainMap interoperability retained

The project also carries the TinyDNG DNG fixes needed for strict readers:

- OpcodeList2 GainMap rectangle order: Top, Left, Bottom, Right
- opcode version: 1.3.0.0 serialized correctly on little-endian Android
- Camera2 map spacing: 1/(N-1)
- four Bayer phase maps, RowPitch=ColPitch=2
- exclusive Bottom/Right bounds
- DNG ActiveArea aligned with the RAW active crop
- TinyDNG header vendored so the patched writer is deterministic

## Validation performed

A standalone TinyDNG writer smoke test generated a DNG and parsed tag 51009.
It verified GainMap opcode id 9, version 1.3.0.0 and Top/Left/Bottom/Right ordering.

The full Gradle compile could not run in the sandbox because the wrapper attempts to
fetch Gradle 8.9 from services.gradle.org and outbound DNS/network is unavailable.
