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


# ---------------------------------------------------------------- the kitchen tune (16 bars)
CHORDS = ['C', 'Am', 'F', 'G', 'C', 'Em', 'FG', 'C', 'F', 'G', 'Em', 'Am', 'Dm', 'G', 'C', 'G']
ROOT = {'C': 36, 'Am': 45, 'F': 41, 'G': 43, 'Em': 40, 'Dm': 38}
TRIAD = {'C': [60, 64, 67], 'Am': [57, 60, 64], 'F': [57, 60, 65], 'G': [55, 59, 62],
         'Em': [55, 59, 64], 'Dm': [57, 62, 65]}
# (beat in bar, length in beats, midi note)
MELODY = [
    [(0, .5, 76), (.5, .5, 79), (1, 1, 84), (2, .5, 79), (2.5, .5, 76), (3, 1, 74)],
    [(0, .5, 72), (.5, .5, 76), (1, 1, 81), (2, .5, 79), (2.5, .5, 76), (3, 1, 72)],
    [(0, .5, 77), (.5, .5, 81), (1, .5, 84), (1.5, .5, 81), (2, 1, 79), (3, 1, 77)],
    [(0, 1.5, 79), (1.5, .5, 77), (2, .5, 76), (2.5, .5, 74), (3, 1, 71)],
    [(0, .5, 72), (.5, .5, 76), (1, .5, 79), (1.5, .5, 84), (2, 1, 83), (3, 1, 79)],
    [(0, .5, 76), (.5, .5, 79), (1, 1, 83), (2, .5, 79), (2.5, .5, 76), (3, 1, 71)],
    [(0, .5, 77), (.5, .5, 81), (1, 1, 84), (2, .5, 79), (2.5, .5, 83), (3, 1, 86)],
    [(0, 2, 84), (2.5, .5, 79), (3, .5, 76), (3.5, .5, 79)],
    [(0, .5, 81), (.5, .5, 81), (1, .5, 77), (1.5, .5, 81), (2, 1, 84), (3, 1, 81)],
    [(0, .5, 83), (.5, .5, 83), (1, .5, 79), (1.5, .5, 83), (2, 1, 86), (3, 1, 83)],
    [(0, 1, 79), (1, .5, 76), (1.5, .5, 79), (2, 1, 83), (3, 1, 79)],
    [(0, 1, 81), (1, .5, 76), (1.5, .5, 72), (2, 1.5, 76), (3.5, .5, 74)],
    [(0, .5, 74), (.5, .5, 77), (1, 1, 81), (2, .5, 77), (2.5, .5, 74), (3, 1, 77)],
    [(0, .5, 79), (.5, .5, 83), (1, 1, 86), (2, .5, 83), (2.5, .5, 79), (3, 1, 77)],
    [(0, .5, 76), (.5, .5, 79), (1, .5, 84), (1.5, .5, 79), (2, .5, 76), (2.5, .5, 79), (3, 1, 84)],
    [(0, 1, 83), (1, 1, 79), (2, 1, 74), (3, .5, 71), (3.5, .5, 74)],
]


def kitchen_tune(beat, hurry=False):
    bars = len(CHORDS)
    tr = Track(bars * 4 * beat)
    for b, chord in enumerate(CHORDS):
        t0 = b * 4 * beat
        halves = ['F', 'G'] if chord == 'FG' else [chord]
        for h, ch in enumerate(halves):
            span = 4 / len(halves)
            start = t0 + h * span * beat
            root = ROOT[ch]
            # bouncy bass: root, octave, fifth, octave
            pattern = [(0, root), (1, root + 12), (2, root + 7), (3, root + 12)] if span == 4 else [(0, root), (1, root + 7)]
            for beat_i, m in pattern:
                tr.add(start + beat_i * beat, bass(m, beat * 0.8, 0.55))
            # off-beat marimba chord "chicks"
            for k in range(int(span)):
                for m in TRIAD[ch]:
                    tr.add(start + (k + 0.5) * beat, marimba(m, beat * 0.4, 0.13))
        # drums
        for k in range(4):
            if k % 2 == 0:
                tr.add(t0 + k * beat, kick(0.7))
            else:
                tr.add(t0 + k * beat, snare(0.32))
            tr.add(t0 + (k + 0.5) * beat, hat(0.16))
            tr.add(t0 + k * beat, hat(0.08))
            if hurry:
                tr.add(t0 + (k + 0.25) * beat, shaker(0.07))
                tr.add(t0 + (k + 0.75) * beat, shaker(0.07))
        # melody: soft lead doubled by marimba an octave up on the downbeats
        for at, length, m in MELODY[b]:
            s = t0 + at * beat
            tr.add(s, soft_lead(m, length * beat * 0.92, 0.42))
            tr.add(s, marimba(m + 12, length * beat, 0.22 if at == int(at) else 0.12))
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
# Beats chosen so a loop is a whole number of game ticks: 0.45 s -> 576 ticks, 0.375 s -> 480 ticks.
lengths['bgm'] = write('music/bgm', kitchen_tune(0.45).render(loop=True, peak=0.8))
lengths['bgm_hurry'] = write('music/bgm_hurry', kitchen_tune(0.375, hurry=True).render(loop=True, peak=0.8))
lengths['start'] = write('sfx/start', jingle_start().render(loop=False))
lengths['clear'] = write('sfx/clear', jingle_clear().render(loop=False))
lengths['fail'] = write('sfx/fail', jingle_fail().render(loop=False))
with open(LENGTHS, 'w', encoding='utf-8') as f:
    for k, v in lengths.items():
        f.write(f'{k} {round(v * 20)}\n')
print({k: round(v, 2) for k, v in lengths.items()})
