# Main continuation prompt

Continue working on RawLens in this existing workspace:
`/Users/monikamalinowska/AndroidStudioProjects/RawLens`.

The user's objective is to improve **stability, performance, memory efficiency,
and UI/UX responsiveness without changing image quality**. Research relevant
official Android/Google, Khronos Vulkan/OpenGL ES/EGL, and Linux documentation
when making platform-specific decisions. Implement and validate improvements;
do not stop at general advice. The user explicitly added UI/UX speed to the
original objective and asked to preserve all ongoing work.

First read:

1. `docs/performance-handoff-2026-09-24/HANDOFF.md`
2. `docs/stability-performance-audit-2026-09-24.md`
3. Relevant current source and any applicable `AGENTS.md` discovered in your session.

The working tree contains hundreds of pre-existing staged, unstaged, and untracked
changes. **Do not reset, clean, overwrite, or attribute all of them to this task.**
The handoff lists this session's targeted edits. No commit was made by this session.
Do not spawn agents unless the user or applicable instructions explicitly ask.

Completed: lazy CPU fallback buffers; bounded readback scratch reuse; row-wise
RGBA-to-RGB repacking; Vulkan AHB/buffer memory-type intersection; redundant UI
update avoidance; histogram buffer reuse; HDR worker failure/interruption draining;
preview UV reuse until rotation/mirroring changes. Processing math, precision,
resolution, capture counts, and encoder settings were preserved.

Latest validation: build passed; 749 unit tests total, 747 passed, 2 skipped;
all 7 targeted device tests passed without skips against the latest installed
debug build. Evidence is saved under `docs/performance-handoff-2026-09-24/evidence/`.
Passing parity tests cover synthetic fixtures on one phone, not every capture path.

**Next priority: measured UI jank remains unresolved.** A clean 20.27-second debug
preview observation (before the last UV-cache edit) reported 31.58% HWUI jank,
median 17 ms, p95 23 ms, and p99 25 ms. These are UI counters, not RAW preview FPS.
PSS rose from 220,378 to 227,871 KiB during that single window; this does not prove
a leak. There is no controlled before/after performance comparison yet.

Collect a controlled, representative release/non-debuggable trace and comparable
memory samples; identify the actual main/render/GPU-thread bottleneck, then make
targeted fixes and repeat the same workload. Verify visibility at both ends,
camera mode/backend, refresh rate, thermal conditions, and device connection.
Do not claim an FPS gain or measured PSS saving from allocation arithmetic alone.
Keep all quality settings intact, and preserve GPU ownership/completion barriers
unless a correct replacement is demonstrated and tested.

The user authorized device installation and regression testing and repeatedly
confirmed readiness to retry. Device serial: `fe79feha9lmb6hhi`; reported model:
`25080RABDG`. ADB: `/Users/monikamalinowska/Library/Android/sdk/platform-tools/adb`.
Android sometimes rejects installs with `INSTALL_FAILED_USER_RESTRICTED`; the user
may need to approve the phone prompt. Check actual installation/test registration.
Respect current tool/sandbox approval rules; prior context does not override them.

Give concise progress updates, continue useful independent work when the device
disconnects, and keep the audit/handoff current. Distinguish measured results,
calculated allocation savings, hypotheses, and pending checks in the final report.
