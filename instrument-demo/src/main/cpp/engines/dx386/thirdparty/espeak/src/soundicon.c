/*
 * Copyright (C) 2005 to 2015 by Jonathan Duddington
 * email: jonsd@users.sourceforge.net
 * Copyright (C) 2015-2017 Reece H. Dunn
 * Copyright (C) 2021 Juho Hiltunen
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see: <http://www.gnu.org/licenses/>.
 */

#include "config.h"

#include <ctype.h>
#include <errno.h>
#include <locale.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <espeak-ng/espeak_ng.h>
#include <espeak-ng/speak_lib.h>
#include <espeak-ng/encoding.h>
#include <ucd/ucd.h>

#include "soundicon.h" 
#include "common.h"                // for GetFileLength
#include "error.h"                // for create_file_error_context
#include "speech.h"                   // for path_home, PATHSEP
#include "synthesize.h"                   // for samplerate

int n_soundicon_tab = 0;
SOUND_ICON soundicon_tab[N_SOUNDICON_TAB];


static espeak_ng_STATUS LoadSoundFile(const char *fname, int index, espeak_ng_ERROR_CONTEXT *context)
{
(void)fname;(void)index;(void)context;return ENOENT;
}

int LookupSoundicon(int c)
{
	// Find the sound icon number for a punctuation character and load the audio file if it's not yet loaded
	int ix;

	for (ix = 0; ix < n_soundicon_tab; ix++) {
		if (soundicon_tab[ix].name == c) {
			if (soundicon_tab[ix].length == 0) { // not yet loaded, load now
				if (LoadSoundFile(NULL, ix, NULL) != ENS_OK) {
					return -1; // sound file is not available
				}
			}
			return ix;
		}
	}
	return -1;
}

int LoadSoundFile2(const char *fname)
{
	// Load a sound file into the sound icon table and memory
	// (if it's not already loaded)
	// returns -1 on error or the index of loaded file on success

	int ix;
	for (ix = 0; ix < n_soundicon_tab; ix++) {
		if (((soundicon_tab[ix].filename != NULL) && strcmp(fname, soundicon_tab[ix].filename) == 0)) {
			// the file information is found. If length = 0 it needs to be loaded to memory
			if (soundicon_tab[ix].length == 0) {
				if (LoadSoundFile(NULL, ix, NULL) != ENS_OK)
					return -1; // sound file is not available
			}
			return ix; // sound file already loaded to memory
		}
	}

	// load the file into the current slot and increase index
	if (LoadSoundFile(fname, n_soundicon_tab, NULL) != ENS_OK)
		return -1;

	soundicon_tab[n_soundicon_tab].filename = (char *)realloc(soundicon_tab[n_soundicon_tab].filename, strlen(fname)+1);
	strcpy(soundicon_tab[n_soundicon_tab].filename, fname);
	n_soundicon_tab++;
	return n_soundicon_tab - 1;
}
