"""Check delay sign, distinct source sections and refusal of weak matches."""
import unittest
import numpy as np
from scipy import signal
from measure_sync_recording import RATE, match, summarise


class SyncMeasurementChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rng = np.random.default_rng(81091)
        raw = rng.standard_normal(20 * RATE)
        band = signal.butter(3, [500, 3400], fs=RATE, btype="bandpass", output="sos")
        cls.source = signal.sosfiltfilt(band, raw)
        pulse = np.arange(len(raw)) % (RATE // 2) < int(0.12 * RATE)
        cls.source *= pulse

    def measure(self, first_delay):
        time = np.arange(12 * RATE) / RATE
        delay = np.where(time < 4, first_delay, 0)
        source_indices = np.rint((time + 2 - delay) * RATE).astype(int)
        recording = self.source[source_indices].copy()
        recording[4 * RATE:8 * RATE] = 0  # exclude the simulated walk
        result = summarise(match(self.source, recording), (0, 4), (8, 12))
        return result["first_speaker_delay_relative_to_second_ms"]

    def test_first_speaker_late(self):
        self.assertAlmostEqual(self.measure(0.230), 230, delta=0.2)

    def test_first_speaker_early(self):
        self.assertAlmostEqual(self.measure(-0.120), -120, delta=0.2)

    def test_silence_is_not_a_measurement(self):
        with self.assertRaisesRegex(ValueError, "Too few confident"):
            summarise(match(self.source, np.zeros(12 * RATE)), (0, 4), (8, 12))

    def test_inconsistent_stationary_offsets(self):
        rows = [{"recording_start_seconds": i, "offset_seconds": i / 10,
                 "correlation": 0.9, "peak_ratio": 5} for i in range(12)]
        with self.assertRaisesRegex(ValueError, "vary by over"):
            summarise(rows, (0, 4), (8, 12))


if __name__ == "__main__":
    unittest.main()
