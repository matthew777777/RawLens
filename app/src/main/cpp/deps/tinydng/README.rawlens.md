Source: https://github.com/matthew777777/tinydng
Base commit: 1f181699511e08e51baabd9fdab2311ea1253d4b

Vendored C11 v3 core and lossless JPEG codec; ZIP, baseline JPEG, PSD and threading disabled.
rawlens.patch records the writer metadata/streaming extensions now ported upstream:
checked TIFF fields, EXIF/GPS directories, sequential header-first strips and
precompressed JPEG XL (DNG 1.7). The upstream port also fixes directory allocation
cleanup, header-first flush/completeness handling and JXL version validation.
Camera2 metadata conversion and JPEG XL encoding remain in RawLens.
UPSTREAM.json retains hashes of the base sources; reproduce by copying those
sources and applying rawlens.patch with patch -p1.
