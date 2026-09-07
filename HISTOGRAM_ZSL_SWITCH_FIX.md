# ZSL histogram source switching fix

The live histogram now changes source based on actual frame availability rather than the requested capture mode.

- Entering ZSL: the processed YUV preview histogram keeps updating while the RAW stream warms up.
- First exact timestamp-paired RAW_SENSOR frame: the histogram switches immediately to RAW SENSOR.
- While RAW histogram is live: TextureView bitmap readback stops, avoiding unnecessary UI/GPU readback during full-resolution RAW streaming.
- Leaving ZSL or entering ZSL fallback: RAW hold is cleared immediately and a fresh YUV preview histogram is requested, so the old RAW graph does not remain frozen for two seconds.
- Late RAW callbacks from ordinary non-ZSL still captures are ignored by the live histogram UI.

The prior PREVIEW + RAW_SENSOR repeating-request ZSL implementation is retained because this app uses its own RAW ImageReader/ring buffer rather than Camera2 reprocessing ZSL.
