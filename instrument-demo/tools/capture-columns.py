"""Write a second copy of the release's column formatting routines for the wire view.

The release source stays intact. Only the drawing destination changes when the
generated include is compiled inside skiff_capture_cards.inc.
"""
from pathlib import Path
import sys

source, destination = map(Path, sys.argv[1:])
text = source.read_text(encoding="utf-8")
functions = []
for name in ("draw_act_column", "engine_columns", "fr_columns", "draw_columns"):
    start = text.index("static void " + name + "(")
    end = text.index("\n}", start) + 2
    functions.append(text[start:end])
generated = "/* Generated from pinned GPL-3.0-only Skiff ui_draw.c. */\n" + "\n\n".join(functions) + "\n"
if not destination.exists() or destination.read_text() != generated:
    pending = destination.with_suffix(".tmp")
    pending.write_text(generated, encoding="utf-8")
    pending.replace(destination)
