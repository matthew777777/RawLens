Four 32x32 camera-linear RGB samples from the owner's local DNGs under
`~/Downloads/Photos Linear 2`, filenames preserved. Little-endian float32 RGB,
row-major. Sites are selected using 32 evenly spaced integer x/y coordinates
including both image boundaries. Values are (code - BlackLevel)/(WhiteLevel -
BlackLevel), from the DNG tags; no display transform or WB applied.

These compact fixtures exercise the actual Kotlin RGB meter. They establish
histogram-policy equivalence, not full-resolution sampling or rendered-image parity.
