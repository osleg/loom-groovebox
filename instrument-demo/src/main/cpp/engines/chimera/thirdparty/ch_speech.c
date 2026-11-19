/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Jameson Hodge (CHIMERA, https://github.com/jamesondh/chimera-vocal-synth)
 * C port for the M-VAVE FM-1: Copyright (c) 2026 Shan-Sherif.
 *
 * A literal port of CHIMERA's English front end and planner; see ch_speech.h. The comments are CHIMERA's, shortened. */
#include "ch_speech.h"
#include "ch_common.h"
#include "ch_str.h"

/* ------------------------------------------------------------------------ the tables --- */
enum { CH_M_VOWEL, CH_M_STOP, CH_M_NASAL, CH_M_FRICATIVE, CH_M_AFFRICATE, CH_M_APPROXIMANT, CH_M_LATERAL, CH_M_TAP,
       CH_M_TRILL, CH_M_GLOTTAL };
enum { CH_G_NONE, CH_G_OPEN, CH_G_STOP, CH_G_BREATHY };
typedef struct {
    const char *ipa;
    uint8_t manner, voiced, nasal, glottal;
    const char *place;
    ch_real dur;
    const char *release;
    uint32_t dominant;           /* ArticKey bits */
    ch_real t[CH_NARTIC];        /* NaN: none */
} ch_phon_t;
typedef struct {
    const char *ipa;
    ch_real t[CH_NARTIC];
    ch_real f1, f2;              /* its calibrated formants (Hz) */
} ch_vowel_t;
#include "ch_phon_data.h"
#include "ch_dict_data.h"

/* the body's calibrated tables (CHIMERA's phoneme(ipa, body), vowel_artic(ipa, body) for its three bodies): the
 * same inventory in each, so the sizes are the male's */
#define NPHON ((int)(sizeof CH_PHONEMES_MALE / sizeof CH_PHONEMES_MALE[0]))
#define NVOW ((int)(sizeof CH_VOWELS_MALE / sizeof CH_VOWELS_MALE[0]))
static _Thread_local const ch_phon_t *ph_tab = CH_PHONEMES_MALE;
static _Thread_local const ch_vowel_t *vow_tab = CH_VOWELS_MALE;
#define CH_PHONEMES ph_tab
#define CH_VOWELS vow_tab
void ch_speech_body(int body)
{
    if (body < 0 || body > 2)
        body = 0;
    ph_tab = CH_PHONEMES_BODY[body];
    vow_tab = CH_VOWELS_BODY[body];
}
#define BIT(k) (1u << (k))
#define CH_NAN ((ch_real)__builtin_nanf(""))
#if defined(CH_FM1)
#define LOG(x) (fm_log2f(x) * 0.69314718f)
#elif defined(CH_DOUBLE)
#define LOG log
#else
#define LOG logf
#endif

static inline int is_nan(ch_real x) { return x != x; }
static inline int streq(const char *a, const char *b) { return strcmp(a, b) == 0; }
static ch_real js_round(ch_real x)   /* Math.round */
{
    ch_real y = x + R(0.5);
    long k = (long)y;
    if ((ch_real)k > y)
        k--;
    return (ch_real)k;
}

static const ch_phon_t *phoneme(const char *ipa)
{
    int i;
    for (i = NPHON - 1; i >= 0; i--)   /* (a later duplicate would win: the table has none) */
        if (streq(CH_PHONEMES[i].ipa, ipa))
            return &CH_PHONEMES[i];
    return 0;
}

static const ch_real *vowel_artic(const char *ipa)
{
    int i;
    for (i = 0; i < NVOW; i++)
        if (streq(CH_VOWELS[i].ipa, ipa))
            return CH_VOWELS[i].t;
    return 0;
}

/* diphthongs and r-coloured vowels expand to vowel sequences (American /eɪ oʊ/ glide toward [i u]) */
static int expand(const char *ipa, const char **a, const char **b)
{
    static const char *const E[][3] = {{"aɪ", "a", "ɪ"}, {"aʊ", "a", "ʊ"}, {"ɔɪ", "ɔ", "ɪ"}, {"eɪ", "e", "i"},
                                       {"oʊ", "o", "u"}, {"ɚ", "ɝ", 0}};
    unsigned i;
    for (i = 0; i < sizeof E / sizeof E[0]; i++)
        if (streq(E[i][0], ipa)) {
            *a = E[i][1];
            *b = E[i][2];
            return E[i][2] ? 2 : 1;
        }
    return 0;
}

/* the planner's vowel: the calibrated table's, or one that expands */
static int plan_is_vowel(const char *ipa)
{
    const char *a, *b;
    return vowel_artic(ipa) != 0 || expand(ipa, &a, &b) != 0;
}

static void seg_set(ch_seg_t *s, const char *ipa)
{
    size_t n = strlen(ipa);
    if (n > CH_IPA - 1)
        n = CH_IPA - 1;
    memcpy(s->ipa, ipa, n);
    s->ipa[n] = 0;
}

static void seg_init(ch_seg_t *s, const char *ipa)
{
    seg_set(s, ipa);
    s->dur = CH_NAN;
    s->stress = -1;
    s->word_end = 0;
    s->f0 = 0;
}

/* ----------------------------------------------------------------------- english.rs --- */
static const char *arpa(const char *b, size_t n)
{
    static const char *const A[][2] = {
        {"AA", "ɑ"}, {"AE", "æ"}, {"AH", "ʌ"}, {"AO", "ɔ"}, {"AW", "aʊ"}, {"AY", "aɪ"}, {"EH", "ɛ"}, {"ER", "ɝ"},
        {"EY", "eɪ"}, {"IH", "ɪ"}, {"IY", "i"}, {"OW", "oʊ"}, {"OY", "ɔɪ"}, {"UH", "ʊ"}, {"UW", "u"}, {"B", "b"},
        {"CH", "tʃ"}, {"D", "d"}, {"DH", "ð"}, {"F", "f"}, {"G", "g"}, {"HH", "h"}, {"JH", "dʒ"}, {"K", "k"},
        {"L", "l"}, {"M", "m"}, {"N", "n"}, {"NG", "ŋ"}, {"P", "p"}, {"R", "ɹ"}, {"S", "s"}, {"SH", "ʃ"},
        {"T", "t"}, {"TH", "θ"}, {"V", "v"}, {"W", "w"}, {"Y", "j"}, {"Z", "z"}, {"ZH", "ʒ"}};
    unsigned i;
    for (i = 0; i < sizeof A / sizeof A[0]; i++)
        if (strlen(A[i][0]) == n && !memcmp(A[i][0], b, n))
            return A[i][1];
    return 0;
}

static int en_is_vowel(const char *ipa)
{
    static const char *const V[] = {"ɑ", "æ", "ʌ", "ə", "ɔ", "aʊ", "aɪ", "ɛ", "ɝ", "ɚ", "eɪ", "ɪ", "i", "oʊ", "ɔɪ", "ʊ", "u"};
    unsigned i;
    for (i = 0; i < sizeof V / sizeof V[0]; i++)
        if (streq(V[i], ipa))
            return 1;
    return 0;
}

/* the dictionary keeps one pronunciation per word; for these homographs the common reading wins */
static const char *homograph(const char *w)
{
    static const char *const H[][2] = {{"wind", "W IH1 N D"}, {"read", "R IY1 D"}, {"live", "L IH1 V"},
                                       {"lead", "L IY1 D"}, {"close", "K L OW1 Z"}, {"wound", "W UW1 N D"}};
    unsigned i;
    for (i = 0; i < sizeof H / sizeof H[0]; i++)
        if (streq(H[i][0], w))
            return H[i][1];
    return 0;
}

/* monosyllabic function words are unstressed in running speech (and reduce) */
static int is_function_word(const char *w)
{
    static const char FW[] = " a an the of to in on at by for from with as and or but nor so if is am are was were be "
                             "been do does did has have had will would can could should shall may might must it its i you "
                             "he she we they me him her us them my your his our their this that than then there not no ";
    char k[24];
    size_t n = strlen(w);
    if (n + 2 >= sizeof k)
        return 0;
    k[0] = ' ';
    memcpy(k + 1, w, n);
    k[n + 1] = ' ';
    k[n + 2] = 0;
    return strstr(FW, k) != 0;
}

static const char *reduce_vowel(const char *ipa)
{
    static const char *const R_[] = {"ʌ", "æ", "ɑ", "ɛ", "ɔ", "u", "ʊ"};
    unsigned i;
    for (i = 0; i < sizeof R_ / sizeof R_[0]; i++)
        if (streq(R_[i], ipa))
            return "ə";
    return 0;
}

int ch_arpabet(const char *phones, ch_seg_t *out, int cap)
{
    int n = 0;
    const char *p = phones;
    while (*p) {
        const char *s, *ipa;
        size_t len, blen;
        int stress = 0, digit = -1, ok = 1;
        size_t k;
        while (*p == ' ' || *p == '\t')
            p++;
        if (!*p)
            break;
        s = p;
        while (*p && *p != ' ' && *p != '\t')
            p++;
        len = (size_t)(p - s);
        blen = len;
        if (s[len - 1] == '0' || s[len - 1] == '1' || s[len - 1] == '2') {
            digit = s[len - 1] - '0';
            blen--;
        }
        for (k = 0; k < blen; k++)
            if (s[k] < 'A' || s[k] > 'Z')
                ok = 0;
        if (!blen || !ok)
            continue;
        stress = digit == 1 ? 1 : digit == 2 ? 2 : 0;
        if (!(ipa = arpa(s, blen)))
            continue;
        if (blen == 2 && !memcmp(s, "AH", 2) && stress == 0)
            ipa = "ə";
        if (blen == 2 && !memcmp(s, "ER", 2) && stress == 0)
            ipa = "ɚ";
        if (n < cap) {
            seg_init(&out[n], ipa);
            out[n].stress = (int8_t)(en_is_vowel(ipa) ? stress : 0);
            n++;
        }
    }
    return n;
}

/* the letter-to-sound fallback: tried longest-first at each position; '#' word boundary, 'V' vowel letter,
 * 'C' consonant letter. (pre, grapheme, post, phones) */
static const char *const RULES[][4] = {
    {"", "tion", "", "ʃ ə n"}, {"", "sion", "", "ʒ ə n"}, {"", "ture", "", "tʃ ɚ"}, {"", "ough", "#", "oʊ"}, {"", "augh", "", "ɔ"},
    {"", "igh", "", "aɪ"}, {"", "eigh", "", "eɪ"}, {"", "tch", "", "tʃ"}, {"", "dge", "", "dʒ"}, {"", "sch", "", "s k"},
    {"", "ch", "", "tʃ"}, {"", "sh", "", "ʃ"}, {"", "th", "", "θ"}, {"", "ph", "", "f"}, {"", "wh", "", "w"}, {"", "ck", "", "k"},
    {"", "ng", "#", "ŋ"}, {"", "ng", "", "ŋ g"}, {"", "qu", "", "k w"}, {"#", "kn", "", "n"}, {"#", "wr", "", "ɹ"}, {"", "gh", "", ""},
    {"", "ee", "", "i"}, {"", "ea", "", "i"}, {"", "oo", "", "u"}, {"", "ou", "", "aʊ"}, {"", "ow", "#", "oʊ"}, {"", "ow", "", "aʊ"},
    {"", "oi", "", "ɔɪ"}, {"", "oy", "", "ɔɪ"}, {"", "ai", "", "eɪ"}, {"", "ay", "", "eɪ"}, {"", "au", "", "ɔ"}, {"", "aw", "", "ɔ"},
    {"", "ie", "#", "aɪ"}, {"", "ie", "", "i"}, {"", "ei", "", "eɪ"}, {"", "ey", "#", "i"}, {"", "oa", "", "oʊ"}, {"", "ue", "#", "u"},
    {"", "er", "#", "ɚ"}, {"", "er", "C", "ɝ"}, {"", "ir", "", "ɝ"}, {"", "ur", "", "ɝ"}, {"", "or", "", "ɔ ɹ"}, {"", "ar", "", "ɑ ɹ"},
    {"", "le", "#", "ə ɫ"}, {"", "ed", "#", "d"}, {"", "es", "#", "z"}, {"C", "s", "#", "s"}, {"V", "s", "#", "z"}, {"", "y", "#", "i"},
    {"#", "y", "", "j"}, {"C", "y", "", "aɪ"}, {"", "e", "#", ""},
    {"", "a", "CV", "eɪ"}, {"", "a", "", "æ"}, {"", "e", "", "ɛ"}, {"", "i", "CV", "aɪ"}, {"", "i", "", "ɪ"}, {"", "o", "CV", "oʊ"},
    {"", "o", "", "ɑ"}, {"", "u", "CV", "u"}, {"", "u", "", "ʌ"}, {"", "y", "", "ɪ"},
    {"", "c", "e", "s"}, {"", "c", "i", "s"}, {"", "c", "y", "s"}, {"", "c", "", "k"}, {"", "g", "e", "dʒ"}, {"", "g", "i", "dʒ"}, {"", "g", "", "g"},
    {"", "x", "", "k s"}, {"", "j", "", "dʒ"}, {"", "q", "", "k"}, {"", "b", "", "b"}, {"", "d", "", "d"}, {"", "f", "", "f"}, {"", "h", "", "h"},
    {"", "k", "", "k"}, {"", "l", "", "l"}, {"", "m", "", "m"}, {"", "n", "", "n"}, {"", "p", "", "p"}, {"", "r", "", "ɹ"}, {"", "s", "", "s"},
    {"", "t", "", "t"}, {"", "v", "", "v"}, {"", "w", "", "w"}, {"", "z", "", "z"},
};

/* The FM-1's additions (not CHIMERA's: CHIMERA reads common words from its dictionary and these rules only the rare
 * ones, the FM-1 has no room for the dictionary and reads most words by rule). Tried first, when ch_lts_device is
 * set (the firmware); each kept only where it read more of CMUdict right (lab: tools/lts_eval). */
int ch_lts_device;
static const char *const DEVICE_RULES[][4] = {
    {"", "all", "", "ɔ l"}, {"w", "or", "C", "ɝ"}, {"", "oo", "k", "ʊ"}, {"", "mb", "#", "m"},
    {"", "nk", "", "ŋ k"}, {"", "ng", "s#", "ŋ"}, {"", "ow", "s#", "oʊ"},
    {"t", "ed", "#", "ɪ d"}, {"d", "ed", "#", "ɪ d"}, {"p", "ed", "#", "t"}, {"k", "ed", "#", "t"}, {"s", "ed", "#", "t"},
    {"h", "ed", "#", "t"}, {"f", "ed", "#", "t"},
    {"b", "s", "#", "z"}, {"d", "s", "#", "z"}, {"g", "s", "#", "z"}, {"l", "s", "#", "z"}, {"m", "s", "#", "z"},
    {"n", "s", "#", "z"}, {"r", "s", "#", "z"}, {"v", "s", "#", "z"}, {"w", "s", "#", "z"},
    {"", "ll", "", "l"}, {"", "tt", "", "t"}, {"", "ss", "", "s"}, {"", "pp", "", "p"}, {"", "ff", "", "f"},
    {"", "mm", "", "m"}, {"", "nn", "", "n"}, {"", "rr", "", "ɹ"}, {"", "dd", "", "d"}, {"", "bb", "", "b"},
    {"", "gg", "", "g"}, {"", "zz", "", "z"}, {"", "cc", "e", "k s"}, {"", "cc", "i", "k s"}, {"", "cc", "", "k"},
};

static int lts_v(int c) { return c && strchr("aeiouy", c) != 0; }

/* a device rule that applies at i: its index, or -1 */
static int device_rule(const char *w, int len, int i);

static int match_ctx(const char *w, int len, int i, const char *ctx, int dir)
{
    int k, n = (int)strlen(ctx), pos = i;
    for (k = 0; k < n; k++) {
        int c = dir == 1 ? ctx[k] : ctx[n - 1 - k];
        int ch = pos >= 0 && pos < len ? (unsigned char)w[pos] : 0;
        if (c == '#') {
            if (ch)
                return 0;
        } else if (c == 'V') {
            if (!lts_v(ch))
                return 0;
        } else if (c == 'C') {
            if (!ch || lts_v(ch))
                return 0;
        } else if (ch != c) {
            return 0;
        }
        pos += dir;
    }
    return 1;
}

static int device_rule(const char *w, int len, int i)
{
    unsigned r;
    int k, vowel_before = 0;
    for (k = 0; k < i; k++)
        vowel_before |= lts_v((unsigned char)w[k]);
    for (r = 0; r < sizeof DEVICE_RULES / sizeof DEVICE_RULES[0]; r++) {
        const char *g = DEVICE_RULES[r][1];
        int gl = (int)strlen(g);
        if (i + gl > len || memcmp(w + i, g, (size_t)gl))
            continue;
        if (!match_ctx(w, len, i - 1, DEVICE_RULES[r][0], -1) || !match_ctx(w, len, i + gl, DEVICE_RULES[r][2], 1))
            continue;
        if (!strcmp(g, "ed") && !vowel_before)   /* "red", "shed": the e is the word's vowel */
            continue;
        return (int)r;
    }
    return -1;
}

int ch_letter_to_sound(const char *w, ch_seg_t *out, int cap)
{
    int len = (int)strlen(w), i = 0, n = 0, stressed = 0;
    while (i < len) {
        unsigned r;
        int done = 0, d = ch_lts_device ? device_rule(w, len, i) : -1;
        for (r = 0; r < sizeof RULES / sizeof RULES[0] || d >= 0; r++) {
            const char *g = d >= 0 ? DEVICE_RULES[d][1] : RULES[r][1], *ph = d >= 0 ? DEVICE_RULES[d][3] : RULES[r][3];
            int gl = (int)strlen(g);
            if (d < 0) {
                if (i + gl > len || memcmp(w + i, g, (size_t)gl))
                    continue;
                if (!match_ctx(w, len, i - 1, RULES[r][0], -1) || !match_ctx(w, len, i + gl, RULES[r][2], 1))
                    continue;
            }
            while (*ph) {   /* its phones, space-separated */
                const char *e = strchr(ph, ' ');
                size_t pl = e ? (size_t)(e - ph) : strlen(ph);
                if (n < cap) {
                    char b[CH_IPA];
                    memcpy(b, ph, pl < CH_IPA - 1 ? pl : CH_IPA - 1);
                    b[pl < CH_IPA - 1 ? pl : CH_IPA - 1] = 0;
                    seg_init(&out[n], b);
                    /* stress the first vowel (crude but serviceable) */
                    out[n].stress = (int8_t)(en_is_vowel(b) && !stressed ? 1 : 0);
                    if (en_is_vowel(b))
                        stressed = 1;
                    n++;
                }
                ph += pl;
                if (*ph == ' ')
                    ph++;
            }
            i += gl;
            done = 1;
            break;
        }
        if (!done)
            i++;
    }
    /* the FM-1's addition (0.8.0): a final -s after a voiceless consonant is voiceless too ("tapes", "makes", "notes":
     * CHIMERA's rules give /z/, which its dictionary never needs; between /p/ and a following /t/ the /z/ barely
     * sounded, and "tapes" lost its hiss) */
    if (ch_lts_device && n >= 2 && len >= 2 && w[len - 1] == 's' && !strcmp(out[n - 1].ipa, "z")) {
        const char *v = out[n - 2].ipa;
        if (!strcmp(v, "p") || !strcmp(v, "t") || !strcmp(v, "k") || !strcmp(v, "f") || !strcmp(v, "θ"))
            seg_set(&out[n - 1], "s");
        /* and a final -es after a hissing sound has its own vowel ("bushes", "boxes", "wishes": /ɪz/) */
        else if (len >= 3 && w[len - 2] == 'e' && n < cap
                 && (!strcmp(v, "s") || !strcmp(v, "z") || !strcmp(v, "ʃ") || !strcmp(v, "ʒ") || !strcmp(v, "tʃ") || !strcmp(v, "dʒ"))) {
            out[n] = out[n - 1];
            seg_init(&out[n - 1], "ɪ");
            out[n - 1].stress = 0;
            n++;
        }
    }
    return n;
}

/* the built-in words (the FM-1's: ch_dict_data.h), by index: what the word editor's random line picks from */
int ch_dict_count(void) { return (int)(sizeof CH_DICT / sizeof CH_DICT[0]); }
const char *ch_dict_word(int i) { return i >= 0 && i < ch_dict_count() ? CH_DICT[i].w : ""; }

static const char *dict_builtin(const char *w)
{
    int lo = 0, hi = (int)(sizeof CH_DICT / sizeof CH_DICT[0]) - 1;
    while (lo <= hi) {
        int mid = (lo + hi) / 2, c = strcmp(w, CH_DICT[mid].w);
        if (!c)
            return CH_DICT[mid].p;
        if (c < 0)
            hi = mid - 1;
        else
            lo = mid + 1;
    }
    return 0;
}

int ch_word(const char *word, int reduce, ch_lookup_fn lookup, void *user, ch_seg_t *out, int cap)
{
    char clean[48];
    const char *entry, *s = word, *e;
    size_t n;
    int k, i;
    while (*s == '\'')
        s++;
    e = s + strlen(s);
    while (e > s && e[-1] == '\'')
        e--;
    n = (size_t)(e - s);
    if (n >= sizeof clean)
        n = sizeof clean - 1;
    for (k = 0; k < (int)n; k++)
        clean[k] = (char)(s[k] >= 'A' && s[k] <= 'Z' ? s[k] - 'A' + 'a' : s[k]);
    clean[n] = 0;
    entry = homograph(clean);
    if (!entry && lookup)
        entry = lookup(clean, user);
    if (!entry)
        entry = dict_builtin(clean);
    if (entry && *entry) {
        int c = ch_arpabet(entry, out, cap);
        if (reduce && is_function_word(clean)) {
            int nv = 0;
            for (i = 0; i < c; i++)
                nv += en_is_vowel(out[i].ipa);
            for (i = 0; i < c; i++) {
                const char *r;
                out[i].stress = 0;
                /* short function words reduce toward schwa, except /i ɪ eɪ aɪ oʊ/ */
                if (nv == 1 && (r = reduce_vowel(out[i].ipa)) && !streq(clean, "you") && !streq(clean, "do")
                    && !streq(clean, "to") && !streq(clean, "who") && !streq(clean, "not") && !streq(clean, "no"))
                    seg_set(&out[i], r);
            }
        }
        return c;
    }
    /* hyphenated compounds */
    if (strchr(clean, '-')) {
        char part[48];
        const char *p = clean;
        int c = 0;
        for (;;) {
            const char *h = strchr(p, '-');
            size_t pl = h ? (size_t)(h - p) : strlen(p);
            memcpy(part, p, pl);
            part[pl] = 0;
            c += ch_word(part, reduce, lookup, user, out + c, cap - c);
            if (!h)
                break;
            p = h + 1;
        }
        return c;
    }
    return ch_letter_to_sound(clean, out, cap);
}

/* American English allophones: flapping and velarised coda /l/ */
static void apply_allophones(ch_seg_t *s, int n)
{
    int i;
    for (i = 1; i < n - 1; i++) {
        int pv = en_is_vowel(s[i - 1].ipa) || streq(s[i - 1].ipa, "ɹ"), nv = en_is_vowel(s[i + 1].ipa);
        int next_stressed = s[i + 1].stress > 0, prev_word_end = s[i - 1].word_end;
        /* /t d/ between a vowel and an unstressed vowel -> tap, but not at the start of a word */
        if ((streq(s[i].ipa, "t") || streq(s[i].ipa, "d")) && pv && nv && !next_stressed && !prev_word_end)
            seg_set(&s[i], "ɾ");
    }
    for (i = 0; i < n; i++) {
        int next_vowel = i + 1 < n && en_is_vowel(s[i + 1].ipa);
        if (!streq(s[i].ipa, "l"))
            continue;
        /* coda /l/ (before a consonant, pause, or word end) is velarised */
        if (!next_vowel || s[i].word_end)
            seg_set(&s[i], "ɫ");
    }
}

/* numbers */
static const char *const ONES[20] = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
                                     "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
                                     "nineteen"};
