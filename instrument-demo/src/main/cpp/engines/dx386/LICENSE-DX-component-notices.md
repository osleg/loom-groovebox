# Licensing

386 DX Engine is GPL-3.0-only (see `LICENSE`).

| What | Licence |
| --- | --- |
| The singing layer, the runtime and the build (`src/speech.c`, `src/speech_runtime.c`, `src/singing.c`, their headers, `src/espeak_port.h`, `tools/`, `examples/`), from 386DX | GPL-3.0-only |
| eSpeak NG 1.52.0 (<https://github.com/espeak-ng/espeak-ng>, revision `4870adfa25b1a32b4361592f1be8a40337c58d6c`): the English front end, the Klatt synthesizer and the English data. Not in this repository: fetched into `vendor/` and patched at build time by `tools/prepare_espeak.py` | GPL-3.0-or-later |
| eSpeak NG's Unicode character data (a subset generated at build time from ucd-tools) | Unicode Data Files licence (data); ucd-tools GPL-3.0-or-later |
| `src/fastmath.h`, from X0X by Charles Vestal (<https://github.com/charlesvestal/fm1-x0x>) | GPL-3.0 |
