package com.matthew.rawlens

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real AAC encoding and MP4 metadata readback; needs neither camera nor location permission. */
@RunWith(AndroidJUnit4::class)
class VideoLocationMetadataDeviceTest {
    @Test fun recordingAndRemuxPreserveLocationWhileFixlessOutputOmitsIt() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val tagged = File.createTempFile("gps-tagged", ".mp4", cache)
        val copied = File.createTempFile("gps-copied", ".mp4", cache)
        val plain = File.createTempFile("gps-plain", ".mp4", cache)
        try {
            encode(tagged) { VideoLocationMetadata.writeTo(it, GpsLocation(-33.86, 151.21)) }
            assertPosition(tagged)
            encode(copied) { VideoLocationMetadata.copyFrom(tagged, it) }
            assertPosition(copied)
            encode(plain) { VideoLocationMetadata.writeTo(it, null) }
            assertNull(read(plain))
        } finally {
            tagged.delete()
            copied.delete()
            plain.delete()
        }
    }

    private fun assertPosition(file: File) {
        val position = requireNotNull(VideoLocationMetadata.parse(read(file)))
        assertEquals(-33.86f, position.first, 0.0002f)
        assertEquals(151.21f, position.second, 0.0002f)
    }

    private fun read(file: File): String? {
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(file.absolutePath)
            return reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
        } finally { reader.release() }
    }

    private fun encode(file: File, metadata: (MediaMuxer) -> Unit) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        var track = -1
        try {
            metadata(muxer)
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64000)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var inputCount = 0
            var inputDone = false
            var outputDone = false
            val deadline = SystemClock.elapsedRealtime() + 15000
            val info = MediaCodec.BufferInfo()
            while (!outputDone && SystemClock.elapsedRealtime() < deadline) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val eos = inputCount == 4
                        val size = if (eos) 0 else 2048
                        codec.getInputBuffer(index)!!.apply { clear(); put(ByteArray(size)) }
                        codec.queueInputBuffer(index, 0, size, inputCount * 1024L * 1000000L / 48000,
                            if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        inputCount++
                        inputDone = eos
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                } else if (index >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        check(started)
                        muxer.writeSampleData(track, codec.getOutputBuffer(index)!!, info)
                    }
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                }
            }
            check(outputDone) { "AAC encoder timed out" }
            muxer.stop()
            started = false
        } finally {
            if (started) runCatching { muxer.stop() }
            muxer.release()
            runCatching { codec.stop() }
            codec.release()
        }
    }
}