static const char *const TENS[10] = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};

static void cat(char *d, size_t cap, const char *s)
{
    size_t n = strlen(d);
    while (*s && n + 1 < cap)
        d[n++] = *s++;
    d[n] = 0;
}

static void number_words(unsigned long v, char *d, size_t cap)
{
    if (v < 20) {
        cat(d, cap, ONES[v]);
    } else if (v < 100) {
        cat(d, cap, TENS[v / 10]);
        if (v % 10) {
            cat(d, cap, " ");
            cat(d, cap, ONES[v % 10]);
        }
    } else if (v < 1000) {
        cat(d, cap, ONES[v / 100]);
        cat(d, cap, " hundred");
        if (v % 100) {
            cat(d, cap, " ");
            number_words(v % 100, d, cap);
        }
    } else {
        number_words(v / 1000, d, cap);
        cat(d, cap, " thousand");
        if (v % 1000) {
            cat(d, cap, " ");
            number_words(v % 1000, d, cap);
        }
    }
}

/* digits -> words (" twenty one "); a million or more, digit by digit */
static void expand_numbers(const char *t, char *d, size_t cap)
{
    d[0] = 0;
    while (*t) {
        if (*t >= '0' && *t <= '9') {
            const char *s = t;
            unsigned long v = 0;
            int nd = 0;
            while (*t >= '0' && *t <= '9') {
                if (nd < 9)
                    v = v * 10 + (unsigned long)(*t - '0');
                nd++;
                t++;
            }
            cat(d, cap, " ");
            if (nd <= 6 && v < 1000000ul) {
                number_words(v, d, cap);
            } else {
                for (; s < t; s++) {
                    cat(d, cap, ONES[*s - '0']);
                    if (s + 1 < t)
                        cat(d, cap, " ");
                }
            }
            cat(d, cap, " ");
        } else {
            char c[2] = {*t++, 0};
            cat(d, cap, c);
        }
    }
}

