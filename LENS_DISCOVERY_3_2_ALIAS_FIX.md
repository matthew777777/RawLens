# Lens discovery logical/physical alias fix

This revision changes lens discovery/routing only. Autofocus code is unchanged.

- Working composite IDs (for example `3/0`) continue to be used directly.
- If a logical/physical ID such as `3/2` is advertised or recognized by the HAL but is not itself RAW-openable, RawLens keeps `3/2` as the discovered/saved lens identity and resolves it internally to physical camera ID `2` for Camera2 characteristics/opening.
- The fallback is constrained to advertised physical membership or a composite ID that the vendor camera service itself recognizes, so generated probe IDs do not create arbitrary fake lenses.
- Optical/zoom labeling uses the resolved physical camera characteristics, so `3/2` retains the same optical metric as physical ID `2`.
