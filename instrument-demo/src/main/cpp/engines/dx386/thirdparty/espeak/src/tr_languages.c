/*
 * Copyright (C) 2005 to 2015 by Jonathan Duddington
 * email: jonsd@users.sourceforge.net
 * Copyright (C) 2015-2016, 2020 Reece H. Dunn
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
#include <locale.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <espeak-ng/espeak_ng.h>
#include <espeak-ng/speak_lib.h>
#include <espeak-ng/encoding.h>

#include "common.h"
#include "setlengths.h"          // for SetLengthMods
#include "translate.h"           // for Translator, LANGUAGE_OPTIONS, L, NUM...

// start of unicode pages for character sets
#define OFFSET_GREEK    0x380
#define OFFSET_CYRILLIC 0x420
#define OFFSET_ARMENIAN 0x530
#define OFFSET_HEBREW   0x590
#define OFFSET_ARABIC   0x600
#define OFFSET_SYRIAC   0x700
#define OFFSET_DEVANAGARI  0x900
#define OFFSET_BENGALI  0x980
#define OFFSET_GURMUKHI 0xa00
#define OFFSET_GUJARATI 0xa80
#define OFFSET_ORIYA    0xb00
#define OFFSET_TAMIL    0xb80
#define OFFSET_TELUGU   0xc00
#define OFFSET_KANNADA  0xc80
#define OFFSET_MALAYALAM 0xd00
#define OFFSET_SINHALA  0x0d80
#define OFFSET_THAI     0x0e00
#define OFFSET_LAO      0x0e80
#define OFFSET_TIBET    0x0f00
#define OFFSET_MYANMAR  0x1000
#define OFFSET_GEORGIAN 0x10a0
#define OFFSET_KOREAN   0x1100
#define OFFSET_ETHIOPIC 0x1200
#define OFFSET_SHAVIAN  0x10450

// character ranges must be listed in ascending unicode order
const ALPHABET *AlphabetFromChar(int c)
{
	(void)c;
	return NULL;
}

static void Translator_Russian(Translator *tr);

static void SetLetterVowel(Translator *tr, int c)
{
	tr->letter_bits[c] = (tr->letter_bits[c] & 0x40) | 0x81; // keep value for group 6 (front vowels e,i,y)
}

static void ResetLetterBits(Translator *tr, int groups)
{
	// Clear all the specified groups
	unsigned int ix;
	unsigned int mask;

	mask = ~groups;

	for (ix = 0; ix < sizeof(tr->letter_bits); ix++)
		tr->letter_bits[ix] &= mask;
}

static void SetLetterBits(Translator *tr, int group, const char *string)
{
	int bits;
	unsigned char c;

	bits = (1L << group);
	while ((c = *string++) != 0)
		tr->letter_bits[c] |= bits;
}

static void SetLetterBitsRange(Translator *tr, int group, int first, int last)
{
	int bits;
	int ix;

	bits = (1L << group);
	for (ix = first; ix <= last; ix++)
		tr->letter_bits[ix] |= bits;
}

static void SetLetterBitsUTF8(Translator *tr, int group, const char *letters, int offset)
{
	// Add the letters to the specified letter group.
	const char *p = letters;
	int code = -1;
	while (code != 0) {
		int bytes = utf8_in(&code, p);
		if (code > 0x20)
			tr->letter_bits[code - offset] |= (1L << group);
		p += bytes;
	}
}

// ignore these characters
static const unsigned short chars_ignore_default[] = {
	// U+00AD SOFT HYPHEN
	//     Used to mark hyphenation points in words for where to split a
	//     word at the end of a line to provide readable justified text.
	0xad,   1,
	// U+200C ZERO WIDTH NON-JOINER
	//     Used to prevent combined ligatures being displayed in their
	//     combined form.
	0x200c, 1,
	// U+200D ZERO WIDTH JOINER
	//     Used to indicate an alternative connected form made up of the
	//     characters surrounding the ZWJ in Devanagari, Kannada, Malayalam
	//     and Emoji.
//	0x200d, 1, // Not ignored.
	// End of the ignored character list.
	0,      0
};

// alternatively, ignore characters but allow zero-width-non-joiner (lang-fa)
static const unsigned short chars_ignore_zwnj_hyphen[] = {
	// U+00AD SOFT HYPHEN
	//     Used to mark hyphenation points in words for where to split a
	//     word at the end of a line to provide readable justified text.
	0xad,   1,
	// U+0640 TATWEEL (KASHIDA)
	//     Used in Arabic scripts to stretch characters for justifying
	//     the text.
	0x640,  1,
	// U+200C ZERO WIDTH NON-JOINER
	//     Used to prevent combined ligatures being displayed in their
	//     combined form.
	0x200c, '-',
	// U+200D ZERO WIDTH JOINER
	//     Used to indicate an alternative connected form made up of the
	//     characters surrounding the ZWJ in Devanagari, Kannada, Malayalam
	//     and Emoji.
//	0x200d, 1, // Not ignored.
	// End of the ignored character list.
	0,      0
};

static const unsigned char utf8_ordinal[] = { 0xc2, 0xba, 0 }; // masculine ordinal character, UTF-8
static const unsigned char utf8_null[] = { 0 }; // null string, UTF-8

static Translator *NewTranslator(void)
{
	Translator *tr;
	int ix;
	static const unsigned char stress_amps2[] = { 18, 18, 20, 20, 20, 22, 22, 20 };
	static const short stress_lengths2[8] = { 182, 140, 220, 220, 220, 240, 260, 280 };
	static const wchar_t empty_wstring[1] = { 0 };
	static const wchar_t punct_in_word[2] = { '\'', 0 };  // allow hyphen within words
	static const unsigned char default_tunes[6] = { 0, 1, 2, 3, 0, 0 };

	// Translates character codes in the range transpose_min to transpose_max to
	// a number in the range 1 to 63.  0 indicates there is no translation.
	// Used up to 57 (max of 63)
	static const char transpose_map_latin[] = {
		 0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14, 15, // 0x60
		16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26,  0,  0,  0,  0,  0, // 0x70
		 0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0, // 0x80
		 0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0, // 0x90
		 0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0, // 0xa0
		 0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0, // 0xb0
		 0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0, // 0xc0
		 0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0, // 0xd0
		27, 28, 29,  0,  0, 30, 31, 32, 33, 34, 35, 36,  0, 37, 38,  0, // 0xe0
		 0,  0,  0, 39,  0,  0, 40,  0, 41,  0, 42,  0, 43,  0,  0,  0, // 0xf0
		 0,  0,  0, 44,  0, 45,  0, 46,  0,  0,  0,  0,  0, 47,  0,  0, // 0x100
		 0, 48,  0,  0,  0,  0,  0,  0,  0, 49,  0,  0,  0,  0,  0,  0, // 0x110
		 0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0, // 0x120
		 0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0, // 0x130
		 0,  0, 50,  0, 51,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0, // 0x140
		 0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0, 52,  0,  0,  0,  0, // 0x150
		 0, 53,  0, 54,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0, // 0x160
		 0,  0,  0,  0,  0,  0,  0,  0,  0,  0, 55,  0, 56,  0, 57,  0, // 0x170
	};

	if ((tr = (Translator *)malloc(sizeof(Translator))) == NULL)
		return NULL;

	tr->encoding = ESPEAKNG_ENCODING_ISO_8859_1;
	dictionary_name[0] = 0;
	tr->dictionary_name[0] = 0;
	tr->phonemes_repeat[0] = 0;
	tr->dict_condition = 0;
	tr->dict_min_size = 0;
	tr->data_dictrules = NULL; // language_1   translation rules file
	tr->data_dictlist = NULL;  // language_2   dictionary lookup file

	tr->transpose_min = 0x60;
	tr->transpose_max = 0x17f;
	tr->transpose_map = transpose_map_latin;
	tr->frequent_pairs = NULL;

	tr->expect_verb = 0;
	tr->expect_past = 0;
	tr->expect_verb_s = 0;
	tr->expect_noun = 0;

	tr->clause_upper_count = 0;
	tr->clause_lower_count = 0;

	// only need lower case
	tr->letter_bits_offset = 0;
	memset(tr->letter_bits, 0, sizeof(tr->letter_bits));
	memset(tr->letter_groups, 0, sizeof(tr->letter_groups));

	// 0-6 sets of characters matched by A B C H F G Y  in pronunciation rules
	// these may be set differently for different languages
	SetLetterBits(tr, 0, "aeiou"); // A  vowels, except y
	SetLetterBits(tr, 1, "bcdfgjklmnpqstvxz"); // B  hard consonants, excluding h,r,w
	SetLetterBits(tr, 2, "bcdfghjklmnpqrstvwxz"); // C  all consonants
	SetLetterBits(tr, 3, "hlmnr"); // H  'soft' consonants
	SetLetterBits(tr, 4, "cfhkpqstx"); // F  voiceless consonants
	SetLetterBits(tr, 5, "bdgjlmnrvwyz"); // G voiced
	SetLetterBits(tr, 6, "eiy"); // Letter group Y, front vowels
	SetLetterBits(tr, 7, "aeiouy"); // vowels, including y

	tr->char_plus_apostrophe = empty_wstring;
	tr->punct_within_word = punct_in_word;
	tr->chars_ignore = chars_ignore_default;

	for (ix = 0; ix < 8; ix++) {
		tr->stress_amps[ix] = stress_amps2[ix];
		tr->stress_lengths[ix] = stress_lengths2[ix];
	}
	memset(&(tr->langopts), 0, sizeof(tr->langopts));
	tr->langopts.max_lengthmod = 500;
	tr->langopts.lengthen_tonic = 20;

	tr->langopts.stress_rule = STRESSPOSN_2R;
	tr->langopts.unstressed_wd1 = 1;
	tr->langopts.unstressed_wd2 = 3;
	tr->langopts.param[LOPT_SONORANT_MIN] = 95;
	tr->langopts.param[LOPT_LONG_VOWEL_THRESHOLD] = 190/2;
	tr->langopts.param[LOPT_MAXAMP_EOC] = 19;
	tr->langopts.param[LOPT_UNPRONOUNCABLE] = 's'; // don't count this character at start of word
	tr->langopts.param[LOPT_BRACKET_PAUSE] = 4; // pause at bracket
	tr->langopts.param[LOPT_BRACKET_PAUSE_ANNOUNCED] = 2; // pauses when announcing bracket names
	tr->langopts.spelling_stress = false;
	tr->langopts.max_initial_consonants = 3;
	tr->langopts.replace_chars = NULL;
	tr->langopts.alt_alphabet_lang = L('e', 'n');
	tr->langopts.roman_suffix = utf8_null;
	tr->langopts.lowercase_sentence = false;

	SetLengthMods(tr, 201);

	tr->langopts.long_stop = 100;

	tr->langopts.max_roman = 49;
	tr->langopts.min_roman = 2;
	tr->langopts.thousands_sep = ',';
	tr->langopts.decimal_sep = '.';
	tr->langopts.numbers = NUM_DEFAULT;
	tr->langopts.break_numbers = BREAK_THOUSANDS;
	tr->langopts.max_digits = 14;

	// index by 0=. 1=, 2=?, 3=! 4=none, 5=emphasized
	unsigned char punctuation_to_tone[INTONATION_TYPES][PUNCT_INTONATIONS] = {
		{  0,  1,  2,  3, 0, 4 },
		{  0,  1,  2,  3, 0, 4 },
		{  5,  6,  2,  3, 0, 4 },
		{  5,  7,  1,  3, 0, 4 },
		{  8,  9, 10,  3, 0, 0 },
		{  8,  8, 10,  3, 0, 0 },
		{ 11, 11, 11, 11, 0, 0 }, // 6 test
		{ 12, 12, 12, 12, 0, 0 }
	};

	memcpy(tr->punct_to_tone, punctuation_to_tone, sizeof(tr->punct_to_tone));

	memcpy(tr->langopts.tunes, default_tunes, sizeof(tr->langopts.tunes));

	return tr;
}

// common letter pairs, encode these as a single byte
//  2 bytes, using the transposed character codes
static const short pairs_ru[] = {
	0x010c, //  ла   21052  0x23
	0x010e, //  на   18400
	0x0113, //  та   14254
	0x0301, //  ав   31083
	0x030f, //  ов   13420
	0x060e, //  не   21798
	0x0611, //  ре   19458
	0x0903, //  ви   16226
	0x0b01, //  ак   14456
	0x0b0f, //  ок   17836
	0x0c01, //  ал   13324
	0x0c09, //  ил   16877
	0x0e01, //  ан   15359
	0x0e06, //  ен   13543  0x30
	0x0e09, //  ин   17168
	0x0e0e, //  нн   15973
	0x0e0f, //  он   22373
	0x0e1c, //  ын   15052
	0x0f03, //  во   24947
	0x0f11, //  ро   13552
	0x0f12, //  со   16368
	0x100f, //  оп   19054
	0x1011, //  рп   17067
	0x1101, //  ар   23967
	0x1106, //  ер   18795
	0x1109, //  ир   13797
	0x110f, //  ор   21737
	0x1213, //  тс   25076
	0x1220, //  яс   14310
	0x7fff
};

static const unsigned char ru_vowels[] = { // (also kazakh) offset by 0x420 -- а е ё и о у ы э ю я ә ө ұ ү і
	0x10, 0x15, 0x31, 0x18, 0x1e, 0x23, 0x2b, 0x2d, 0x2e, 0x2f,  0xb9, 0xc9, 0x91, 0x8f, 0x36, 0
};
static const unsigned char ru_consonants[] = { // б в г д ж з й к л м н п р с т ф х ц ч ш щ ъ ь қ ң һ
	0x11, 0x12, 0x13, 0x14, 0x16, 0x17, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1f, 0x20, 0x21, 0x22, 0x24, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2a, 0x2c, 0x73, 0x7b, 0x83, 0x9b, 0
};

static void SetArabicLetters(Translator *tr)
{
	const char *arab_vowel_letters = "َ  ُ  ِ";
	const char *arab_consonant_vowel_letters = "ا و ي";
	const char *arab_consonant_letters = "ب پ ت ة ث ج ح خ د ذ ر ز س ش ص ض ط ظ ع غ ف ق ك ل م ن ئ ؤ ء أ آ إ ه";
	const char *arab_thick_letters = "ص ض ط ظ";
	const char *arab_shadda_letter = " ّ ";
	const char *arab_hamza_letter = " ّ ";
	const char *arab_sukun_letter = " ّ ";

	SetLetterBitsUTF8(tr, LETTERGP_A, arab_vowel_letters, OFFSET_ARABIC);
	SetLetterBitsUTF8(tr, LETTERGP_B, arab_consonant_vowel_letters, OFFSET_ARABIC);
	SetLetterBitsUTF8(tr, LETTERGP_C, arab_consonant_letters, OFFSET_ARABIC);
	SetLetterBitsUTF8(tr, LETTERGP_F, arab_thick_letters, OFFSET_ARABIC);
	SetLetterBitsUTF8(tr, LETTERGP_G, arab_shadda_letter, OFFSET_ARABIC);
	SetLetterBitsUTF8(tr, LETTERGP_H, arab_hamza_letter, OFFSET_ARABIC);
	SetLetterBitsUTF8(tr, LETTERGP_Y, arab_sukun_letter, OFFSET_ARABIC);
}

static void SetCyrillicLetters(Translator *tr)
{
	// Set letter types for Cyrillic script languages: bg (Bulgarian), ru (Russian), tt (Tatar), uk (Ukrainian).

	// character codes offset by 0x420
	static const char cyrl_soft[] = { 0x2c, 0x19, 0x27, 0x29, 0 }; // letter group B  [k ts; s;] -- ь й ч щ
	static const char cyrl_hard[] = { 0x2a, 0x16, 0x26, 0x28, 0 }; // letter group H  [S Z ts] -- ъ ж ц ш
	static const char cyrl_nothard[] = { 0x11, 0x12, 0x13, 0x14, 0x17, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1f, 0x20, 0x21, 0x22, 0x24, 0x25, 0x27, 0x29, 0x2c, 0 }; // б в г д з й к л м н п р с т ф х ч щ ь
	static const char cyrl_voiced[] = { 0x11, 0x12, 0x13, 0x14, 0x16, 0x17, 0 };    // letter group G  (voiced obstruents) -- б в г д ж з
	static const char cyrl_ivowels[] = { 0x2c, 0x2e, 0x2f, 0x31, 0 };   // letter group Y  (iotated vowels & soft-sign) -- ь ю я ё
	tr->encoding = ESPEAKNG_ENCODING_KOI8_R;
	tr->transpose_min = 0x430;  // convert cyrillic from unicode into range 0x01 to 0x22
	tr->transpose_max = 0x451;
	tr->transpose_map = NULL;
	tr->frequent_pairs = pairs_ru;

	tr->letter_bits_offset = OFFSET_CYRILLIC;
	memset(tr->letter_bits, 0, sizeof(tr->letter_bits));
	SetLetterBits(tr, LETTERGP_A, (char *)ru_vowels);
	SetLetterBits(tr, LETTERGP_B, cyrl_soft);
	SetLetterBits(tr, LETTERGP_C, (char *)ru_consonants);
	SetLetterBits(tr, LETTERGP_H, cyrl_hard);
	SetLetterBits(tr, LETTERGP_F, cyrl_nothard);
	SetLetterBits(tr, LETTERGP_G, cyrl_voiced);
	SetLetterBits(tr, LETTERGP_Y, cyrl_ivowels);
	SetLetterBits(tr, LETTERGP_VOWEL2, (char *)ru_vowels);
}

static void SetIndicLetters(Translator *tr)
{
	// Set letter types for Devanagari (Indic) script languages: Devanagari, Tamill, etc.

	static const char deva_consonants2[] = { 0x02, 0x03, 0x58, 0x59, 0x5a, 0x5b, 0x5c, 0x5d, 0x5e, 0x5f, 0x7b, 0x7c, 0x7e, 0x7f, 0 };
	static const char deva_vowels2[] = { 0x60, 0x61, 0x55, 0x56, 0x57, 0x62, 0x63, 0 };  // non-consecutive vowels and vowel-signs

	memset(tr->letter_bits, 0, sizeof(tr->letter_bits));
	SetLetterBitsRange(tr, LETTERGP_A, 0x04, 0x14); // vowel letters
	SetLetterBitsRange(tr, LETTERGP_A, 0x3e, 0x4d); // + vowel signs, and virama
	SetLetterBits(tr, LETTERGP_A, deva_vowels2);     // + extra vowels and vowel signs

	SetLetterBitsRange(tr, LETTERGP_B, 0x3e, 0x4d); // vowel signs, and virama
	SetLetterBits(tr, LETTERGP_B, deva_vowels2);     // + extra vowels and vowel signs

	SetLetterBitsRange(tr, LETTERGP_C, 0x15, 0x39); // the main consonant range
	SetLetterBits(tr, LETTERGP_C, deva_consonants2); // + additional consonants

	SetLetterBitsRange(tr, LETTERGP_Y, 0x04, 0x14); // vowel letters
	SetLetterBitsRange(tr, LETTERGP_Y, 0x3e, 0x4c); // + vowel signs
	SetLetterBits(tr, LETTERGP_Y, deva_vowels2);     // + extra vowels and vowel signs

	tr->langopts.param[LOPT_UNPRONOUNCABLE] = 1;    // disable check for unpronouncable words
	tr->langopts.suffix_add_e = tr->letter_bits_offset + 0x4d; // virama
}

static void SetupTranslator(Translator *tr, const short *lengths, const unsigned char *amps)
{
	if (lengths != NULL)
		memcpy(tr->stress_lengths, lengths, sizeof(tr->stress_lengths));
	if (amps != NULL)
		memcpy(tr->stress_amps, amps, sizeof(tr->stress_amps));
}

Translator *SelectTranslator(const char *name)
{
	int name2 = 0;
	Translator *tr;

	static const short stress_lengths_equal[8] = { 230, 230,  230, 230,  0, 0,  230, 230 };
	static const unsigned char stress_amps_equal[8] = { 19, 19, 19, 19, 19, 19, 19, 19 };

	static const short stress_lengths_fr[8] = { 190, 170,  190, 200,  0, 0,  190, 240 };
	static const unsigned char stress_amps_fr[8] = { 18, 16, 18, 18, 18, 18, 18, 18 };

	static const unsigned char stress_amps_sk[8] = { 17, 16, 20, 20, 20, 22, 22, 21 };
	static const short stress_lengths_sk[8] = { 190, 190, 210, 210, 0, 0, 210, 210 };

	static const short stress_lengths_ta[8] = { 200, 200,  210, 210,  0, 0,  230, 230 };
	static const short stress_lengths_ta2[8] = { 230, 230,  240, 240,  0, 0,  260, 260 };
	static const unsigned char stress_amps_ta[8] = { 18, 18, 18, 18, 20, 20, 22, 22 };

	tr = NewTranslator();
	strcpy(tr->dictionary_name, name);

	// convert name string into a word of up to 4 characters, for the switch()
	while (*name != 0)
		name2 = (name2 << 8) + *name++;

	switch(name2) {
	case L('e', 'n'):
	{
		static const short stress_lengths_en[8] = { 182, 140, 220, 220, 0, 0, 248, 275 };
		SetupTranslator(tr, stress_lengths_en, NULL);

		tr->langopts.stress_rule = STRESSPOSN_1L;
		tr->langopts.stress_flags = 0x08;
		tr->langopts.numbers = NUM_HUNDRED_AND | NUM_ROMAN | NUM_1900;
		tr->langopts.max_digits = 33;
		tr->langopts.param[LOPT_COMBINE_WORDS] = 2; // allow "mc" to cmbine with the following word
		tr->langopts.suffix_add_e = 'e';
		tr->langopts.param[LOPT_UNPRONOUNCABLE] = 2; // use en_rules for unpronouncable rules
		SetLetterBits(tr, 6, "aeiouy"); // Group Y: vowels, including y
	}
		break;
	default: DeleteTranslator(tr); return NULL;
	}

	tr->translator_name = name2;

	ProcessLanguageOptions(&tr->langopts);
	return tr;
}

void ProcessLanguageOptions(LANGUAGE_OPTIONS *langopts)
{
	if (langopts->numbers & NUM_DECIMAL_COMMA) {
		// use . and ; for thousands and decimal separators
		langopts->thousands_sep = '.';
		langopts->decimal_sep = ',';
	}
	if (langopts->numbers & NUM_THOUS_SPACE)
		langopts->thousands_sep = 0; // don't allow thousands separator, except space
}

static void Translator_Russian(Translator *tr)
{
	static const unsigned char stress_amps_ru[] = { 16, 16, 18, 18, 20, 24, 24, 22 };
	static const short stress_lengths_ru[8] = { 150, 140, 220, 220, 0, 0, 260, 280 };
	static const char ru_ivowels[] = { 0x15, 0x18, 0x34, 0x37, 0 }; // add "е и є ї" to Y lettergroup (iotated vowels & soft-sign)

	SetupTranslator(tr, stress_lengths_ru, stress_amps_ru);
	SetCyrillicLetters(tr);
	SetLetterBits(tr, LETTERGP_Y, ru_ivowels);

	tr->langopts.param[LOPT_UNPRONOUNCABLE] = 0x432; // [v]  don't count this character at start of word
	tr->langopts.param[LOPT_REGRESSIVE_VOICING] = 0x03;
	tr->langopts.param[LOPT_REDUCE] = 2;
	tr->langopts.stress_rule = STRESSPOSN_SYLCOUNT;
	tr->langopts.stress_flags = S_NO_AUTO_2;

	tr->langopts.numbers = NUM_DECIMAL_COMMA | NUM_OMIT_1_HUNDRED;
	tr->langopts.numbers2 = NUM2_THOUSANDPLEX_VAR_THOUSANDS | NUM2_THOUSANDS_VAR1; // variant numbers before thousands
	tr->langopts.max_digits = 32;
	tr->langopts.max_initial_consonants = 5;
}		