static int is_phrase_punct(int c) { return c == '.' || c == '!' || c == '?' || c == ';' || c == ':' || c == ','; }

int ch_text_to_phrases(const char *text, ch_lookup_fn lookup, void *user, ch_seg_t *segs, int seg_cap, int *n_segs,
                       ch_phrase_t *ph, int ph_cap)
{
    static char norm[512];
    const char *p;
    int n = 0, np = 0, first = 0;
    expand_numbers(text, norm, sizeof norm);
    p = norm;
    for (;;) {
        /* the words up to the next run of phrase punctuation (or the end), then the run */
        char delim[8];
        int dl = 0;
        while (*p && !is_phrase_punct(*p)) {
            char w[48];
            int wl = 0;
            /* a word: letters, apostrophes and hyphens (lowercased) */
            int c = *p;
            int lc = c >= 'A' && c <= 'Z' ? c - 'A' + 'a' : c;
            if (!((lc >= 'a' && lc <= 'z') || lc == '\'' || lc == '-')) {
                p++;
                continue;
            }
            while (*p) {
                c = *p;
                lc = c >= 'A' && c <= 'Z' ? c - 'A' + 'a' : c;
                if (!((lc >= 'a' && lc <= 'z') || lc == '\'' || lc == '-'))
                    break;
                if (wl < (int)sizeof w - 1)
                    w[wl++] = (char)lc;
                p++;
            }
            w[wl] = 0;
            {
                int c0 = ch_word(w, 1, lookup, user, segs + n, seg_cap - n);
                if (c0 > 0) {
                    n += c0;
                    segs[n - 1].word_end = 1;
                }
            }
        }
        while (*p && is_phrase_punct(*p)) {
            if (dl < (int)sizeof delim - 1)
                delim[dl++] = *p;
            p++;
        }
        delim[dl] = 0;
        if (n > first && np < ph_cap) {
            ph[np].first = first;
            ph[np].n = n - first;
            apply_allophones(segs + first, n - first);
            ph[np].intonation = strchr(delim, '?') ? CH_INTON_QUESTION : CH_INTON_STATEMENT;
            ph[np].pause = (strchr(delim, '.') || strchr(delim, '!') || strchr(delim, '?')) ? R(0.35) : dl ? R(0.18) : R(0.3);
            np++;
            first = n;
        }
        if (!*p)
            break;
    }
    *n_segs = n;
    return np;
}

