/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Jameson Hodge (CHIMERA, https://github.com/jamesondh/chimera-vocal-synth)
 * C port for the M-VAVE FM-1: Copyright (c) 2026 Shan-Sherif.
 *
 * A literal port of CHIMERA's beatbox.rs and beat_player.rs; see ch_beat.h. The comments are CHIMERA's, shortened. */
#include "ch_beat.h"
#include "ch_common.h"
#include "ch_str.h"

#define CH_NAN ((ch_real)__builtin_nanf(""))
static inline int is_nan(ch_real x) { return x != x; }

void ch_beat_ready(ch_real out[CH_NARTIC])
{
    /* with the jaw nearly closed the tongue rests a little higher than in a spoken schwa; the velar hits need it there:
     * from lower, the release peels open and loses its punch */
    const ch_real *schwa = ch_vowel_artic("ə");
    int k;
    for (k = 0; k < CH_NARTIC; k++)
        out[k] = schwa ? schwa[k] : CH_NAN;
    out[CH_TB_Y] = (schwa && !is_nan(schwa[CH_TB_Y]) ? schwa[CH_TB_Y] : R(6.85)) + R(0.1);
    out[CH_JAW] = R(0.4);
    out[CH_LIP_AP] = R(0.25);
    out[CH_LIP_PROT] = R(0.1);
}

/* a gesture with no targets yet (Gesture::new) */
static ch_gesture_t *g(ch_plan_t *o, ch_real t0, ch_real t1, ch_real ri, ch_real ro, ch_real strength, ch_real speed)
{
    static ch_gesture_t spare;
    ch_gesture_t *x;
    int k;
    if (o->n < o->cap) {
        x = &o->g[o->n++];
    } else {
        o->dropped++;
        x = &spare;
    }
    x->t0 = t0;
    x->t1 = t1;
    x->ramp_in = ri;
    x->ramp_out = ro;
    x->strength = strength;
    x->speed = speed;
    x->release_hold = x->tag = CH_NAN;
    for (k = 0; k < CH_NCONTROL; k++)
        x->targets[k] = CH_NAN;
    return x;
}

/* beatboxers snap their releases: faster than speech, and the heavy dorsum is no exception */
static void snap(ch_plan_t *o, int from, ch_real speed, ch_real ramp_out)
{
    int i;
    for (i = from; i < o->n; i++)
        if (o->g[i].strength >= R(40.0)) {
            o->g[i].speed = speed;
            o->g[i].ramp_out = ramp_out;
        }
}

/* a release fricative that starts only after the burst (with the planner's 30 ms ramp it would grip the articulators
 * while the closure is still held and pry it open early) */
static void after(ch_plan_t *o, int from, ch_real ramp_in)
{
    int i;
    for (i = from; i < o->n; i++)
        o->g[i].ramp_in = mn(o->g[i].ramp_in, ramp_in);
}

/* the glottalic piston: the glottis sealed through the closure, the larynx raised just before the release */
static void ejective(ch_plan_t *o, ch_real at, ch_real accent)
{
    g(o, at - R(0.012), R(0.03), R(0.012), R(0.03), R(8.0), CH_NAN)->targets[CH_CLOSURE] = R(1.0);
    g(o, R(-0.05), R(0.004), R(0.03), R(0.08), R(40.0), R(3.0))->targets[CH_LARYNX] = mn(R(1.6), R(1.25) + R(0.3) * accent);
}

/* each sound's breath on the FM-1 (0.8.0): its waveguide answers a beatboxer's strong breaths (1.2-1.75 x the
 * performer's pressure) a few dB louder than CHIMERA's airway, where speech's are within 1 dB; each breath sound's
 * level scaled so its hits sit with the kick's and the hum's against CHIMERA's renders (lab/port/beat_test, the
 * beats' hits by RMS) */
ch_real ch_beat_breath[CH_BEAT_N] = {R(1.0), R(1.0), R(1.0), R(0.35), R(1.0), R(0.6), R(1.0), R(1.0), R(0.4), R(1.0)};
/* (the snare 0.35, the inward snare 0.6, the crash 0.4: each was 3-5 dB over the kick's balance with CHIMERA, now
 * within 0.5 dB; the hats already sat 1-2 dB under it) */
static ch_real k_breath;                      /* (the sound being built's) */

/* lung pressure (x the performer's) over [t0, t1], the glottis held open until the breath has gone (otherwise the
 * folds start to vibrate on the leftover pressure) */
static void blow(ch_plan_t *o, ch_real t0, ch_real t1, ch_real level, ch_real abduction, ch_real tail)
{
    g(o, t0, t1 + tail + R(0.02), R(0.02), R(0.03), R(6.0), CH_NAN)->targets[CH_ABDUCTION] = abduction;
    g(o, t0 - R(0.01), t1, R(0.02), tail, R(1.0), CH_NAN)->targets[CH_BREATH] = level * k_breath;
}

static const int K_JAW[] = {CH_JAW}, K_TBY[] = {CH_TB_Y}, K_TBY_JAW[] = {CH_TB_Y, CH_JAW}, K_LIP[] = {CH_LIP_AP};

static void kick(ch_real accent, ch_plan_t *o)
{
    static const ch_real v[] = {R(0.3)};
    snap(o, ch_oral("p", R(-0.075), 0, R(2.0), K_JAW, v, 1, o), R(3.5), R(0.01));
    ejective(o, R(-0.075), accent);
}

static void hat(ch_real accent, ch_plan_t *o)
{
    static const ch_real v[] = {R(0.3)};
    snap(o, ch_oral("t", R(-0.05), 0, R(2.0), K_JAW, v, 1, o), R(3.5), R(0.01));
    after(o, ch_oral("s", R(0.012), R(0.03), R(1.6), 0, 0, 0, o), R(0.008));
    blow(o, R(-0.03), R(0.026), R(1.25) * accent, R(0.36), R(0.02));
}

static void open_hat(ch_real len, ch_real accent, ch_plan_t *o)
{
    static const ch_real v[] = {R(0.3)};
    const ch_real end = mx(R(0.09), mn(R(0.26), len * R(1.6)));
    snap(o, ch_oral("t", R(-0.05), 0, R(2.0), K_JAW, v, 1, o), R(3.5), R(0.01));
    after(o, ch_oral("s", R(0.012), end, R(1.4), 0, 0, 0, o), R(0.008));
    blow(o, R(-0.03), end, R(1.2) * accent, R(0.4), R(0.09));
}

static void snare(ch_real accent, ch_plan_t *o)
{
    snap(o, ch_oral("p", R(-0.06), 0, R(2.0), 0, 0, 0, o), R(3.5), R(0.01));
    after(o, ch_oral("f", R(0.018), R(0.08), R(1.6), 0, 0, 0, o), R(0.008));
    blow(o, R(-0.045), R(0.075), R(1.7) * accent, R(0.45), R(0.04));
}

static void rimshot(ch_real accent, ch_plan_t *o)
{
    static const ch_real v[] = {R(0.35)};
    snap(o, ch_oral("k", R(-0.08), 0, R(2.0), K_JAW, v, 1, o), R(4.0), R(0.008));
    ejective(o, R(-0.08), accent);
}

static void inward_snare(ch_real accent, ch_plan_t *o)
{
    static const ch_real v[] = {R(0.35)}, w[] = {R(7.35)};
    snap(o, ch_oral("k", R(-0.06), 0, R(2.0), K_JAW, v, 1, o), R(4.0), R(0.008));
    after(o, ch_oral("x", R(0.012), R(0.085), R(1.4), K_TBY, w, 1, o), R(0.008));
    g(o, R(-0.05), R(0.085), R(0.02), R(0.04), R(6.0), CH_NAN)->targets[CH_ABDUCTION] = R(0.5);
    /* draw the breath in behind the closure so the release rushes inward */
    g(o, R(-0.035), R(0.085), R(0.015), R(0.05), R(2.0), CH_NAN)->targets[CH_BREATH] = R(-1.7) * accent * k_breath;
}

static void tongue_click(ch_plan_t *o)
{
    static const ch_real v[] = {R(7.85), R(0.4)};
    int s, r, w;
    /* the tip seals without the alveolar's fronted tongue body (that would close the pocket) */
    s = ch_oral("t", R(-0.12), 0, R(2.0), 0, 0, 0, o);
    snap(o, s, R(3.5), R(0.01));
    for (r = w = s; r < o->n; r++)
        if (is_nan(o->g[r].targets[CH_TB_X]))
            o->g[w++] = o->g[r];
    o->n = w;
    /* the back seal sits low, leaving a pocket for the dorsum to hollow */
    ch_oral("k", R(-0.12), R(0.035), R(2.0), K_TBY_JAW, v, 2, o);
    g(o, R(-0.03), R(0.006), R(0.04), R(0.03), R(40.0), R(2.0))->targets[CH_DORSUM] = R(1.0);
}

static void lip_pop(ch_plan_t *o)
{
    static const ch_real v[] = {R(-0.08)};
    ch_gesture_t *x;
    /* a light seal (pressing the lips squeezes the pocket before the suction starts) */
    snap(o, ch_oral("p", R(-0.12), 0, R(2.0), K_LIP, v, 1, o), R(5.0), R(0.006));
    ch_oral("k", R(-0.12), R(0.03), R(2.0), 0, 0, 0, o);
    /* the lips stay sealed while the jaw and tongue hollow the mouth: about -30 cmH2O of suction */
    x = g(o, R(-0.07), R(0.004), R(0.04), R(0.05), R(40.0), R(2.0));
    x->targets[CH_JAW] = R(1.6);
    x->targets[CH_DORSUM] = R(1.0);
}

static void crash(ch_real len, ch_real accent, ch_plan_t *o)
{
    const ch_real end = mx(R(0.14), mn(R(0.34), len * R(2.0)));
    /* the "ch": the back of the tongue lingers by the velum after the burst ([x]) while the blade rises into the long
     * rounded [ʃ] */
    snap(o, ch_oral("k", R(-0.06), 0, R(2.0), 0, 0, 0, o), R(4.0), R(0.008));
    after(o, ch_oral("x", R(0.002), R(0.045), R(2.0), 0, 0, 0, o), R(0.008));
    after(o, ch_oral("ʃ", R(0.025), end, R(1.4), 0, 0, 0, o), R(0.008));
    blow(o, R(-0.035), end, R(1.75) * accent, R(0.45), R(0.14));
}

static void hum(ch_real f0, ch_real len, ch_real accent, ch_plan_t *o)
{
    const ch_real end = mx(R(0.06), len * R(0.92));
    /* lips shut before the voice starts, so the note begins as a hum rather than a vowel; the folds stay apart
     * (breathing) while the velum drops into the sealed mouth, so the squeezed air goes to the lungs instead of
     * popping out through the nose */
    ch_oral("m", R(-0.03), end, R(1.6), 0, 0, 0, o);
    g(o, R(-0.02), end, R(0.03), R(0.03), R(4.0), CH_NAN)->targets[CH_VELUM] = R(0.9);
    g(o, R(-0.08), R(-0.03), R(0.02), R(0.02), R(5.0), CH_NAN)->targets[CH_ABDUCTION] = R(0.3);
    g(o, R(-0.01), end, R(0.02), R(0.04), R(1.0), CH_NAN)->targets[CH_BREATH] = R(0.95) * accent;
    g(o, R(-0.02), end, R(0.015), R(0.02), R(8.0), CH_NAN)->targets[CH_F0] = f0;
}

const ch_beat_info_t CH_BEAT_SOUNDS[CH_BEAT_N] = {
    {"B", "KICK", CH_ROLE_KICK, CH_AIR_EJECTIVE, R(0.01)},
    {"t", "HI-HAT", CH_ROLE_HAT, CH_AIR_PULMONIC, R(-0.005)},
    {"tss", "OPEN HAT", CH_ROLE_HAT, CH_AIR_PULMONIC, R(-0.005)},
    {"Pf", "SNARE", CH_ROLE_SNARE, CH_AIR_PULMONIC, R(0.018)},
    {"K", "RIMSHOT", CH_ROLE_SNARE, CH_AIR_EJECTIVE, R(-0.0165)},
    {"^K", "INWARD SNARE", CH_ROLE_SNARE, CH_AIR_INGRESSIVE, R(-0.014)},
    {"!", "TONGUE CLICK", CH_ROLE_PERC, CH_AIR_CLICK, R(-0.007)},
    {"P!", "LIP POP", CH_ROLE_PERC, CH_AIR_CLICK, R(-0.001)},
    {"kch", "CRASH", CH_ROLE_HAT, CH_AIR_PULMONIC, R(-0.013)},
    {"m", "HUM", CH_ROLE_BASS, CH_AIR_VOICED, R(0.0056)},
};

void ch_beat_build(int id, ch_real f0, ch_real len, ch_real accent, ch_real hit, ch_plan_t *o)
{
    int i, start = o->n;
    ch_real off;
    if (id < 0 || id >= CH_BEAT_N)
        return;
    k_breath = ch_beat_breath[id];
    switch (id) {
    case CH_BEAT_KICK: kick(accent, o); break;
    case CH_BEAT_HAT: hat(accent, o); break;
    case CH_BEAT_OPEN_HAT: open_hat(len, accent, o); break;
    case CH_BEAT_SNARE: snare(accent, o); break;
    case CH_BEAT_RIMSHOT: rimshot(accent, o); break;
    case CH_BEAT_INWARD: inward_snare(accent, o); break;
    case CH_BEAT_CLICK: tongue_click(o); break;
    case CH_BEAT_LIP_POP: lip_pop(o); break;
    case CH_BEAT_CRASH: crash(len, accent, o); break;
    case CH_BEAT_HUM: hum(f0, len, accent, o); break;
    default: return;
    }
    /* (BeatPlayer::gestures: the hit's time, less the sound's latency) */
    off = hit - CH_BEAT_SOUNDS[id].latency;
    for (i = start; i < o->n; i++) {
        o->g[i].t0 += off;
        o->g[i].t1 += off;
    }
}

ch_real ch_beat_lead_in(int id)
{
    ch_gesture_t buf[CH_BEAT_MAX_G];
    ch_plan_t o;
    ch_real lead = 0;
    int i;
    if (id < 0 || id >= CH_BEAT_N)
        return 0;
    o.g = buf;
    o.cap = CH_BEAT_MAX_G;
    o.n = 0;
    o.dropped = 0;
    ch_beat_build(id, R(100.0), R(0.15), R(1.0), CH_BEAT_SOUNDS[id].latency, &o);   /* (at the sound's own times) */
    for (i = 0; i < o.n; i++)
        lead = mx(lead, -(buf[i].t0 - buf[i].ramp_in));
    return lead + CH_BEAT_SOUNDS[id].latency;
}

/* ------------------------------------------------------------------------- patterns --- */
const ch_beat_preset_t CH_BEAT_PRESETS[CH_BEAT_NPRESETS] = {
    {"BOOTS AND CATS", R(96.0), R(0.0), "B . t . | Pf . t . | B . t . | Pf . t ."},
    {"FOUR ON THE FLOOR", R(122.0), R(0.0), "B . t . | B . tss . | B . t . | B . tss ."},
    {"BREAKBEAT", R(94.0), R(0.18), "B . t . | Pf . t B | . B t . | Pf . t t"},
    {"RIMSHOT SHUFFLE", R(88.0), R(0.4), "B . t K | . t B . | t . K t | . B K t"},
    {"INWARD SNARE", R(100.0), R(0.0), "B . t . | ^K . t . | B B t . | ^K . t ."},
    {"CLICKS AND POPS", R(90.0), R(0.25), "B . ! . | P! . ! . | B ! . ! | P! . ! !"},
    {"HUM BASS", R(92.0), R(0.1), "B m . m:43 | Pf . m:45 . | B m:40 . m | Pf . t kch"},
};

static int lower_c(int c) { return c >= 'A' && c <= 'Z' ? c - 'A' + 'a' : c; }

/* the sound with this symbol: exactly, else ignoring case */
static int sound_of(const char *tok, int n)
{
    int i, j, pass;
    for (pass = 0; pass < 2; pass++)
        for (i = 0; i < CH_BEAT_N; i++) {
            const char *id = CH_BEAT_SOUNDS[i].id;
            for (j = 0; j < n && id[j]; j++)
                if (pass ? lower_c(id[j]) != lower_c(tok[j]) : id[j] != tok[j])
                    break;
            if (j == n && !id[j])
                return i;
        }
    return -1;
}

int ch_beat_parse(const char *text, ch_beat_step_t *out, int cap)
{
    int n = 0;
    const char *p = text;
    while (*p && n < cap) {
        const char *s;
        int len, note = 0, accent = 0, colon;
        while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r' || *p == '|')
            p++;
        if (!*p)
            break;
        s = p;
        while (*p && *p != ' ' && *p != '\t' && *p != '\n' && *p != '\r' && *p != '|')
            p++;
        len = (int)(p - s);
        if (len == 1 && (*s == '.' || *s == '-')) {
            out[n].id = -1;
            out[n].note = out[n].accent = 0;
            n++;
            continue;
        }
        if (s[len - 1] == '>') {
            accent = 1;
            len--;
        }
        for (colon = len - 1; colon > 0 && s[colon] >= '0' && s[colon] <= '9'; colon--)
            ;
        if (colon > 0 && colon < len - 1 && s[colon] == ':') {   /* "m:43": the digits after the last colon */
            int i;
            for (i = colon + 1; i < len; i++)
                note = note * 10 + (s[i] - '0');
            len = colon;
        }
        out[n].id = (int8_t)sound_of(s, len);
        out[n].note = (uint8_t)(note > 127 ? 127 : note);
        out[n].accent = (uint8_t)accent;
        if (out[n].id < 0)                 /* (an unknown symbol: a rest, as parse_beat's None) */
            out[n].note = out[n].accent = 0;
        n++;
    }
    return n;
}

/* ------------------------------------------------------------------------ the player --- */
void ch_beat_player_init(ch_beat_player_t *p)
{
    int i;
    p->n = 0;
    p->bpm = R(96.0);
    p->swing = 0;
    p->playing = 0;
    p->anchor_time = 0;
    p->anchor_step = p->next = 0;
    for (i = 0; i < CH_BEAT_MARKS; i++) {
        p->mark_n[i] = -1;
        p->mark_t[i] = 0;
    }
    p->mark_pos = 0;
}

void ch_beat_set_steps(ch_beat_player_t *p, const ch_beat_step_t *steps, int n)
{
    int i;
    if (n > CH_BEAT_MAX_STEPS)
        n = CH_BEAT_MAX_STEPS;
    for (i = 0; i < n; i++)
        p->steps[i] = steps[i];
    p->n = n;
}

ch_real ch_beat_step_dur(const ch_beat_player_t *p) { return R(60.0) / p->bpm / R(4.0); }

static ch_real step_time(const ch_beat_player_t *p, long n)
{
    const ch_real base = p->anchor_time + (ch_real)(n - p->anchor_step) * ch_beat_step_dur(p);
    return n % 2 == 1 ? base + p->swing * R(0.5) * ch_beat_step_dur(p) : base;
}

void ch_beat_start(ch_beat_player_t *p, ch_real clock)
{
    int i;
    p->playing = 1;
    p->anchor_time = clock + CH_BEAT_HORIZON;
    p->anchor_step = p->next = 0;
    for (i = 0; i < CH_BEAT_MARKS; i++)
        p->mark_n[i] = -1;
}

void ch_beat_stop(ch_beat_player_t *p) { p->playing = 0; }

void ch_beat_set_tempo(ch_beat_player_t *p, ch_real bpm, ch_real swing)
{
    const ch_real b = mn(R(200.0), mx(R(40.0), bpm));
    if (p->playing && b != p->bpm) {
        p->anchor_time += (ch_real)(p->next - p->anchor_step) * ch_beat_step_dur(p);
        p->anchor_step = p->next;
    }
    p->bpm = b;
    p->swing = mn(R(0.6), mx(0, swing));
}

int ch_beat_step_at(const ch_beat_player_t *p, ch_real clock)
{
    long best = -1;
    ch_real best_t = R(-1e30);
    int i;
    if (!p->playing || p->n == 0)
        return -1;
    for (i = 0; i < CH_BEAT_MARKS; i++)
        if (p->mark_n[i] >= 0 && p->mark_t[i] <= clock && p->mark_t[i] > best_t) {
            best = p->mark_n[i];
            best_t = p->mark_t[i];
        }
    return best < 0 ? -1 : (int)(best % p->n);
}

void ch_beat_tick(ch_beat_player_t *p, ch_real clock, ch_score_t *score)
{
    ch_gesture_t buf[CH_BEAT_MAX_G];
    int guard;
    if (!p->playing || p->n == 0)
        return;
    for (guard = 0; guard < 64; guard++) {
        const ch_real hit = step_time(p, p->next);
        const long l = p->n;
        ch_beat_step_t cell;
        if (hit > clock + CH_BEAT_HORIZON)
            break;
        p->mark_n[p->mark_pos] = p->next;
        p->mark_t[p->mark_pos] = hit;
        p->mark_pos = (p->mark_pos + 1) % CH_BEAT_MARKS;
        cell = p->steps[p->next % l];
        if (cell.id >= 0 && hit >= clock) {
            /* sustained sounds (hum, open hat) last until the next sound */
            long rests = 0;
            ch_plan_t o;
            int i;
            while (rests < l - 1 && p->steps[(p->next + 1 + rests) % l].id < 0)
                rests++;
            o.g = buf;
            o.cap = CH_BEAT_MAX_G;
            o.n = 0;
            o.dropped = 0;
            ch_beat_build(cell.id, ch_midi_hz((ch_real)(cell.note ? cell.note : CH_BEAT_NOTE)),
                          (ch_real)(1 + rests) * ch_beat_step_dur(p), cell.accent ? CH_BEAT_ACCENT : R(1.0), hit, &o);
            for (i = 0; i < o.n; i++)
                ch_score_add(score, &buf[i]);
        }
        p->next++;
    }
}
