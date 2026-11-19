/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Jameson Hodge (CHIMERA, https://github.com/jamesondh/chimera-vocal-synth)
 * C port for the M-VAVE FM-1: Copyright (c) 2026 Shan-Sherif.
 *
 * A literal port of CHIMERA's audio rate; see ch_voice.h. Section numbers and comments follow
 * the Rust (crates/chimera/src/airway/network.rs AirwayNetwork::step and friends). Built as double
 * it matches CHIMERA to ~1e-10; it is the reference the fast version (ch_fast.c) is measured against. */
#include "ch_common.h"

void ch_glottis_init(ch_glottis_t *g, ch_real fs) { glottis_init(g, fs); }
void ch_init_state(ch_voice_t *v) { init_state(v); }
void ch_block(ch_voice_t *v, const ch_block_t *b) { block_common(v, b); }

static ch_real step(ch_voice_t *v)
{
    const int n = v->n, nb = v->nb;
    const ch_real t = v->t, inv_t = v->inv_t, min_a = v->min_node_area, sq = v->squeeze_area;
    const int vol_on = v->enable_volume_source;
    ch_real visc, closed, half_rho, wall_rad_sum, t2, rad_total, cl, out;
    int k, j;

    /* 1) geometry ramps and the moving-wall volume source */
    if (vol_on)
        for (k = 0; k < n; k++)
            v->spill[k] = 0;
    for (k = 0; k < n; k++) {
        ch_real a = v->area[k] + v->area_step[k], am, amk, d, ia, sa, vv;
        if (a < 0)
            a = 0;
        v->area[k] = a;
        am = v->amin[k] + v->amin_step[k];
        if (am < 0)
            am = 0;
        amk = am > a ? a : am;
        v->amin[k] = amk;
        v->dx[k] += v->dx_step[k];
        d = v->dx[k];
        ia = R(1.0) / (a > R(1e-6) ? a : R(1e-6));
        v->inv_a[k] = ia;
        v->inv_amin[k] = R(1.0) / (amk > R(1e-6) ? amk : R(1e-6));
        sa = SQRT(a);
        v->sqrt_a[k] = sa;
        v->half_l[k] = d * ia;
        v->half_r[k] = v->wall_perim[k] * d * sa * ia * ia * v->visc_f[k];
        vv = (a > min_a ? a : min_a) * d;
        if (vol_on) {
            ch_real raw = -(vv - v->vol[k]) * inv_t;
            ch_real ae = v->amin[k] < a ? v->amin[k] : a;
            if (raw != 0 && ae < sq) {
                ch_real tt = ae / sq, keep = tt * tt * (R(3.0) - R(2.0) * tt), sp;
                int e, s0 = v->nbr_start[k], s1 = v->nbr_start[k + 1], n_open = 0;
                v->spill[k] += raw * keep;
                sp = raw * (R(1.0) - keep);
                for (e = s0; e < s1; e++) {
                    int m = v->nbr_list[e];
                    if (mn(v->area[m], v->amin[m]) >= sq || v->amin_target[m] >= sq)
                        n_open++;
                }
                if (n_open > 0) {
                    ch_real share = sp / (ch_real)n_open;
                    for (e = s0; e < s1; e++) {
                        int m = v->nbr_list[e];
                        if (mn(v->area[m], v->amin[m]) >= sq || v->amin_target[m] >= sq)
                            v->spill[m] += share;
                    }
                }
            } else {
                v->spill[k] += raw;
            }
        }
        v->vol[k] = vv;
        v->inv_lsum[k] = 0;
        v->flow_sum[k] = 0;   /* + ext_flow, which nothing in the human voice sets */
    }
    if (vol_on) {
        ch_real c = v->vol_lp_coef;
        for (k = 0; k < n; k++) {
            ch_real s1;
            v->vol_src1[k] += c * (v->spill[k] - v->vol_src1[k]);
            s1 = v->vol_src1[k];
            v->vol_src2[k] += c * (s1 - v->vol_src2[k]);
            v->flow_sum[k] += v->vol_src2[k];
        }
    }

    /* 2) turbulence noise → series pressure sources */
    for (j = 0; j < nb; j++)
        v->psrc[j] = 0;
    if (v->n_noise)
        compute_noise(v);

    /* 3) branches */
    visc = SQRT((RHO * MU * v->viscous_omega) / R(2.0)) * v->viscous_scale;
    closed = v->closed_area;
    half_rho = R(0.5) * RHO;
    for (j = 0; j < nb; j++) {
        int a = v->b_a[j], b = v->b_b[j];
        ch_real l, r, kf, kb, a_eff, lp, m0, c, bq, u;
        if (v->b_kind[j] == CH_SERIES) {
            ch_real aa = v->area[a], ab = v->area[b], ma = v->amin[a], mb = v->amin[b];
            ch_real ia, ib, ima, imb, ef, eb, cf, cb;
            a_eff = ma < mb ? ma : mb;
            if ((aa < ab ? aa : ab) < closed || a_eff < closed) {
                v->u[j] = 0;
                v->mom[j] = 0;
                v->branch_area[j] = a_eff;
                continue;
            }
            ia = v->inv_a[a];
            ib = v->inv_a[b];
            l = half_rho * (v->half_l[a] + v->half_l[b]);
            /* viscous: R = S·l/A² · sqrt(ρμω/2) with S = perimeter of each half-section */
            r = R(0.5) * visc * (v->half_r[a] + v->half_r[b]);
            /* Borda–Carnot */
            ima = v->inv_amin[a];
            imb = v->inv_amin[b];
            ef = ima - ib;
            eb = imb - ia;
            kf = ef > 0 ? half_rho * ef * ef : 0;
            kb = eb > 0 ? half_rho * eb * eb : 0;
            /* small contraction (vena contracta) loss entering a narrower node */
            cf = imb - ia;
            cb = ima - ib;
            if (cf > 0)
                kf += R(0.1) * half_rho * cf * cf;
            if (cb > 0)
                kb += R(0.1) * half_rho * cb * cb;
        } else {
            ch_real ap, ip, len, ll, w, rr, ex_f, ex_b, kin;
            v->port_area[j] += v->port_area_step[j];
            ap = v->port_area[j];
            if (ap < 0) {
                v->port_area[j] = 0;
                ap = 0;
            }
            a_eff = ap;
            if (ap < closed) {
                v->u[j] = 0;
                v->mom[j] = 0;
                v->branch_area[j] = ap;
                continue;
            }
            ip = R(1.0) / ap;
            len = v->port_len[j];
            ll = RHO * len * ip;
            if (v->port_half[j])
                ll += half_rho * (v->half_l[a] + v->half_l[b]);
            l = ll;
            w = v->port_slit[j];
            rr = w > 0 ? (R(12.0) * MU * len * w * w) * ip * ip * ip
                       : visc * R(2.0) * SQRT(CH_PI) * len * SQRT(ap) * ip * ip;
            rr += v->port_extra_r[j];
            r = rr;
            ex_f = ip - v->inv_a[b];
            ex_b = ip - v->inv_a[a];
            kin = v->port_kin[j] * half_rho * ip * ip;
            kf = kin + v->port_kex[j] * half_rho * (ex_f > 0 ? ex_f * ex_f : 0);
            kb = kin + v->port_kex[j] * half_rho * (ex_b > 0 ? ex_b * ex_b : 0);
        }
        v->branch_area[j] = a_eff;
        if (v->symmetric_kinetic) {
            ch_real km = kf > kb ? kf : kb;
            kf = km;
            kb = km;
        }
        lp = v->l_prev[j];
        m0 = (v->energy_safe && l < lp) ? l * v->u[j] : v->mom[j];
        c = v->p[a] - v->p[b] + v->psrc[j] + m0 * inv_t;
        bq = l * inv_t + r;
        if (c >= 0)
            u = kf > 0 ? (R(2.0) * c) / (bq + SQRT(bq * bq + R(4.0) * kf * c)) : c / bq;
        else if (kb > 0)
            u = (R(2.0) * c) / (bq + SQRT(bq * bq - R(4.0) * kb * c));
        else
            u = c / bq;
        v->u[j] = u;
        v->mom[j] = l * u;
        v->l_prev[j] = l;
        v->flow_sum[a] -= u;
        v->flow_sum[b] += u;
        if (v->is_junction[a] | v->is_junction[b]) {
            ch_real il = R(1.0) / l;
            v->inv_lsum[a] += il;
            v->inv_lsum[b] += il;
        }
    }

    /* 4) walls (velocity at half step from p(n), displacement integrates) */
    wall_rad_sum = 0;
    for (k = 0; k < n; k++) {
        ch_real m, r, vel, sa, uw;
        if (!v->has_wall[k])
            continue;
        m = v->wall_m[k];
        r = v->wall_r[k];
        vel = ((m * inv_t - R(0.5) * r) * v->wall_v[k] + v->p[k] - v->wall_k[k] * v->wall_y[k]) / (m * inv_t + R(0.5) * r);
        v->wall_v[k] = vel;
        v->wall_y[k] += t * vel;
        sa = v->area[k] > min_a ? v->sqrt_a[k] : SQRT(min_a);
        uw = v->wall_perim[k] * sa * v->dx[k] * vel;
        v->flow_sum[k] -= uw;
        wall_rad_sum += uw * v->wall_rad[k];
    }

    /* 5) nodes (radiation loads handled semi-implicitly) */
    t2 = t * t;
    rad_total = wall_rad_sum;
    cl = v->closed_area;
    for (k = 0; k < n; k++) {
        ch_real c;
        int r;
        if (v->fixed[k])
            continue;
        if (v->area[k] < cl || v->amin[k] < cl) {
            int rr = v->rad_of[k];
            if (rr >= 0) {
                v->p[k] *= R(0.98);
            } else {
                ch_real sum = 0;
                int cnt = 0, e;
                for (e = v->nbr_start[k]; e < v->nbr_start[k + 1]; e++) {
                    int m = v->nbr_list[e];
                    if (v->area[m] >= cl && v->amin[m] >= cl) {
                        sum += v->p[m];
                        cnt++;
                    }
                }
                if (cnt > 0)
                    v->p[k] += R(0.02) * (sum / (ch_real)cnt - v->p[k]);
            }
            if (rr >= 0) {
                v->rad_ul[rr] *= R(0.995);
                v->rad_u[rr] = v->rad_ul[rr];
                rad_total += v->rad_ul[rr];
            }
            continue;
        }
        c = v->vol[k] / RHO_C2;
        /* junction stability: local breathing mode ω² ≈ ΣL⁻¹/C must satisfy T²ω² ≤ 2.4 */
        if (v->is_junction[k] && v->enable_junction_floor) {
            ch_real c_min = (t2 * v->inv_lsum[k]) / R(2.4);
            if (c < c_min)
                c = c_min;
        }
        r = v->rad_of[k];
        if (r < 0) {
            v->p[k] += (t / c) * v->flow_sum[k];
        } else {
            ch_real a = v->area[k] > R(1e-4) ? v->area[k] : R(1e-4);
            ch_real lr = (R(8.0) * RHO) / (R(3.0) * CH_PI * SQRT(CH_PI * a));
            ch_real rr = (R(128.0) * RHO * C_SOUND) / (R(9.0) * CH_PI * CH_PI * a);
            ch_real p_old = v->p[k], lp = v->rad_l_prev[r], ul_prev = v->rad_ul[r], h, g, p_new, ul, ur;
            if (lp > 0 && lr > lp)
                ul_prev *= lp / lr;
            v->rad_l_prev[r] = lr;
            /* Crank–Nicolson coupling of the node and its parallel R–L load */
            h = t / (R(2.0) * lr);
            g = R(0.5) / rr;
            p_new = (p_old * (c * inv_t - R(0.5) * h - g) + v->flow_sum[k] - ul_prev) / (c * inv_t + R(0.5) * h + g);
            v->p[k] = p_new;
            ul = ul_prev + h * (p_old + p_new);
            v->rad_ul[r] = ul;
            ur = R(0.5) * (ul_prev + ul) + g * (p_new + p_old);
            v->rad_u[r] = ur;
            rad_total += ur;
        }
    }
    out = (rad_total - v->rad_prev_total) * inv_t;
    v->rad_prev_total = rad_total;
    return out;
}

/* ------------------------------------------------ voice/human_voice.rs: render (oversample 1) --- */
void ch_render(ch_voice_t *v, float *out, uint32_t n)
{
    uint32_t i;
    for (i = 0; i < n; i++) {
        ch_real a, y, x, hp, av, al;
        v->p_lung_now += v->p_lung_step;
        v->p[v->lungs] = v->p_lung_now;
        a = glottis_next(&v->glottis, v->p[v->sub_node] - v->p[v->supra]);
        /* set_port_area */
        v->port_area[v->glottis_port] = a < 0 ? 0 : a;
        v->port_area_target[v->glottis_port] = v->port_area[v->glottis_port];
        v->port_area_step[v->glottis_port] = 0;
        y = step(v);
        x = y * v->output_gain;
        /* DC blocker */
        hp = x - v->dc_x + v->dc_coef * v->dc_y;
        v->dc_x = x;
        v->dc_y = hp;
        x = hp;
        /* output limiter: a held peak envelope, then a soft clip above 0.85 */
        av = FABS(x);
        if (av >= v->lim_env) {
            v->lim_env = av;
            v->lim_hold = v->lim_hold_n;
        } else if (v->lim_hold > 0) {
            v->lim_hold -= 1;
        } else {
            v->lim_env *= v->lim_release;
        }
        if (v->lim_env > R(0.75))
            x *= R(0.75) / v->lim_env;
        al = FABS(x);
        if (al > R(0.85))
            x = (x < 0 ? R(-1.0) : R(1.0)) * (R(0.85) + R(0.15) * TANH((al - R(0.85)) / R(0.15)));
        out[i] = (float)x;
    }
}