/* --------------------------------------------------------------------- durations.rs --- */
/* Klatt's inherent / minimum durations (ms) */
static int klatt_table(const char *ipa, ch_real *inh, ch_real *mn_)
{
    static const struct { const char *ipa; float inh, mn; } T[] = {
        {"i", 155, 55}, {"ɪ", 135, 40}, {"e", 190, 70}, {"eɪ", 190, 70}, {"ɛ", 150, 70}, {"æ", 230, 80}, {"a", 230, 80},
        {"ɑ", 240, 100}, {"ɒ", 240, 100}, {"ɔ", 240, 100}, {"o", 220, 80}, {"oʊ", 220, 80}, {"ʊ", 160, 60}, {"u", 210, 70},
        {"ʌ", 140, 60}, {"ə", 120, 60}, {"ɚ", 180, 80}, {"ɝ", 180, 80}, {"aɪ", 250, 150}, {"aʊ", 240, 100},
        {"ɔɪ", 280, 150}, {"ɨ", 110, 40},
        {"p", 85, 50}, {"t", 75, 50}, {"k", 80, 60}, {"b", 85, 60}, {"d", 75, 50}, {"g", 80, 60}, {"ʔ", 40, 30},
        {"tʃ", 70, 50}, {"dʒ", 50, 30}, {"f", 100, 80}, {"v", 60, 40}, {"θ", 90, 60}, {"ð", 50, 30}, {"s", 105, 60},
        {"z", 75, 40}, {"ʃ", 105, 80}, {"ʒ", 70, 40}, {"h", 80, 20}, {"m", 70, 60}, {"n", 60, 50}, {"ŋ", 95, 45},
        {"l", 80, 40}, {"ɫ", 80, 40}, {"ɹ", 80, 30}, {"w", 80, 60}, {"j", 80, 40}, {"ɾ", 20, 20}};
    unsigned i;
    for (i = 0; i < sizeof T / sizeof T[0]; i++)
        if (streq(T[i].ipa, ipa)) {
            *inh = (ch_real)T[i].inh;
            *mn_ = (ch_real)T[i].mn;
            return 1;
        }
    return 0;
}

typedef struct {
    uint8_t vowel, has_manner, manner, voiced;
    ch_real dur;                 /* NaN: none */
} seg_info_t;

static void klatt_durations(const ch_seg_t *s, int n, ch_real *out)
{
    static seg_info_t inf[CH_SEG_MAX];
    static int word[CH_SEG_MAX], word_start[CH_SEG_MAX], vowels_in_word[CH_SEG_MAX];
    int i, nw = 0, last_vowel = -1;
    for (i = 0; i < n; i++) {
        const ch_phon_t *d;
        memset(&inf[i], 0, sizeof inf[i]);
        inf[i].dur = CH_NAN;
        if (plan_is_vowel(s[i].ipa)) {
            inf[i].vowel = 1;
        } else {
            d = phoneme(s[i].ipa);
            inf[i].has_manner = d != 0;
            inf[i].manner = d ? d->manner : 0;
            inf[i].voiced = d && d->voiced;
            if (d)
                inf[i].dur = d->dur;
        }
        if (i == 0 || s[i - 1].word_end)
            word_start[nw++] = i;
        word[i] = nw - 1;
    }
    for (i = 0; i < nw; i++)
        vowels_in_word[i] = 0;
    for (i = 0; i < n; i++)
        if (inf[i].vowel) {
            vowels_in_word[word[i]]++;
            last_vowel = i;
        }
    for (i = 0; i < n; i++) {
        ch_real inh, mn_, p = 1, fallback = is_nan(inf[i].dur) ? R(0.08) : inf[i].dur;
        if (!is_nan(s[i].dur)) {
            out[i] = s[i].dur;
            continue;
        }
        if (!klatt_table(s[i].ipa, &inh, &mn_)) {
            inh = R(1000.0) * fallback;
            mn_ = R(600.0) * fallback;
        }
        if (inf[i].vowel) {
            int stress = s[i].stress < 0 ? 0 : s[i].stress, final_syl = i == last_vowel, word_final_syl = 1, k;
            ch_real d;
            for (k = i + 1; k < n && word[k] == word[i]; k++)
                if (inf[k].vowel)
                    word_final_syl = 0;
            p *= final_syl ? R(1.4) : R(0.6);              /* clause-final lengthening / non-final shortening */
            if (!word_final_syl)
                p *= R(0.85);                              /* non-word-final shortening */
            if (vowels_in_word[word[i]] > 1)
                p *= R(0.8);                               /* polysyllabic shortening */
            if (stress == 0) {
                mn_ *= R(0.5);                             /* unstressed: minimum halved, shortened */
                p *= R(0.7);
            }
            /* the following consonant (same word): voiced fricatives lengthen, voiceless stops shorten */
            if (i + 1 < n && word[i + 1] == word[i] && !inf[i + 1].vowel) {
                const seg_info_t *nx = &inf[i + 1];
                ch_real f = 1;
                if (nx->has_manner && nx->manner == CH_M_FRICATIVE)
                    f = nx->voiced ? R(1.6) : R(1.0);
                else if (nx->has_manner && (nx->manner == CH_M_STOP || nx->manner == CH_M_AFFRICATE))
                    f = nx->voiced ? R(1.2) : R(0.7);
                else if (nx->has_manner && nx->manner == CH_M_NASAL)
                    f = R(0.85);
                p *= final_syl ? f : R(0.7) + R(0.3) * f;
            }
            if (i + 1 < n && inf[i + 1].vowel)
                p *= R(1.2);
            if (i > 0 && inf[i - 1].vowel)
                p *= R(0.7);
            d = mn_ + (inh - mn_) * p;
            /* aspiration after a voiceless stop eats into a stressed vowel's onset: give it back */
            if (stress > 0 && i > 0 && inf[i - 1].has_manner && inf[i - 1].manner == CH_M_STOP && !inf[i - 1].voiced)
                d += R(20.0);
            out[i] = d / R(1000.0);
            continue;
        }
        /* consonants */
        if (i > last_vowel && inf[i].has_manner
            && (inf[i].manner == CH_M_NASAL || inf[i].manner == CH_M_LATERAL || inf[i].manner == CH_M_APPROXIMANT))
            p *= R(1.4);
        if (i != word_start[word[i]])
            p *= R(0.85);
        {
            int prev_c = i > 0 && !inf[i - 1].vowel, next_c = i + 1 < n && !inf[i + 1].vowel;
            if (prev_c && next_c)
                p *= R(0.5);
            else if (prev_c || next_c)
                p *= R(0.7);
        }
        out[i] = (mn_ + (inh - mn_) * p) / R(1000.0);
    }
}

/* ----------------------------------------------------------------------- planner.rs --- */
#define DEFAULT_VOT R(0.05)
#define PLACED_CLOSURE_DELAY R(0.025)

static ch_gesture_t *gesture(ch_plan_t *o, ch_real t0, ch_real t1, ch_real ri, ch_real ro, ch_real strength)
{
    static ch_gesture_t spare;   /* (one that does not fit is built here and counted) */
    ch_gesture_t *g;
    int k;
    if (o->n < o->cap) {
        g = &o->g[o->n++];
    } else {
        o->dropped++;
        g = &spare;
    }
    g->t0 = t0;
    g->t1 = t1;
    g->ramp_in = ri;
    g->ramp_out = ro;
    g->strength = strength;
    g->speed = g->release_hold = g->tag = CH_NAN;
    for (k = 0; k < CH_NCONTROL; k++)
        g->targets[k] = CH_NAN;
    return g;
}

static void one(ch_plan_t *o, ch_real t0, ch_real t1, ch_real ri, ch_real ro, ch_real strength, int key, ch_real x)
{
    gesture(o, t0, t1, ri, ro, strength)->targets[key] = x;
}

static int place_rank(const char *p)
{
    static const char *const P[] = {"bilabial", "labiodental", "dental", "alveolar", "postalveolar", "retroflex", "palatal",
                                    "velar", "uvular"};
    int i;
    for (i = 0; i < 9; i++)
        if (streq(P[i], p))
            return i;
    return -1;
}

static int closes_in_front(const char *front, const char *back)
{
    int f = place_rank(front), b = place_rank(back);
    return f >= 0 && b >= 0 && f < b;
}

typedef struct {
    const char *ipa;
    ch_real dur, f0;
    int8_t stress;
    const ch_phon_t *def;
    const ch_real *vowel;
    uint8_t glide_from;
} pseg_t;

/* a stop that closes behind the closure of the stop before it, its articulators already set by the consonant
 * before that one (or a rhotic vowel's raised tip) */
static int pre_placed_behind_closure(const pseg_t *s, int i)
{
    const ch_phon_t *def = s[i].def, *prev = i > 0 ? s[i - 1].def : 0;
    const ch_real *tg;
    int k;
    if (!def || !prev || def->manner != CH_M_STOP || prev->manner != CH_M_STOP || !closes_in_front(prev->place, def->place) || i < 2)
        return 0;
    if (s[i - 2].def)
        tg = s[i - 2].def->t;
    else if (s[i - 2].vowel && !is_nan(s[i - 2].vowel[CH_TT_CURL]) && s[i - 2].vowel[CH_TT_CURL] > R(0.05))
        tg = s[i - 2].vowel;
    else
        return 0;
    for (k = 0; k < CH_NARTIC; k++)
        if ((def->dominant & BIT(k)) && is_nan(tg[k]))
            return 0;
    return 1;
}

/* a consonant constriction held acoustically from a to b: a dominant gesture on its own articulators, a weak one on
 * the rest; movement latencies compensated so the acoustic closure and release land on a and b */
