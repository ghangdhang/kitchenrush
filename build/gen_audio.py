"""Synthesizes Kitchen Rush's original background music and jingles as mono Ogg Vorbis.

Run with the build venv (numpy + soundfile):  build/.venv/Scripts/python.exe build/gen_audio.py <out_dir> <lengths_txt>
Everything here is composed for this project: a cosy, bouncy kitchen tune in C major.
"""
import os
import sys

import numpy as np
import soundfile as sf

SR = 44100
OUT = sys.argv[1]
LENGTHS = sys.argv[2]
rng = np.random.default_rng(7)


def hz(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def env(n, attack=0.004, decay=6.0):
    t = np.arange(n) / SR
    a = np.clip(t / attack, 0, 1)
    return a * np.exp(-t * decay)


def marimba(m, dur, vol=1.0):
    n = int(SR * max(dur, 0.35))
    t = np.arange(n) / SR
    f = hz(m)
    w = np.sin(2 * np.pi * f * t) + 0.25 * np.sin(2 * np.pi * f * 4 * t) * np.exp(-t * 18)
    return vol * w * env(n, 0.002, 7.5)


def glock(m, dur, vol=1.0):
    n = int(SR * max(dur, 0.9))
    t = np.arange(n) / SR
    f = hz(m)
    w = np.sin(2 * np.pi * f * t) + 0.35 * np.sin(2 * np.pi * f * 2.76 * t) * np.exp(-t * 6) \
        + 0.15 * np.sin(2 * np.pi * f * 5.4 * t) * np.exp(-t * 12)
    return vol * w * env(n, 0.001, 3.2)


def soft_lead(m, dur, vol=1.0, bend=0.0):
    """Rounded square-ish lead with a little vibrato (odd harmonics, gently rolled off)."""
    n = int(SR * (dur + 0.08))
    t = np.arange(n) / SR
    f = hz(m) * (1 + 0.006 * np.sin(2 * np.pi * 5.5 * t) * np.clip(t / 0.25, 0, 1))
    if bend:
        f = f * 2 ** (bend * np.clip(t / dur, 0, 1) / 12)
    ph = 2 * np.pi * np.cumsum(f) / SR
    w = np.sin(ph) + 0.28 * np.sin(3 * ph) + 0.1 * np.sin(5 * ph)
    a = np.clip(t / 0.012, 0, 1)
    r = np.clip((dur + 0.08 - t) / 0.08, 0, 1)
    return vol * 0.6 * w * a * r * (0.75 + 0.25 * np.exp(-t * 4))


def bass(m, dur, vol=1.0):
    n = int(SR * dur)
    t = np.arange(n) / SR
    f = hz(m)
    tri = 2 * np.abs(2 * ((f * t) % 1) - 1) - 1
    w = 0.7 * tri + 0.3 * np.sin(2 * np.pi * f * t)
    return vol * w * env(n, 0.004, 3.5) * np.clip((dur - t) / 0.03, 0, 1)


def kick(vol=1.0):
    n = int(SR * 0.28)
    t = np.arange(n) / SR
    f = 45 + 110 * np.exp(-t * 28)
    return vol * np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 11)


def snare(vol=1.0):
    n = int(SR * 0.18)
    t = np.arange(n) / SR
    noise = rng.uniform(-1, 1, n)
    noise = noise - np.concatenate([[0], noise[:-1]]) * 0.6
    return vol * (0.7 * noise * np.exp(-t * 22) + 0.4 * np.sin(2 * np.pi * 210 * t) * np.exp(-t * 30))


def hat(vol=1.0, length=0.05):
    n = int(SR * length)
    t = np.arange(n) / SR
    noise = rng.uniform(-1, 1, n)
    hp = noise - np.concatenate([[0], noise[:-1]])
    return vol * hp * np.exp(-t * 70)


def shaker(vol=1.0):
    return hat(vol, 0.08) * 0.8


