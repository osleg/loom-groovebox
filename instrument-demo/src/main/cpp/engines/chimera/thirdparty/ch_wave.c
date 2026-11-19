/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Jameson Hodge (CHIMERA, https://github.com/jamesondh/chimera-vocal-synth)
 * C port for the M-VAVE FM-1: Copyright (c) 2026 Shan-Sherif.
 *
 * The FM-1's tract: a Kelly-Lochbaum waveguide driven by CHIMERA's anatomy, glottis and noise sources; see
 * ch_wave.h. The glottis's flow, the noise law and the output stage are CHIMERA's (crates/chimera/src/airway/
 * network.rs, sources/glottis.rs, voice/human_voice.rs), as in ch_voice.c.
 *
 * The waves are pressures. At a junction between sections of areas A1 and A2, with k = (A1 - A2) / (A1 + A2), the
 * waves arriving from either side (in1 travelling right, in2 left) leave as in1 + d (right) and in2 + d (left),
 * d = k (in1 - in2). Where the nose joins, the junction's pressure is 2 (A1 in1 + A2 in2 + A3 in3) / (A1 + A2 + A3)
 * and each wave leaves as that pressure less the one arriving from its side (the two-port case is the same). A
 * section's volume flow is (p+ - p-) A / (rho c). Each sample reads one wave buffer and writes the other. */
#define CH_RAM   /* the audio rate in RAM on the FM-1 (ch_common.h CH_HOT) */
#ifdef CH_WAVE_NANCHECK
#include <stdio.h>
#endif
#include "ch_wave.h"
#include "ch_common.h"
#ifdef CH_WAVE_DEBUG
#include <stdio.h>
#endif

static ch_real nose_area(ch_real u, ch_real k)   /* HumanAirway's nasal profile, u along it 0..1 */
{
    ch_real a = u < R(0.18) ? R(2.6) : u < R(0.75) ? R(2.0) - R(0.6) * (u - R(0.18)) : u < R(0.9) ? R(1.4) : R(1.0);
    return a * k * k;
}

/* a junction's reflection, |k| <= 1. (The areas clamped at 0: a closing section ramps to 0 and float leaves it at
 * +-1e-9; with two such, k came out at -3.7 and the tract blew up: 0.0.8, silent for good after some playing) */
CH_INLINE ch_real refl(ch_real a1, ch_real a2)
{
    ch_real s;
    a1 = a1 > 0 ? a1 : 0;
    a2 = a2 > 0 ? a2 : 0;
    s = a1 + a2;
    return s > R(1e-9) ? (a1 - a2) / s : 0;
}

void ch_wave_init(ch_wave_t *w, ch_real fs, int ctrl_every, ch_real k, ch_real tract_len)
{
    const ch_real dx = C_SOUND / fs, nose_len = R(11.4) * k;
    int i;
    w->fs = fs;
    w->rho_c = RHO * C_SOUND;
    w->closed_area = R(2e-4);
    w->ctrl_every = ctrl_every;
    w->dx = dx;
    w->var_len = 0;
    w->n = (int)js_round_c(tract_len / dx);
    w->n = w->n < 8 ? 8 : w->n > CH_WMAX ? CH_WMAX : w->n;
    w->nn = (int)js_round_c(nose_len / dx);
    w->nn = w->nn < 4 ? 4 : w->nn > CH_WNMAX ? CH_WNMAX : w->nn;
    w->jv = w->n / 2;
    w->p = 0;
    for (i = 0; i < CH_WMAX; i++) {
        ch_wsec_t *s = &w->sec[i];
        s->R[0] = s->R[1] = s->L[0] = s->L[1] = s->k = s->dk = s->darea = 0;
        s->area = R(1.0) * k * k;
    }
    w->w1 = w->w2 = R(1.0);
    w->w3 = w->dw1 = w->dw2 = w->dw3 = w->nk1_d = 0;
    for (i = 0; i < CH_WNMAX; i++) {
        ch_wnsec_t *s = &w->nsec[i];
        s->R[0] = s->R[1] = s->L[0] = s->L[1] = s->k = 0;
        s->area = i == 0 ? 0 : nose_area(((ch_real)i + R(0.5)) / (ch_real)w->nn, k);
    }
    for (i = 1; i < w->nn; i++)
        w->nsec[i].k = refl(w->nsec[i - 1].area, w->nsec[i].area);
    /* the losses and the ends: tuned against CHIMERA's renders (lab/port/replay --wave) */
    w->fade = R(0.995);
    w->fade_n = R(0.99);
    w->r_lip = R(-0.85);
    w->r_nose = R(-0.85);
    w->r_glottis = R(0.75);
    w->rad_load = 1;
    w->noise_gain = R(0.4);                 /* CHIMERA's sources through the waveguide's flows: tuned on its renders */
    w->n_jet = 0;
    w->len_changed = 0;
    for (i = 0; i < 8; i++)
        w->jet_u[i] = 0;
    w->walls = 1;
    w->vol_src = 2;
    w->vol_narrow = R(0.1);
    w->vol_smooth = R(0.003);                 /* (~8 ms) */
    w->narrowest = 2;
    w->vol_u = w->vol_goal = 0;
    w->resets = 0;
    w->vol_ok = 0;
    w->vol_cells = 0;
    w->n_pock = 0;
    w->n_fast = 0;
    w->glot_sealed = 0;
    w->wall_rad = R(0.25);
    w->pock_k = RHO * C_SOUND * C_SOUND;      /* adiabatic air */
    w->pock_wall = R(1.0) / R(3.0e5);         /* the walls' compliance a cm² (CHIMERA's wall stiffness, 3e5 CGS) */
    w->pock_max = R(10.0) * R(980.665);       /* 10 cmH2O: a sealed throat (lab: CHIMERA's kick) */
    w->pock_max_oral = R(4.0) * R(980.665);   /* 4 cmH2O: a pocket in the mouth. CHIMERA's reach ~-30 but radiate less
                                               * through its slower-opening releases (lab: its clicks and lip pops) */
    w->wall_s = w->wall_v = w->wall_y = w->wall_u = 0;
    for (i = 0; i < 2; i++) {
        w->rb0[i] = R(-1.0);
        w->rb1[i] = w->ra1[i] = w->r_in[i] = w->r_out[i] = 0;
    }
    glottis_init(&w->glottis, fs);
    w->port_len = R(0.3) * k;          /* HumanAirway's glottis_port: PortSpec::new(0, 0.3k).kinetic(0.37, 1).slit(1.4k) */
    w->port_slit = R(1.4) * k;
    w->port_kin = R(0.37);
    w->port_kex = R(1.0);
    w->u_g = w->l_prev = w->mom = w->p_supra = w->a_g = 0;
    w->p_lung = w->p_lung_now = w->p_lung_step = 0;
    w->n_noise = 0;
    w->rng = 12345;
    w->rad_prev = 0;
    /* HumanVoice's output: smaller bodies radiate more high-frequency energy at their higher f0 */
    w->output_gain = R(1.33) * R(5.5e-7) * (k >= R(1.0) ? R(1.0) : k <= R(0.9) ? R(0.55) : R(0.55) + R(0.45) * (k - R(0.9)) / R(0.1));
    w->dc_x = w->dc_y = w->lim_env = 0;
    w->dc_coef = EXP((R(-2.0) * CH_PI * R(25.0)) / fs);
    w->shelf_g = w->shelf_lp = 0;
    w->shelf_c = EXP((R(-2.0) * CH_PI * R(2500.0)) / fs);
    w->lim_hold = 0;
    w->lim_hold_n = (int32_t)js_round_c(R(0.025) * fs);
    w->lim_release = EXP(R(-1.0) / (R(0.2) * fs));
}

/* a jet's kinetic loss coefficients between areas a and b, each way (CHIMERA's: the expansion leaving the narrower
 * side, a tenth of it entering) */
CH_HOT static void jet_coefs(ch_real a, ch_real b, ch_real *kf, ch_real *kb)
{
    const ch_real half_rho = R(0.5) * RHO;
    const ch_real ef = R(1.0) / a - R(1.0) / b, eb = -ef;   /* forward: out of a into b */
    *kf = (ef > 0 ? half_rho * ef * ef : 0) + (eb > 0 ? R(0.1) * half_rho * eb * eb : 0);
    *kb = (eb > 0 ? half_rho * eb * eb : 0) + (ef > 0 ? R(0.1) * half_rho * ef * ef : 0);
}

/* the radiation load's reflection at an open end of area a: CHIMERA's parallel R-L (Lr = 8 rho / (3 pi sqrt(pi a)),
 * Rr = 128 rho c / (9 pi^2 a)) against the section's rho c / a, through the bilinear transform. -1 at DC (the end is
 * an open short), rising toward (Rr - Zc) / (Rr + Zc) at high frequencies */
CH_HOT static void rad_coefs(ch_wave_t *w, int e, ch_real a)
{
    ch_real lr, rr, zc, kk = R(2.0) * w->fs, d0;
    a = mx(a, R(1e-4));
    lr = (R(8.0) * RHO) / (R(3.0) * CH_PI * SQRT(CH_PI * a));
    rr = (R(128.0) * RHO * C_SOUND) / (R(9.0) * CH_PI * CH_PI * a);
    zc = w->rho_c / a;
    d0 = kk * lr * (rr + zc) + zc * rr;
    w->rb0[e] = (kk * lr * (rr - zc) - zc * rr) / d0;
    w->rb1[e] = -(kk * lr * (rr - zc) + zc * rr) / d0;
    w->ra1[e] = (zc * rr - kk * lr * (rr + zc)) / d0;
}

/* the area a section at s (cm from the glottis) gets from the anatomy's two tubes: closed where their minimum is */
/* one anatomy cell: its area, 0 when it is shut (its area or its narrowest point under the closure's) */
CH_HOT static ch_real cell_area(const ch_shape_t *t, int oral, int i, ch_real closed)
{
    ch_real a = oral ? t->or_areas[i] : t->ph_areas[i], m = oral ? t->or_min[i] : t->ph_min[i];
    return m < closed || a < closed ? 0 : a;
}

/* a section's area over [s0, s1] (cm from the glottis): the anatomy's cell at its middle; shut when any cell it
 * covers is (a tip closure is shorter than a section: sampled at the middle alone, a /d/ was missed), the narrowest
 * cell's area when ch_wave_t.narrowest (constrictions) */
CH_HOT static ch_real tube_area(const ch_wave_t *w, const ch_shape_t *t, ch_real s0, ch_real s1, ch_real closed, ch_real *amin)
{
    const ch_real dph = t->ph_len / (ch_real)t->n_ph, dor = t->or_len / (ch_real)t->n_or, mid = R(0.5) * (s0 + s1);
    ch_real a, lo = R(1e9), lm = R(1e9), lc = R(1e9);
    int part, i, i0, i1, shut = 0;
    if (mid < t->ph_len) {
        i = (int)(mid / dph);
        a = cell_area(t, 0, i < 0 ? 0 : i > t->n_ph - 1 ? t->n_ph - 1 : i, closed);
    } else {
        i = (int)((mid - t->ph_len) / dor);
        a = cell_area(t, 1, i < 0 ? 0 : i > t->n_or - 1 ? t->n_or - 1 : i, closed);
    }
    for (part = 0; part < 2; part++) {   /* every cell the section covers, in the pharynx and in the mouth */
        const ch_real base = part ? t->ph_len : 0, d = part ? dor : dph;
        const int n = part ? t->n_or : t->n_ph;
        i0 = (int)((s0 - base) / d);
        i1 = (int)((s1 - base) / d);
        if (s1 <= base || (part == 0 && s0 >= t->ph_len))
            continue;
        i0 = i0 < 0 ? 0 : i0;
        i1 = i1 > n - 1 ? n - 1 : i1;
        for (i = i0; i <= i1; i++) {
            ch_real c = cell_area(t, part, i, closed), m = part ? t->or_min[i] : t->ph_min[i];
            const ch_real cc = base + ((ch_real)i + R(0.5)) * d;
            if (c == 0)
                shut = 1;
            if (c < lo)
                lo = c;
            if (m < lm)
                lm = m;
            if (cc >= s0 && cc < s1 && c < lc)     /* a cell centred in the section (one shorter than a section) */
                lc = c;
        }
    }
    if (shut) {
        *amin = 0;
        return 0;
    }
    *amin = lm;
    if (w->narrowest == 1)
        return lo < a ? lo : a;
    return w->narrowest == 2 && lc < a ? lc : a;
}

CH_HOT void ch_wave_block(ch_wave_t *w, const ch_shape_t *t, int steps, const ch_glottis_params_t *g, ch_real p_lung)
{
    const ch_real inv1 = R(1.0) / (ch_real)w->ctrl_every;
    ch_real inv, len, target[CH_WMAX], tmin[CH_WMAX], a1, a2, a3, sum, per;
    int i, m;
    /* the lungs' pressure, ramped over the step; the glottis's parameters */
    w->p_lung = p_lung;
    if (w->p_lung == 0)
        tiny(&w->p_lung_now);
    w->p_lung_step = (w->p_lung - w->p_lung_now) * inv1;
    w->glottis.p = *g;
    w->glot_sealed = g->closure >= R(0.5);   /* (the sealed pockets': a sealed glottis keeps a chink of ~0.001 cm²) */
    glottis_flush_tiny(&w->glottis);
    tiny(&w->dc_x);
    tiny(&w->dc_y);
    tiny(&w->lim_env);
#ifdef CH_WAVE_NANCHECK
    {
        static int since, told;
        since = t ? 0 : since + 1;
        if (told < 6 && (w->w1 < -1e-3 || w->w2 < -1e-3 || w->w3 < -1e-3 || w->w1 > 2.001 || w->w2 > 2.001 || w->w3 > 2.001)) {
            told++;
            fprintf(stderr, "nose weights out of range: %g %g %g (d %g %g %g), jv %d, %d blocks since a shape, steps %d\n",
                    (double)w->w1, (double)w->w2, (double)w->w3, (double)w->dw1, (double)w->dw2, (double)w->dw3, w->jv, since, steps);
        }
    }
#endif
    /* a snapped release's glide is done (it took one control step) */
    for (i = 0; i < w->n_fast; i++) {
        const int f = w->fast[i];
        w->sec[f].darea = w->sec[f].dk = 0;
        if (f + 1 < w->n)
            w->sec[f + 1].dk = 0;
    }
    w->n_fast = 0;
    if (!t)
        return;                                 /* no new shape: the tract glides on */
    inv = inv1 / (ch_real)(steps < 1 ? 1 : steps);
    len = t->ph_len + t->or_len;
    /* the tract's length: as many sections as fit (the lips move a section at a time; a new section starts as the
     * lips' was, an old one's waves go) */
    if (w->var_len) {
        int nl = (int)js_round_c(len / w->dx);
        nl = nl < 8 ? 8 : nl > CH_WMAX ? CH_WMAX : nl;
        for (i = w->n; i < nl; i++) {
            w->sec[i] = w->sec[w->n - 1];
            w->sec[i].dk = w->sec[i].darea = 0;
        }
        if (nl != w->n)
            w->len_changed = 1;
        w->n = nl;
    }
    per = len / (ch_real)w->n;
    /* the tract's sections from the anatomy, spread over its current length; their reflections, ramped */
    for (i = 0; i < w->n; i++) {
        target[i] = tube_area(w, t, (ch_real)i * per, (ch_real)(i + 1) * per, w->closed_area, &tmin[i]);
        tmin[i] = mn(tmin[i], target[i]);
        if (w->sec[i].area < 0)                /* (a ramp to 0 that float left a hair below) */
            w->sec[i].area = 0;
        w->sec[i].darea = (target[i] - w->sec[i].area) * inv;
    }
    /* the sections shut now or by the end of the step */
    w->n_shut = 0;
    for (i = 0; i < w->n; i++)
        if (w->sec[i].area < w->closed_area || target[i] < w->closed_area)
            w->shut[w->n_shut++] = (int16_t)i;
    /* the closures opening beside a sealed pocket this step (snapped, below) */
    for (i = 0; i < w->n_pock; i++) {
        const int e[2] = {w->pock[i].glottis ? -1 : w->pock[i].a - 1, w->pock[i].b + 1};
        int j;
        for (j = 0; j < 2; j++)
            if (e[j] >= 0 && e[j] < w->n && w->n_fast < 4 && target[e[j]] >= w->closed_area && w->sec[e[j]].area < w->closed_area)
                w->fast[w->n_fast++] = (int16_t)e[j];
    }
    /* the sealed pockets: runs of open anatomy cells shut in at both ends (a closed cell, or the glottis sealed at the
     * start), the nose not open to them; each one's pressure moves with its volume, over the cells it had at the last
     * shape too (a cell joining or leaving as a closure's front moves is no volume change: that read as a jump) */
    {
        const ch_real dph = t->ph_len / (ch_real)t->n_ph, dor = t->or_len / (ch_real)t->n_or;
        const int nc = t->n_ph + t->n_or, glot = w->glot_sealed, port = t->velum_port_area >= w->closed_area;
        int np = 0, start = glot ? 0 : -1, c, j;
        ch_wpock_t next[2];
        for (c = 0; c < nc && np < 2; c++) {
            const int part = c >= t->n_ph, i = part ? c - t->n_ph : c;
            int c0, c1, a, b, old;
            ch_real v = 0, dv = 0, s0, s1, p = 0, wall = 0;
            if (cell_area(t, part, i, w->closed_area) != 0)
                continue;
            c0 = start;
            c1 = c - 1;
            start = c + 1;
            if (c0 < 0 || c1 < c0 || (port && c0 <= t->n_ph && c1 >= t->n_ph - 1))
                continue;                       /* (no run, an empty one, or the nose opens into it) */
            for (j = c0; j <= c1; j++) {
                const int jp = j >= t->n_ph, ji = jp ? j - t->n_ph : j, vc = jp * CH_MAXTUBE + ji;
                const ch_real aj = jp ? t->or_areas[ji] : t->ph_areas[ji], vj = aj * (jp ? dor : dph);
                v += vj;
                wall += R(1.4) * R(2.0) * SQRT(CH_PI * mx(aj, R(0.02))) * (jp ? dor : dph);
                for (old = 0; old < w->n_pock; old++)
                    if (j >= w->pock[old].c0 && j <= w->pock[old].c1)
                        break;
                if (old < w->n_pock && w->vol_ok)
                    dv += vj - w->vcell[vc];
            }
            /* its sections: the open ones whose middles lie in its cells' span */
            s0 = c0 < t->n_ph ? (ch_real)c0 * dph : t->ph_len + (ch_real)(c0 - t->n_ph) * dor;
            s1 = c1 < t->n_ph ? (ch_real)(c1 + 1) * dph : t->ph_len + (ch_real)(c1 + 1 - t->n_ph) * dor;
            a = (int)(s0 / per + R(0.5));
            b = (int)(s1 / per - R(0.5));
            a = a < (c0 == 0 && glot ? 0 : 1) ? (c0 == 0 && glot ? 0 : 1) : a;   /* (a closure before it, or the glottis) */
            b = b > w->n - 2 ? w->n - 2 : b;    /* (a pocket always has a closure past it) */
            while (a <= b && target[a] < w->closed_area)
                a++;
            while (b >= a && target[b] < w->closed_area)
                b--;
            if (a > b || v <= 0)
                continue;
            /* its pressure: the old pocket's it overlaps, moved by the volume change; a new one starts at the pressure
             * its waves hold */
            for (old = 0; old < w->n_pock; old++)
                if (c0 <= w->pock[old].c1 && c1 >= w->pock[old].c0)
                    break;
            if (old < w->n_pock) {
                p = w->pock[old].p;             /* (where the last step's ramp has brought it) */
            } else {
                for (j = a; j <= b; j++)
                    p += w->sec[j].R[w->p] + w->sec[j].L[w->p];
                p /= (ch_real)(b - a + 1);
            }
            {
                /* the volume change over the pocket's compliance: the air's (V / rho c^2) and its walls' (their area over
                 * CHIMERA's soft-tissue stiffness): a throat's walls give ~14x what its air does */
                ch_real goal = p - dv / (v / w->pock_k + wall * w->pock_wall);
                {
                    const ch_real lim = c0 == 0 && glot ? w->pock_max : w->pock_max_oral;
                    goal = goal > lim ? lim : goal < -lim ? -lim : goal;
                }
                next[np].c0 = (int16_t)c0;
                next[np].c1 = (int16_t)c1;
                next[np].a = (int16_t)a;
                next[np].b = (int16_t)b;
                next[np].glottis = (uint8_t)(c0 == 0 && glot);
                next[np].p = p;
                next[np].dp = (goal - p) * inv;
                np++;
            }
        }
        for (j = 0; j < np; j++)
            w->pock[j] = next[j];
        w->n_pock = (uint8_t)np;
    }
    /* the volume source: what the anatomy's cells behind the first closure lost since the last shape, each cell's own
     * change, spread over this shape's glide. (Not the volume behind the closure then and now: the closure's front
     * moving a cell forward read as that cell's whole volume appearing, a suction that broke the /d/ of "red") */
    {
        const ch_real dph = t->ph_len / (ch_real)t->n_ph, dor = t->or_len / (ch_real)t->n_or;
        ch_real dv = 0;
        int shut = 0, part, n_cells;
        for (part = 0; part < 2; part++) {
            const int nc = part ? t->n_or : t->n_ph;
            for (i = 0; i < nc; i++) {
                const int c = part * CH_MAXTUBE + i;
                const ch_real v = (part ? t->or_areas[i] * dor : t->ph_areas[i] * dph);
                if (!shut && (cell_area(t, part, i, w->closed_area) == 0
                              || (w->vol_src == 2 && (part ? t->or_min[i] : t->ph_min[i]) < w->vol_narrow)))
                    shut = 1;   /* (2: a narrow constriction holds the air back too) */
                if (!shut && w->vol_ok)
                    dv += v - w->vcell[c];
                w->vcell[c] = v;
            }
        }
        n_cells = t->n_ph + t->n_or;
        w->vol_goal = w->vol_src && shut && w->vol_ok && n_cells == w->vol_cells ? -dv * inv * w->fs : 0;
        /* with no breath behind it (a beatboxer's set-up, the gate shut) the air the moving walls push goes back to
         * the lungs through the open glottis, which this end treats as nearly rigid: there the source would build a
         * pressure CHIMERA's open glottis lets go (a lip pop's closing came out ~20 dB too loud). It fades in with
         * the lungs' pressure to 2 cmH2O; speech always has that, so it is as before (0.7.0) */
        w->vol_goal *= clampr(w->p_lung / (R(2.0) * R(980.665)), 0, R(1.0));
        w->vol_ok = 1;
        w->vol_cells = n_cells;
    }
    /* every junction restarts from its sections' areas now (a junction that was the nose's, or had a stale value,
     * would otherwise jump: a one-sample spike) and ramps to the target's */
    w->sec[0].dk = 0;
    for (m = 1; m < w->n; m++) {
        w->sec[m].k = refl(w->sec[m - 1].area, w->sec[m].area);
        w->sec[m].dk = (refl(target[m - 1], target[m]) - w->sec[m].k) * inv;
    }
    /* the snapped releases: their glides (and their two junctions') in one control step */
    for (i = 0; i < w->n_fast; i++) {
        const int f = w->fast[i];
        const ch_real x = (ch_real)(steps < 1 ? 1 : steps);
        w->sec[f].darea *= x;
        w->sec[f].dk *= x;
        if (f + 1 < w->n)
            w->sec[f + 1].dk *= x;
    }
    /* the nose: where it joins, the port's area as its first section. It moves only when the pharynx's end is a whole
     * section away (not half): a move restarts the junction's waves, and with the nose carrying the mouth's held
     * pressure (a closed /m/ or /n/) that was a spike, a crack as the word ended (before 0.6.0 it moved at every half) */
    {
        const ch_real at = t->ph_len / per;
        if (w->jv < 1 || w->jv > w->n - 1 || FABS(at - (ch_real)w->jv) >= R(1.0)) {
            m = (int)js_round_c(at);
            w->jv = m < 1 ? 1 : m > w->n - 1 ? w->n - 1 : m;
        }
    }
    /* (its weights likewise restart from the areas now, at its junction; clamped at 0, as refl's) */
    a1 = mx(0, w->sec[w->jv - 1].area);
    a2 = mx(0, w->sec[w->jv].area);
    a3 = mx(0, w->nsec[0].area);
    sum = a1 + a2 + a3;
    if (sum > R(1e-9)) {
        w->w1 = R(2.0) * a1 / sum;
        w->w2 = R(2.0) * a2 / sum;
        w->w3 = R(2.0) * a3 / sum;
    }
    a1 = mx(0, target[w->jv - 1]);
    a2 = mx(0, target[w->jv]);
    a3 = mx(0, t->velum_port_area);
    sum = a1 + a2 + a3;
    if (sum > R(1e-9)) {
        w->dw1 = (R(2.0) * a1 / sum - w->w1) * inv;
        w->dw2 = (R(2.0) * a2 / sum - w->w2) * inv;
        w->dw3 = (R(2.0) * a3 / sum - w->w3) * inv;
    } else {
        w->dw1 = w->dw2 = w->dw3 = 0;
    }
    /* the velum port: its junction ramps to the new area's too (before 0.6.0 it was set at once: with the nose
     * carrying the mouth's held pressure, as a final /m/ opens, that step was a one-sample spike, a crack at the
     * word's end) */
    w->nsec[1].k = refl(w->nsec[0].area, w->nsec[1].area);
    w->nsec[0].area = a3;
    w->nk1_d = (refl(a3, w->nsec[1].area) - w->nsec[1].k) * inv;
    rad_coefs(w, 0, target[w->n - 1]);
    rad_coefs(w, 1, w->nsec[w->nn - 1].area);
    /* the jets: CHIMERA's kinetic coefficients (the expansion leaving the narrower side, a tenth of it entering) at
     * each open junction narrower than CH_WJET_AREA; a jet already there keeps its flow. The coefficients ramp from
     * the sections' areas now to the target's, as the reflections do (before 0.6.0 the target's were set at once:
     * lips closing fast, as into a final /m/, stepped the flow through them, a one-sample spike) */
    {
        ch_real u_old[CH_WMAX];
        int j, nj = 0;
        for (m = 0; m < w->n; m++)
            u_old[m] = 0;
        for (j = 0; j < w->n_jet; j++)
            u_old[w->jet_m[j]] = w->jet_u[j];
        for (m = 1; m < w->n && nj < 8; m++) {
            ch_real a = target[m - 1], b = target[m], kf, kb, kf0, kb0;
            if (m == w->jv || mn(a, b) >= (ch_real)CH_WJET_AREA || a < w->closed_area || b < w->closed_area)
                continue;
            jet_coefs(a, b, &kf, &kb);
            if (w->sec[m - 1].area >= w->closed_area && w->sec[m].area >= w->closed_area)
                jet_coefs(w->sec[m - 1].area, w->sec[m].area, &kf0, &kb0);
            else
                kf0 = kf, kb0 = kb;                    /* (still closed: it starts at the opening's) */
            w->jet_m[nj] = (int16_t)m;
            w->jet_kf[nj] = kf0;
            w->jet_kb[nj] = kb0;
            w->jet_dkf[nj] = (kf - kf0) * inv;
            w->jet_dkb[nj] = (kb - kb0) * inv;
            w->jet_u[nj] = u_old[m];
            nj++;
        }
        w->n_jet = (uint8_t)nj;
    }
    /* the walls' area: each section's perimeter (CHIMERA's pharynx and oral, ~1.4 x a circle's) times its length */
    {
        ch_real sum_w = 0;
        for (i = 0; i < w->n; i++)
            sum_w += R(1.4) * R(2.0) * SQRT(CH_PI) * SQRT(mx(target[i], R(0.02))) * w->dx;
        w->wall_s = sum_w;
    }
    /* the noise sources: CHIMERA's list, each one's filter kept while its source and target stay (as
     * set_noise_sources), at the junctions nearest its places */
    {
        ch_noise_t next[4];
        int16_t src[4], fwd[4], bwd[4];
        int j, q;
        for (j = 0; j < t->n_noise && j < 4; j++) {
            const ch_real *s = t->noise_s[j];
            next[j] = t->noise[j];
            next[j].hp = next[j].lp1 = next[j].lp2 = 0;
            src[j] = (int16_t)(s[0] <= 0 ? -1 : js_round_c(s[0] / per));
            fwd[j] = (int16_t)js_round_c(s[1] / per);
            bwd[j] = (int16_t)(s[2] < 0 ? 0 : js_round_c(s[2] / per));
            if (src[j] >= 0)
                src[j] = (int16_t)(src[j] < 1 ? 1 : src[j] > w->n - 1 ? w->n - 1 : src[j]);
            fwd[j] = (int16_t)(fwd[j] < 1 ? 1 : fwd[j] > w->n - 1 ? w->n - 1 : fwd[j]);
            bwd[j] = (int16_t)(bwd[j] > w->n - 1 ? w->n - 1 : bwd[j]);
            for (q = 0; q < w->n_noise; q++)
                if (w->noise[q].src == next[j].src && w->noise[q].fwd == next[j].fwd) {
                    next[j].hp = w->noise[q].hp;
                    next[j].lp1 = w->noise[q].lp1;
                    next[j].lp2 = w->noise[q].lp2;
                    break;
                }
        }
        for (q = 0; q < j; q++) {
            w->noise[q] = next[q];
            w->n_src[q] = src[q];
            w->n_fwd[q] = fwd[q];
            w->n_bwd[q] = bwd[q];
        }
        w->n_noise = (uint8_t)j;
    }
}

/* the glottis's volume flow: CHIMERA's port branch (inertance, slit resistance, entrance and exit losses) solved
 * against the lungs' pressure and the pressure just above the glottis, together: that pressure is the wave
 * arriving at the glottis and its reflection, (1 + r) l0, plus the flow's own push into the tract, Z0 U. Solved with
 * the flow (Z0 joins the resistance) the coupling is stable; lagged a sample, an open glottis (a large flow for a
 * small pressure) made it oscillate. */
CH_HOT static ch_real glottal_flow(ch_wave_t *w, ch_real a_g, ch_real l0, ch_real a0)
{
    const ch_real half_rho = R(0.5) * RHO;
    ch_real ip, l, r, kin, ex_f, ex_b, kf, kb, m0, c, bq, x, kk, u;
    if (a_g < w->closed_area) {
        w->mom = 0;
        w->l_prev = 0;
        return 0;
    }
    ip = R(1.0) / a_g;
    l = RHO * w->port_len * ip + half_rho * w->dx / a0;
    r = (R(12.0) * MU * w->port_len * w->port_slit * w->port_slit) * ip * ip * ip;
    kin = w->port_kin * half_rho * ip * ip;
    ex_f = ip - R(1.0) / a0;                          /* a flow up exits into the tract */
    ex_b = ip - R(1.0) / R(2.5);                      /* and a flow down into the trachea (HumanAirway's 2.5 k² cm²) */
    kf = kin + w->port_kex * half_rho * (ex_f > 0 ? ex_f * ex_f : 0);
    kb = kin + w->port_kex * half_rho * (ex_b > 0 ? ex_b * ex_b : 0);
    m0 = l < w->l_prev ? l * w->u_g : w->mom;          /* energy-safe, as CHIMERA */
    c = w->p_lung_now - (R(1.0) + w->r_glottis) * l0 + m0 * w->fs;
    bq = l * w->fs + r + w->rho_c / a0;
    kk = c >= 0 ? kf : kb;
    x = R(4.0) * kk * FABS(c) / (bq * bq);
    u = (c / bq) * R(2.0) / (R(1.0) + SQRT(R(1.0) + x));
    w->mom = l * u;
    w->l_prev = l;
    return u;
}

/* the turbulence: each source's flow, CHIMERA's law, its filtered noise as a series pressure source at its junction
 * (half each way); after the scattering, into the waves just written (q) */
CH_HOT static void noise(ch_wave_t *w, int p, int q, ch_real ug)
{
    const ch_real scale = SQRT(R(96000.0) / w->fs);   /* CHIMERA's noise: white noise power normalised to 96 kHz */
    const ch_real sat = R(12000.0) * R(12000.0) - RE_CRIT * RE_CRIT;
    int j;
    for (j = 0; j < w->n_noise; j++) {
        ch_noise_t *ns = &w->noise[j];
        ch_real a, u, amp = 0, wn, hpo, v;
        int at;
        if (w->n_src[j] < 0) {
            a = w->a_g;
            u = ug;
        } else {
            const ch_wsec_t *s0 = &w->sec[w->n_src[j] - 1], *s1 = &w->sec[w->n_src[j]];
            a = mn(s0->area, s1->area);
            u = (s0->R[p] - s0->L[q]) * s0->area / w->rho_c;
        }
        if (a > w->closed_area) {
            ch_real re = (FABS(u) * R(2.0)) / (NU * SQRT(CH_PI * a));
            if (re > RE_CRIT) {
                ch_real xx = re * re - RE_CRIT * RE_CRIT;
                amp = (ns->gain * w->noise_gain * scale * xx) / (R(1.0) + xx / sat);
            }
        }
        wn = rng_bipolar(&w->rng) * R(1.732);
        ns->hp += (R(1.0) - ns->hp_coef) * (wn - ns->hp);
        hpo = wn - ns->hp;
        ns->lp1 += ns->lp_coef * (hpo - ns->lp1);
        ns->lp2 += ns->lp_coef * (ns->lp1 - ns->lp2);
        ns->level = amp;
        if (amp == 0)
            continue;
        v = R(0.5) * amp * ns->lp2;
        if (u < 0)
            v = -v;
        at = u >= 0 ? w->n_fwd[j] : w->n_bwd[j];
        w->sec[at].R[q] += v;
        if (at > 0)
            w->sec[at - 1].L[q] -= v;
    }
}

CH_HOT void ch_wave_render(ch_wave_t *w, float *out, uint32_t n)
{
    const int nt = w->n, nn = w->nn, jv = w->jv;
    const ch_real fade = w->fade, fade_n = w->fade_n;
    ch_wsec_t *const sec = w->sec;
    ch_wnsec_t *const ns = w->nsec;
    uint32_t i;
    for (i = 0; i < n; i++) {
        const int p = w->p, q = p ^ 1;
        ch_real ug, x, hp, av, al, rad, y, r0, in, pj;
        ch_wsec_t *s;
        int m;
        w->p_lung_now += w->p_lung_step;
        /* the glottis: its area from the pressure across it, its flow (solved with the tract's push back) */
        w->a_g = glottis_next(&w->glottis, w->p_lung_now - w->p_supra);
        ug = w->u_g = glottal_flow(w, w->a_g, sec[0].L[p], mx(sec[0].area, R(1e-4)));
        /* the walls: moved by the pressure above the glottis (CHIMERA's update, its constants per cm²) */
        if (w->walls) {
            const ch_real m_t = R(1.5) * w->fs, half_r = R(0.5) * R(1600.0);
            ch_real v = ((m_t - half_r) * w->wall_v + w->p_supra - R(3.0e5) * w->wall_y) / (m_t + half_r);
            w->wall_v = v;
            w->wall_y += v / w->fs;
            w->wall_u = w->wall_s * v;
        }
        /* the glottis's end: a flow source at a nearly rigid end, less what the walls take */
        w->vol_u += w->vol_smooth * (w->vol_goal - w->vol_u);   /* (a step's change is not a click) */
        r0 = w->r_glottis * sec[0].L[p] + w->rho_c / mx(sec[0].area, R(1e-4)) * (ug - w->wall_u + w->vol_u);
        sec[0].R[q] = r0 * fade;
        w->p_supra = sec[0].L[p] + r0;
        /* the junctions below and above the nose's: the arriving waves, scattered (and the ramps) */
        for (m = 1, s = sec + 1; m < jv; m++, s++) {
            ch_real in1 = s[-1].R[p], in2 = s->L[p], d = s->k * (in1 - in2);
            s->R[q] = (in1 + d) * fade;
            s[-1].L[q] = (in2 + d) * fade;
            s->k += s->dk;
            s[-1].area += s[-1].darea;
        }
        /* the nose's junction */
        {
            ch_real in1 = sec[jv - 1].R[p], in2 = sec[jv].L[p], in3 = ns[0].L[p];
            w->w1 += w->dw1;
            w->w2 += w->dw2;
            w->w3 += w->dw3;
            pj = w->w1 * in1 + w->w2 * in2 + w->w3 * in3;
            sec[jv].R[q] = (pj - in2) * fade;
            sec[jv - 1].L[q] = (pj - in1) * fade;
            ns[0].R[q] = (pj - in3) * fade_n;
            ns[1].k += w->nk1_d;
            sec[jv].k += sec[jv].dk;
            sec[jv - 1].area += sec[jv - 1].darea;
        }
        for (m = jv + 1, s = sec + jv + 1; m < nt; m++, s++) {
            ch_real in1 = s[-1].R[p], in2 = s->L[p], d = s->k * (in1 - in2);
            s->R[q] = (in1 + d) * fade;
            s[-1].L[q] = (in2 + d) * fade;
            s->k += s->dk;
            s[-1].area += s[-1].darea;
        }
        sec[nt - 1].area += sec[nt - 1].darea;
        /* the jets: those junctions again, with the series resistance (the loss times last sample's flow), from
         * the sections' areas as they are now (from the target's, a release opened in one sample: a spike):
         * U = 2 (in1 - in2) / (Z1 + Z2 + R), out right in2 + Z2 U, out left in1 - Z1 U */
        for (m = 0; m < w->n_jet; m++) {
            const int jm = w->jet_m[m];
            const ch_real a1 = sec[jm - 1].area, a2 = sec[jm].area;
            ch_real in1, in2, up, rj, z1, z2, u;
            w->jet_kf[m] += w->jet_dkf[m];
            w->jet_kb[m] += w->jet_dkb[m];
            if (a1 < w->closed_area || a2 < w->closed_area) {
                w->jet_u[m] = 0;
                continue;                       /* still closed on one side: the plain scattering's reflection */
            }
            in1 = sec[jm - 1].R[p];
            in2 = sec[jm].L[p];
            up = w->jet_u[m];
            rj = mx(0, up >= 0 ? w->jet_kf[m] : w->jet_kb[m]) * FABS(up);
            z1 = w->rho_c / a1;
            z2 = w->rho_c / a2;
            u = R(2.0) * (in1 - in2) / (z1 + z2 + rj);
            sec[jm].R[q] = (in2 + z2 * u) * fade;
            sec[jm - 1].L[q] = (in1 - z1 * u) * fade;
            w->jet_u[m] = u;
        }
        /* a section shut (or still shut) holds no air: its waves emptied while its area is under the closure's */
        for (m = 0; m < w->n_shut; m++) {
            ch_wsec_t *c = &sec[w->shut[m]];
            if (c->area < w->closed_area)
                c->R[q] = c->L[q] = 0;
        }
        /* the sealed pockets: each one's sections held at its pressure (their mean moved to it, the waves' differences
         * kept), until a closure around it opens; then the waves carry the pressure out (the click, the ejective) */
        for (m = 0; m < w->n_pock; m++) {
            ch_wpock_t *k = &w->pock[m];
            ch_real sum = 0, d;
            int j;
            if ((k->glottis ? !w->glot_sealed : sec[k->a - 1].area >= w->closed_area && sec[k->a - 1].darea >= 0)
                || (sec[k->b + 1].area >= w->closed_area && sec[k->b + 1].darea >= 0)) {   /* (a closure still closing
                                                                                       * keeps it sealed) */
                w->pock[m] = w->pock[--w->n_pock];   /* (open: it is the waves' now) */
                m--;
                continue;
            }
            k->p += k->dp;
            for (j = k->a; j <= k->b; j++)
                sum += sec[j].R[q] + sec[j].L[q];
            d = R(0.5) * (k->p - sum / (ch_real)(k->b - k->a + 1));
            for (j = k->a; j <= k->b; j++) {
                sec[j].R[q] += d;
                sec[j].L[q] += d;
            }
        }
        /* the lips: the radiation load's reflection */
        in = sec[nt - 1].R[p];
        if (w->rad_load) {
            y = w->rb0[0] * in + w->rb1[0] * w->r_in[0] - w->ra1[0] * w->r_out[0];
            w->r_in[0] = in;
            w->r_out[0] = y;
        } else {
            y = w->r_lip * in;
        }
        sec[nt - 1].L[q] = y * fade;
        rad = (in - y) * sec[nt - 1].area;
        /* the nose's junctions and the nostrils */
        for (m = 1; m < nn; m++) {
            ch_real in1 = ns[m - 1].R[p], in2 = ns[m].L[p], d = ns[m].k * (in1 - in2);
            ns[m].R[q] = (in1 + d) * fade_n;
            ns[m - 1].L[q] = (in2 + d) * fade_n;
        }
        in = ns[nn - 1].R[p];
        if (w->rad_load) {
            y = w->rb0[1] * in + w->rb1[1] * w->r_in[1] - w->ra1[1] * w->r_out[1];
            w->r_in[1] = in;
            w->r_out[1] = y;
        } else {
            y = w->r_nose * in;
        }
        ns[nn - 1].L[q] = y * fade_n;
        rad = (rad + (in - y) * ns[nn - 1].area) / w->rho_c   /* the flow leaving the lips and the nostrils */
              + (w->glot_sealed ? R(0.2) * w->wall_rad : w->wall_rad) * w->wall_u;   /* and the walls' share */
        /* the turbulence, into the waves just written */
        if (w->n_noise)
            noise(w, p, q, ug);
        w->p = q;
#ifdef CH_WAVE_DEBUG
        {
            extern long ch_wave_dbg_from, ch_wave_dbg_pos;
            if (ch_wave_dbg_pos >= ch_wave_dbg_from && ch_wave_dbg_pos < ch_wave_dbg_from + 24) {
                int qq, mi = 0;
                for (qq = 1; qq < nt; qq++)
                    if (sec[qq].area < sec[mi].area)
                        mi = qq;
                printf("    %ld: rad %7.2f | area/press/flow per section 15..21:", ch_wave_dbg_pos, (double)rad);
                for (qq = 15; qq < nt; qq++)
                    printf("  [%d %.3f %6.0f %6.1f]", qq, (double)sec[qq].area, (double)(sec[qq].R[q] + sec[qq].L[p]),
                           (double)((sec[qq].R[q] - sec[qq].L[p]) * sec[qq].area / w->rho_c));
                printf("\n");
                (void)mi;
            }
            ch_wave_dbg_pos++;
        }
#endif
#ifdef CH_WAVE_NANCHECK
        {   /* (lab / host: the first sample whose state is not a number, and what led to it) */
            static int told;
            if (!told && !(w->p_supra < 1e5 && w->p_supra > -1e5)) {
                int qq;
                told = 1;
                fprintf(stderr, "wave out of range: rad %g ug %g a_g %g p_supra %g p_lung %g vol_u %g wall_v %g y %g\n", (double)rad,
                        (double)ug, (double)w->a_g, (double)w->p_supra, (double)w->p_lung_now, (double)w->vol_u,
                        (double)w->wall_v, (double)w->wall_y);
                fprintf(stderr, "  glottis: mom %g l_prev %g u_g %g\n", (double)w->mom, (double)w->l_prev, (double)w->u_g);
                for (qq = 0; qq < nt; qq++)
                    fprintf(stderr, "  sec %2d area %9.5f darea %9.2e k %8.5f dk %9.2e R %10.4g L %10.4g\n", qq, (double)sec[qq].area,
                            (double)sec[qq].darea, (double)sec[qq].k, (double)sec[qq].dk, (double)sec[qq].R[q], (double)sec[qq].L[q]);
                for (qq = 0; qq < w->n_jet; qq++)
                    fprintf(stderr, "  jet at %d: kf %g kb %g u %g\n", w->jet_m[qq], (double)w->jet_kf[qq], (double)w->jet_kb[qq], (double)w->jet_u[qq]);
                for (qq = 0; qq < w->n_noise; qq++)
                    fprintf(stderr, "  noise %d: src %d fwd %d level %g\n", qq, w->n_src[qq], w->n_fwd[qq], (double)w->noise[qq].level);
                fprintf(stderr, "  nose: w1 %g w2 %g w3 %g jv %d; rad filter %g %g %g\n", (double)w->w1, (double)w->w2, (double)w->w3, jv,
                        (double)w->rb0[0], (double)w->rb1[0], (double)w->ra1[0]);
            }
        }
#endif
        /* the output: the radiated flow's derivative, CHIMERA's DC blocker and limiter */
        if (w->len_changed) {               /* the lips moved a section: no jump in the derivative */
            w->rad_prev = rad;
            w->len_changed = 0;
        }
        y = (rad - w->rad_prev) * w->fs;
        w->rad_prev = rad;
        x = y * w->output_gain;
        hp = x - w->dc_x + w->dc_coef * w->dc_y;
        w->dc_x = x;
        w->dc_y = hp;
        x = hp;
        if (w->shelf_g != 0) {
            w->shelf_lp = x + w->shelf_c * (w->shelf_lp - x);
            x += w->shelf_g * (x - w->shelf_lp);
        }
        av = FABS(x);
        if (av >= w->lim_env) {
            w->lim_env = av;
            w->lim_hold = w->lim_hold_n;
        } else if (w->lim_hold > 0) {
            w->lim_hold -= 1;
        } else {
            w->lim_env *= w->lim_release;
        }
        if (w->lim_env > R(0.75))
            x *= R(0.75) / w->lim_env;
        al = FABS(x);
        if (al > R(0.85))
            x = (x < 0 ? R(-1.0) : R(1.0)) * (R(0.85) + R(0.15) * TANH((al - R(0.85)) / R(0.15)));
        out[i] = (float)x;
    }
    /* the safety net: a state that is no longer a number stays so (the voice silent until power-off), so it is
     * cleared, the shape and the glottis kept. (0.0.8's nose junction did this; ch_wave_t.resets counts them) */
    if (!(w->p_supra == w->p_supra && w->p_supra < R(1e7) && w->p_supra > R(-1e7) && w->rad_prev == w->rad_prev)) {
        int m;
        for (m = 0; m < CH_WMAX; m++)
            w->sec[m].R[0] = w->sec[m].R[1] = w->sec[m].L[0] = w->sec[m].L[1] = 0;
        for (m = 0; m < CH_WNMAX; m++)
            w->nsec[m].R[0] = w->nsec[m].R[1] = w->nsec[m].L[0] = w->nsec[m].L[1] = 0;
        for (m = 0; m < 8; m++)
            w->jet_u[m] = 0;
        w->n_pock = 0;
        w->u_g = w->l_prev = w->mom = w->p_supra = 0;
        w->wall_v = w->wall_y = w->wall_u = w->vol_u = 0;
        w->r_in[0] = w->r_in[1] = w->r_out[0] = w->r_out[1] = 0;
        w->rad_prev = w->dc_x = w->dc_y = w->lim_env = 0;
        for (i = 0; i < n; i++)
            out[i] = 0;
        w->resets++;
    }
}
