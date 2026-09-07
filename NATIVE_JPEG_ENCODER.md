# Native JPEG encoder

RawLens SDR JPEG output now uses libjpeg-turbo 3.2.0 through JNI.

- Default JPEG quality: 100
- Selectable quality: 1-100
- Default chroma subsampling: 4:2:2
- Selectable chroma subsampling: 4:2:2 / 4:4:4
- Existing sRGB / Display P3 output transforms remain unchanged.
- The native writer extracts Android's own ICC profile from a 1x1 color-tagged probe JPEG and embeds that ICC payload into native JPEG output, retaining platform color tagging without shipping a hard-coded profile.
- Quality >= 98 explicitly uses the accurate integer DCT (JDCT_ISLOW).
- JPEG entropy coding uses the standard Huffman tables. This avoids the extra full-image
  statistics pass required by optimized Huffman coding; decoded pixels and the selected quality
  and chroma subsampling are unchanged, at the cost of a usually small increase in file size.
- Native output uses a 256 KiB stdio buffer to avoid excessive small writes through MediaStore's
  file-descriptor layer.
- Ultra HDR remains on Android's JPEG/R encoder because the Bitmap gainmap/JPEG-R container is platform-specific. The selected JPEG quality is passed to that encoder; explicit chroma subsampling selection applies to native SDR JPEG output.

Native code is compiled into the existing `dngCreator` shared library and links statically against a SHA-256-pinned libjpeg-turbo 3.2.0 build.