static void oral_gestures(const ch_phon_t *def, const ch_real *tg, ch_real a, ch_real b, ch_real speed, ch_plan_t *o)
{
    ch_real dom[CH_NARTIC], sec[CH_NARTIC], sp, lead, early, place = CH_NAN;
    int k, has_dom = 0, has_sec = 0, body_only = 1, lips_only = 1, closing, alveolar;
    for (k = 0; k < CH_NARTIC; k++) {
        dom[k] = sec[k] = CH_NAN;
        if (is_nan(tg[k]))
            continue;
        if (def->dominant & BIT(k)) {
            dom[k] = tg[k];
            has_dom = 1;
        } else {
            sec[k] = tg[k];
            has_sec = 1;
        }
    }
    for (k = 0; k < CH_NARTIC; k++) {
        if (!(def->dominant & BIT(k)))
            continue;
        if (k != CH_TB_X && k != CH_TB_Y && k != CH_ROOT)
            body_only = 0;
        if (k != CH_LIP_AP && k != CH_LABIODENTAL)
            lips_only = 0;
    }
    /* the dorsum is heavy: its releases are slower than tip and lip releases */
    sp = body_only ? R(1.0) + (speed - R(1.0)) * R(0.35) : speed;
    closing = def->manner == CH_M_STOP || def->manner == CH_M_NASAL || def->manner == CH_M_AFFRICATE;
    lead = closing ? (body_only ? R(0.034) : lips_only ? R(0.019) : R(0.02)) : 0;
    early = closing ? (body_only ? R(0.024) : lips_only ? R(0.022) : R(0.031)) : R(0.008);
    if (has_dom) {
        ch_gesture_t *g = gesture(o, a - early, mx(a + R(0.01), b - lead), R(0.03), body_only ? R(0.03) : R(0.018), R(40.0));
        for (k = 0; k < CH_NARTIC; k++)
            g->targets[k] = dom[k];
        g->speed = sp;
        g->release_hold = R(0.045);
    }
    /* alveolars front the tongue body during the closure (the alveolar F2 locus) */
    alveolar = streq(def->place, "alveolar") && is_nan(tg[CH_TB_X])
               && (def->manner == CH_M_STOP || def->manner == CH_M_NASAL || def->manner == CH_M_FRICATIVE
                   || def->manner == CH_M_AFFRICATE || def->manner == CH_M_TAP);
    if (alveolar) {
        ch_gesture_t *g = gesture(o, a, b, R(0.05), R(0.05), R(1.2));
        g->targets[CH_TB_X] = R(3.2);
        g->targets[CH_TB_Y] = R(7.4);
    }
    if (has_sec) {
        /* a velar's front-back place follows the vowel: its own tbX only weakly */
        if (body_only && !is_nan(sec[CH_TB_X])) {
            place = sec[CH_TB_X];
            sec[CH_TB_X] = CH_NAN;
            has_sec = 0;
            for (k = 0; k < CH_NARTIC; k++)
                if (!is_nan(sec[k]))
                    has_sec = 1;
        }
        if (has_sec) {
            ch_gesture_t *g = gesture(o, a, b, R(0.05), R(0.05), R(2.2));
            for (k = 0; k < CH_NARTIC; k++)
                g->targets[k] = sec[k];
        }
        if (!is_nan(place))
            one(o, a, b, R(0.05), R(0.05), R(0.25), CH_TB_X, place);
    }
}

/* the beatbox's oral (beatbox.rs `oral`): the consonant's gestures with `n` extra targets over its own; returns where
 * they start in the plan (nothing added: no such phoneme) */
int ch_oral(const char *ipa, ch_real a, ch_real b, ch_real speed, const int *keys, const ch_real *vals, int n, ch_plan_t *o)
{
    const ch_phon_t *d = phoneme(ipa);
    ch_real tg[CH_NARTIC];
    int k, start = o->n;
    if (!d)
        return start;
    for (k = 0; k < CH_NARTIC; k++)
        tg[k] = d->t[k];
    for (k = 0; k < n; k++)
        tg[keys[k]] = vals[k];
    oral_gestures(d, tg, a, b, speed, o);
    return start;
}

const ch_real *ch_vowel_artic(const char *ipa) { return vowel_artic(ipa); }

void ch_plan_defaults(ch_plan_opts_t *o)
{
    o->t0 = 0;
    o->rate = R(1.0);
    o->f0 = R(110.0);
    o->accent = R(0.14);
    o->intonation = CH_INTON_STATEMENT;
    o->open_end = 0;
    o->sung = 0;
}

static ch_real pitch_ramp(ch_real dur) { return mn(R(0.06), mx(R(0.008), R(0.5) * dur)); }

