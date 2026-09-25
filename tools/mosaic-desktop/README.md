# mosaic-desktop

Mosaic SR desktop CLI (unified library: `tools/sr-vulkan`).
See `tools/sr-vulkan/README.md` for build/run and `tools/sr-vulkan/PARITY.md`
for the 1:1 contract.

```bash
./gradlew :tools:mosaic-desktop:installDist
tools/mosaic-desktop/build/install/mosaic-desktop/bin/mosaic-desktop \
  --in <dng-dir> --out /tmp/mos --crop 0,0,512,512
```