class Track:
    def __init__(self, seconds):
        self.buf = np.zeros(int(SR * seconds) + SR * 3)
        self.length = int(SR * seconds)

    def add(self, at, sig):
        i = int(at * SR)
        self.buf[i:i + len(sig)] += sig[:max(0, len(self.buf) - i)]

    def render(self, loop=True, peak=0.88):
        out = self.buf.copy()
        if loop:
            tail = out[self.length:]
            out = out[:self.length].copy()
            out[:len(tail)] += tail[:self.length]
        else:
            nz = np.nonzero(np.abs(out) > 1e-4)[0]
            out = out[:nz[-1] + SR // 10] if len(nz) else out
        out = np.tanh(out / (np.max(np.abs(out)) + 1e-9) * 1.4)
        return out / np.max(np.abs(out)) * peak


# ---------------------------------------------------------------- the kitchen tune
# The first Kitchen Rush melody (originally played on note blocks): 8 bars of eighth notes.
# Values are note-block pitch steps (6 = C5 ... 22 = E6, i.e. midi 66 + step); -1 is a rest.
MELODY = [
    10, 13, 18, 13, 15, 13, 10, -1,
    11, 15, 20, 15, 13, 11, 10, 8,
    10, 13, 18, 22, 20, 18, 15, 13,
    11, 10, 8, 10, 6, -1, -1, -1,
    15, 15, 13, 15, 18, -1, 15, 13,
    11, 11, 10, 11, 15, -1, 13, 11,
    10, 13, 18, 20, 22, 20, 18, 15,
    13, -1, 20, -1, 18, -1, -1, -1,
]
# Chord per bar; the last bar turns G -> C halfway.
CHORDS = ['C', 'F', 'C', 'G', 'Am', 'F', 'C', 'GC']
ROOT = {'C': 36, 'F': 41, 'G': 43, 'Am': 45}
TRIAD = {'C': [60, 64, 67], 'Am': [57, 60, 64], 'F': [57, 60, 65], 'G': [55, 59, 62]}


def kitchen_tune(beat, hurry=False, passes=2):
    """The melody twice per loop (the second pass adds a glockenspiel sparkle), over bass, marimba and drums."""
    e = beat / 2
    bars = len(CHORDS)
    tr = Track(passes * bars * 4 * beat)
    for rep in range(passes):
        base = rep * bars * 4 * beat
        for b, chord in enumerate(CHORDS):
            t0 = base + b * 4 * beat
            halves = ['G', 'C'] if chord == 'GC' else [chord]
            for h, ch in enumerate(halves):
                span = 4 // len(halves)
                start = t0 + h * span * beat
                root = ROOT[ch]
                pattern = [(0, root), (1, root + 12), (2, root + 7), (3, root + 12)] if span == 4 \
                    else [(0, root), (1, root + 7)]
                for beat_i, m in pattern:
                    tr.add(start + beat_i * beat, bass(m, beat * 0.8, 0.55))
                for k in range(span):
                    for m in TRIAD[ch]:
                        tr.add(start + (k + 0.5) * beat, marimba(m, beat * 0.4, 0.13))
            for k in range(4):
                tr.add(t0 + k * beat, kick(0.7) if k % 2 == 0 else snare(0.32))
                tr.add(t0 + (k + 0.5) * beat, hat(0.16))
                tr.add(t0 + k * beat, hat(0.08))
                if hurry:
                    tr.add(t0 + (k + 0.25) * beat, shaker(0.07))
                    tr.add(t0 + (k + 0.75) * beat, shaker(0.07))
            for i in range(8):
                step = MELODY[b * 8 + i]
                if step < 0:
                    continue
                length = 1
                while i + length < 8 and MELODY[b * 8 + i + length] < 0 and length < 3:
                    length += 1
                m = 66 + step
                s = t0 + i * e
                tr.add(s, soft_lead(m, length * e * 0.9, 0.42))
                tr.add(s, marimba(m + 12, length * e, 0.2 if i % 2 == 0 else 0.11))
                if rep == 1 and i % 2 == 0:
                    tr.add(s, glock(m + 12, 0.4, 0.09))
    return tr


# ---------------------------------------------------------------- jingles

def jingle_start():
    tr = Track(2.0)
    for i, m in enumerate([72, 76, 79, 84]):
        tr.add(i * 0.075, glock(m, 0.5, 0.55))
        tr.add(i * 0.075, marimba(m - 12, 0.3, 0.3))
    t = 0.34
    for m in (72, 76, 79, 84, 88):
        tr.add(t, glock(m, 1.2, 0.32))
    tr.add(t, kick(0.8))
    tr.add(t, bass(48, 0.5, 0.6))
    for k in range(6):
        tr.add(t + 0.05 + k * 0.045, hat(0.12 - k * 0.015))
    tr.add(t + 0.18, glock(91, 0.8, 0.35))
    return tr


def jingle_clear():
    tr = Track(3.0)
    step = 0.11
    for i, m in enumerate([67, 69, 71]):
        tr.add(i * step, soft_lead(m + 5, step * 0.9, 0.45))
        tr.add(i * step, marimba(m + 5, 0.3, 0.3))
    t = 3 * step
    for m in (60, 64, 67, 72):
        tr.add(t, marimba(m, 0.6, 0.35))
    tr.add(t, soft_lead(76, 0.32, 0.5))
    tr.add(t, kick(0.8))
    tr.add(t, bass(48, 0.4, 0.6))
    tr.add(t + 0.36, soft_lead(79, 0.16, 0.5))
    tr.add(t + 0.52, soft_lead(84, 0.75, 0.55))
    tr.add(t + 0.52, kick(0.6))
    tr.add(t + 0.52, snare(0.35))
    for m in (72, 76, 79, 84, 88, 91):
        tr.add(t + 0.52, glock(m, 1.6, 0.25))
    for k in range(8):
        tr.add(t + 0.6 + k * 0.06, glock(96 - (k % 3) * 5, 0.5, 0.12))
    return tr


def jingle_fail():
    tr = Track(3.0)
    notes = [(0.0, 67, 0.28), (0.36, 66, 0.28), (0.72, 65, 0.28), (1.08, 64, 0.9)]
    for at, m, d in notes:
        tr.add(at, soft_lead(m - 12, d, 0.55, bend=-1.0 if d > 0.5 else 0))
        tr.add(at, bass(m - 24, d, 0.35))
    tr.add(1.08, marimba(52, 0.6, 0.25))
    return tr


def write(name, data):
    path = os.path.join(OUT, name + '.ogg')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    # libsndfile's Vorbis encoder can crash on one huge write, so feed it small blocks.
    with sf.SoundFile(path, 'w', SR, 1, format='OGG', subtype='VORBIS') as f:
        pcm = data.astype(np.float32)
        for i in range(0, len(pcm), 2048):
            f.write(pcm[i:i + 2048])
    return len(data) / SR


os.makedirs(OUT, exist_ok=True)
lengths = {}
# Same tempos as the original note-block version (an eighth note = 4 ticks, 3 ticks when hurrying),
# so each loop is a whole number of game ticks: 0.4 s beats -> 512 ticks, 0.3 s beats -> 384 ticks.
lengths['bgm'] = write('music/bgm', kitchen_tune(0.4).render(loop=True, peak=0.8))
lengths['bgm_hurry'] = write('music/bgm_hurry', kitchen_tune(0.3, hurry=True).render(loop=True, peak=0.8))
lengths['start'] = write('sfx/start', jingle_start().render(loop=False))
lengths['clear'] = write('sfx/clear', jingle_clear().render(loop=False))
lengths['fail'] = write('sfx/fail', jingle_fail().render(loop=False))
with open(LENGTHS, 'w', encoding='utf-8') as f:
    for k, v in lengths.items():
        f.write(f'{k} {round(v * 20)}\n')
print({k: round(v, 2) for k, v in lengths.items()})
