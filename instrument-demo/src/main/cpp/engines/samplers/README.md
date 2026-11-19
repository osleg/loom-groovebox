# Skiff sample engines

`LOFI SMP` and `CLOUD` are original GPL-3.0-only phone engines built on Skiff 2.5.1's GPL-3.0-only sample and grain code. `prepare-skiff.sh` pins that source to `fe387231f7b7cb98823f19d8d4f99fee4fcd3792`. The existing Skiff factory banks and USR1 through USR3 supply their audio. The engines contain no additional sample assets.

- **LOFI SMP** latches rounded, signed 12-bit audio at either 26.04 or 27.5 kHz. `ROOT` uses the selected zone's root with `SLOT`, or an explicit note from C2 to C6. `LOOP` can play once, follow loop points from the source, or repeat the entire zone.
- **CLOUD** uses Skiff's ADPCM seek and Hann grain readers. `POSITION` sets the starting point and `DRIFT` scans the sample forward or backward while a note is held. `SPRAY` varies grain position and timing. `RANDOM` adds pitch variation and reverse grains.
