#!/usr/bin/env python3
"""Generate a known, non-repeating source for room-to-room audio alignment.

Requires NumPy. Output belongs in ignored build storage. This is a relative
speaker-delay test, not an absolute microphone or acoustic-latency calibration.
"""
import argparse
import json
from pathlib import Path
import wave

import numpy as np


def generate(output: Path):
    output.mkdir(parents=True, exist_ok=True)
    rate = 44100
    duration = 90
    rng = np.random.default_rng(910026)
    audio = np.zeros(rate * duration, dtype=np.float32)
    markers = []
    length = int(rate * 0.12)
    frequencies = np.fft.rfftfreq(length, 1 / rate)
    band = (frequencies >= 450) & (frequencies <= 3500)
    taper = np.ones(length)
    edge = int(rate * 0.008)
    ramp = np.sin(np.linspace(0, np.pi / 2, edge)) ** 2
    taper[:edge] = ramp
    taper[-edge:] = ramp[::-1]
    for second in range(2, duration - 1):
        for slot in (0.15, 0.65):
            position = int((second + slot + rng.uniform(-0.09, 0.09)) * rate)
            # Every band-limited noise burst has a different waveform and time.
            # This avoids the whole-beat ambiguity of a repeating metronome.
            spectrum = np.fft.rfft(rng.standard_normal(length))
            pulse = np.fft.irfft(spectrum * band, n=length) * taper
            pulse *= 0.14 / np.max(np.abs(pulse))
            audio[position:position + length] += pulse
            markers.append({"sample": position, "duration_samples": length})
    pcm = np.rint(np.clip(audio, -1, 1) * 32767).astype("<i2")
    stereo = np.repeat(pcm[:, None], 2, axis=1)
    with wave.open(str(output / "sync-test.wav"), "wb") as stream:
        stream.setnchannels(2)
        stream.setsampwidth(2)
        stream.setframerate(rate)
        stream.writeframes(stereo.tobytes())
    (output / "sync-test-markers.json").write_text(json.dumps({
        "sample_rate": rate, "duration_seconds": duration,
        "seed": 910026, "peak_dbfs": float(20 * np.log10(np.max(np.abs(audio)))),
        "markers": markers,
    }, indent=2) + "\n")
    print(f"Generated {duration}s coded test, {len(markers)} distinct bursts: {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    generate(parser.parse_args().output)
