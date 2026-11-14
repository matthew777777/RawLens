# Equation parity progress

> Repository context: [current source state](../../../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

Current production policy: no prior by default, optional FFDNet/GAT, RawLens motion and artifact protections retained. No Mini weights or model replacement introduced.

Corrected NumPy FrameOp.jacobian to differentiate the same bilinear taps as forward sampling. Torch Ops and Vulkan global/local Jacobians already use this derivative after the earlier reapplication. Affine coordinate arithmetic in shaders uses precise to preserve boundary validity decisions.

Added Torch Ops.adj using differentiable scatter_add. Added `lkr_torch.unrolled` with explicit positive mu/eta schedules, differentiable image/motion/prior updates, fixed reference motion and explicit 1e-9 normal-equation damping. This is a research reference, not the published trained model. Existing inference API and app settings remain.

Validation: float64 exact-adjoint versus autograd below 1e-12, NumPy/Torch Jacobians agree below 1e-10 on valid samples, all six finite-difference columns below 1e-7 at both 1x and 2x. `python/lkr/check_unrolled.py` checks nonzero finite gradients through motion, mu, eta and prior.

Remaining: rerun current Vulkan stage parity and protected real-burst A/B quality checks; implement and train the four-scale ResUNet with learned schedules under the paper's Zurich/SyntheticBurst protocol. No claim of published-model weights, training, quality or phone parity. Architecture alone does not establish parity.
