// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** File descriptor/provider boundary coverage. Does not open the camera. */
class DirectRecordingOutputDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun pipeDestinationIsRejectedBeforeRecording() {
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            try {
                DirectRecordingOutput.requireSeekable(pipe[1])
                fail("A cloud/provider pipe must not start recording")
            } catch (_: IOException) {}
        } finally { pipe.forEach { it.close() } }
    }

    @Test fun crawFooterLandsInTheOriginalDestinationAndCallerFdRemainsOpen() {
        assumeTrue(CrawWriter.available)
        val output = DirectRecordingOutput.create(context,
            "direct-fd-test-${System.nanoTime()}.craw", "application/octet-stream")
        var handle = 0L
        try {
            val raw = ByteBuffer.allocateDirect(32 * 32 * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(32 * 32) { raw.putShort((1000 + it % 31).toShort()) }
            raw.flip()
            val encoded = ByteBuffer.allocateDirect(CrawFormat.payloadBound(32, 32))
            val n = CrawWriter.encodeFrame(raw, raw.remaining(), 32, 32, 64,
                0, 32, encoded, false, 0, 12)
            encoded.flip()
            handle = CrawWriter.openFd(output.descriptor.fd, "{}")
            CrawWriter.writeFrame(handle, encoded, n, 0, "{}")
            CrawWriter.close(handle)
            handle = 0
            assertTrue(Os.fstat(output.fileDescriptor).st_size > 16)
            assertTrue(output.finish(true))
            val bytes = context.contentResolver.openInputStream(output.uri)!!.use { it.readBytes() }
            assertArrayEquals("CRAW".toByteArray(), bytes.copyOfRange(0, 4))
            assertArrayEquals("CRAWEND\u0000".toByteArray(), bytes.takeLast(8).toByteArray())
        } finally {
            if (handle != 0L) runCatching { CrawWriter.close(handle) }
            runCatching { output.close() }
            if (android.provider.DocumentsContract.isDocumentUri(context, output.uri)) {
                android.provider.DocumentsContract.deleteDocument(context.contentResolver, output.uri)
            } else context.contentResolver.delete(output.uri, null, null)
        }
    }
}
