# KernelNet model (verbatim from PhotonCamera)

Source: https://github.com/eszdman/PhotonCamera @ b2eff821a858033515eb2700e7292befecc95225
Files: app/src/main/assets/models/kernelnet_aniso_v2_2_params.ncnn.{param,bin}
License: GPL-3.0 (PhotonCamera), compatible with RawLens (GPL-3.0-or-later).

SHA-256:
7060a9456f331a7b59ff6826f7ca4b636caab1d806b4d8acb12ee51a0f437f15  kernelnet_aniso_v2_2_params.ncnn.param
b3a5156433c3ad9cafbb52729060c3de867416ce356589bae6a8ef6c305fc645  kernelnet_aniso_v2_2_params.ncnn.bin

Evaluated in-engine by shaders/kernelnet.comp (no ncnn runtime); parity
against ncnn is checked by tools/sr-pipeline/parity/kernelnet_parity.py.
