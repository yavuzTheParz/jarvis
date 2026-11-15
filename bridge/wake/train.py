#!/usr/bin/env python3
"""Trains the small classifier that hears "Jarvis" on top of openWakeWord's speech features.

Usage: train.py CLIPS MODELS OUT [--holdout VOICE_PREFIX] [--check DIR...]
CLIPS holds pos/ and neg/ WAVs (16 kHz mono); MODELS holds melspectrogram.onnx and embedding_model.onnx.
Real recordings go in CLIPS/pos and CLIPS/neg too, named real__*.wav; they count extra.
"""
import argparse
import glob
import os

import numpy as np
import onnxruntime as ort
import soundfile as sf

RATE, T, HIDDEN = 16000, 16, 64
CHUNK, OVERLAP = 1280, 480
rng = np.random.default_rng(3)


class Features:
    def __init__(self, d):
        o = ort.SessionOptions()
        o.intra_op_num_threads, o.log_severity_level = 1, 3
        self.mel = ort.InferenceSession(d + "/melspectrogram.onnx", o)
        self.emb = ort.InferenceSession(d + "/embedding_model.onnx", o)

    def __call__(self, pcm):
        """int16 samples to N x 96, piece by piece exactly as WakeWord.kt does on the phone.

        The spectrogram model scales to the loudest moment of its input, so a whole clip at once
        gives different numbers from 80 ms pieces. Embedding i ends at sample end_sample(i).
        """
        name = self.mel.get_inputs()[0].name
        buf, mels = np.zeros(CHUNK + OVERLAP, "f"), []
        for i in range(0, len(pcm) - CHUNK + 1, CHUNK):
            buf[:OVERLAP] = buf[CHUNK:]
            buf[OVERLAP:] = pcm[i : i + CHUNK]
            mels.append(self.mel.run(None, {name: buf[None]})[0].squeeze() / 10 + 2)
        if not mels:
            return np.zeros((0, 96), "f")
        m = np.concatenate(mels)
        win = [m[i - 76 : i] for i in range(80, len(m) + 1, 8)]
        if not win:
            return np.zeros((0, 96), "f")
        return self.emb.run(None, {self.emb.get_inputs()[0].name: np.array(win, "f")[..., None]})[0].squeeze(axis=(1, 2))


def end_sample(i):
    return (i + 10) * CHUNK


def load(path):
    return sf.read(path, dtype="int16")[0]


def trim(a):
    """Cuts the silence around a clip, so its end is where the word ends, even over a noisy room."""
    frames = [np.sqrt(np.mean(a[i : i + 160].astype("f") ** 2)) for i in range(0, len(a) - 159, 160)]
    if not frames:
        return a[:0]
    level = np.array(frames)
    loud = np.flatnonzero(level > max(300, level.max() * 0.05, np.percentile(level, 10) * 2.5))
    return a[loud[0] * 160 : (loud[-1] + 1) * 160] if len(loud) else a[:0]


