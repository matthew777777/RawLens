import unittest
import numpy as np
from compare_focus_metrics import scores, BLUR


class FocusMetricsTest(unittest.TestCase):
    def test_optical_like_blur_reduces_scores_without_changing_exposure(self):
        image = np.zeros((64, 64)) + .15
        image[:, 29:35] = .7
        blurred = image.copy()
        for _ in range(6):
            windows = np.lib.stride_tricks.sliding_window_view(np.pad(blurred, 1, mode='edge'), (3, 3))
            blurred = np.einsum('ijkl,kl->ij', windows, BLUR)
        a, b = scores(image, 0), scores(blurred, 0)
        for name in a:
            self.assertGreater(a[name], b[name], name)

    def test_clipped_and_textureless_regions_do_not_create_evidence(self):
        flat = np.full((64, 64), .2)
        self.assertTrue(all(v < 1e-20 for v in scores(flat, 0).values()))
        flat[:] = np.nan
        self.assertTrue(all(v is None for v in scores(flat, 0).values()))

    def test_noise_correction_reduces_noise_scores(self):
        rng = np.random.default_rng(123)
        image = .2 + rng.normal(0, .01, (64, 64))
        raw, corrected = scores(image, 0), scores(image, .0001)
        for name in raw:
            self.assertLess(corrected[name], raw[name]*.15, name)

if __name__ == '__main__':
    unittest.main()
