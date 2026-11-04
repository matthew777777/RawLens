// SPDX-License-Identifier: GPL-3.0-or-later
// PhotonCamera, Copyright (C) 2020-2022 Eszdman and contributors.
// Verbatim NoiseEstimate and IsoBin from DynamicNoiseStore.java at
// b2eff821a858033515eb2700e7292befecc95225 (blob f1dfd229a25cf6d107bfc6ba4fa797c94f848471).
// Only the enclosing class and stdin driver below are test adapters.
import java.util.ArrayList;
import java.util.Scanner;
public class PhotonIsoBin {
    public static final class NoiseEstimate {
        public final double s;
        public final double o;
        public final long key;
        public NoiseEstimate(double s, double o) {
            this(s, o, 0L);
        }
        public NoiseEstimate(double s, double o, long key) {
            this.s = s;
            this.o = o;
            this.key = key;
        }
    }
    public static final class IsoBin {
        public static final int MAX_WINDOW = 10;
        private final ArrayList<NoiseEstimate> samples = new ArrayList<>(MAX_WINDOW);
        // Total estimations ever committed to this bin (for diagnostics).
        private int totalEstimations = 0;

        public void add(double s, double o) {
            add(s, o, 0L);
        }

        public void add(double s, double o, long key) {
            samples.add(new NoiseEstimate(s, o, key));
            totalEstimations++;
            // Adaptive moving window: cap at MAX_WINDOW. Before 10 estimations the
            // window grows with the data (averaging all available); once 10 is
            // reached it becomes a fixed moving average of the last 10.
            if (samples.size() > MAX_WINDOW) {
                samples.remove(0);
            }
        }

        /**
         * Duplicate-scene sharpening: replace the most recent sample with
         * this key by the lower-S pair, in place - the window never slides,
         * so other scenes keep their slots. No-op for unknown keys.
         */
        public void replaceMin(long key, double s, double o) {
            if (key == 0L) return;
            for (int i = samples.size() - 1; i >= 0; i--) {
                NoiseEstimate n = samples.get(i);
                if (n.key == key) {
                    if (s < n.s) samples.set(i, new NoiseEstimate(s, o, key));
                    return;
                }
            }
        }

        public NoiseEstimate average() {
            if (samples.isEmpty()) return null;
            // Lower-half trimmed mean (minimum-statistics style): per-capture
            // noise estimates are right-skewed because scene texture leaks
            // positively into the estimate (measured variance = noise + leak
            // >= noise), so the lower half of the window tracks the
            // clean-capture noise floor; a plain mean is dragged up by every
            // textured capture. Trimmed instead of a pure minimum so one
            // unlucky low capture cannot latch the store low.
            ArrayList<NoiseEstimate> sorted = new ArrayList<>(samples);
            sorted.sort((a, b) -> Double.compare(a.s, b.s));
            int keep = Math.max(1, (sorted.size() + 1) / 2);
            double ss = 0.0, so = 0.0;
            for (int i = 0; i < keep; i++) {
                ss += sorted.get(i).s;
                so += sorted.get(i).o;
            }
            return new NoiseEstimate(ss / keep, so / keep);
        }

        public int count() {
            return samples.size();
        }

        public int totalEstimations() {
            return totalEstimations;
        }
    }
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        while (in.hasNextInt()) {
            int n = in.nextInt();
            IsoBin bin = new IsoBin();
            for (int i = 0; i < n; ++i) bin.add(in.nextDouble(), in.nextDouble());
            NoiseEstimate e = bin.average();
            System.out.println(e.s + " " + e.o);
        }
    }
}
