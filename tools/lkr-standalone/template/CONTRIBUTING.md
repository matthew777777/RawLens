# Contributing

RawLens is the canonical home of the shared sources. Change the same paths in
RawLens, then export with `python3 tools/lkr-standalone/sync.py DESTINATION` and
verify with `--check`. Change standalone documentation and packaging in
`tools/lkr-standalone/template/` in RawLens. Do not hand-patch the exported
sources or transform headers during sync.

Before submitting, build, run the colour and Vulkan robustness tests, and verify
the sync manifest. Include the exact command, GPU/driver, source commit and input
description for image-quality changes. Use a crop and fixed frame order for
comparisons. Keep RAW-domain diagnostics separate from display rendering.

Bug reports should include OS, compiler, CMake version, Vulkan loader/driver,
complete invocation, console output, and whether a smaller crop reproduces it.
Share a burst only when you have permission to distribute its images and metadata.
