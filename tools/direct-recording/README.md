# Direct recording checks

RAW video and Direct Log create their final file before camera start. The
recorders receive an open descriptor; stopping only closes/finalizes/publishes
that file. `MainActivity.videoStageDir` is retained solely to rescue recordings
made by older versions.

Run the native CRAW descriptor test without Android hardware:

```sh
bash tools/direct-recording/run_host_smoke.sh
```

It generates the maintained FD adaptation in a temporary build directory,
leaves vendored upstream files unchanged, and verifies a frame/footer, caller
FD ownership, and cleanup after a broken destination stream.

Android boundary tests (no camera needed) are in
`DirectRecordingOutputDeviceTest`: a pipe must be refused before recording;
a CRAW file must finalize in its original provider URI with the caller FD
still open after native writer close. These require a connected device.

Phone/USB acceptance:

1. Select a folder on a connected writable USB drive in Settings → Video.
2. Record short CRAW and Direct Log takes. Confirm frames advance and both
   final files are readable at the selected destination. Live logs include
   the destination URI; no new app-private raw-video take should appear.
3. Compare the selected drive's space before/during/after: one take's growth,
   not a staged take plus an export copy. No post-record export progress.
4. Disconnect the selected drive before REC: refuse startup without silently
   recording in Downloads. Reconnect and retry using the same saved grant.
5. Disconnect during a take: report a destination write/finalization failure,
   stop admitting frames, preserve nonempty partial data if the drive exposes
   it, and never claim success or switch destinations mid-recording.
6. Clear the selection: new takes write directly to Downloads/RawLens using
   one pending MediaStore file, published on successful finalization.
7. Post-record stabilization must remain unavailable in this single-file path:
   it requires a second video. Existing staged-take recovery is explicit only.

Sustained rate and disconnect behavior still depend on the phone, drive, cable,
and filesystem; host tests cannot validate those hardware combinations.