void ch_plan_utterance(const ch_seg_t *input, int n_in, const ch_plan_opts_t *o, ch_plan_t *out)
{
    static ch_real durs[CH_SEG_MAX], t0s[CH_SEG_MAX * 2], t1s[CH_SEG_MAX * 2];
    static pseg_t segs[CH_SEG_MAX * 2];
    static int v_idx[CH_SEG_MAX * 2];
    const ch_real rate = o->rate;
    ch_real t, end, f0, accent, total;
    int n = 0, nv = 0, i, si, nuclear = -1;
    if (n_in > CH_SEG_MAX)
        n_in = CH_SEG_MAX;
    out->exhale = -1;
    /* durations by the Klatt rules, on whole segments (diphthongs before they are split) */
    klatt_durations(input, n_in, durs);
    /* expand diphthongs into two vowel targets sharing the duration */
    for (si = 0; si < n_in; si++) {
        const char *a, *b;
        int parts = expand(input[si].ipa, &a, &b), p;
        if (parts) {
            for (p = 0; p < parts; p++) {
                pseg_t *s = &segs[n++];
                s->ipa = p ? b : a;
                s->dur = parts > 1 ? durs[si] * (p == 0 ? R(0.55) : R(0.45)) : durs[si];
                s->stress = input[si].stress;
                s->f0 = input[si].f0;
                s->glide_from = (uint8_t)(p > 0);
            }
        } else {
            pseg_t *s = &segs[n++];
            s->ipa = input[si].ipa;
            s->dur = durs[si];
            s->stress = input[si].stress;
            s->f0 = input[si].f0;
            s->glide_from = 0;
        }
    }
    for (i = 0; i < n; i++) {
        segs[i].vowel = vowel_artic(segs[i].ipa);
        segs[i].def = segs[i].vowel ? 0 : phoneme(segs[i].ipa);
    }
    t = o->t0 + R(0.06);   /* breath onset lead */
    for (i = 0; i < n; i++) {
        ch_real d = segs[i].dur / rate;
        t0s[i] = t;
        t1s[i] = t + d;
        t += d;
        if (segs[i].vowel)
            v_idx[nv++] = i;
    }
    end = t;
    /* vowel gestures: overlap neighbours so the tongue travels vowel-to-vowel through the consonant (Öhman) */
    for (si = 0; si < nv; si++) {
        int k;
        ch_real prev, next, r_in, r_out;
        ch_gesture_t *g;
        i = v_idx[si];
        prev = si > 0 ? t1s[v_idx[si - 1]] : t0s[i] - R(0.08);
        next = si < nv - 1 ? t0s[v_idx[si + 1]] : t1s[i] + R(0.08);
        r_in = mx(R(0.03), R(0.75) * (t0s[i] - prev)) + R(0.01);
        r_out = mx(R(0.03), R(0.4) * (next - t1s[i])) + R(0.01);
        g = gesture(out, t0s[i], t1s[i], r_in, r_out, R(1.0));
        for (k = 0; k < CH_NARTIC; k++)
            g->targets[k] = segs[i].vowel[k];
    }
    /* consonant gestures */
    for (i = 0; i < n; i++) {
        const pseg_t *s = &segs[i];
        const ch_phon_t *def = s->def;
        const ch_real a0 = t0s[i], a1 = t1s[i], dur = a1 - a0;
        int next_vowel;
        if (!def)
            continue;
        next_vowel = i < n - 1 && segs[i + 1].vowel;
        switch (def->manner) {
        case CH_M_STOP:
            if (pre_placed_behind_closure(segs, i)) {
                oral_gestures(def, def->t, a0 + mn(PLACED_CLOSURE_DELAY, mx(0, dur - R(0.03))), a1, R(1.55), out);
                break;
            }
            /* fall through */
        case CH_M_NASAL:
            oral_gestures(def, def->t, a0, a1, R(1.55), out);
            break;
        case CH_M_TAP:
            oral_gestures(def, def->t, a0, a1, R(2.8), out);
            break;
        case CH_M_TRILL: {   /* driven trill: rapid alternation of near-closure and release at ~26 Hz */
            ch_real nn = mx(R(2.0), js_round(dur * R(26.0))), k;
            for (k = 0; k < nn; k += 1) {
                ch_real a = a0 + (k / nn) * dur;
                oral_gestures(def, def->t, a, a + R(0.35) / R(26.0), R(3.2), out);
            }
            break;
        }
        case CH_M_AFFRICATE: {
            ch_real split = a0 + dur * R(0.55);
            const ch_phon_t *rel = def->release ? phoneme(def->release) : 0;
            oral_gestures(def, def->t, a0, split, R(2.0), out);
            if (rel)
                oral_gestures(def, rel->t, split + R(0.008), a1, R(1.4), out);
            break;
        }
        case CH_M_FRICATIVE:
        case CH_M_APPROXIMANT:
        case CH_M_LATERAL:
            oral_gestures(def, def->t, a0, a1, R(1.3), out);
            break;
        default:
            break;
        }
        /* nasality: the velum lowers in anticipation and seals again by the end of the nasal */
        if (def->nasal)
            one(out, a0 - R(0.02), a1 - R(0.02), R(0.05), R(0.03), R(4.0), CH_VELUM, R(0.45));
        /* phonation */
        if (def->glottal == CH_G_OPEN) {
            one(out, a0 - R(0.01), a1, R(0.03), R(0.035), R(5.0), CH_ABDUCTION, R(0.33));
        } else if (def->glottal == CH_G_BREATHY) {
            one(out, a0, a1, R(0.03), R(0.03), R(5.0), CH_ABDUCTION, R(0.1));
        } else if (def->glottal == CH_G_STOP) {
            one(out, a0, a1, R(0.012), R(0.015), R(8.0), CH_CLOSURE, R(1.0));
        } else if (!def->voiced) {
            if (def->manner == CH_M_STOP || def->manner == CH_M_AFFRICATE) {
                /* English's rule: aspirated when syllable-initial before a vowel and not after /s/ */
                int aspirated = next_vowel && !(i > 0 && streq(segs[i - 1].ipa, "s"));
                ch_real past = aspirated ? DEFAULT_VOT / rate * R(0.4) : R(0.012) * R(0.4);
                one(out, a0 + R(0.25) * dur, a1 + past, R(0.035), aspirated ? R(0.045) : R(0.02), R(6.0), CH_ABDUCTION,
                    aspirated ? R(0.36) : R(0.22));
            } else {
                one(out, a0 - R(0.015), a1 + R(0.005), R(0.03), R(0.03), R(6.0), CH_ABDUCTION, R(0.3));
            }
        } else if (def->manner == CH_M_FRICATIVE) {
            one(out, a0, a1, R(0.03), R(0.03), R(3.0), CH_ABDUCTION, R(0.035));
        }
    }
    /* breath: on through the utterance (a sung phrase reaches full drive as its first segment starts) */
    one(out, o->t0 + (o->sung ? R(0.06) : R(0.04)), end, R(0.04), o->open_end ? R(0.07) : R(0.05), R(1.0), CH_BREATH, R(1.0));
    /* end of the breath group: the folds part as the breath stops */
    if (!o->open_end && n > 0) {
        const pseg_t *l = &segs[n - 1];
        int sonorant = l->vowel || (l->def && (l->def->manner == CH_M_NASAL || l->def->manner == CH_M_APPROXIMANT
                                               || l->def->manner == CH_M_LATERAL || l->def->manner == CH_M_TAP
                                               || l->def->manner == CH_M_TRILL));
        ch_real at = end + (sonorant ? R(0.045) : R(0.015));
        out->exhale = out->n;
        one(out, at, end + R(0.1), R(0.03), R(0.06), R(6.0), CH_ABDUCTION, R(0.22));
    }
    /* intonation: pitch targets on vowels; the nuclear accent is the last primary-stressed vowel */
    f0 = o->f0;
    accent = o->accent;
    total = mx(R(0.2), end - o->t0);
    for (si = 0; si < nv; si++)
        if (segs[v_idx[si]].stress == 1 && !segs[v_idx[si]].glide_from)
            nuclear = v_idx[si];
    for (si = 0; si < nv; si++) {
        const pseg_t *s;
        ch_real a0, a1, hold, target, r;
        int has_f0;
        i = v_idx[si];
        s = &segs[i];
        a0 = t0s[i];
        a1 = t1s[i];
        /* pitch holds through the consonants up to the next vowel */
        hold = si < nv - 1 ? mx(a1, t0s[v_idx[si + 1]] - R(0.03)) : a1;
        has_f0 = s->f0 != 0 && !is_nan(s->f0);
        if (has_f0) {
            target = s->f0;
        } else {
            ch_real prog = (a0 - o->t0) / total, decl = R(1.0) - R(0.1) * prog, acc;
            int is_nuclear = i == nuclear, last = si == nv - 1;
            acc = s->stress == 1 ? R(1.0) + (is_nuclear ? accent * R(1.8) : accent) : s->stress == 2 ? R(1.0) + accent * R(0.5) : R(1.0);
            target = f0 * decl * acc;
            if (last && o->intonation == CH_INTON_QUESTION)
                target = f0 * R(1.35);
            if (last && o->intonation == CH_INTON_STATEMENT) {
                if (is_nuclear && a1 - a0 > R(0.08)) {
                    /* accent peak early in the vowel, then the fall within it */
                    one(out, a0, a0 + R(0.35) * (a1 - a0), R(0.05), R(0.05), R(1.0), CH_F0, target);
                    one(out, a0 + R(0.55) * (a1 - a0), a1, R(0.06), R(0.06), R(1.0), CH_F0, f0 * R(0.82));
                    continue;
                }
                target = f0 * R(0.82);
            }
        }
        r = has_f0 && o->sung ? pitch_ramp(a1 - a0) : R(0.06);
        one(out, a0, has_f0 ? a1 : hold, r, r, has_f0 ? R(6.0) : R(1.0), CH_F0, target);
    }
    /* sung: a consonant given its note's pitch holds it */
    if (o->sung)
        for (i = 0; i < n; i++)
            if (!segs[i].vowel && segs[i].f0 != 0 && !is_nan(segs[i].f0)) {
                ch_real r = pitch_ramp(t1s[i] - t0s[i]);
                one(out, t0s[i], t1s[i], r, r, R(6.0), CH_F0, segs[i].f0);
            }
    out->end = end;
    if (n > 0) {
        out->last_t0 = t0s[n - 1];
        out->last_t1 = t1s[n - 1];
    }
}

void ch_plan_phrases(const ch_seg_t *segs, const ch_phrase_t *ph, int n_ph, const ch_plan_opts_t *o, ch_plan_t *out)
{
    ch_plan_opts_t po = *o;
    ch_real t = o->t0;
    int i;
    out->end = o->t0;
    for (i = 0; i < n_ph; i++) {
        po.t0 = t;
        po.intonation = ph[i].intonation;
        ch_plan_utterance(segs + ph[i].first, ph[i].n, &po, out);
        t = out->end + ph[i].pause;
    }
}

/* --------------------------------------------------------------------- syllables.rs --- */
static int is_onset(const ch_seg_t *c, int n)
{
    static const char *const ONSETS[] = {
        "p", "b", "t", "d", "k", "g", "f", "v", "θ", "ð", "s", "z", "ʃ", "ʒ", "h", "m", "n", "l", "ɹ", "w", "j", "tʃ", "dʒ",
        "pl", "bl", "kl", "gl", "fl", "sl", "pɹ", "bɹ", "tɹ", "dɹ", "kɹ", "gɹ", "fɹ", "θɹ", "ʃɹ",
        "sp", "st", "sk", "sm", "sn", "sw", "sf", "tw", "kw", "dw", "gw", "θw",
        "pj", "bj", "kj", "gj", "mj", "nj", "fj", "vj", "hj", "lj",
        "spl", "spɹ", "stɹ", "skɹ", "skw", "skj", "spj"};
    char b[32] = "";
    unsigned i;
    int k;
    for (k = 0; k < n; k++)
        cat(b, sizeof b, c[k].ipa);
    for (i = 0; i < sizeof ONSETS / sizeof ONSETS[0]; i++)
        if (streq(ONSETS[i], b))
            return 1;
    return 0;
}

/* one word's segments -> onset / nucleus / coda syllables, by the maximal onset */
static int syllabify(const ch_seg_t *s, int n, ch_syl_t *out, int cap)
{
    static int vi[CH_SEG_MAX];
    int nv = 0, i, start = 0, ns = 0;
    for (i = 0; i < n; i++)
        if (plan_is_vowel(s[i].ipa))
            vi[nv++] = i;
    for (i = 0; i < nv && ns < cap; i++) {
        int v = vi[i], end = n, k = 0, len, j;
        ch_syl_t *y = &out[ns++];
        if (i + 1 < nv) {
            int next = vi[i + 1], nb = next - v - 1;
            for (len = nb; len >= 1; len--)
                if (is_onset(s + next - len, len)) {
                    k = len;
                    break;
                }
            end = next - k;
        }
        memset(y, 0, sizeof *y);
        for (j = start; j < v && y->n_onset < CH_ONSET_MAX; j++)
            y->onset[y->n_onset++] = s[j];
        y->nucleus = s[v];
        for (j = v + 1; j < end && y->n_coda < CH_CODA_MAX; j++)
            y->coda[y->n_coda++] = s[j];
        start = end;
    }
    return ns;
}

static int sv(int c) { return c && strchr("aeiouy", c >= 'A' && c <= 'Z' ? c - 'A' + 'a' : c) != 0; }
static int lower_c(int c) { return c >= 'A' && c <= 'Z' ? c - 'A' + 'a' : c; }

