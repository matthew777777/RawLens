Source: https://github.com/matthew777777/tinydng
Commit: 1f181699511e08e51baabd9fdab2311ea1253d4b

Vendored C11 v3 core and lossless JPEG codec; ZIP, baseline JPEG, PSD and threading disabled.
rawlens.patch adds checked extra TIFF fields to the writer and increases its tag capacity.
Upstream v3 parses NoiseProfile/GainMap but does not emit them; the app supplies these
and full Camera2 calibration as serialized extra fields. Duplicate generated tags fail.
Reproduce by copying the named upstream files and applying this patch with patch -p1.
