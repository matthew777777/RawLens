# linear-sr-desktop

Linear RGB SR desktop CLI (unified library: `tools/sr-vulkan`).
See `tools/sr-vulkan/README.md` for build/run and `tools/sr-vulkan/PARITY.md`
for the 1:1 contract.

```bash
./gradlew :tools:linear-sr-desktop:installDist
tools/linear-sr-desktop/build/install/linear-sr-desktop/bin/linear-sr-desktop \
  --in <dng-dir> --out /tmp/lin --crop 0,0,512,512
```
