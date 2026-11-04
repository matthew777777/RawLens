// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.muxer.MediaMuxerCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

/** PCM must survive the container byte-for-byte, including L/R order and signed peaks. */
class PcmVideoMuxerDeviceTest {
    @Test fun pcm16RoundTrip() {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "pcm_roundtrip.mp4")
        val expected = ByteBuffer.allocateDirect(48000 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(48000) { i ->
            expected.putShort((i * 37).toShort())
            expected.putShort((-i * 53).toShort())
        }
        expected.flip()
        val mux = MediaMuxerCompat(file.absolutePath, MediaMuxerCompat.OUTPUT_FORMAT_MP4)
        try {
            val track = mux.addTrack(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_RAW, 48000, 2).apply {
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            })
            mux.start()
            repeat(50) { index ->
                val chunk = expected.duplicate().apply { position(index * 3840); limit((index + 1) * 3840) }.slice()
                mux.writeSampleData(track, chunk, MediaCodec.BufferInfo().apply { set(0, 3840, index * 20000L, 0) })
            }
            mux.stop()
        } finally { mux.release() }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_AUDIO_RAW, format.getString(MediaFormat.KEY_MIME))
            assertEquals(48000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(2, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            assertEquals(AudioFormat.ENCODING_PCM_16BIT, format.getInteger(MediaFormat.KEY_PCM_ENCODING))
            extractor.selectTrack(0)
            val actual = ByteBuffer.allocateDirect(expected.remaining())
            val sample = ByteBuffer.allocateDirect(expected.remaining())
            while (true) {
                sample.clear()
                val size = extractor.readSampleData(sample, 0)
                if (size < 0) break
                sample.position(0); sample.limit(size)
                actual.put(sample)
                extractor.advance()
            }
            actual.flip()
            assertEquals(expected.remaining(), actual.remaining())
            while (expected.hasRemaining()) assertEquals(expected.get(), actual.get())
        } finally { extractor.release(); file.delete() }
    }
}
