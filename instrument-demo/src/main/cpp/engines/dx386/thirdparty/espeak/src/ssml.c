/*
 * Copyright (C) 2005 to 2015 by Jonathan Duddington
 * email: jonsd@users.sourceforge.net
 * Copyright (C) 2015-2017 Reece H. Dunn
 * Copyright (C) 2018 Juho Hiltunen
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
#include <wchar.h>
#include <wctype.h>


#include <espeak-ng/espeak_ng.h>
#include <espeak-ng/speak_lib.h>
#include <espeak-ng/encoding.h>
#include <ucd/ucd.h>

#include "ssml.h"
#include "common.h"           // for strncpy0
#include "mnemonics.h"               // for LookupMnemName, MNEM_TAB, 
#include "readclause.h"           // for PARAM_STACK, param_stack, AddNameData
#include "soundicon.h"               // for LoadSoundFile2
#include "synthesize.h"           // for SPEED_FACTORS, speed
#include "translate.h"            // for CTRL_EMBEDDED
#include "voice.h"                // for SelectVoice, SelectVoiceByName
#include "speech.h"               // for MAKE_MEM_UNDEFINED

static const MNEM_TAB ssmltags[] = {
	{ "speak",     SSML_SPEAK },
	{ "voice",     SSML_VOICE },
	{ "prosody",   SSML_PROSODY },
	{ "say-as",    SSML_SAYAS },
	{ "mark",      SSML_MARK },
	{ "s",         SSML_SENTENCE },
	{ "p",         SSML_PARAGRAPH },
	{ "phoneme",   SSML_PHONEME },
	{ "sub",       SSML_SUB },
	{ "tts:style", SSML_STYLE },
	{ "audio",     SSML_AUDIO },
	{ "emphasis",  SSML_EMPHASIS },
	{ "break",     SSML_BREAK },
	{ "metadata",  SSML_IGNORE_TEXT },

	{ "br",     HTML_BREAK },
	{ "li",     HTML_BREAK },
	{ "dd",     HTML_BREAK },
	{ "img",    HTML_BREAK },
	{ "td",     HTML_BREAK },
	{ "h1",     SSML_PARAGRAPH },
	{ "h2",     SSML_PARAGRAPH },
	{ "h3",     SSML_PARAGRAPH },
	{ "h4",     SSML_PARAGRAPH },
	{ "hr",     SSML_PARAGRAPH },
	{ "script", SSML_IGNORE_TEXT },
	{ "style",  SSML_IGNORE_TEXT },
	{ "font",   HTML_NOSPACE },
	{ "b",      HTML_NOSPACE },
	{ "i",      HTML_NOSPACE },
	{ "strong", HTML_NOSPACE },
	{ "em",     HTML_NOSPACE },
	{ "code",   HTML_NOSPACE },

	{ NULL, 0 }
};

static int (*uri_callback)(int, const char *, const char *) = NULL;

static int attrcmp(const wchar_t *string1, const char *string2)
{
	int ix;

	if (string1 == NULL)
		return 1;

	for (ix = 0; (string1[ix] == string2[ix]) && (string1[ix] != 0); ix++)
		;
	if (((string1[ix] == '"') || (string1[ix] == '\'')) && (string2[ix] == 0))
		return 0;
	return 1;
}


static int attrlookup(const wchar_t *string1, const MNEM_TAB *mtab)
{
	int ix;

	for (ix = 0; mtab[ix].mnem != NULL; ix++) {
		if (attrcmp(string1, mtab[ix].mnem) == 0)
			return mtab[ix].value;
	}
	return mtab[ix].value;
}

static int attrnumber(const wchar_t *pw, int default_value, int type)
{
	int value = 0;

	if ((pw == NULL) || !IsDigit09(*pw))
		return default_value;

	while (IsDigit09(*pw))
		value = value*10 + *pw++ - '0';
	if ((type == 1) && (ucd_tolower(*pw) == 's')) {
		// time: seconds rather than ms
		value *= 1000;
	}
	return value;
}

static int attrcopy_utf8(char *buf, const wchar_t *pw, int len)
{
	// Convert attribute string into utf8, write to buf, and return its utf8 length
	int ix = 0;

	if (pw != NULL) {
		wchar_t quote = pw[-1];
		if ((quote != '"') && (quote != '\'')) quote = 0;

		unsigned int c;
		int prev_c = 0;
		while ((ix < (len-4)) && ((c = *pw++) != 0)) {
			if ((quote == 0) && (isspace(c) || (c == '/')))
				break;
			if ((quote != 0) && (c == quote) && (prev_c != '\\'))
				break; // " indicates end of attribute, unless preceded by backstroke

			int n = utf8_out(c, &buf[ix]);
			ix += n;
			prev_c = c;
		}
	}
	buf[ix] = 0;
	return ix;
}

static int attr_prosody_value(int param_type, const wchar_t *pw, int *value_out)
{
	int sign = 0;
	wchar_t *tail;
	double value;

	while (iswspace(*pw)) pw++;
	if (*pw == '+') {
		pw++;
		sign = 1;
	}
	if (*pw == '-') {
		pw++;
		sign = -1;
	}
	value = (double)wcstod(pw, &tail);
	if (tail == pw) {
		// failed to find a number, return 100%
		*value_out = 100;
		return 2;
	}

	if (*tail == '%') {
		if (sign != 0)
			value = 100 + (sign * value);
		*value_out = (int)value;
		return 2; // percentage
	}

	if ((tail[0] == 's') && (tail[1] == 't')) {
		double x;
		// convert from semitones to a  frequency percentage
		x = pow((double)2.0, (double)((value*sign)/12)) * 100;
		*value_out = (int)x;
		return 2; // percentage
	}

	if (param_type == espeakRATE) {
		if (sign == 0)
			*value_out = (int)(value * 100);
		else
			*value_out = 100 + (int)(sign * value * 100);
		return 2; // percentage
	}

	*value_out = (int)value;
	return sign;   // -1, 0, or 1
}

static const char *VoiceFromStack(SSML_STACK *ssml_stack, int n_ssml_stack, espeak_VOICE *base_voice, char base_voice_variant_name[40])
{
	// Use the voice properties from the SSML stack to choose a voice, and switch
	// to that voice if it's not the current voice

	int ix;
	const char *p;
	SSML_STACK *sp;
	const char *v_id;
	int voice_found;
	espeak_VOICE voice_select;
	static char voice_name[40];
	static char identifier[40];
	char language[40];

	MAKE_MEM_UNDEFINED(&voice_name, sizeof(voice_name));

	strcpy(voice_name, ssml_stack[0].voice_name);
	strcpy(language, ssml_stack[0].language);
	voice_select.age = ssml_stack[0].voice_age;
	voice_select.gender = ssml_stack[0].voice_gender;
	voice_select.variant = ssml_stack[0].voice_variant_number;
	voice_select.identifier = NULL;

	for (ix = 0; ix < n_ssml_stack; ix++) {
		espeak_VOICE *v;
		sp = &ssml_stack[ix];
		int voice_name_specified = 0;

		if ((sp->voice_name[0] != 0) && ((v = SelectVoiceByName(NULL, sp->voice_name)) != NULL)) {
			voice_name_specified = 1;
			strcpy(voice_name, sp->voice_name);
			strcpy(identifier, v->identifier);
			language[0] = 0;
			voice_select.gender = ENGENDER_UNKNOWN;
			voice_select.age = 0;
			voice_select.variant = 0;
		}
		if (sp->language[0] != 0) {
			strcpy(language, sp->language);

			// is this language provided by the base voice?
			p = base_voice->languages;
			while (*p++ != 0) {
				if (strcmp(p, language) == 0) {
					// yes, change the language to the main language of the base voice
					strcpy(language, &base_voice->languages[1]);
					break;
				}
				p += (strlen(p) + 1);
			}

			if (voice_name_specified == 0)
			{
				voice_name[0] = 0; // forget a previous voice name if a language is specified
				identifier[0] = 0;
			}
		}
		if (sp->voice_gender != ENGENDER_UNKNOWN)
			voice_select.gender = sp->voice_gender;

		if (sp->voice_age != 0)
			voice_select.age = sp->voice_age;
		if (sp->voice_variant_number != 0)
			voice_select.variant = sp->voice_variant_number;
	}

	voice_select.name = voice_name;
	voice_select.identifier = identifier;
	voice_select.languages = language;

	v_id = SelectVoice(&voice_select, &voice_found);
	if (v_id == NULL)
		return "default";

	if ((strchr(v_id, '+') == NULL) && ((voice_select.gender == ENGENDER_UNKNOWN) || (voice_select.gender == base_voice->gender)) && (base_voice_variant_name[0] != 0)) {
		// a voice variant has not been selected, use the original voice variant
		char buf[80];
		sprintf(buf, "%s+%s", v_id, base_voice_variant_name);
		strncpy0(voice_name, buf, sizeof(voice_name));
		return voice_name;
	}
	return v_id;
}


static const wchar_t *GetSsmlAttribute(wchar_t *pw, const char *name)
{
	// Gets the value string for an attribute.
	// Returns NULL if the attribute is not present

	int ix;
	static const wchar_t empty[1] = { 0 };

	while (*pw != 0) {
		if (iswspace(pw[-1])) {
			ix = 0;
			while (*pw == name[ix]) {
				pw++;
				ix++;
			}
			if (name[ix] == 0) {
				// found the attribute, now get the value
				while (iswspace(*pw)) pw++;
				if (*pw == '=') pw++;
				while (iswspace(*pw)) pw++;
				if ((*pw == '"') || (*pw == '\'')) // allow single-quotes ?
					return pw+1;
				else if (iswspace(*pw) || (*pw == '/')) // end of attribute
					return empty;
				else
					return pw;
			}
		}
		pw++;
	}
	return NULL;
}


static int GetVoiceAttributes(wchar_t *pw, int tag_type, SSML_STACK *ssml_sp, SSML_STACK *ssml_stack, int n_ssml_stack, char current_voice_id[40], espeak_VOICE *base_voice, char *base_voice_variant_name)
{
	// Determines whether voice attribute are specified in this tag, and if so, whether this means
	// a voice change.
	// If it's a closing tag, delete the top frame of the stack and determine whether this implies
	// a voice change.
	// Returns  CLAUSE_TYPE_VOICE_CHANGE if there is a voice change

	const char *new_voice_id;

	static const MNEM_TAB mnem_gender[] = {
		{ "male", ENGENDER_MALE },
		{ "female", ENGENDER_FEMALE },
		{ "neutral", ENGENDER_NEUTRAL },
		{ NULL, ENGENDER_UNKNOWN }
	};

	if (tag_type & SSML_CLOSE) {
		// delete a stack frame
		if (n_ssml_stack > 1)
			n_ssml_stack--;
	} else {
		const wchar_t *lang;
    	const wchar_t *gender;
    	const wchar_t *name;
    	const wchar_t *age;
    	const wchar_t *variant;

		// add a stack frame if any voice details are specified
		lang = GetSsmlAttribute(pw, "xml:lang");

		if (tag_type != SSML_VOICE) {
			// only expect an xml:lang attribute
			name = NULL;
			variant = NULL;
			age = NULL;
			gender = NULL;
		} else {
			name = GetSsmlAttribute(pw, "name");
			variant = GetSsmlAttribute(pw, "variant");
			age = GetSsmlAttribute(pw, "age");
			gender = GetSsmlAttribute(pw, "gender");
		}

		if ((tag_type != SSML_VOICE) && (lang == NULL))
			return 0; // <s> or <p> without language spec, nothing to do

		ssml_sp = &ssml_stack[n_ssml_stack++];

		int value;

		attrcopy_utf8(ssml_sp->language, lang, sizeof(ssml_sp->language));
		attrcopy_utf8(ssml_sp->voice_name, name, sizeof(ssml_sp->voice_name));
		if ((value = attrnumber(variant, 1, 0)) > 0)
			value--; // variant='0' and variant='1' the same
		ssml_sp->voice_variant_number = value;
		ssml_sp->voice_age = attrnumber(age, 0, 0);
		ssml_sp->voice_gender = attrlookup(gender, mnem_gender);
		ssml_sp->tag_type = tag_type;
	}

	new_voice_id = VoiceFromStack(ssml_stack, n_ssml_stack, base_voice, base_voice_variant_name);
	if (strcmp(new_voice_id, current_voice_id) != 0) {
		// add an embedded command to change the voice
		strcpy(current_voice_id, new_voice_id);
		return CLAUSE_TYPE_VOICE_CHANGE;
	}

	return 0;
}

static void ProcessParamStack(char *outbuf, int *outix, int n_param_stack, PARAM_STACK *param_stack, int *speech_parameters)
{
	// Set the speech parameters from the parameter stack
	int param;
	int ix;
	char buf[20];
	int new_parameters[N_SPEECH_PARAM];
	static const char cmd_letter[N_SPEECH_PARAM] = { 0, 'S', 'A', 'P', 'R', 0, 'C', 0, 0, 0, 0, 0, 'F' }; // embedded command letters

	for (param = 0; param < N_SPEECH_PARAM; param++)
		new_parameters[param] = -1;

	for (ix = 0; ix < n_param_stack; ix++) {
		for (param = 0; param < N_SPEECH_PARAM; param++) {
			if (param_stack[ix].parameter[param] >= 0)
				new_parameters[param] = param_stack[ix].parameter[param];
		}
	}

	for (param = 0; param < N_SPEECH_PARAM; param++) {
		int value;
		if ((value = new_parameters[param]) != speech_parameters[param]) {
			buf[0] = 0;

			switch (param)
			{
			case espeakPUNCTUATION:
				option_punctuation = value-1;
				break;
			case espeakCAPITALS:
				option_capitals = value;
				break;
			case espeakRATE:
			case espeakVOLUME:
			case espeakPITCH:
			case espeakRANGE:
			case espeakEMPHASIS:
				sprintf(buf, "%c%d%c", CTRL_EMBEDDED, value, cmd_letter[param]);
				break;
			}

			speech_parameters[param] = new_parameters[param];
			strcpy(&outbuf[*outix], buf);
			*outix += strlen(buf);
		}
	}
}

static PARAM_STACK *PushParamStack(int tag_type, int *n_param_stack, PARAM_STACK *param_stack)
{
	int ix;
	PARAM_STACK *sp;

	sp = &param_stack[*n_param_stack];
	if (*n_param_stack < (N_PARAM_STACK-1))
		(*n_param_stack)++;

	sp->type = tag_type;
	for (ix = 0; ix < N_SPEECH_PARAM; ix++)
		sp->parameter[ix] = -1;
	return sp;
}

static void PopParamStack(int tag_type, char *outbuf, int *outix, int *n_param_stack, PARAM_STACK *param_stack, int *speech_parameters)
{
	// unwind the stack up to and including the previous tag of this type
	int ix;
	int top = 0;

	if (tag_type >= SSML_CLOSE)
		tag_type -= SSML_CLOSE;

	for (ix = 0; ix < *n_param_stack; ix++) {
		if (param_stack[ix].type == tag_type)
			top = ix;
	}
	if (top > 0)
		*n_param_stack = top;
	ProcessParamStack(outbuf, outix, *n_param_stack, param_stack, speech_parameters);
}

static int ReplaceKeyName(char *outbuf, int index, int *outix)
{
	// Replace some key-names by single characters, so they can be pronounced in different languages
	static const MNEM_TAB keynames[] = {
		{ "space ",        0xe020 },
		{ "tab ",          0xe009 },
		{ "underscore ",   0xe05f },
		{ "double-quote ", '"' },
		{ NULL,            0 }
	};

	int letter;
	char *p;

	p = &outbuf[index];

	if ((letter = LookupMnem(keynames, p)) != 0) {
		int ix;
		 ix = utf8_out(letter, p);
		*outix = index + ix;
		return letter;
	}
	return 0;
}

static void SetProsodyParameter(int param_type, const wchar_t *attr1, PARAM_STACK *sp, PARAM_STACK *param_stack, int *speech_parameters)
{
	int value;


	static const MNEM_TAB mnem_volume[] = {
		{ "default", 100 },
		{ "silent",    0 },
		{ "x-soft",   30 },
		{ "soft",     65 },
		{ "medium",  100 },
		{ "loud",    150 },
		{ "x-loud",  230 },
		{ NULL,       -1 }
	};

	static const MNEM_TAB mnem_rate[] = {
		{ "default", 100 },
		{ "x-slow",   60 },
		{ "slow",     80 },
		{ "medium",  100 },
		{ "fast",    125 },
		{ "x-fast",  160 },
		{ NULL,       -1 }
	};

	static const MNEM_TAB mnem_pitch[] = {
		{ "default", 100 },
		{ "x-low",    70 },
		{ "low",      85 },
		{ "medium",  100 },
		{ "high",    110 },
		{ "x-high",  120 },
		{ NULL,       -1 }
	};

	static const MNEM_TAB mnem_range[] = {
		{ "default", 100 },
		{ "x-low",    20 },
		{ "low",      50 },
		{ "medium",  100 },
		{ "high",    140 },
		{ "x-high",  180 },
		{ NULL,       -1 }
	};

	static const MNEM_TAB * const mnem_tabs[5] = {
		NULL, mnem_rate, mnem_volume, mnem_pitch, mnem_range
	};

	if ((value = attrlookup(attr1, mnem_tabs[param_type])) >= 0) {
		// mnemonic specifies a value as a percentage of the base pitch/range/rate/volume
		sp->parameter[param_type] = (param_stack[0].parameter[param_type] * value)/100;
	} else {
		int sign = attr_prosody_value(param_type, attr1, &value);

		if (sign == 0)
			sp->parameter[param_type] = value; // absolute value in Hz
		else if (sign == 2) {
			// change specified as percentage or in semitones
			sp->parameter[param_type] = (speech_parameters[param_type] * value)/100;
		} else {
			// change specified as plus or minus Hz
			sp->parameter[param_type] = speech_parameters[param_type] + (value*sign);
		}
	}
}

int ProcessSsmlTag(wchar_t *xml_buf, char *outbuf, int *outix, int n_outbuf, const char *xmlbase, bool *audio_text, char *current_voice_id, espeak_VOICE *base_voice, char *base_voice_variant_name, bool *ignore_text, bool *clear_skipping_text, int *sayas_mode, int *sayas_start, SSML_STACK *ssml_stack, int *n_ssml_stack, int *n_param_stack, int *speech_parameters)
{
return 0; /* SSML is not enabled in this instrument. */
}

#pragma GCC visibility push(default)
ESPEAK_API void espeak_SetUriCallback(int (*UriCallback)(int, const char *, const char *))
{
	uri_callback = UriCallback;
}
#pragma GCC visibility pop

static const MNEM_TAB xml_entity_mnemonics[] = {
	{ "gt",   '>' },
	{ "lt",   0xe000 + '<' },   // private usage area, to avoid confusion with XML tag
	{ "amp",  '&' },
	{ "quot", '"' },
	{ "nbsp", ' ' },
	{ "apos", '\'' },
	{ NULL,   -1 }
};

int ParseSsmlReference(char *ref, int *c1, int *c2) {
	// Check if buffer *ref contains an XML character or entity reference
	// if found, set *c1 to the replacement char
	// change *c2 for entity references
	// returns >= 0 on success

	if (ref[0] == '#') {
		// character reference
		if (ref[1] == 'x')
			return sscanf(&ref[2], "%x", c1);
		else
			return sscanf(&ref[1], "%d", c1);
	} else { 
		// entity reference
		int found;
		if ((found = LookupMnem(xml_entity_mnemonics, ref)) != -1) {
			*c1 = found;
			if (*c2 == 0)
				*c2 = ' ';
			return found;
		}
	}
	return -1;
}
