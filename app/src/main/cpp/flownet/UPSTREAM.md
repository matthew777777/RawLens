# FlowNet upstream provenance

These FlowNet-v2 custom layers, the JNI bridge in `../ncnnMl.cpp`, the NCNN headers and static
libraries in `../ncnn/`, the Java wrapper, and the model assets were copied from PhotonCamera:

- Repository: https://github.com/eszdman/PhotonCamera
- Commit: `9efb24a44119b04223b4a2eef50c7837ad643970`
- License: GNU General Public License version 3 or later (PhotonCamera)
- NCNN project: https://github.com/Tencent/ncnn (BSD 3-Clause)

RawLens keeps these files in-tree so a clean clone contains the complete FlowNet build input.
