import unittest
from analyze_manual_latency import summarize


class LatencyAnalysisTest(unittest.TestCase):
    def test_requires_matching_command_and_three_stationary_frames(self):
        records = [dict(type='config', maxFocus=4, timestampSource=0),
                   dict(type='command', command=1, target=2, submittedNs=1_000_000)]
        for i in range(8):
            records.append(dict(type='frame', command=0 if i == 0 else 1, frame=i,
                                timestampNs=i * 10_000_000, startedCallbackNs=1_000_000 + i * 10_000_000,
                                reportedFocus=2, lensState=1 if i == 1 else 0,
                                gradientEnergy=.01, mean=.2, validPixels=3000))
        result = summarize(records)
        self.assertEqual(20, result['commands'][0]['stableMetadataCallbackMs'])
        self.assertIsNone(result['commands'][0]['stableSensorMs'])

    def test_missing_lens_state_is_not_stationary(self):
        records = [dict(type='config', maxFocus=4), dict(type='command', command=0, target=2, submittedNs=0)]
        records += [dict(type='frame', command=0, frame=i, timestampNs=i, reportedFocus=2,
                         startedCallbackNs=i, gradientEnergy=0, mean=.1, validPixels=3000) for i in range(8)]
        result = summarize(records)
        self.assertIsNone(result['commands'][0]['stableMetadataCallbackMs'])
        self.assertIsNone(result['commands'][0]['provisionalOpticalCallbackMs'])

if __name__ == '__main__':
    unittest.main()
