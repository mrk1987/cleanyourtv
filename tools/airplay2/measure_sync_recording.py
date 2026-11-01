#!/usr/bin/env python3
"""Match two stationary recording sections to a known coded source.

Requires PyAV, NumPy and SciPy; Matplotlib is optional with --plot. Keep recordings
and output reports in ignored storage. Positive measured delay means the first
speaker plays later than the second speaker, including sound travel time.
"""
import argparse
import json
from pathlib import Path

import av
import numpy as np
from scipy import signal

RATE = 16000


def decode(path):
    with av.open(str(path)) as container:
        stream = container.streams.audio[0]
        converter = av.AudioResampler(format="fltp", layout="mono", rate=RATE)
        frames = []
        for frame in container.decode(stream):
            frames.extend(f.to_ndarray().ravel() for f in converter.resample(frame))
        frames.extend(f.to_ndarray().ravel() for f in converter.resample(None))
    if not frames:
        raise ValueError("No decoded audio.")
    return np.concatenate(frames).astype(np.float64)


def match(reference, recording, window_seconds=1.5, step_seconds=0.75):
    window = int(window_seconds * RATE)
    step = int(step_seconds * RATE)
    if min(window, step) < 1 or len(reference) < window or len(recording) < window:
        raise ValueError("Audio is shorter than the matching window.")
    cumulative = np.concatenate(([0.0], np.cumsum(reference ** 2)))
    energy = np.maximum(cumulative[window:] - cumulative[:-window], 1e-12)
    rows = []
    for begin in range(0, len(recording) - window + 1, step):
        section = recording[begin:begin + window]
        correlation = signal.fftconvolve(reference, section[::-1], mode="valid")
        scores = np.abs(correlation) / np.maximum(np.sqrt(energy * np.sum(section ** 2)), 1e-12)
        best = int(np.argmax(scores))
        # Ignore adjacent correlation lobes when assessing a competing match.
        alternatives = scores.copy()
        alternatives[max(0, best - RATE // 5):best + RATE // 5 + 1] = 0
        runner_up = float(np.max(alternatives))
        rows.append({"recording_start_seconds": begin / RATE,
                     "source_start_seconds": best / RATE,
                     "offset_seconds": (best - begin) / RATE,
                     "correlation": float(scores[best]),
                     "peak_ratio": float(scores[best] / max(runner_up, 1e-12))})
    return rows


def summarise(rows, first, second, window_seconds=1.5):
    groups = []
    for start, end in (first, second):
        candidates = [r for r in rows if r["recording_start_seconds"] >= start and
                      r["recording_start_seconds"] + window_seconds <= end and
                      r["correlation"] >= 0.15 and r["peak_ratio"] >= 1.8]
        if len(candidates) < 3:
            raise ValueError("Too few confident matches in a stationary section; no delay reported.")
        offsets = np.array([r["offset_seconds"] for r in candidates])
        middle = np.median(offsets)
        mad = np.median(np.abs(offsets - middle))
        accepted = [r for r in candidates if abs(r["offset_seconds"] - middle) <= max(0.003, 4 * mad)]
        if len(accepted) < 3:
            raise ValueError("Stationary-section matches are inconsistent; no delay reported.")
        values = np.array([r["offset_seconds"] for r in accepted])
        if np.ptp(values) > 0.020:
            raise ValueError("Stationary-section offsets vary by over 20 ms; no delay reported.")
        groups.append({"region_seconds": [start, end], "accepted_windows": len(accepted),
                       "median_offset_seconds": float(np.median(values)),
                       "offset_10th_90th_percentile_seconds": np.percentile(values, [10, 90]).tolist()})
    return {"first_speaker_delay_relative_to_second_ms":
            1000 * (groups[1]["median_offset_seconds"] - groups[0]["median_offset_seconds"]),
            "groups": groups,
            "note": "Window spread is not a confidence interval; distance, reflections, processing and recorder drift contribute uncertainty."}


def region(value):
    start, end = map(float, value.split(":"))
    if start < 0 or end <= start:
        raise argparse.ArgumentTypeError("Use increasing non-negative START:END seconds.")
    return start, end


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference", type=Path)
    parser.add_argument("recording", type=Path)
    parser.add_argument("--first", type=region, required=True)
    parser.add_argument("--second", type=region, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--plot", action="store_true")
    args = parser.parse_args()
    source, recording = decode(args.reference), decode(args.recording)
    if max(args.first[1], args.second[1]) > len(recording) / RATE:
        parser.error("Stationary region extends beyond the recording.")
    band = signal.butter(4, [500, 3400], btype="bandpass", fs=RATE, output="sos")
    source, recording = signal.sosfiltfilt(band, source), signal.sosfiltfilt(band, recording)
    rows = match(source, recording)
    result = summarise(rows, args.first, args.second)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "measurement.json").write_text(json.dumps({**result, "windows": rows}, indent=2) + "\n")
    print(json.dumps(result, indent=2))
    if args.plot:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        fig, axes = plt.subplots(2, 1, figsize=(11, 6), constrained_layout=True)
        time = np.arange(len(recording)) / RATE
        axes[0].plot(time[::32], recording[::32], linewidth=0.4)
        axes[0].set(ylabel="Filtered recording", title="Coded-source speaker alignment")
        for bounds, label, color in ((args.first, "First speaker", "tab:orange"),
                                     (args.second, "Second speaker", "tab:blue")):
            for ax in axes:
                ax.axvspan(*bounds, color=color, alpha=0.1)
            axes[0].plot([], [], color=color, label=label)
        second_offset = result["groups"][1]["median_offset_seconds"]
        valid = [r for r in rows if r["correlation"] >= 0.15 and r["peak_ratio"] >= 1.8 and
                 min(abs(r["offset_seconds"] - g["median_offset_seconds"]) for g in result["groups"]) < 0.05]
        axes[1].scatter([r["recording_start_seconds"] + 0.75 for r in valid],
                        [1000 * (second_offset - r["offset_seconds"]) for r in valid], s=20)
        axes[1].axhline(0, color="gray", linewidth=0.8)
        axes[1].set(xlabel="Recording time (seconds)", ylabel="Delay vs second speaker (ms)")
        axes[0].legend()
        for ax in axes:
            ax.grid(alpha=0.2)
        fig.savefig(args.output / "alignment.png", dpi=150)


if __name__ == "__main__":
    main()
