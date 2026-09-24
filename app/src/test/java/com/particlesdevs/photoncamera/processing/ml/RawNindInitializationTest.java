package com.particlesdevs.photoncamera.processing.ml;

import android.content.Context;
import android.content.res.AssetManager;
import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class RawNindInitializationTest {
    private static final class Api implements RawNindNcnnProcessor.NativeApi {
        final List<Long> destroyed = Collections.synchronizedList(new ArrayList<>());
        boolean throwTiny;
        boolean failWarmup;
        int bayerRuns;
        public long create(AssetManager assets, String path) {
            if (path.contains("tiny")) {
                if (throwTiny) throw new IllegalStateException("load failed");
                return 0;
            }
            return 2;
        }
        public boolean run(long handle, FloatBuffer input, int width, int height, FloatBuffer output) {
            assertEquals(2L, handle);
            assertEquals(width * height * 4, input.capacity());
            assertEquals(width * height * 12, output.capacity());
            bayerRuns++;
            if (failWarmup) return false;
            for (int i = 0; i < output.capacity(); i++) output.put(i, 0.25f);
            return true;
        }
        public void destroy(long handle) { destroyed.add(handle); }
    }

    private RawNindNcnnProcessor start(Api api) {
        Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getAssets()).thenReturn(mock(AssetManager.class));
        return new RawNindNcnnProcessor(context, api);
    }

    @Test public void missingTinyStillLoadsAndRunsBayer() {
        Api api = new Api();
        RawNindNcnnProcessor processor = start(api);
        try {
            assertFalse(processor.waitReady(2000));
            assertTrue(processor.waitBayerReady(2000));
            assertFalse(processor.isLoading());
            assertFalse(processor.isBayerLoading());
            FloatBuffer input = ByteBuffer.allocateDirect(4 * 4 * 4 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            ByteBuffer output = processor.runInferenceBayer(input, 4, 4);
            assertNotNull(output);
            assertEquals(8 * 8 * 3 * 4, output.capacity());
            assertEquals(0.25f, output.asFloatBuffer().get(0), 0f);
            assertEquals(2, api.bayerRuns); // warmup plus requested inference
        } finally { processor.close(); }
        assertEquals(Collections.singletonList(2L), api.destroyed);
        assertFalse(processor.isBayerReady());
    }

    @Test public void tinyLoadExceptionDoesNotSkipBayerOrLeaveLatchPending() {
        Api api = new Api();
        api.throwTiny = true;
        RawNindNcnnProcessor processor = start(api);
        try {
            assertTrue(processor.waitBayerReady(2000));
            assertFalse(processor.waitReady(0));
            assertFalse(processor.isLoading());
        } finally { processor.close(); }
    }

    @Test public void failedWarmupIsUnavailableRatherThanReadyOrPermanentlyLoading() {
        Api api = new Api();
        api.failWarmup = true;
        RawNindNcnnProcessor processor = start(api);
        try {
            assertFalse(processor.waitBayerReady(2000));
            assertFalse(processor.isBayerLoading());
            assertFalse(processor.isBayerReady());
            assertEquals(Collections.singletonList(2L), api.destroyed);
        } finally { processor.close(); }
        assertEquals(1, api.destroyed.size());
    }
}
