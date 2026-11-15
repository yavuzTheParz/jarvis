#!/usr/bin/env python3
"""Turns two plain recordings of your voice into training clips for the wake word.

  split_recording.py name.m4a  clips/pos   # you saying "Jarvis" ~30 times, a pause between each
  split_recording.py talk.m4a  clips/neg   # a minute or two of you talking normally, without the name

Any format ffmpeg reads works (a phone's voice recorder app is ideal: it is the microphone Jarvis will
listen with). Clips are written as real__*.wav, which train.py counts extra.
"""
import subprocess
import sys
from pathlib import Path

import numpy as np
import soundfile as sf

RATE = 16000


def load(path: str) -> np.ndarray:
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", str(RATE), "-f", "s16le", "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, "<i2")


def words(a: np.ndarray) -> list[tuple[int, int]]:
    """Stretches of voice separated by at least a quarter second of quiet."""
    hop = 160
    level = np.array([np.sqrt(np.mean(a[i:i + hop].astype("f") ** 2)) for i in range(0, len(a) - hop, hop)])
    on = level > max(300, np.percentile(level, 20) * 3, level.max() * 0.06)
    spans, start, quiet = [], None, 0
    for i, v in enumerate(on):
        if v:
            start = i if start is None else start
            quiet = 0
        elif start is not None:
            quiet += 1
            if quiet >= 25:
                spans.append((start * hop, (i - quiet + 1) * hop))
                start, quiet = None, 0
    if start is not None:
        spans.append((start * hop, len(a)))
    return spans


def main():
    if len(sys.argv) != 3 or Path(sys.argv[2]).name not in ("pos", "neg"):
        sys.exit(__doc__)
    a = load(sys.argv[1])
    out = Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    pad = int(RATE * 0.15)
    n = len(list(out.glob("real__*.wav")))
    if out.name == "pos":
        kept = [(s, e) for s, e in words(a) if 0.2 * RATE <= e - s <= 1.6 * RATE]
        for s, e in kept:
            sf.write(out / f"real__{n:03d}.wav", a[max(0, s - pad):e + pad], RATE)
            n += 1
        print(f"{len(kept)} name clips in {out}. Aim for 20 or more.")
    else:
        step = 4 * RATE
        pieces = [a[i:i + step] for i in range(0, len(a) - RATE, step)]
        for p in pieces:
            sf.write(out / f"real__{n:03d}.wav", p, RATE)
            n += 1
        print(f"{len(pieces)} talk clips in {out}.")


if __name__ == "__main__":
    main()
