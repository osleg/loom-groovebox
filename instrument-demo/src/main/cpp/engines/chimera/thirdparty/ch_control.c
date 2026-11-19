/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Jameson Hodge (CHIMERA, https://github.com/jamesondh/chimera-vocal-synth)
 * C port for the M-VAVE FM-1: Copyright (c) 2026 Shan-Sherif.
 *
 * A literal port of CHIMERA's control rate; see ch_control.h. The comments are CHIMERA's, shortened. */
#include "ch_control.h"
#include "ch_common.h"

#define CM_H2O R(980.665)
static inline int is_nan(ch_real x) { return x != x; }

/* ------------------------------------------------------------------ articulators.rs --- */
/* per-articulator range and speed (natural frequency, Hz), in ArticKey order */
static const ch_real SPEC_MIN[CH_NARTIC] = {-0.1, 1.0, 4.8, 3.5, 5.0, 0.0, -1.0, -0.4, -0.2, 0.0, 0.0, -0.25, -1.6, 0.0, 0.0, 0.0, 0.0};
static const ch_real SPEC_MAX[CH_NARTIC] = {2.6, 4.8, 9.2, 8.0, 10.8, 1.0, 1.0, 2.4, 1.5, 1.0, 1.0, 1.4, 1.6, 1.0, 1.0, 1.0, 1.0};
static const ch_real SPEC_HZ[CH_NARTIC] = {10.0, 12.0, 12.0, 13.0, 13.0, 9.0, 9.0, 11.0, 11.0, 11.0, 10.0, 12.0, 8.0, 6.0, 6.0, 10.0, 9.0};
/* NEUTRAL (schwa-like), template coordinates */
const ch_real CH_NEUTRAL[CH_NARTIC] = {0.6, 2.9, 6.85, 6.45, 7.3, 0.0, 0.0, 0.7, 0.2, 0.5, 0.0, -0.15, 0.0, 0.0, 0.0, 0.0, 0.0};

/* the tongue tip's rest behind the lower incisors, carried by the jaw and dragged by the tongue body */
static void rest_tip(ch_real jaw, ch_real tb_x, ch_real tb_y, ch_real *x, ch_real *y)
{
    const ch_real cx = R(-2.5), cy = R(10.5), px = R(6.45), py = R(7.35);
    ch_real ang = -jaw / R(9.84), dx = px - cx, dy = py - cy;
    *x = cx + dx * COS(ang) - dy * SIN(ang) + R(0.3) * (tb_x - CH_NEUTRAL[CH_TB_X]);
    *y = cy + dx * SIN(ang) + dy * COS(ang) + R(0.35) * (tb_y - CH_NEUTRAL[CH_TB_Y]);
}

/* advance the dynamics; with blend, each key's gesture blend joins the performer's target at base_weight */
static void artic_step(ch_control_t *c, ch_real dt, int use_blend, ch_real base_weight)
{
    int i;
    if (c->tip_free)
        rest_tip(c->x[CH_JAW], c->x[CH_TB_X], c->x[CH_TB_Y], &c->target[CH_TT_X], &c->target[CH_TT_Y]);
    for (i = 0; i < CH_NARTIC; i++) {
        ch_real tgt = c->target[i], sp = c->speed[i], w, x, acc, nx;
        if (use_blend) {
            const ch_blended_t *b = &c->blend[i];
            if (b->weight > 0) {
                if (i == CH_VELUM) {
                    /* the velum follows the gesture's own ramps (bounded: base + min(1, w/2) (value - base)) */
                    tgt += mn(R(1.0), b->weight / R(2.0)) * (b->value - tgt);
                } else {
                    tgt = (tgt * base_weight + b->value * b->weight) / (base_weight + b->weight);
                }
                sp *= b->speed;
            }
        }
        if (tgt < SPEC_MIN[i])
            tgt = SPEC_MIN[i];
        else if (tgt > SPEC_MAX[i])
            tgt = SPEC_MAX[i];
        c->effective[i] = tgt;
        w = R(2.0) * CH_PI * SPEC_HZ[i] * sp;
        /* semi-implicit Euler on x'' = w² (T - x) - 2 w x' */
        x = c->x[i];
        acc = w * w * (tgt - x) - R(2.0) * w * c->v[i];
        c->v[i] += acc * dt;
        nx = x + c->v[i] * dt;
        if (tgt == 0)
            tiny(&nx);
        tiny(&c->v[i]);
        c->x[i] = nx;
    }
}

/* --------------------------------------------------------------------------- score.rs --- */
static ch_real gesture_end(const ch_gesture_t *g)
{
    return g->t1 + g->ramp_out + (is_nan(g->release_hold) ? 0 : g->release_hold);
}

/* smooth activation 0..1..0 with cosine ramps */
static ch_real activation(const ch_gesture_t *g, ch_real t)
{
    ch_real u;
    if (t <= g->t0 - g->ramp_in || t >= g->t1 + g->ramp_out)
        return 0;
    if (t < g->t0) {
        u = (t - (g->t0 - g->ramp_in)) / mx(R(1e-6), g->ramp_in);
        return R(0.5) - R(0.5) * COS(CH_PI * u);
    }
    if (t > g->t1) {
        u = (t - g->t1) / mx(R(1e-6), g->ramp_out);
        return R(0.5) + R(0.5) * COS(CH_PI * u);
    }
    return 1;
}

void ch_score_add(ch_score_t *s, const ch_gesture_t *g)
{
    if (s->n >= CH_SCORE_CAP) {
        s->dropped++;
        return;
    }
    s->g[s->n++] = *g;
    s->end = mx(s->end, gesture_end(g));
}

void ch_score_clear(ch_score_t *s)
{
    s->n = 0;
    s->end = 0;
}

/* end every gesture with this tag at t: one not begun is dropped, one ramping in turns round from its present
 * activation, one at full strength starts its ramp-out now */
void ch_score_release(ch_score_t *s, ch_real tag, ch_real t)
{
    int i, j = 0;
    ch_real end = 0;
    for (i = 0; i < s->n; i++) {
        ch_gesture_t *g = &s->g[i];
        if (g->tag == tag && !(t > g->t0 - g->ramp_in))
            continue;
        s->g[j++] = *g;
    }
    s->n = j;
    for (i = 0; i < s->n; i++) {
        ch_gesture_t *g = &s->g[i];
        if (g->tag != tag || g->t1 <= t)
            continue;
        if (t < g->t0) {
            ch_real a = activation(g, t), u = ACOS(mn(R(1.0), mx(R(-1.0), R(2.0) * a - R(1.0)))) / CH_PI;
            g->t1 = t - u * mx(R(1e-6), g->ramp_out);
            g->t0 = g->t1;
        } else {
            g->t1 = t;
        }
    }
    for (i = 0; i < s->n; i++)
        end = mx(end, gesture_end(&s->g[i]));
    s->end = end;
}

/* drop the gestures that have fully finished before t (in place, in order) */
static void score_prune(ch_score_t *s, ch_real t)
{
    int i, j = 0;
    for (i = 0; i < s->n; i++)
        if (gesture_end(&s->g[i]) > t)
            s->g[j++] = s->g[i];
    s->n = j;
}

/* every key's blend at t, in one pass over the score */
static void blend_all(const ch_score_t *s, ch_real t, ch_blended_t *out)
{
    ch_real wsum[CH_NCONTROL], vsum[CH_NCONTROL], speed[CH_NCONTROL];
    int i, k;
    for (k = 0; k < CH_NCONTROL; k++) {
        wsum[k] = vsum[k] = 0;
        speed[k] = 1;
    }
    for (i = 0; i < s->n; i++) {
        const ch_gesture_t *g = &s->g[i];
        ch_real lo = g->t0 - g->ramp_in, act, w, gs;
        int in_speed = !is_nan(g->speed) && g->speed != 0 && t >= lo && t <= gesture_end(g);
        act = activation(g, t);
        if (act <= 0 && !in_speed)
            continue;
        w = act * g->strength;
        gs = is_nan(g->speed) ? 0 : g->speed;
        for (k = 0; k < CH_NCONTROL; k++) {
            ch_real target = g->targets[k];
            if (is_nan(target))
                continue;
            if (in_speed && gs > speed[k])
                speed[k] = gs;
            if (act <= 0)
                continue;
            wsum[k] += w;
            vsum[k] += w * target;
        }
    }
    for (k = 0; k < CH_NCONTROL; k++) {
        out[k].value = wsum[k] > 0 ? vsum[k] / wsum[k] : 0;
        out[k].weight = wsum[k];
        out[k].speed = speed[k];
    }
}

/* ------------------------------------------------------------------- human_voice.rs --- */
static const ch_source_t DEFAULT_SOURCE = {110.0, 0.06, 8.0, 0.15, 0.5, 0.0, 0.1, 0.0, 6.0, 5.0, 1, 0.25};

static void apply_source(ch_control_t *c)
{
    const ch_source_t *s = &c->source;
    ch_glottis_params_t *g = &c->glottis;
    ch_real k2 = c->k * c->k, bright, by_tension;
    g->amplitude = R(0.17) * k2 * (s->voiced ? R(1.0) : R(0.0));
    g->abduction = (s->voiced ? R(0.1) * s->breathiness * s->breathiness : R(0.2) + R(0.1) * s->breathiness) * k2;
    c->base_abduction = g->abduction;
    c->base_closure = 0;
    /* the posterior gap through the closed phase (Södersten & Lindestad 1990) */
    g->chink = (R(0.008) + R(0.03) * s->breathiness) * k2;
    g->open_quotient = R(0.78) - R(0.36) * s->tension;
    /* the folds close over a return phase; pressing harder brings the abrupt closure back; brightness moves it */
    bright = s->brightness - DEFAULT_SOURCE.brightness;
    by_tension = R(0.4) * s->tension + R(1.375) * mx(0, s->tension - R(0.6));
    g->closure_sharpness = clampr(by_tension + R(0.8) * bright, 0, R(1.0));
    g->skew = R(0.56) + R(0.16) * s->tension + R(0.2) * bright;
    g->fry = s->fry;
    g->jitter = R(0.0021) + R(0.0245) * s->roughness;
    g->shimmer = R(0.02) + R(0.22) * s->roughness;
    g->diplophonia = s->roughness > R(0.5) ? (s->roughness - R(0.5)) * R(1.4) : 0;
    g->vibrato_depth = s->vibrato_depth;
    g->vibrato_rate = s->vibrato_rate;
    g->drift = s->drift;
    g->effort = clampr((FABS(s->pressure) - R(3.0)) / R(10.0), 0, R(1.0));
}

void ch_control_init(ch_control_t *c, ch_real fs, int ctrl_every, ch_real body_scale)
{
    static const ch_glottis_params_t DEFAULT_GLOTTIS = {110.0, 0.16, 0.0, 0.006, 0.62, 0.62, 0.6, 0.7, 0.004, 0.03, 0.0, 6.0, 6.0, 0.0, 0.0, 0.0, 0.0};
    int i;
    for (i = 0; i < CH_NARTIC; i++) {
        c->x[i] = c->target[i] = c->effective[i] = CH_NEUTRAL[i];
        c->v[i] = 0;
        c->speed[i] = 1;
    }
    rest_tip(CH_NEUTRAL[CH_JAW], CH_NEUTRAL[CH_TB_X], CH_NEUTRAL[CH_TB_Y], &c->x[CH_TT_X], &c->x[CH_TT_Y]);
    c->target[CH_TT_X] = c->x[CH_TT_X];
    c->target[CH_TT_Y] = c->x[CH_TT_Y];
    c->tip_free = 1;
    ch_score_clear(&c->score);
    c->score.dropped = 0;
    c->k = body_scale;
    c->fs = fs;
    c->dt = (ch_real)ctrl_every / fs;
    c->clock = 0;
    c->max_penetration = R(0.1);
    c->anat_every = 1;
    c->anat_count = 0;
    c->reapplies = 0;
    c->defer_contacts = 0;
    c->gate = c->gate_target = c->score_breath_hold = c->p_lung = 0;
    c->f0 = DEFAULT_SOURCE.f0;
    c->score_f0 = 0;
    c->has_score_f0 = 0;
    c->glottis = DEFAULT_GLOTTIS;
    c->source = DEFAULT_SOURCE;
    apply_source(c);
}

void ch_control_set_source(ch_control_t *c, const ch_source_t *s)
{
    c->source = *s;
    apply_source(c);
}

void ch_control_set_gate(ch_control_t *c, ch_real level)
{
    c->gate_target = clampr(level, 0, R(1.5));
}

/* articulators cannot travel into the palate: beyond max_penetration the tongue tip / body is put back along the
 * contact's normal and its inward velocity removed. 1 when it moved one */
static int enforce_contacts(ch_control_t *c, const ch_tract_t *t)
{
    const ch_real k = t->k, lim = -c->max_penetration * k;
    const int kx[2] = {CH_TT_X, CH_TB_X}, ky[2] = {CH_TT_Y, CH_TB_Y};
    const ch_real d[2] = {t->contact_tip, t->contact_body};
    const ch_v2 n[2] = {t->contact_tip_n, t->contact_body_n};
    int changed = 0, j;
    for (j = 0; j < 2; j++) {
        ch_real depth, sx, sy, vn;
        int ix = kx[j], iy = ky[j];
        if (!(d[j] < lim))
            continue;
        changed = 1;
        depth = lim - d[j];
        /* body → template displacement (oral x is compressed by 0.86 in the template map) */
        sx = (c->x[ix] > R(0.5) ? R(1.0) / R(0.86) : R(1.0)) / k;
        sy = R(1.0) / k;
        c->x[ix] += n[j].x * depth * sx;
        c->x[iy] += n[j].y * depth * sy;
        vn = c->v[ix] * n[j].x / sx + c->v[iy] * n[j].y / sy;
        if (vn < 0) {
            c->v[ix] -= vn * n[j].x * sx;
            c->v[iy] -= vn * n[j].y * sy;
        }
    }
    return changed;
}

static int trunc_i(ch_real x) { return (int)x; }

int ch_control_step(ch_control_t *c, ch_tract_t *t)
{
    int anat;
    const ch_real dt = c->dt, now = c->clock, k2 = c->k * c->k;
    ch_real breath_act = 0, breath_val = 0, f0_target = c->source.f0, score_breath, gate_goal, tau, g;
    if (c->score.n > 0) {
        const ch_blended_t *ab, *cl, *br, *vb, *pf;
        ch_real ab_act;
        blend_all(&c->score, now, c->blend);
        /* the performer's base posture keeps a small say on keys the score controls */
        artic_step(c, dt, 1, R(0.05));
        ab = &c->blend[CH_ABDUCTION];
        ab_act = ab->weight > 0 ? mn(R(1.0), ab->weight / R(2.0)) : 0;
        c->glottis.abduction = c->base_abduction + ab->value * ab_act * k2;
        cl = &c->blend[CH_CLOSURE];
        c->glottis.closure = mx(c->base_closure, cl->weight > 0 ? cl->value * mn(R(1.0), cl->weight / R(2.0)) : 0);
        br = &c->blend[CH_BREATH];
        if (br->weight > 0) {
            breath_act = mn(R(1.0), br->weight);
            breath_val = br->value;
        }
        vb = &c->blend[CH_VIBRATO];
        c->glottis.vibrato_depth = vb->weight > 0 ? (c->source.vibrato_depth * R(0.05) + vb->value * vb->weight) / (R(0.05) + vb->weight)
                                                  : c->source.vibrato_depth;
        pf = &c->blend[CH_F0];
        if (pf->weight > 0) {
            f0_target = (c->source.f0 * R(0.02) + pf->value * pf->weight) / (R(0.02) + pf->weight);
            c->score_f0 = f0_target;
            c->has_score_f0 = 1;
        } else {
            c->has_score_f0 = 0;
        }
        if (trunc_i(c->clock * R(10.0)) != trunc_i((c->clock - dt) * R(10.0)))
            score_prune(&c->score, now - R(0.2));
    } else {
        artic_step(c, dt, 0, R(1.0));
        c->glottis.abduction = c->base_abduction;
        c->glottis.closure = c->base_closure;
        c->has_score_f0 = 0;
    }
    c->clock += dt;
    /* the anatomy; the contact constraint, and the anatomy again if it moved an articulator */
    anat = c->anat_count == 0;
    if (anat) {   /* (the FM-1: every second step; the contacts are the anatomy's, so they go with it) */
        ch_tract_apply(t, c->x, dt * (ch_real)c->anat_every);
        if (enforce_contacts(c, t) && !c->defer_contacts) {
            ch_tract_reapply(t, c->x);
            c->reapplies++;
        }
    }
    if (++c->anat_count >= c->anat_every)
        c->anat_count = 0;
    /* breath: fast attack, slower release; a speaking score breathes on its own, a negative breath (ingressive)
     * overrides the performer's */
    score_breath = breath_val * breath_act;
    gate_goal = score_breath < 0 ? score_breath : mx(c->gate_target, score_breath);
    c->score_breath_hold = breath_act > 0 ? R(0.15) : mx(0, c->score_breath_hold - dt);
    tau = gate_goal > c->gate ? R(0.025) : c->score_breath_hold > 0 ? R(0.02) : R(0.07);
    c->gate += (gate_goal - c->gate) * (R(1.0) - EXP(-dt / tau));
    tiny(&c->gate);
    c->p_lung = c->source.pressure * CM_H2O * c->gate;
    /* vocal effort follows the pressure actually applied */
    c->glottis.effort = clampr((FABS(c->source.pressure * c->gate) - R(3.0)) / R(10.0), 0, R(1.0));
    /* the pitch glide */
    g = mx(R(0.002), c->has_score_f0 ? R(0.03) : c->source.glide);
    c->f0 *= POW(f0_target / c->f0, R(1.0) - EXP(-dt / g));
    c->glottis.f0 = c->f0;
    return anat;
}
