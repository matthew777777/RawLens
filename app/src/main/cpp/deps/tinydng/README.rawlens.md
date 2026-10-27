Source: https://github.com/matthew777777/tinydng
Base commit: 1f181699511e08e51baabd9fdab2311ea1253d4b

Vendored C11 v3 core and lossless JPEG codec; ZIP, baseline JPEG, PSD and threading disabled.
rawlens.patch records the writer metadata/streaming extensions now ported upstream:
checked TIFF fields, EXIF/GPS directories, sequential header-first strips and
native lossless JPEG XL and precompressed copy-through (DNG 1.7). The upstream port also fixes directory allocation
cleanup, header-first flush/completeness handling and JXL version validation.
Camera2 metadata conversion remains in RawLens. TinyDNG owns DNG JPEG XL encoding
when TINYDNG_HAVE_JXL is defined; Android and optional host builds link libjxl.
UPSTREAM.json retains hashes of the base sources; reproduce by copying those
sources and applying rawlens.patch with patch -p1.

Shared-source sync: all 13 files named by UPSTREAM.json match TinyDNG commit
`e1d6442` byte-for-byte. The manifest records the older base intentionally;
apply rawlens.patch to that base to reproduce the current header and writer.
The app no longer contains a separate DNG JXL encoder in tinydng_jni.cpp.
Host builds detect libjxl/libjxl_threads >=0.8 through pkg-config; Android
reuses its bundled targets. TinyDNG defaults to effort 3 / automatic threads,
emits missing DNG 1.7 versions and supports serial encoding when requested.
RawLens's one-shot saves still buffer the final TIFF; libjxl owns internal
frame/runner memory. JXL decode and tiled/header-first JXL are unsupported.
See ../../../../../../docs/tinydng-integration.md for the current integration.
