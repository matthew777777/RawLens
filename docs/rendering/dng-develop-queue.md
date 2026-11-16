# DNG development queue

Open **Settings → Burst → Develop DNGs · Import / export queue**, or use the
same button at the top of **Open merges**. Import one or multiple DNGs through
the system document picker. Source access and the ordered queue survive leaving
the screen and restarting the app. Removing an item removes only its queue entry.

Open **Export settings** to choose Basic/AgX, automatic exposure, exposure
compensation, highlight shoulder, JPEG quality, sRGB/Display P3, false-color
suppression and purple defringe. Starting freezes these settings on waiting
items. Photos are developed sequentially through the shared photo-development
executor. The foreground notification opens the queue and supports stopping
after the current photo. Failed files do not stop later files; use Retry failed
to try them again. Completed photos can be opened from their row. The primary action becomes
**Export again** after a batch completes; individual rows also offer **Queue again**.
Settings use the shared Material controls on a dedicated page, and the queue
uses compact thumbnail rows with per-file action menus.

JPEGs are new MediaStore files under `DCIM/RawLens/Developed`, with source
orientation, capture time, camera make/model and exposure facts. Originals are
never changed. The queue records the output URI only after successful saving.
An interrupted in-progress item becomes Failed on process restart and asks the
user to check exports before retrying; it is not silently rerun or overwritten.

## Decoder and development

`DngImportReader` traverses TIFF/SubIFDs and selects the largest raw IFD instead
of an embedded preview. Camera profiles and white balance can be inherited from
the parent IFD. Metadata parsing bounds directory counts, field payloads and
pixel dimensions before allocating. Raw data is decoded through the bundled
TinyDNG library, with Android zlib for Deflate and a libjxl bridge for DNG 1.7
JPEG XL segments. Uncompressed, lossless JPEG, Deflate, LZW and PackBits paths
follow TinyDNG's decoder support. Strips, tiles and planar input are assembled
into interleaved native-order samples. The import bridge supports unsigned
integer and IEEE float samples.

Normalization honors black/white levels, repeating CFA black levels, horizontal
and vertical black-level corrections, linearization tables, active/default crop,
CFA phase, gain maps, AnalogBalance, white balance, calibration and color/forward
matrices. BaselineExposure is added to the user's compensation. Bayer DNGs use
the regular demosaic/development path; linear RGB bypasses demosaic and retains
the calibrated Basic highlight-exposure guard. Floating-point values above white
are retained by decoding.

This is not a universal DNG SDK: BigTIFF, non-Bayer mosaics, signed samples,
baseline-JPEG DNGs and unsupported required opcodes report a per-file error.
Files are limited to 512 MB and 50 million pixels; the queue holds 200 entries.
Optional unsupported optics/profile editing operations are not an emulation of
RawTherapee. The queue supplies the app's Basic/AgX look, not the appearance of
an external editor.

## Purple subject protection

The shared defringe detector formerly used green contrast and purple hue alone,
which also selected real purple/magenta petals. The revised detector requires a
neutral/blue bright background and the dark side of the edge. It also counts
coherent chroma support in a 3×3 neighborhood: broad purple surfaces are retained,
while narrow chroma ridges remain eligible. False-color median filtering remains
a separate setting; it can still remove tiny colored details and can be disabled
independently in the queue.

Validation is covered by `DngImportReaderTest`, `DngImportQueueInstrumentedTest`
and `ChromaCleanupDeviceTest`. External personal photographs are optional test
fixtures and are never bundled in the app or repository.
