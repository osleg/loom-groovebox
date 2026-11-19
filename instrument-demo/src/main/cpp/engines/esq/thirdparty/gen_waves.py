#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""engine/esq_waves.h: the 64 KB the DOC reads, and the tables that say which part of it a wave plays.
With the SQ-80's four wave ROMs in rom/ (2202.bin .. 2205.bin; the first is the ESQ-1's) the header is the
SQ-80's: 75 waves in 256 KB.

    tools/gen_waves.py [--rom DIR] [--stand-in] [-o engine/esq_waves.h]

With the ESQ-1's two wave ROMs in rom/ (esq1wavlo.bin, esq1wavhi.bin: your own dumps; they are Ensoniq's and
are not in this repository) their bytes go to build/rom/esq_waves.h, which the engine takes in place of
engine/esq_waves.h when it is there (build/ is not kept in git). Without them, or with --stand-in, the
64 KB are computed here: every raw wave at its own page, size and resolution, with a spectrum after its
name (saw.1 .. saw.6 band-limited for the keys they serve, the pulses, the formants at a fixed frequency,
...). The stand-in is not the ROM: it has the ESQ-1's layout and the kinds of wave, not its samples.

The DOC's bytes are unsigned with 0x80 as zero, and 0x00 stops the oscillator; the header holds them signed
(byte - 128), which is what the engine multiplies."""
import argparse, math, sys, zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from esq_data import FS, RAW, WAVE_NAMES, ZONES, SQ80_RAW, SQ80_WAVE_NAMES, SQ80_ZONES, raw_cycles, raw_res, raw_size

ROOT = Path(__file__).resolve().parent.parent
ROM_CRC = {"esq1wavlo.bin": 0x4D04AC87, "esq1wavhi.bin": 0x94C554A3}   # as MAME names and checks them
# the SQ-80's transient attacks and drums (the manual: "they play through once and stop")
ONCE = ("bowing", "plunk", "pluck", "pick1", "pick2", "plink", "chiff", "slap", "mallet", "tomtom", "snare", "logdrm",
        "hi-hat", "click", "thump")
SQ80_CRC = {"2202.bin": 0xDFFD538C, "2203.bin": 0x9BE8CCEB, "2204.bin": 0x4937C6F7, "2205.bin": 0x0F917D40}


def key_hz(k):
    return 440.0 * 2.0 ** ((k - 69) / 12.0)


def served_keys():
    """for each raw wave, the keys it plays in any of the 32 waves"""
    keys = {}
    for z in ZONES:
        for zi, r in enumerate(z):
            keys.setdefault(r, set()).update(range(zi * 8, zi * 8 + 8))
    return keys


class Rng:                                          # a small LCG: the same stand-in on every computer
    def __init__(self, seed):
        self.s = seed & 0xFFFFFFFF

    def next(self):
        self.s = (self.s * 1664525 + 1013904223) & 0xFFFFFFFF
        return self.s / 4294967296.0


def additive(n, cycles, partials):
    """n samples of `cycles` cycles: partials is [(harmonic, amplitude, phase in turns)]; a harmonic may be a
    fraction when cycles > 1 (it is rounded to the table's own bins, so the loop stays seamless)"""
    out = [0.0] * n
    sin = [math.sin(2.0 * math.pi * i / n) for i in range(n)]
    for h, a, ph in partials:
        b = int(round(h * cycles))
        if b < 1 or b >= n // 2 or a == 0.0:
            continue
        off = int(round(ph * n)) % n
        for i in range(n):
            out[i] += a * sin[(b * i + off) % n]
    return out


def schroeder(h, top):
    return (-0.5 * h * (h - 1) / max(top, 1)) % 1.0     # phases that keep the peak low


def formant(f, centre, width):
    return math.exp(-0.5 * ((f - centre) / width) ** 2)


def spectrum(name, hmax, f0):
    """[(harmonic, amplitude, phase)] for a raw wave by its name; hmax: the highest harmonic that stays under
    the DOC's Nyquist on the highest key the wave serves; f0: the pitch of the middle of the keys it serves"""
    fam = name.split(".")[0]
    H = range(1, hmax + 1)
    if fam in ("saw", "saw2"):
        return [(h, 1.0 / h, 0.0) for h in H]
    if fam in ("square",):
        return [(h, 1.0 / h, 0.0) for h in H if h & 1]
    if fam == "triang":
        return [(h, (1.0 if h % 4 == 1 else -1.0) / (h * h), 0.0) for h in H if h & 1]
    if fam == "pulse":                              # a narrow pulse, an eighth of the cycle
        return [(h, math.sin(math.pi * h * 0.125) / h, 0.25) for h in H]
    if fam == "sine":
        return [(1, 1.0, 0.0)]
    if fam == "sine2":                              # "a more aggressive sine"
        return [(1, 1.0, 0.0), (2, 0.3, 0.0)][:hmax]
    if fam == "octave":
        return [(1, 1.0, 0.0), (2, 0.9, 0.0)][:hmax]
    if fam == "oct+5":
        return [(1, 1.0, 0.0), (2, 0.5, 0.0), (3, 0.9, 0.0)][:hmax]
    if fam == "organ":
        if name == "organ.2":                       # 4 OCTS: four octaves of sine
            return [(h, 1.0, 0.0) for h in (1, 2, 4, 8) if h <= hmax]
        bars = {1: 1.0, 2: 0.9, 3: 0.7, 4: 0.55, 6: 0.3, 8: 0.35}
        return [(h, a, 0.0) for h, a in bars.items() if h <= hmax]
    if fam == "bell":                               # the partials of a bell, on the nearest harmonics
        bell = {1: 1.0, 2: 0.2, 3: 0.55, 5: 0.45, 8: 0.3, 9: 0.22, 13: 0.18, 17: 0.12, 22: 0.08}
        return [(h, a, schroeder(h, 22)) for h, a in bell.items() if h <= hmax]
    if fam == "prime":
        primes = [1, 2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47]
        return [(h, 1.0 / math.sqrt(h), schroeder(i, 16)) for i, h in enumerate(primes) if h <= hmax]
    if fam == "synth1":                             # a bright saw with a resonance near the 6th harmonic
        return [(h, (1.0 + 2.5 * formant(h, 6.0, 1.6)) / h, 0.0) for h in H]
    if fam == "synth2":                             # hollow: odd harmonics, a peak near the 9th
        return [(h, (1.0 + 3.0 * formant(h, 9.0, 2.0)) / h, 0.0) for h in H if h & 1]
    if fam == "synth3":                             # every third harmonic missing
        return [(h, 1.0 / h ** 0.8, 0.0) for h in H if h % 3]
    if fam == "reed":
        return [(h, (1.0 if h & 1 else 0.4) / h ** 0.9 * (1.0 + 1.5 * formant(h * f0, 1500.0, 500.0)),
                 schroeder(h, hmax)) for h in H]
    if fam == "bass":
        return [(h, (1.0 + (0.8 if h in (2, 3) else 0.0)) / h ** 1.5, 0.0) for h in H]
    if fam == "el-pno":
        ep = {1: 1.0, 2: 0.35, 3: 0.12, 4: 0.2, 7: 0.1, 14: 0.06}
        return [(h, a, 0.0) for h, a in ep.items() if h <= hmax]
    if fam == "piano":                              # a string: the hammer's notch at every 8th, the upper
        out = []                                    # partials a little sharp (the low tables hold four cycles)
        for h in H:
            if h % 8 == 0:
                continue
            stretch = h * math.sqrt(1.0 + 0.0004 * h * h)
            out.append((stretch, 1.0 / h ** 1.25, schroeder(h, hmax)))
        return out
    if fam == "voice":                              # a sung "ah": three formants over a glottal slope
        return [(h, (formant(h * f0, 700.0, 130.0) + 0.6 * formant(h * f0, 1150.0, 150.0)
                     + 0.3 * formant(h * f0, 2700.0, 250.0) + 0.04) / h ** 0.6, schroeder(h, hmax)) for h in H]
    if fam == "formt":                              # one formant, fixed in Hz as the keys go up (set per table)
        return [(h, formant(h * f0, 500.0, 90.0) + 0.02 / h, schroeder(h, hmax)) for h in H]
    if fam == "kick":
        return [(1, 1.0, 0.0), (2, 0.5, 0.1), (3, 0.25, 0.2), (4, 0.12, 0.3)][:hmax]
    raise SystemExit(f"gen_waves: no recipe for {name}")


def noise(n, seed, colour):
    r = Rng(seed)
    x = [r.next() * 2.0 - 1.0 for _ in range(n)]
    if colour:                                      # a band of it: two passes of a one-pole, wrapped
        for _ in range(2):
            y, acc = [], x[-1]
            for v in x + x:
                acc += colour * (v - acc)
                y.append(acc)
            x = y[n:]
    return x


def stand_in():
    rom = [0] * 65536
    keys = served_keys()
    for i, (page, _wsr, name) in enumerate(RAW):
        n, cyc = raw_size(i), raw_cycles(i)
        if name == "noise3":                        # the first half of noise1's page, read at another rate
            continue
        if name == "noise1":
            w = noise(n, 0x1234, 0.0)
        elif name == "noise2":
            w = noise(n, 0x5678, 0.35)
        else:
            ks = sorted(keys.get(i, {60}))
            top = ks[-1]
            # FORMT1..5 and VOICE1/2 share tables a zone apart: the table's pitch is that of its first use
            mid = ks[0] + 4 if name.split(".")[0] in ("formt", "voice") else (ks[0] + ks[-1]) // 2
            if name.split(".")[0] in ("formt", "voice") and ks[0] == 0:
                mid = ks[-1] - 4 if name != "formt.1" else 12   # the lowest table: the top of its span
            hmax = max(1, min(int(0.47 * FS / key_hz(top)), n // cyc // 2 - 1))
            w = additive(n, cyc, spectrum(name, hmax, key_hz(mid)))
        peak = max(abs(v) for v in w) or 1.0
        for k, v in enumerate(w):
            s = int(round(v / peak * 120.0))
            rom[page * 256 + k] = max(-127, min(127, s))
    return rom, "computed stand-in (tools/gen_waves.py): the ESQ-1's layout, not its samples"


def from_rom(d):
    data = b""
    for f, crc in ROM_CRC.items():
        b = (d / f).read_bytes()
        if len(b) != 32768:
            raise SystemExit(f"gen_waves: {d / f} is {len(b)} bytes, not 32768")
        if zlib.crc32(b) != crc:
            print(f"gen_waves: {f} has CRC {zlib.crc32(b):08x}, not {crc:08x} (the dump MAME knows): used as it is",
                  file=sys.stderr)
        data += b
    rom = [b - 128 for b in data]
    # the engine's pitch takes a resolution-1 table for one cycle of the note: count the sine's
    sine = next(i for i, r in enumerate(RAW) if r[2] == "sine")
    w = rom[RAW[sine][0] * 256:RAW[sine][0] * 256 + raw_size(sine)]
    ups = sum(1 for k in range(len(w)) if w[k - 1] < 0 <= w[k])
    if ups != 1:
        print(f"gen_waves: the sine's table holds {ups} cycles, not the one the engine's pitch assumes "
              f"(tools/gen_tables.py, ESQ_FR): the notes will be {ups} times too high", file=sys.stderr)
    return rom, "the ESQ-1's wave ROMs (esq1wavlo.bin, esq1wavhi.bin)"


def from_sq80(d):
    """the four ROMs one after another; a zero byte (where the DOC stops an oscillator) becomes silence"""
    data = b""
    for f, crc in SQ80_CRC.items():
        b = (d / f).read_bytes()
        if len(b) != 65536:
            raise SystemExit(f"gen_waves: {d / f} is {len(b)} bytes, not 65536")
        if zlib.crc32(b) != crc:
            print(f"gen_waves: {f} has CRC {zlib.crc32(b):08x}, not {crc:08x}: used as it is", file=sys.stderr)
        data += b
    return data, "the SQ-80's wave ROMs (2202.bin .. 2205.bin): the ESQ-1's and three more"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rom", type=Path, default=ROOT / "rom")
    ap.add_argument("--stand-in", action="store_true")
    ap.add_argument("-o", type=Path)
    a = ap.parse_args()
    have = all((a.rom / f).is_file() for f in ROM_CRC)
    sq80 = all((a.rom / f).is_file() for f in SQ80_CRC) and not a.stand_in
    # the SQ-80's tables are in every build, its ROMs' bytes only in the one made with them: the engine's host
    # may bring tables from elsewhere (esq_table)
    raw = [(pg, wsr, nm, 0, 0) for pg, wsr, nm in RAW] + SQ80_RAW
    zones, names, ends = ZONES + SQ80_ZONES, WAVE_NAMES + SQ80_WAVE_NAMES, {}
    for i, (pg, wsr, nm, _s, _f) in enumerate(raw):
        if nm.split(".")[0] in ONCE or nm == "kick.2":
            ends[i] = 256 << ((wsr >> 3) & 7)               # the DOC's one-shot mode: to the table's end
    if sq80:
        have = True
        data, what = from_sq80(a.rom)
        for i, (pg, wsr, nm, _s, _f) in enumerate(raw):     # a table with a zero byte plays once, to there
            at = (wsr >> 6) * 65536 + pg * 256
            z = data.find(b"\0", at, at + (256 << ((wsr >> 3) & 7)))
            if z >= 0:
                ends[i] = z - at
        rom = [b - 128 if b else 0 for b in data]
        out = a.o or ROOT / "build/rom/esq_waves.h"
    elif have and not a.stand_in:
        rom, what = from_rom(a.rom)
        out = a.o or ROOT / "build/rom/esq_waves.h"
    else:
        rom, what = stand_in()
        out = a.o or ROOT / "engine/esq_waves.h"
        if not a.o and (ROOT / "build/rom/esq_waves.h").exists():
            (ROOT / "build/rom/esq_waves.h").unlink()   # (no ROMs any more: the stand-in again)
    L = ["/* SPDX-License-Identifier: GPL-3.0-only */",
         f"/* Generated by tools/gen_waves.py: {what}. */",
         "#ifndef ESQ_WAVES_H", "#define ESQ_WAVES_H", "#include <stdint.h>", "",
         f"#define ESQ_WAVES_ROM {1 if have and not a.stand_in else 0}   /* 1: the real ROMs, 0: the stand-in */",
         f"#define ESQ_N_WAVES {len(zones)}", f"#define ESQ_N_RAW {len(raw)}", f"#define ESQ_ROM_BYTES {len(rom)}", "",
         "/* a raw wave: where it starts (in 256-byte pages from the first ROM), log2 of its length, the DOC's",
         " * resolution, its tuning (semitones, 1/256 semitone), and where it ends if it plays once (the place",
         " * of its first zero byte, or its length; 0xFFFF: it goes round) */",
         "typedef struct { uint16_t page; uint8_t bits, res; int8_t semi; uint8_t fine; uint16_t end; } esq_raw_t;",
         "static const esq_raw_t ESQ_RAW[ESQ_N_RAW] = {"]
    row = []
    for i, (page, wsr, _n, semi, fine) in enumerate(raw):
        bits = 8 + ((wsr >> 3) & 7)
        row.append(f"{{0x{(wsr >> 6) * 256 + page:03x}, {bits}, {wsr & 7}, {semi}, {fine}, {ends.get(i, 0xFFFF)}}}")
        if len(row) == 4 or i == len(raw) - 1:
            L.append("    " + ", ".join(row) + ",")
            row = []
    L += ["};", "", "/* the raw wave a wave plays in each zone of eight keys */",
          "static const uint8_t ESQ_ZONE[ESQ_N_WAVES][16] = {"]
    for z, nm in zip(zones, names):
        L.append("    {" + ", ".join(f"0x{r:02x}" for r in z) + f"}},   /* {nm} */")
    L += ["};", "", "static const char ESQ_WAVE_NAME[ESQ_N_WAVES][8] = {"]
    for k in range(0, len(names), 12):
        L.append("    " + ", ".join(f'"{n}"' for n in names[k:k + 12]) + ",")
    L += ["};", "", "static const int8_t ESQ_WAVE_ROM[ESQ_ROM_BYTES] = {"]
    for k in range(0, len(rom), 32):
        L.append("    " + ",".join(str(v) for v in rom[k:k + 32]) + ",")
    L += ["};", "", "#endif", ""]
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(L))
    print(f"gen_waves: {out} ({what})")
    if ends:
        print("gen_waves: play once: " + " ".join(f"{raw[i][2]}:{n}/{256 << ((raw[i][1] >> 3) & 7)}" for i, n in sorted(ends.items())))


if __name__ == "__main__":
    main()