/* a written word split into n chunks at its vowel groups (approximate hyphenation) */
static void split_spelling(const char *w, int n, char (*out)[CH_SYL_TEXT])
{
    int groups[CH_SYL_MAX][2], ng = 0, i = 0, len = (int)strlen(w), cuts[CH_SYL_MAX + 1], nc = 0, silent_e, le;
    if (n <= 1) {
        strncpy(out[0], w, CH_SYL_TEXT - 1);
        out[0][CH_SYL_TEXT - 1] = 0;
        return;
    }
    while (i < len) {
        if (sv(w[i])) {
            int s = i;
            while (i < len && sv(w[i]))
                i++;
            if (ng < CH_SYL_MAX) {
                groups[ng][0] = s;
                groups[ng][1] = i;
                ng++;
            }
        } else {
            i++;
        }
    }
    silent_e = len >= 2 && lower_c(w[len - 1]) == 'e' && !sv(w[len - 2]);
    le = len >= 3 && lower_c(w[len - 1]) == 'e' && lower_c(w[len - 2]) == 'l' && !sv(w[len - 3]) && lower_c(w[len - 3]) != 'l';
    /* a final silent e ("like") is not a syllable, but consonant + "le" is ("twin-kle") */
    if (ng > 1 && silent_e && !le && groups[ng - 1][0] + 1 == len)
        ng--;
    if (ng < n) {   /* fewer written vowels than sung syllables ("fire"): hold the word, then continue marks */
        strncpy(out[0], w, CH_SYL_TEXT - 1);
        out[0][CH_SYL_TEXT - 1] = 0;
        for (i = 1; i < n; i++)
            strcpy(out[i], "~");
        return;
    }
    while (ng > n) {
        groups[ng - 2][1] = groups[ng - 1][1];
        ng--;
    }
    cuts[nc++] = 0;
    for (i = 0; i < ng - 1; i++) {
        int a = groups[i][1], b = groups[i + 1][0], gap = b - a, dig = 0;
        if (gap >= 2) {
            static const char *const D[] = {"th", "sh", "ch", "ph", "wh", "ck", "gh"};
            unsigned k;
            for (k = 0; k < 7; k++)
                if (lower_c(w[b - 2]) == D[k][0] && lower_c(w[b - 1]) == D[k][1])
                    dig = 1;
        }
        /* a digraph stays together on the right; otherwise one consonant goes right, longer runs split */
        cuts[nc++] = dig ? b - 2 : a + (gap <= 1 ? 0 : gap / 2);
    }
    cuts[nc++] = len;
    for (i = 0; i < nc - 1; i++) {
        int l = cuts[i + 1] - cuts[i];
        if (l > CH_SYL_TEXT - 1)
            l = CH_SYL_TEXT - 1;
        memcpy(out[i], w + cuts[i], (size_t)l);
        out[i][l] = 0;
    }
}

