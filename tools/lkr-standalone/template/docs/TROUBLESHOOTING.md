# Troubleshooting

> Scope: this document describes the generated standalone package. Android app behavior and integration are documented in the source repository’s `docs/CURRENT_STATE.md`.

| Symptom | What to check |
| --- | --- |
| CMake cannot find `glslc` | Install shaderc, or add the Vulkan SDK `bin` to PATH; the original CMake also searches macOS Android NDK shader tools |
| Vulkan headers or library missing | Install both headers and loader; on macOS keep Homebrew architecture consistent |
| JNI/JDK not found | Set `JAVA_HOME` to a JDK, not a runtime-only installation; Homebrew OpenJDK is supported by `build.sh` |
| GCC rejects `-flto=thin` | Use Clang as shown in the Linux instructions; use a fresh build directory when changing compilers |
| Configure reuses an old compiler | Choose another build directory with direct CMake commands; CMake caches compiler selection |
| No Vulkan device / exit 1 or 2 | Check the GPU vendor driver and loader, ICD discovery, and whether the process has GPU access |
| macOS has MoltenVK but no device | Locate its `MoltenVK_icd.json`; try `VK_ICD_FILENAMES=/opt/homebrew/etc/vulkan/icd.d/MoltenVK_icd.json` on Apple Silicon Homebrew |
| Shared library cannot be loaded | The run wrapper adds common Homebrew library paths; custom installs may need a matching library path |
| Empty input or quick exit | Files must end in lowercase `.dng`; check the directory, reference index and matching Bayer RAW metadata |
| Proprietary RAW / CRAW input fails | Extract or convert to compatible Bayer DNG first; no CRAW container importer is included |
| Memory pressure or GPU allocation failure | Start with `--frames 8 --crop 512 512 --workers 1`; reducing scale from 2 to 1 reduces HR buffers |
| Missing or incomplete output | Use an existing writable output directory; check free space and actual DNG / `.f32` files |
| Dark or flat appearance | Output is camera-linear RGB; develop it in a RAW editor using a consistent exposure and tone transform |
| Unexpected colour | Check CFA, fractional black levels, white level, colour matrix and AsShotNeutral in the reference DNG |
| Moving objects ghost | Compare production defaults, motion diagnostics and a held-out frame; changes in the scene can exceed the model |
| Detail differs between full frame and crop | Noise and alignment are estimated over the processed region; a crop is a separate reconstruction |
| Prior seems inactive | The desktop default is `--prior 0`; enable `--prior 1` and inspect the prior-call count |
| Trace renderer reports no prior calls | Enable DCT and choose the actual emitted metadata file for a traced tile |
| Historical parity command cannot find Python files | Those references live in the separate RawLens research tree; see the resource guide |
| Sync reports local changes | Copy intentional exported-file edits back to the matching RawLens canonical/template files, then sync |

For a custom build location:

```sh
CC=clang CXX=clang++ cmake -S . -B build-clang -DCMAKE_BUILD_TYPE=Release
cmake --build build-clang --parallel 4
./build-clang/pipeline/lkr_full_run burst result.dng --s 2
```

Direct executable calls do not receive the run wrapper's macOS library-path
setup. Use the appropriate Vulkan loader and ICD paths for your installation.

To report a problem, include the commit/manifest, OS, compiler, GPU/driver,
complete invocation and console output. State whether a small crop reproduces
it, and include a shareable input only when you can distribute it.
