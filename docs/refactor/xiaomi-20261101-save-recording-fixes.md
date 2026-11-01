# Xiaomi save and recording failures — 2026-11-01

Evidence: the six supplied logcat exports (Xiaomi 24030PN60G, SDK 34).

- 10:40:01 and 10:41:41: RAW acquisition/pairing succeeds at 4096x2304, then
  Android DngCreator throws an uncaught AssertionError for dimensions that do
  not match the pre-correction active array or pixel array.
- 10:42:25: direct route 2 saves via TinyDNG after the existing missing-WB fallback.
- 10:42:34: direct route 2 RAW recording fails at frame metadata, sequence 0,
  with a NullPointerException. This route repeatedly supplies NaN neutral and
  unity gains; the obfuscated trace does not identify the exact null field.
- 60 FPS has range=null and is logged as not advertised; DCG is not verified.

Changes:

1. Check actual image extent against both platform-supported sensor extents.
   Incompatible or unknown geometry uses TinyDNG before entering DngCreator.
2. Convert AssertionError only within the platform writer to IOException so
   explicit Android saves fail recoverably and AUTO can retry TinyDNG using
   its existing temporary-output isolation.
3. Video metadata selects valid camera neutral, valid non-unity camera gains,
   or a same-frame RAW estimate using the existing sensor white-locus estimator.
   Invalid neutral array elements are treated as unavailable. With insufficient
   signal, omit asShotNeutral and record whiteBalanceSource=unavailable rather
   than inventing unity WB or aborting the RAW recording.
4. Serialize optional metadata only when present and finite, using the Object
   JSON overload. Exact timestamp pairing remains required.

Validation includes cropped/full geometry, NaN/unity WB, gains, dark-frame
metadata, missing optional values, JSON serialization and timestamp mismatch.
An affected-device retest is required: save 4096x2304 DNGs on routes 0 and 2,
record CRAW on route 2, and inspect RAW payload and metadata in the resulting
files. These changes do not establish DCG support or advertised 60 FPS.