static int is_space_c(int c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\v' || c == '\f'; }
static int is_lyric_punct(int c) { return c == '.' || c == ',' || c == ';' || c == ':' || c == '!' || c == '?' || c == '\n'; }
static int en_starts(int c) { return (lower_c(c) >= 'a' && lower_c(c) <= 'z') || c == '\''; }

int ch_lyrics(const char *text, ch_lookup_fn lookup, void *user, ch_syl_t *out, int cap)
{
    int n = 0, i = 0, len = (int)strlen(text);
    while (i < len) {
        int c = (unsigned char)text[i];
        if (en_starts(c)) {
            char tok[48], part[48];
            int tl = 0, p = 0, nparts = 0, k;
            while (i < len && (en_starts(text[i]) || text[i] == '-')) {
                if (tl < (int)sizeof tok - 1)
                    tok[tl++] = text[i];
                i++;
            }
            tok[tl] = 0;
            for (k = 0; k < tl; k++)   /* typed hyphens split syllables explicitly ("ti-ger") */
                if (tok[k] != '-' && (k == 0 || tok[k - 1] == '-'))
                    nparts++;
            while (p < tl) {
                static ch_seg_t ws[32];
                static ch_syl_t sy[16];
                static char spell[16][CH_SYL_TEXT];
                int pl = 0, nw, ns, j;
                while (p < tl && tok[p] == '-')
                    p++;
                if (p >= tl)
                    break;
                while (p < tl && tok[p] != '-')
                    part[pl++] = (char)lower_c(tok[p++]);
                part[pl] = 0;
                nw = ch_word(part, 0, lookup, user, ws, 32);
                ns = syllabify(ws, nw, sy, 16);
                if (nparts > 1) {
                    /* the part as typed (its case), then continue marks */
                    int q = 0, s0 = p - pl;
                    for (q = 0; q < pl && q < CH_SYL_TEXT - 1; q++)
                        spell[0][q] = tok[s0 + q];
                    spell[0][q] = 0;
                    for (j = 1; j < ns; j++)
                        strcpy(spell[j], "~");
                } else {
                    split_spelling(tok, ns, spell);
                }
                for (j = 0; j < ns && n < cap; j++) {
                    out[n] = sy[j];
                    strcpy(out[n].text, spell[j]);
                    n++;
                }
            }
            if (n > 0)
                out[n - 1].word_end = 1;
            continue;
        }
        /* a lone "~" or "-" holds the previous vowel onto the next note (melisma) */
        if ((c == '~' || c == '-') && (i == 0 || is_space_c(text[i - 1])) && (i + 1 == len || is_space_c(text[i + 1]))) {
            if (n > 0 && n < cap) {
                ch_syl_t *s = &out[n];
                memset(s, 0, sizeof *s);
                strcpy(s->text, "~");
                s->nucleus = out[n - 1].nucleus;
                s->melisma = 1;
                s->word_end = out[n - 1].word_end;
                n++;
            }
        } else if (is_lyric_punct(c) && n > 0) {
            out[n - 1].phrase_end = 1;
        }
        i++;
    }
    return n;
}

/* -------------------------------------------------------------------------- sing.rs --- */
#define LIVE_T0 R(-0.045)   /* live: gestures relative to now (the planner's 60 ms lead mostly taken back) */
#define HOLD R(30.0)        /* a held vowel lasts at most this long (s) */

/* CHIMERA's vowel pad (src/ui/analysis.ts articForFormants): the articulation for F1, F2, the calibrated vowels nearest
 * (log-formant distance, F1 counted 2.2 times: its differences are perceptually larger) blended by 1 / (d + 0.02)^3,
 * the four nearest; a vowel without a key gives the neutral posture's */
void ch_vowel_pad(ch_real f1, ch_real f2, ch_real *out)
{
    static const int KEYS[9] = {CH_TB_X, CH_TB_Y, CH_JAW, CH_LIP_AP, CH_LIP_PROT, CH_LIP_WIDTH, CH_ROOT, CH_LARYNX, CH_TT_CURL};
    int top[4] = {-1, -1, -1, -1}, i, j, k;
    ch_real w[4] = {0, 0, 0, 0}, sum = 0;
    for (i = 0; i < NVOW; i++) {
        ch_real d1 = LOG(f1 / CH_VOWELS[i].f1) * R(2.2), d2 = LOG(f2 / CH_VOWELS[i].f2), d, wi;
        if (CH_VOWELS[i].f1 <= 0)
            continue;
        d = SQRT(d1 * d1 + d2 * d2);
        wi = R(1.0) / ((d + R(0.02)) * (d + R(0.02)) * (d + R(0.02)));
        for (j = 0; j < 4; j++)           /* keep the four heaviest, heaviest first */
            if (top[j] < 0 || wi > w[j]) {
                for (k = 3; k > j; k--) {
                    top[k] = top[k - 1];
                    w[k] = w[k - 1];
                }
                top[j] = i;
                w[j] = wi;
                break;
            }
    }
    for (j = 0; j < 4; j++)
        if (top[j] >= 0)
            sum += w[j];
    for (k = 0; k < 9 && sum > 0; k++) {
        ch_real acc = 0;
        for (j = 0; j < 4; j++) {
            ch_real x;
            if (top[j] < 0)
                continue;
            x = CH_VOWELS[top[j]].t[KEYS[k]];
            acc += (x != x ? CH_NEUTRAL[KEYS[k]] : x) * w[j];
        }
        out[KEYS[k]] = acc / sum;
    }
}

int ch_vowel_formants(const char *ipa, ch_real *f1, ch_real *f2)
{
    int i;
    for (i = 0; i < NVOW; i++)
        if (streq(CH_VOWELS[i].ipa, ipa)) {
            *f1 = CH_VOWELS[i].f1;
            *f2 = CH_VOWELS[i].f2;
            return 1;
        }
    return 0;
}

const char *ch_vowel_name(const ch_real *x)
{
    static const char *const V[][2] = {{"i", "IY"}, {"ɪ", "IH"}, {"e", "EY"}, {"ɛ", "EH"}, {"æ", "AE"}, {"ɑ", "AA"},
                                       {"ɔ", "AO"}, {"o", "OW"}, {"ʊ", "UH"}, {"u", "UW"}, {"ʌ", "AH"}, {"ə", "AX"},
                                       {"ɝ", "ER"}};
    const char *best = "AX";
    ch_real bd = R(1e9);
    unsigned i;
    for (i = 0; i < sizeof V / sizeof V[0]; i++) {
        const ch_real *t = vowel_artic(V[i][0]);
        ch_real dx, dy, dr, d;
        if (!t)
            continue;
        dx = x[CH_TB_X] - t[CH_TB_X];
        dy = x[CH_TB_Y] - t[CH_TB_Y];
        dr = x[CH_LIP_PROT] - t[CH_LIP_PROT];
        d = dx * dx + dy * dy + R(0.5) * dr * dr;
        if (d < bd) {
            bd = d;
            best = V[i][1];
        }
    }
    return best;
}

ch_real ch_midi_hz(ch_real m) { return R(440.0) * POW(R(2.0), (m - R(69.0)) / R(12.0)); }

/* sung consonants: a little shorter than spoken ones */
static int consonants(const ch_seg_t *c, int n, ch_real k, ch_seg_t *out)
{
    int i;
    for (i = 0; i < n; i++) {
        const ch_phon_t *d = phoneme(c[i].ipa);
        seg_init(&out[i], c[i].ipa);
        out[i].dur = is_nan(c[i].dur) ? (d ? d->dur : R(0.07)) * k : c[i].dur;
    }
    return n;
}

static void nucleus_part(ch_seg_t *s, const char *ipa, ch_real dur, ch_real hz)
{
    seg_init(s, ipa);
    s->dur = dur;
    s->f0 = hz;
    s->stress = 1;
}

static int diphthong_parts(const char *ipa, const char **a, const char **b) { return expand(ipa, a, b) == 2; }

/* what follows a held vowel: the diphthong's glide (at the note's pitch) and the coda */
static int tail(const ch_syl_t *syl, ch_real hz, ch_seg_t *out)
{
    const char *a, *b;
    int n = 0;
    if (diphthong_parts(syl->nucleus.ipa, &a, &b))
        nucleus_part(&out[n++], b, R(0.07), hz);
    n += consonants(syl->coda, syl->n_coda, R(0.7), out + n);
    return n;
}

void ch_plan_note_on(const ch_syl_t *syl, ch_real midi, ch_real vibrato, ch_real tag, const ch_syl_t *legato_from,
                     ch_real legato_midi, ch_plan_t *out)
{
    ch_seg_t segs[CH_ONSET_MAX + CH_CODA_MAX + 4];
    ch_plan_opts_t o;
    const ch_real hz = ch_midi_hz(midi);
    const char *a, *b;
    int n = 0, first = out->n, i;
    ch_gesture_t *g;
    if (legato_from)
        n += tail(legato_from, ch_midi_hz(legato_midi), segs);
    n += consonants(syl->onset, syl->n_onset, R(0.7), segs + n);
    nucleus_part(&segs[n++], diphthong_parts(syl->nucleus.ipa, &a, &b) ? a : syl->nucleus.ipa, HOLD, hz);
    ch_plan_defaults(&o);
    o.t0 = LIVE_T0;
    o.f0 = hz;
    o.intonation = CH_INTON_FLAT;
    o.open_end = 1;
    ch_plan_utterance(segs, n, &o, out);
    for (i = first; i < out->n; i++)
        out->g[i].tag = is_nan(out->g[i].targets[CH_F0]) ? tag : tag + R(1.0);
    /* vibrato blooms a quarter of a second into the held vowel */
    g = gesture(out, out->last_t0 + R(0.22), out->last_t1, R(0.3), R(0.06), R(4.0));
    g->targets[CH_VIBRATO] = vibrato;
    g->tag = tag + R(1.0);
}

void ch_plan_melisma(ch_real midi, ch_real vibrato, ch_real tag, ch_plan_t *out)
{
    ch_gesture_t *g = gesture(out, 0, HOLD, R(0.05), R(0.06), R(6.0));
    g->targets[CH_F0] = ch_midi_hz(midi);
    g->tag = tag + R(1.0);
    g = gesture(out, R(0.22), HOLD, R(0.3), R(0.06), R(4.0));
    g->targets[CH_VIBRATO] = vibrato;
    g->tag = tag + R(1.0);
}

void ch_plan_note_off(const ch_syl_t *syl, ch_real midi, ch_plan_t *out)
{
    ch_seg_t segs[CH_CODA_MAX + 2];
    ch_plan_opts_t o;
    const ch_real hz = ch_midi_hz(midi);
    int n = tail(syl, hz, segs);
    if (!n)
        return;
    ch_plan_defaults(&o);
    o.t0 = LIVE_T0;
    o.f0 = hz;
    o.intonation = CH_INTON_FLAT;
    ch_plan_utterance(segs, n, &o, out);
}

/* ------------------------------------------------------------------ plan_song (a phrase) --- */
/* sing.rs plan_song, a phrase at a time (ch_song.h): its onsets and codas sung (a sung consonant 0.8 of its spoken
 * length, at its note's pitch), squeezed when a note is too short (fit), each vowel on its beat, the phrase's last one
 * held to its end less a breath; one utterance for the phrase, its vibrato on the vowels held over 0.3 s */
#include "ch_song.h"
#define MIN_VOWEL R(0.05)
#define MIN_CONSONANT R(0.012)

static ch_real seg_total(const ch_seg_t *s, int n)
{
    ch_real t = 0;
    int i;
    for (i = 0; i < n; i++)
        t += is_nan(s[i].dur) ? 0 : s[i].dur;
    return t;
}

/* the consonants between a vowel and the next one, into `span` (a vowel keeps MIN_VOWEL) */
static void song_fit(ch_seg_t *a, int na, ch_seg_t *b, int nb, ch_real span)
{
    const ch_real cons = seg_total(a, na) + seg_total(b, nb), room = span - MIN_VOWEL;
    ch_real scale;
    int i;
    if (cons <= room || cons <= 0)
        return;
    scale = mx(0, room) / cons;
    for (i = 0; i < na; i++)
        a[i].dur = mx(MIN_CONSONANT, (is_nan(a[i].dur) ? 0 : a[i].dur) * scale);
    for (i = 0; i < nb; i++)
        b[i].dur = mx(MIN_CONSONANT, (is_nan(b[i].dur) ? 0 : b[i].dur) * scale);
}

static int consonants_at(const ch_seg_t *c, int n, ch_real hz, ch_seg_t *out)
{
    int i;
    consonants(c, n, R(0.8), out);
    for (i = 0; i < n; i++)
        out[i].f0 = hz;
    return n;
}

#define SONG_PH 24                            /* notes a phrase: a line is cut to 1.5x the tune's phrase (CHIMERA's at most 10 notes), so ~15; a longer one is planned in parts */
/* a held note: the fitting's, or a melisma the lyric wrote ("-") */
#define HELD(k) (notes[k].held || syls[notes[k].syl].melisma)
int ch_song_plan_phrase(const ch_sung_t *notes, int n, int i, const ch_syl_t *syls, const ch_real *times, ch_real t0,
                        ch_real vibrato, ch_plan_t *out)
{
    static ch_seg_t on[SONG_PH][CH_ONSET_MAX], co[SONG_PH][CH_CODA_MAX], segs[CH_SEG_MAX];
    static ch_real span_a[SONG_PH], span_b[SONG_PH];
    int non[SONG_PH], nco[SONG_PH], j = i, k, ns = 0, nsp = 0;
    ch_real reserve, last_end, first_onset;
    ch_plan_opts_t o;
    if (i >= n)
        return n;
    while (j < n - 1 && !notes[j].phrase_end && j - i < SONG_PH - 1)
        j++;
    for (k = i; k <= j; k++) {
        const ch_real hz = ch_midi_hz((ch_real)notes[k].midi);
        const ch_syl_t *s = &syls[notes[k].syl];
        int b = k;
        non[k - i] = HELD(k) ? 0 : consonants_at(s->onset, s->n_onset, hz, on[k - i]);
        while (b > 0 && HELD(b))       /* the coda of the syllable a melisma chain started */
            b--;
        nco[k - i] = k < j && HELD(k + 1) ? 0 : consonants_at(syls[notes[b].syl].coda, syls[notes[b].syl].n_coda, hz, co[k - i]);
    }
    reserve = j == n - 1 ? R(0.05) : R(0.12);
    last_end = times[j + 1] - mn(reserve, R(0.3) * (times[j + 1] - times[j]));
    for (k = i; k <= j; k++) {
        if (k < j)
            song_fit(co[k - i], nco[k - i], on[k + 1 - i], non[k + 1 - i], times[k + 1] - times[k]);
        else
            song_fit(co[k - i], nco[k - i], 0, 0, last_end - times[k]);
    }
    first_onset = seg_total(on[0], non[0]);
    for (k = i; k <= j; k++) {
        const ch_real hz = ch_midi_hz((ch_real)notes[k].midi);
        const ch_syl_t *s = &syls[notes[k].syl];
        const int next_melisma = k < j && HELD(k + 1);
        const ch_real note_end = k < j ? times[k + 1] - seg_total(on[k + 1 - i], non[k + 1 - i]) : last_end;
        const ch_real vdur = mx(R(0.02), note_end - times[k] - seg_total(co[k - i], nco[k - i]));
        const char *a, *b;
        int q;
        for (q = 0; q < non[k - i] && ns < CH_SEG_MAX; q++)
            segs[ns++] = on[k - i][q];
        if (diphthong_parts(s->nucleus.ipa, &a, &b) && !next_melisma) {
            if (ns + 2 <= CH_SEG_MAX) {
                nucleus_part(&segs[ns++], a, vdur * R(0.8), hz);
                nucleus_part(&segs[ns++], b, vdur * R(0.2), hz);
            }
        } else if (ns < CH_SEG_MAX) {
            nucleus_part(&segs[ns++], diphthong_parts(s->nucleus.ipa, &a, &b) ? a : s->nucleus.ipa, vdur, hz);
        }
        for (q = 0; q < nco[k - i] && ns < CH_SEG_MAX; q++)
            segs[ns++] = co[k - i][q];
        span_a[nsp] = times[k];
        span_b[nsp++] = times[k] + vdur;
    }
    if (ns) {
        /* the planner lays segments out from t0 + 0.06: start so the first vowel lands on its beat */
        ch_plan_defaults(&o);
        o.t0 = t0 + times[i] - first_onset - R(0.06);
        o.f0 = ch_midi_hz((ch_real)notes[i].midi);
        o.intonation = CH_INTON_FLAT;
        o.sung = 1;
        ch_plan_utterance(segs, ns, &o, out);
        for (k = 0; k < nsp; k++)
            if (span_b[k] - span_a[k] > R(0.3)) {
                ch_gesture_t *g = gesture(out, t0 + span_a[k] + R(0.22), t0 + span_b[k], R(0.3), R(0.06), R(4.0));
                g->targets[CH_VIBRATO] = vibrato;
            }
    }
    return j + 1;
}
