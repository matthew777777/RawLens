package com.matthew.rawlens;

import java.nio.*;
import java.util.*;

/** Same-VM host microbenchmark. No GPU calls and no Android energy claims. */
public final class PreviewBench {
    static volatile Object sink;
    static double measure(Runnable task, int count) {
        for (int i = 0; i < count; i++) task.run();
        double[] times = new double[7];
        for (int round = 0; round < times.length; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < count; i++) task.run();
            times[round] = (System.nanoTime() - start) / (1000.0 * count);
        }
        Arrays.sort(times);
        return times[times.length / 2];
    }
    static void compare(String label, Runnable before, Runnable after, int count) {
        double oldUs = measure(before, count), newUs = measure(after, count);
        System.out.printf(Locale.ROOT, "%s: baseline %.3f us, current %.3f us, %.2fx\n",
                label, oldUs, newUs, oldUs / newUs);
    }
    public static void main(String[] args) {
        System.out.println(System.getProperty("java.vm.name") + " " + System.getProperty("os.arch"));
        for (int white : new int[]{1023, 4095, 16383}) {
            int[] black = {4096, 4096, 4096, 4096};
            int[] den = new int[4]; Arrays.fill(den, (white - 64) * 64);
            compare("Stable LUT white=" + white,
                    () -> sink = VfLevelsBaseline.INSTANCE.lutFor(black, den),
                    () -> sink = VfLevels.INSTANCE.lutFor(black, den), 1000);
        }
        ByteBuffer raw = ByteBuffer.allocateDirect(4080 * 3060 * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < raw.capacity() / 2; i++) raw.putShort(i * 2, (short)(i % 1024));
        int[] channels = {0, 1, 2, 3};
        float[] levels = {64, 65, 66, 67}, inv = new float[4];
        for (int i = 0; i < 4; i++) inv[i] = 1f / (1023 - levels[i]);
        byte[] oldOut = new byte[128 * 96 * 4], newOut = oldOut.clone();
        Runnable oldSample = () -> {
            BguSampleBaseline.INSTANCE.sampleLowFrame(raw, 8160, 2, 0, 0, 16,
                    128, 96, channels, levels, inv, oldOut); sink = oldOut;
        };
        Runnable newSample = () -> {
            BguSample.INSTANCE.sampleLowFrame(raw, 8160, 2, 0, 0, 16,
                    128, 96, channels, levels, inv, newOut); sink = newOut;
        };
        oldSample.run(); newSample.run();
        if (!Arrays.equals(oldOut, newOut)) throw new AssertionError("Sampler mismatch");
        compare("128x96 low sampler", oldSample, newSample, 1000);
        float[] coeffs = new float[17 * 13 * 9 * 12];
        for (int i = 0; i < coeffs.length; i++) coeffs[i] = (i % 257 - 128) / 63f;
        BguGrid grid = new BguGrid(17, 13, 9, coeffs);
        ByteBuffer upload = ByteBuffer.allocateDirect(17 * 13 * 9 * 8).order(ByteOrder.nativeOrder());
        ShortBuffer halves = upload.asShortBuffer();
        short[][] expected = BguSliceBaseline.INSTANCE.packGrid(grid);
        for (int row = 0; row < 3; row++) {
            halves.clear(); BguSlice.INSTANCE.packGridRowInto(grid, row, halves);
            for (int i = 0; i < expected[row].length; i++)
                if (halves.get(i) != expected[row][i]) throw new AssertionError("Grid mismatch");
        }
        compare("17x13x9 grid CPU packing/upload preparation", () -> {
            short[][] packed = BguSliceBaseline.INSTANCE.packGrid(grid);
            for (short[] row : packed) {
                ByteBuffer bytes = ByteBuffer.allocateDirect(row.length * 2).order(ByteOrder.nativeOrder());
                for (short v : row) bytes.putShort(v);
                sink = bytes;
            }
        }, () -> {
            for (int row = 0; row < 3; row++) {
                halves.clear(); BguSlice.INSTANCE.packGridRowInto(grid, row, halves);
                sink = upload;
            }
        }, 250);
    }
}
