#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Generate original wave cycles, six-op patches, and synthetic LPC phrases."""
from math import cos, exp, pi, sin
from pathlib import Path

ROOT = Path(__file__).parent / "thirdparty/eurorack/plaits"


def format_array(values, columns=12):
    return "\n".join(
        "    " + ", ".join(str(value) for value in values[i:i + columns]) + ","
        for i in range(0, len(values), columns)
    )


def integrated_wave(bank, x, y):
    samples = []
    for sample in range(128):
        phase = 2 * pi * sample / 128
        value = 0.0
        for harmonic in range(1, 17):
            if bank == 0:
                # Sweep skew and spectral rolloff across pulse-like waves.
                duty = 0.11 + 0.055 * x
                amplitude = sin(pi * harmonic * duty) / harmonic ** (0.75 + y / 7)
                value += amplitude * cos(harmonic * phase + harmonic * y / 10)
            elif bank == 1:
                # Sweep an overblown formant over a quiet fundamental.
                center = 2 + x * 1.5
                amplitude = exp(-((harmonic - center) / (1 + y / 3)) ** 2)
                value += amplitude * sin(harmonic * phase + harmonic * harmonic / 5)
            else:
                # Alternate odd and even harmonic clusters.
                amplitude = (0.25 + 0.75 * cos(pi * harmonic * (x + 1) / 9) ** 2)
                value += amplitude * sin(harmonic * phase + y * harmonic / 6) / harmonic ** 1.15
        if bank == 1:
            value += 0.4 * sin(phase)
        samples.append(value)
    peak = max(abs(value) for value in samples)
    dc = sum(samples) / len(samples)
    integral = [0.0]
    for value in samples[:-1]:
        integral.append(integral[-1] + 740 * (value - dc) / peak)
    dc = sum(integral) / len(integral)
    # The Hermite interpolator reads one sample before its point of evaluation.
    wrap = integral[-1:] + integral + integral[:3]
    return [round(value - dc) for value in wrap]


def fm_patch(bank, index):
    # Three families of six-op programs. Four voicings per architecture.
    algorithms = ((4, 5, 6, 7, 8, 9, 16, 17),
                  (0, 1, 2, 3, 10, 11, 12, 19),
                  (13, 14, 15, 20, 21, 22, 23, 24))
    rates = ((92, 53, 25, 73), (97, 70, 44, 78), (65, 38, 22, 59))
    ratios = ((1, 1, 2, 1, 3, 1), (2, 3, 7, 1, 4, 1), (1, 2, 1, 3, 2, 1))
    shape = index % 4
    patch = []
    for op in range(6):
        attack, decay, sustain_decay, release = rates[bank]
        sustain = (74, 48, 86)[bank] - 4 * ((op + shape) % 4)
        coarse = ratios[bank][op] + (shape if op % 2 == 0 else 0)
        fine = 0 if op == 5 else (bank * 3 + shape * 4 + op * 3) % 18
        level = 97 if op == 5 else 79 + (3 * op + index) % 16
        patch.extend((attack, decay, sustain_decay, release, 99, sustain + 6, sustain, 0,
                      48, 0, 0, 0, (7 << 3) | 2, 2 << 2, level, coarse << 1, fine))
    patch.extend((80, 70, 55, 75, 50, 50, 50, 50,
                  algorithms[bank][index // 4], 8 | (bank + shape + 1),
                  27 + shape * 3, 40, 2 * bank, 0, 16 | 1, 24))
    patch.extend(f"FM{bank + 1} {index + 1:02}".ljust(10).encode("ascii"))
    assert len(patch) == 128
    return patch


def lpc_phrase(bank, phrase):
    # LPC coefficient indices for voiced vowels and noisy onsets. Each
    # syllable slides between two vowel shapes, with voiced energy contour.
    vowels = ((18, 20, 10, 8), (20, 11, 10, 10), (25, 16, 9, 3),
              (13, 21, 8, 8), (12, 20, 7, 8), (22, 17, 11, 6),
              (16, 19, 12, 10), (24, 15, 8, 7))
    bits = []

    def put(value, width):
        bits.extend((value >> bit) & 1 for bit in range(width - 1, -1, -1))

    def frame(energy, pitch, shape):
        put(energy, 4)
        put(0, 1)
        put(pitch, 6)
        for value, width in zip(shape, (5, 5, 4, 4)):
            put(value, width)
        if pitch:
            for value, width in zip((9, 6, 7, 4, 3, 3), (4, 4, 4, 3, 3, 3)):
                put(value, width)

    for syllable in range(2 + bank % 3):
        vowel = (2 * bank + phrase + 3 * syllable) % len(vowels)
        onset = (vowel + 3) % len(vowels)
        pitch = 47 + (phrase + bank + syllable) % 10
        frame(7 + bank % 3, 0, vowels[onset])
        for i, energy in enumerate((10, 13, 14, 13, 11, 8)):
            frame(energy, pitch + i // 2, vowels[vowel if i < 4 else (vowel + 1) % len(vowels)])
    put(0, 4)
    put(15, 4)
    return [sum(bit << shift for shift, bit in enumerate(bits[i:i + 8]))
            for i in range(0, len(bits), 8)]


def main():
    waves = [value for bank in range(3) for y in range(8) for x in range(8)
             for value in integrated_wave(bank, x, y)]
    fm = [value for bank in range(3) for index in range(32) for value in fm_patch(bank, index)]
    speech = [[value for phrase in range(8) for value in lpc_phrase(bank, phrase)]
              for bank in range(5)]
    data = ["// SPDX-License-Identifier: GPL-3.0-only\n"
            "// Generated by gen_macro_assets.py.\n"
            '#include "plaits/resources.h"\n'
            '#include "plaits/dsp/speech/lpc_speech_synth_words.h"\n\n'
            "namespace plaits {\n"]
    data.append("const int16_t wav_integrated_waves[] = {\n" + format_array(waves) + "\n};\n")
    data.append("const uint8_t macro_fm_banks[] = {\n" + format_array(fm, 16) + "\n};\n")
    for index, bank in enumerate(speech):
        data.append(f"static const uint8_t phrase_bank_{index}[] = {{\n" +
                    format_array(bank, 16) + "\n};\n")
    data.append("const LPCSpeechSynthWordBankData word_banks_[] = {\n" +
                "\n".join(f"    {{ phrase_bank_{i}, sizeof(phrase_bank_{i}) }}," for i in range(5)) +
                "\n};\n\n}  // namespace plaits\n")
    (ROOT / "macro_assets.cc").write_text("\n".join(data), encoding="utf-8")


if __name__ == "__main__":
    main()