def pink(n):
    f = np.fft.irfft(np.fft.rfft(rng.standard_normal(n + 2)) / np.sqrt(np.arange(1, (n + 2) // 2 + 2)))[:n]
    return f / (np.abs(f).max() + 1e-6)


def colour(a):
    """A synthetic voice made to sound like a real phone in a real room: a little faster or slower and
    higher or lower, through a cheap microphone's frequency response, with the room's echo. Without this
    a model trained on text-to-speech hears TTS voices perfectly and real people hardly at all."""
    if len(a) < 400:
        return a
    x = a.astype("f")
    # Speed and pitch together, like a different speaker.
    r = rng.uniform(0.85, 1.15)
    x = np.interp(np.arange(0, len(x) - 1, r), np.arange(len(x)), x).astype("f")
    # Microphone: band-limited, with a random tilt and a resonance somewhere in the voice range.
    n = 1 << int(np.ceil(np.log2(len(x) + 1)))
    f = np.fft.rfftfreq(n, 1 / RATE)
    g = 1 / (1 + (rng.uniform(80, 350) / np.maximum(f, 1)) ** 4) / (1 + (f / rng.uniform(3000, 7500)) ** 4)
    g = g * (np.maximum(f, 50) / 1000) ** rng.uniform(-0.4, 0.4)
    c, w = rng.uniform(800, 3500), rng.uniform(200, 900)
    g = g * (1 + rng.uniform(-0.5, 1.5) * np.exp(-((f - c) / w) ** 2))
    x = np.fft.irfft(np.fft.rfft(x, n) * g, n)[: len(x)]
    # Room: an exponentially fading noise tail, mixed in at a random strength.
    if rng.random() < 0.8:
        rt = rng.uniform(0.15, 0.7)
        k = np.arange(int(RATE * rt))
        ir = rng.standard_normal(len(k)) * np.exp(-6.9 * k / len(k))
        ir[0] = 1 / rng.uniform(0.2, 1)
        wet = np.convolve(x, ir / np.sqrt(np.sum(ir**2)))[: len(x)]
        x = x + wet * rng.uniform(0.2, 0.9)
    x = x / (np.abs(x).max() + 1e-6) * np.abs(a).max()
    return np.clip(x, -32768, 32767).astype("h")


def augment(a, babble):
    """A clip as a phone might hear it: at some distance, with noise or someone talking behind it."""
    x = a.astype("f") * rng.uniform(0.15, 1.1)
    # A microphone is never digitally silent, and the spectrogram reacts badly to true zeros.
    x = x + rng.standard_normal(len(x)) * rng.uniform(15, 400)
    level = np.sqrt(np.mean(x**2) + 1)
    kind = rng.integers(4)
    if kind == 1:
        x = x + pink(len(x)) * level * 10 ** (-rng.uniform(8, 30) / 20) * 3
    elif kind == 2:
        x = x + rng.standard_normal(len(x)) * level * 10 ** (-rng.uniform(15, 35) / 20)
    elif kind == 3 and len(babble) > len(x):
        s = rng.integers(len(babble) - len(x))
        b = babble[s : s + len(x)].astype("f")
        x = x + b / (np.sqrt(np.mean(b**2)) + 1) * level * 10 ** (-rng.uniform(10, 25) / 20)
    return np.clip(x, -32768, 32767).astype("h")


def windows(e, keep):
    return np.array([e[i - T + 1 : i + 1].ravel() for i in range(T - 1, len(e)) if keep(i)], "f").reshape(-1, T * 96)


def positive(feat, a, babble):
    w = colour(trim(a))
    if len(w) < 1600:
        return np.zeros((0, T * 96), "f")
    # Enough time before the word for a full window, and a little after it for the moment of decision.
    x = augment(np.concatenate([np.zeros(RATE * 2, "h"), w, np.zeros(RATE // 2, "h")]), babble)
    end = RATE * 2 + len(w)
    e = feat(x)
    return windows(e, lambda i: -1600 <= end_sample(i) - end <= 4000)


def negative(feat, a, babble, stride=2):
    x = augment(np.concatenate([np.zeros(RATE * 2, "h"), colour(trim(a)) if len(a) else a, np.zeros(RATE // 2, "h")]), babble)
    return windows(feat(x), lambda i: i % stride == 0)


def partial(feat, a, babble):
    """The word still unfinished is not the word yet, so he doesn't fire on its first half."""
    w = colour(trim(a))
    x = augment(np.concatenate([np.zeros(RATE * 2, "h"), w, np.zeros(RATE // 2, "h")]), babble)
    end = RATE * 2 + len(w)
    return windows(feat(x), lambda i: end_sample(i) - end <= -4800 and i % 2 == 0)


class Net:
    def __init__(self, n):
        self.p = []
        for a, b in ((n, HIDDEN), (HIDDEN, HIDDEN), (HIDDEN, 1)):
            self.p += [rng.normal(0, 1 / np.sqrt(a), (a, b)).astype("f"), np.zeros(b, "f")]

    def forward(self, x):
        h = [x]
        for i in range(0, 4, 2):
            h.append(np.maximum(h[-1] @ self.p[i] + self.p[i + 1], 0))
        return h, (h[-1] @ self.p[4] + self.p[5])[:, 0]

    def score(self, x):
        return 1 / (1 + np.exp(-np.clip(self.forward(x)[1], -30, 30)))

    def fit(self, x, y, weight, epochs=30, lr=1e-3, batch=256):
        m = [np.zeros_like(q) for q in self.p]
        s = [np.zeros_like(q) for q in self.p]
        t = 0
        for ep in range(epochs):
            order = rng.permutation(len(x))
            rate = lr * (0.5 * (1 + np.cos(np.pi * ep / epochs)) + 0.02)
            for o in range(0, len(x), batch):
                b = order[o : o + batch]
                h, z = self.forward(x[b])
                pr = 1 / (1 + np.exp(-np.clip(z, -30, 30)))
                g = ((pr - y[b]) * weight[b])[:, None]
                grads = [None] * 6
                grads[4], grads[5] = h[2].T @ g / len(b), g.mean(0)
                g = g @ self.p[4].T
                for i in (2, 0):
                    act = h[i // 2 + 1] > 0
                    g = g * act
                    grads[i], grads[i + 1] = h[i // 2].T @ g / len(b), g.mean(0)
                    g = g @ self.p[i].T
                t += 1
                for i, q in enumerate(grads):
                    m[i] = 0.9 * m[i] + 0.1 * q
                    s[i] = 0.999 * s[i] + 0.001 * q * q
                    self.p[i] -= rate * (m[i] / (1 - 0.9**t)) / (np.sqrt(s[i] / (1 - 0.999**t)) + 1e-8)

    def save(self, path):
        np.concatenate([q.ravel() for q in self.p]).astype("<f4").tofile(path)


def stream_scores(net, feat, a):
    quiet = lambda: (rng.standard_normal(RATE * 2) * 100).astype("h")
    e = feat(np.concatenate([quiet(), a, quiet()]))
    w = windows(e, lambda i: True)
    return net.score(w) if len(w) else np.zeros(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("clips")
    ap.add_argument("models")
    ap.add_argument("out")
    ap.add_argument("--holdout", default="", help="comma-separated voices left out, to test on")
    ap.add_argument("--only-real", action="store_true", help="only the real__ clips count as the name; other voices saying it teach him to ignore them")
    ap.add_argument("--test-mine", help="DIR of the user's clips left out of training")
    ap.add_argument("--test-talk", help="WAV of the user's ordinary talk left out of training")
    ap.add_argument("--check", nargs="*", default=[])
    a = ap.parse_args()
    feat = Features(a.models)
    voices = tuple(v for v in a.holdout.split(",") if v)
    held = lambda f: bool(voices) and os.path.basename(f).startswith(voices)
    pos = sorted(glob.glob(a.clips + "/pos/*.wav"))
    neg = sorted(glob.glob(a.clips + "/neg/*.wav"))
    speech = [f for f in neg if sf.info(f).duration > 2 and not held(f)]
    babble = np.concatenate([load(f) for f in rng.choice(speech, min(60, len(speech)), replace=False)])
    X, Y, W = [], [], []

    def take(w, y, weight):
        if len(w):
            X.append(w)
            Y.append(np.full(len(w), y, "f"))
            W.append(np.full(len(w), weight, "f"))

    for f in pos:
        if held(f):
            continue
        real = os.path.basename(f).startswith("real__")
        c = load(f)
        if a.only_real and not real:
            take(positive(feat, c, babble), 0, 2.0)
            continue
        for _ in range(8 if real else 2):
            take(positive(feat, c, babble), 1, 3.0 if real else 1.0)
        take(partial(feat, c, babble), 0, 1.0)
    for f in neg:
        if held(f):
            continue
        c = load(f)
        real = os.path.basename(f).startswith("real__")
        for _ in range(8 if real else 2):
            take(negative(feat, c, babble), 0, 3.0 if real else 1.0)
    for _ in range(300):
        n = rng.integers(RATE * 3, RATE * 6)
        take(windows(feat((pink(n) * rng.uniform(100, 8000)).astype("h")), lambda i: i % 3 == 0), 0, 1.0)
        take(windows(feat((rng.standard_normal(n) * rng.uniform(5, 1500)).astype("h")), lambda i: i % 3 == 0), 0, 1.0)
    x, y, w = np.concatenate(X), np.concatenate(Y), np.concatenate(W)
    w[y == 1] *= (y == 0).sum() / (y == 1).sum() * 0.15
    print(f"{int((y == 1).sum())} positive and {int((y == 0).sum())} negative windows", flush=True)
    net = Net(T * 96)
    net.fit(x, y, w)
    net.save(a.out)
    print("training set: pos mean %.3f, neg >0.5 share %.5f" % (net.score(x[y == 1]).mean(), (net.score(x[y == 0]) > 0.5).mean()))

    if a.holdout:
        ph = [f for f in pos if held(f)]
        nh = [f for f in neg if held(f)]
        ps = np.array([stream_scores(net, feat, trim(load(f))).max() for f in ph])
        ns = [stream_scores(net, feat, trim(load(f))) for f in nh]
        secs = sum(sf.info(f).duration for f in nh)
        print(f"held-out voice {a.holdout}: {len(ph)} name clips, {len(nh)} other clips, {secs / 60:.1f} min")
        for th in (0.3, 0.5, 0.7, 0.8, 0.9, 0.95, 0.98):
            fa = sum(int((s > th).any()) for s in ns)
            print(f"  th={th}: caught {(ps > th).mean():.3f}, false clips {fa}/{len(ns)}")
    def fires(scores, th):
        """As the phone decides: two windows in a row over the threshold."""
        over = scores > th
        return int(sum(over[i] and over[i - 1] and not (i > 1 and over[i - 2]) for i in range(1, len(over))))

    mine = [stream_scores(net, feat, load(f)) for f in sorted(glob.glob(a.test_mine + "/*.wav"))] if a.test_mine else []
    others = [stream_scores(net, feat, load(f)) for f in pos if held(f)]
    talk = stream_scores(net, feat, load(a.test_talk)) if a.test_talk else None
    for th in (0.5, 0.7, 0.8, 0.9, 0.95, 0.98, 0.99):
        line = f"threshold {th}:"
        if mine:
            line += f" your Jarvis {np.mean([fires(s, th) > 0 for s in mine]):.2f}"
        if others:
            line += f" | other voices {np.mean([fires(s, th) > 0 for s in others]):.2f}"
        if talk is not None:
            line += f" | {fires(talk, th)} false wakes in ordinary talk"
        print(line, flush=True)
    for d in a.check:
        print("--", d)
        for f in sorted(glob.glob(d + "/*.wav")):
            print(f"  {os.path.basename(f)[:30]:31s}{stream_scores(net, feat, load(f)).max():.3f}")


main()
