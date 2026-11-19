/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Jameson Hodge (CHIMERA, https://github.com/jamesondh/chimera-vocal-synth)
 * C port for the M-VAVE FM-1: Copyright (c) 2026 Shan-Sherif.
 *
 * A literal port of CHIMERA's anatomy at control rate; see ch_tract.h. The comments are CHIMERA's
 * (crates/chimera/src/anatomy/human.rs, anatomy/geom.rs, voice/human_airway.rs), shortened. */
#include "ch_tract.h"
#include "ch_common.h"

#define DEG (CH_PI / R(180.0))

/* The three inner loops (rays, roof projections, tongue distances) run from RAM on the FM-1 (CH_IN_RAM), the
 * rest from the flash, which calls them through CH_FAR (ch_common.h). */

/* -DCH_COUNT: how often each inner loop runs (lab/port/replay prints it), for the FM-1 cost estimate */
#ifdef CH_COUNT
unsigned long ch_count[8];
#define COUNT(i) (ch_count[i]++)
#else
#define COUNT(i) ((void)0)
#endif
#if defined(CH_ANALYZE) && (defined(__GNUC__) || defined(__clang__))
#define CH_PART __attribute__((noinline))   /* kept apart so tools/pi_cost.py can cost each loop */
#else
#define CH_PART
#endif
enum { CN_CROSS, CN_PROJ, CN_GAP, CN_AREA_AT, CN_BOX, CN_TRIG, CN_STEPS };
#define INF R(1e30)

/* ------------------------------------------------------------------------------- geom.rs --- */
static inline ch_v2 v2(ch_real x, ch_real y) { ch_v2 r; r.x = x; r.y = y; return r; }
static inline ch_v2 add(ch_v2 a, ch_v2 b) { return v2(a.x + b.x, a.y + b.y); }
static inline ch_v2 sub(ch_v2 a, ch_v2 b) { return v2(a.x - b.x, a.y - b.y); }
static inline ch_v2 scl(ch_v2 a, ch_real s) { return v2(a.x * s, a.y * s); }
static inline ch_real dist(ch_v2 a, ch_v2 b) { return HYPOT(a.x - b.x, a.y - b.y); }
static inline ch_v2 lerpv(ch_v2 a, ch_v2 b, ch_real t) { return v2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t); }
static inline ch_real lerp(ch_real a, ch_real b, ch_real t) { return a + (b - a) * t; }
static inline ch_v2 polar(ch_v2 c, ch_real r, ch_real ang) { return v2(c.x + r * COS(ang), c.y + r * SIN(ang)); }
static inline ch_v2 rotate_about(ch_v2 p, ch_v2 c, ch_real ang)
{
    ch_real s = SIN(ang), co = COS(ang), dx = p.x - c.x, dy = p.y - c.y;
    return v2(c.x + dx * co - dy * s, c.y + dx * s + dy * co);
}
static inline ch_v2 norm2(ch_v2 a)
{
    ch_real l = HYPOT(a.x, a.y);
    if (l == 0 || l != l)
        l = 1;
    return v2(a.x / l, a.y / l);
}

static void push(ch_line *l, ch_v2 p)
{
    if (l->n < CH_MAXPTS)
        l->p[l->n++] = p;
}
static void append(ch_line *l, const ch_v2 *pts, int n, int skip_first)
{
    int i = skip_first && n > 0 ? 1 : 0;
    for (; i < n; i++)
        push(l, pts[i]);
}

/* each chunk's bounding box, once the line is built (the rays then skip whole chunks); each segment's reciprocals */
static void line_boxes(ch_line *l)
{
    int c, i;
    for (i = 0; i + 1 < l->n; i++) {
        const ch_real ex = l->p[i + 1].x - l->p[i].x, ey = l->p[i + 1].y - l->p[i].y, l2 = ex * ex + ey * ey;
        l->il2[i] = l2 > R(1e-12) ? R(1.0) / l2 : 0;
        l->slope[i] = ey != 0 ? ex / ey : 0;
    }
    l->nc = l->n > 1 ? (l->n - 1 + CH_CHUNK - 1) / CH_CHUNK : 0;
    for (c = 0; c < l->nc; c++) {
        int i0 = c * CH_CHUNK, i1 = i0 + CH_CHUNK < l->n - 1 ? i0 + CH_CHUNK : l->n - 1;
        ch_real *b = l->box[c];
        b[0] = b[2] = l->p[i0].x;
        b[1] = b[3] = l->p[i0].y;
        for (i = i0 + 1; i <= i1; i++) {
            b[0] = mn(b[0], l->p[i].x);
            b[2] = mx(b[2], l->p[i].x);
            b[1] = mn(b[1], l->p[i].y);
            b[3] = mx(b[3], l->p[i].y);
        }
    }
}

/* the side of a whole box against the line through o along d: +1 / -1 when all of it is clearly on
 * one side (then so is every point the box holds, and no segment in it crosses), 0 when it may cross.
 * The margin keeps a point on or within rounding of the line out of the shortcut, so the points
 * near the line still get CHIMERA's own test. */
CH_INLINE int box_side(const ch_real *b, ch_real ox, ch_real oy, ch_real dx, ch_real dy)
{
    ch_real s0 = dx * (b[1] - oy) - dy * (b[0] - ox), s1 = dx * (b[1] - oy) - dy * (b[2] - ox);
    ch_real s2 = dx * (b[3] - oy) - dy * (b[0] - ox), s3 = dx * (b[3] - oy) - dy * (b[2] - ox);
    const ch_real eps = R(1e-5);
    if (s0 > eps && s1 > eps && s2 > eps && s3 > eps)
        return 1;
    if (s0 < -eps && s1 < -eps && s2 < -eps && s3 < -eps)
        return -1;
    return 0;
}

/* All crossings of the ray o + t·d (t in [t_min, t_max]) with a polyline (CHIMERA's ray_polyline
 * and ray_crossings with crossing_t). A segment crosses only if its ends lie on different sides of
 * the line, a point on the line counting as the positive side. Each end's side is computed once and
 * carried to the next segment (CHIMERA computes both ends of every segment: the same test), and a
 * chunk of segments whose box is clearly on one side is skipped (none of them can cross). With
 * `exit`, also whether the ray leaves the tissue (the polyline's right-hand side) for the air. */
CH_IN_RAM static int ray_line(ch_real ox, ch_real oy, ch_real dx, ch_real dy, const ch_line *pl, ch_real t_min, ch_real t_max, ch_real *out,
                    uint8_t *exit)
{
    const ch_v2 *p = pl->p;
    int n = 0, c, sa = -1;
    for (c = 0; c < pl->nc; c++) {
        int i0 = c * CH_CHUNK, i1 = i0 + CH_CHUNK < pl->n - 1 ? i0 + CH_CHUNK : pl->n - 1, i;
        int bs = box_side(pl->box[c], ox, oy, dx, dy);
        ch_real ax, ay;
        COUNT(CN_BOX);
        if (bs) {
            sa = bs > 0;   /* the side of the chunk's last point, carried on */
            continue;
        }
        ax = p[i0].x;
        ay = p[i0].y;
        if (sa < 0 || c == 0 || box_side(pl->box[c - 1], ox, oy, dx, dy) == 0)
            sa = dx * (ay - oy) - dy * (ax - ox) >= 0;
        for (i = i0 + 1; i <= i1; i++) {
            ch_real bx = p[i].x, by = p[i].y;
            int sb = dx * (by - oy) - dy * (bx - ox) >= 0;
            COUNT(CN_CROSS);
            if (sa != sb) {
                ch_real ex = bx - ax, ey = by - ay, den = dx * ey - dy * ex;
                if (FABS(den) >= R(1e-15)) {
                    ch_real t = ((ax - ox) * ey - (ay - oy) * ex) / den;
                    if (t >= t_min && t <= t_max && n < CH_MAXCROSS) {
                        out[n] = t;
                        if (exit)
                            exit[n] = dx * ey - dy * ex < 0;
                        n++;
                    }
                }
            }
            ax = bx;
            ay = by;
            sa = sb;
        }
    }
    return n;
}

CH_IN_RAM CH_PART static int ray_polyline(ch_real ox, ch_real oy, ch_real dx, ch_real dy, const ch_line *pl, ch_real t_min, ch_real t_max, ch_real *out)
{
    return ray_line(ox, oy, dx, dy, pl, t_min, t_max, out, 0);
}

CH_IN_RAM CH_PART static int ray_crossings(ch_real ox, ch_real oy, ch_real dx, ch_real dy, const ch_line *pl, ch_real t_min, ch_real t_max, ch_real *out_t, uint8_t *exit)
{
    return ray_line(ox, oy, dx, dy, pl, t_min, t_max, out_t, exit);
}

static void bezier3(ch_v2 p0, ch_v2 p1, ch_v2 p2, ch_v2 p3, int count, ch_v2 *out)
{
    int i;
    for (i = 0; i < count; i++) {
        ch_real t = (ch_real)i / ((ch_real)count - R(1.0)), u = R(1.0) - t;
        ch_real a = u * u * u, b = R(3.0) * u * u * t, c = R(3.0) * u * t * t, d = t * t * t;
        out[i] = v2(a * p0.x + b * p1.x + c * p2.x + d * p3.x, a * p0.y + b * p1.y + c * p2.y + d * p3.y);
    }
}

static void bezier2(ch_v2 p0, ch_v2 p1, ch_v2 p2, int count, ch_v2 *out)
{
    int i;
    for (i = 0; i < count; i++) {
        ch_real t = (ch_real)i / ((ch_real)count - R(1.0)), u = R(1.0) - t;
        out[i] = v2(u * u * p0.x + R(2.0) * u * t * p1.x + t * t * p2.x, u * u * p0.y + R(2.0) * u * t * p1.y + t * t * p2.y);
    }
}

static void arc(ch_v2 c, ch_real r, ch_real a0, ch_real a1, int count, ch_v2 *out)
{
    int i;
    for (i = 0; i < count; i++)
        out[i] = polar(c, r, a0 + ((a1 - a0) * (ch_real)i) / ((ch_real)count - R(1.0)));
}

int ch_catmull_rom(const ch_v2 *pts, int n, int per, ch_v2 *out)
{
    int i, k, m = 0;
    for (i = 0; i < n - 1; i++) {
        ch_v2 p0 = pts[i > 0 ? i - 1 : 0], p1 = pts[i], p2 = pts[i + 1], p3 = pts[i + 2 < n ? i + 2 : n - 1];
        for (k = 0; k < per; k++) {
            ch_real t = (ch_real)k / (ch_real)per, t2 = t * t, t3 = t2 * t;
            out[m++] = v2(R(0.5) * (R(2.0) * p1.x + (-p0.x + p2.x) * t + (R(2.0) * p0.x - R(5.0) * p1.x + R(4.0) * p2.x - p3.x) * t2 +
                                    (-p0.x + R(3.0) * p1.x - R(3.0) * p2.x + p3.x) * t3),
                          R(0.5) * (R(2.0) * p1.y + (-p0.y + p2.y) * t + (R(2.0) * p0.y - R(5.0) * p1.y + R(4.0) * p2.y - p3.y) * t2 +
                                    (-p0.y + R(3.0) * p1.y - R(3.0) * p2.y + p3.y) * t3));
        }
    }
    out[m++] = pts[n - 1];
    return m;
}

/* the upper tangent point from p to the circle (c, r): its angle; 0 = p inside */
static int tangent_angle(ch_v2 c, ch_real r, ch_v2 p, ch_real *ang)
{
    ch_real d = dist(c, p);
    if (d <= r * R(1.0001))
        return 0;
    *ang = ATAN2(p.y - c.y, p.x - c.x) + ACOS(r / d);
    return 1;
}

/* --------------------------------------------------------------------- human.rs helpers --- */
typedef struct {
    ch_real s, dist, sgn;
    int seg;
} proj_t;

/* the squared distance from a point to a chunk's box (0 inside it) */
CH_INLINE ch_real box_d2(const ch_real *b, ch_real px, ch_real py)
{
    ch_real dx = px < b[0] ? b[0] - px : px > b[2] ? px - b[2] : 0;
    ch_real dy = py < b[1] ? b[1] - py : py > b[3] ? py - b[3] : 0;
    return dx * dx + dy * dy;
}

/* Project a point onto a polyline: arc position, distance, and the side. A chunk whose box is no
 * nearer than the best so far is skipped: none of its segments can be nearer (the comparison is
 * strict, as CHIMERA's, so the first of equals still wins). */
CH_IN_RAM CH_PART static proj_t project_on(const ch_line *pl, const ch_real *cum, ch_real px, ch_real py)
{
    proj_t r;
    ch_real best = INF, bs = 0, sign = 1, bd[CH_MAXPTS / CH_CHUNK + 1];
    uint8_t order[CH_MAXPTS / CH_CHUNK + 1];
    int c, k, bseg = 0;
    /* the chunks nearest first (0.8.0: the best falls sooner, more are skipped). Among equally near segments the
     * first in the line must win, as CHIMERA's: a later chunk only wins when strictly nearer, an earlier one when
     * as near (the tie test below) */
    for (c = 0; c < pl->nc; c++) {
        int j = c;
        bd[c] = box_d2(pl->box[c], px, py);
        while (j > 0 && bd[order[j - 1]] > bd[c]) {
            order[j] = order[j - 1];
            j--;
        }
        order[j] = (uint8_t)c;
    }
    for (k = 0; k < pl->nc; k++) {
        const int c2 = order[k];
        int i, i0 = c2 * CH_CHUNK, i1 = i0 + CH_CHUNK < pl->n - 1 ? i0 + CH_CHUNK : pl->n - 1;
        if (bd[c2] > best)
            continue;
        for (i = i0; i < i1; i++) {
            ch_real ax = pl->p[i].x, ay = pl->p[i].y, ex = pl->p[i + 1].x - ax, ey = pl->p[i + 1].y - ay;
            ch_real l2, t, qx, qy, d;
            COUNT(CN_PROJ);
            if (pl->il2[i] == 0)
                continue;
            l2 = ex * ex + ey * ey;
            t = ((px - ax) * ex + (py - ay) * ey) * pl->il2[i];
            t = t < 0 ? 0 : t > 1 ? 1 : t;
            qx = ax + ex * t;
            qy = ay + ey * t;
            d = (px - qx) * (px - qx) + (py - qy) * (py - qy);   /* squared: one root at the end */
            if (d < best || (d == best && i < bseg)) {
                best = d;
                bs = cum[i] + t * SQRT(l2);
                sign = ex * (py - ay) - ey * (px - ax) < 0 ? R(1.0) : R(-1.0);
                bseg = i;
            }
        }
    }
    r.s = bs;
    r.dist = SQRT(best);
    r.sgn = sign * r.dist;
    r.seg = bseg;
    return r;
}

static ch_v2 point_along(const ch_line *pl, const ch_real *cum, int n_cum, ch_real s)
{
    int i = 0;
    ch_real l, u;
    while (i + 2 < n_cum && cum[i + 1] < s)
        i++;
    l = cum[i + 1] - cum[i];
    u = l > R(1e-9) ? clampr((s - cum[i]) / l, 0, 1) : 0;
    return v2(pl->p[i].x + (pl->p[i + 1].x - pl->p[i].x) * u, pl->p[i].y + (pl->p[i + 1].y - pl->p[i].y) * u);
}

/* distance from a point to a closed polygon's boundary, negative inside (even-odd). A chunk no
 * nearer than the best so far skips the distance, and one that doesn't span the point's height
 * can't flip the inside test; a chunk that does neither is skipped whole. */
CH_IN_RAM CH_PART static ch_real signed_gap(const ch_line *pl, ch_real px, ch_real py)
{
    ch_real best = INF, d, bd[CH_MAXPTS / CH_CHUNK + 1];
    uint8_t order[CH_MAXPTS / CH_CHUNK + 1];
    int inside = 0, c, k;
    /* the chunks nearest first (the best distance falls sooner, so more chunks are skipped; the inside test
     * counts every crossing whatever the order) */
    for (c = 0; c < pl->nc; c++) {
        int j = c;
        bd[c] = box_d2(pl->box[c], px, py);
        while (j > 0 && bd[order[j - 1]] > bd[c]) {
            order[j] = order[j - 1];
            j--;
        }
        order[j] = (uint8_t)c;
    }
    for (k = 0; k < pl->nc; k++) {
        const int c2 = order[k];
        const ch_real *b = pl->box[c2];
        int i, i0 = c2 * CH_CHUNK, i1 = i0 + CH_CHUNK < pl->n - 1 ? i0 + CH_CHUNK : pl->n - 1;
        int near = bd[c2] < best, spans = b[1] <= py && b[3] > py;
        if (!near && !spans)
            continue;
        for (i = i0; i < i1; i++) {
            ch_real ax = pl->p[i].x, ay = pl->p[i].y, bx = pl->p[i + 1].x, by = pl->p[i + 1].y;
            ch_real ex = bx - ax, ey = by - ay;
            COUNT(CN_GAP);
            if (near) {
                ch_real t, dx, dy, d2;
                t = ((px - ax) * ex + (py - ay) * ey) * pl->il2[i];
                t = t < 0 ? 0 : t > 1 ? 1 : t;
                dx = px - ax - ex * t;
                dy = py - ay - ey * t;
                d2 = dx * dx + dy * dy;
                if (d2 < best)
                    best = d2;
            }
            if ((ay > py) != (by > py) && px < ax + (py - ay) * pl->slope[i])
                inside = !inside;
        }
    }
    d = SQRT(best);
    return inside ? -d : d;
}

static ch_real closest_approach(const ch_line *pl, const ch_real *cum, const ch_v2 *pts, int n, ch_v2 *normal)
{
    ch_real d = INF, ex, ey, l;
    int seg = 0, i;
    for (i = 0; i < n; i++) {
        proj_t pr = CH_FAR(project_on)(pl, cum, pts[i].x, pts[i].y);
        if (pr.sgn < d) {
            d = pr.sgn;
            seg = pr.seg;
        }
    }
    ex = pl->p[seg + 1].x - pl->p[seg].x;
    ey = pl->p[seg + 1].y - pl->p[seg].y;
    l = HYPOT(ex, ey);
    if (l == 0 || l != l)
        l = 1;
    *normal = v2(ey / l, -ex / l);
    return d;
}

/* ----------------------------------------------------------------------- the tract itself --- */
/* template point → body coordinates (the oral region compressed by 0.86, the pharynx by the body's factor) */
static ch_v2 P(const ch_tract_t *t, ch_real x, ch_real y)
{
    ch_real xx = x > R(0.5) ? R(0.5) + (x - R(0.5)) * R(0.86) : x;
    ch_real yy = y < R(7.0) ? R(7.0) - (R(7.0) - y) * t->pharynx : y;
    return v2(xx * t->k, yy * t->k);
}

ch_v2 ch_tract_point(const ch_tract_t *t, ch_real x, ch_real y) { return P(t, x, y); }
ch_v2 ch_tract_jaw_point(const ch_tract_t *t, ch_real x, ch_real y) { return rotate_about(P(t, x, y), t->condyle, t->jaw_phi); }

void ch_tract_init(ch_tract_t *t, ch_real scale, ch_real pharynx, ch_real tongue_radius, ch_real lip_width)
{
    ch_v2 pw[9], pal[12];
    t->k = scale;
    t->pharynx = pharynx;
    t->tongue_radius = tongue_radius;
    t->lip_width = lip_width;
    pw[0] = P(t, R(-1.0), R(-1.5)); pw[1] = P(t, R(-1.1), R(1.5)); pw[2] = P(t, R(-1.2), R(3.6));
    pw[3] = P(t, R(-1.3), R(5.6)); pw[4] = P(t, R(-1.38), R(7.5)); pw[5] = P(t, R(-1.33), R(8.9));
    pw[6] = P(t, R(-1.05), R(10.1)); pw[7] = P(t, R(-0.45), R(10.9)); pw[8] = P(t, R(0.4), R(11.3));
    t->n_phar_wall = ch_catmull_rom(pw, 9, 4, t->phar_wall);
    t->pns = P(t, R(1.0), R(9.75));
    pal[0] = P(t, R(1.0), R(9.6)); pal[1] = P(t, R(1.8), R(9.92)); pal[2] = P(t, R(2.7), R(10.18));
    pal[3] = P(t, R(3.6), R(10.3)); pal[4] = P(t, R(4.4), R(10.25)); pal[5] = P(t, R(5.1), R(10.05));
    pal[6] = P(t, R(5.7), R(9.72)); pal[7] = P(t, R(6.15), R(9.36)); pal[8] = P(t, R(6.5), R(9.05));
    pal[9] = P(t, R(6.78), R(8.7)); pal[10] = P(t, R(6.98), R(8.3)); pal[11] = P(t, R(7.12), R(7.95));
    t->n_palate = ch_catmull_rom(pal, 12, 3, t->palate);
    t->condyle = P(t, R(-2.5), R(10.5));
    t->polar_c = P(t, R(2.6), R(6.95));
    t->polar_c2 = P(t, R(5.0), R(6.85));
    t->contact_tip = t->contact_body = 1;
    t->contact_tip_n = t->contact_body_n = v2(0, -1);
    t->have_base = 0;
    t->draw = 0;
    t->ph_len = R(8.8);
    t->or_len = R(8.3);
}

typedef struct {
    ch_v2 inner, outer;
    ch_real d;
} measured_t;

/* a fan ray's inner surface (see CHIMERA's fan_inner) */
static ch_real fan_inner(ch_tract_t *t, ch_v2 o, ch_v2 dir, ch_real t_out)
{
    const ch_real k = t->k;
    int n = CH_FAR(ray_crossings)(o.x, o.y, dir.x, dir.y, &t->inner, 0, t_out + R(1.5) * k, t->cross_t, t->cross_exit), i, jj;
    ch_real t_in, tissue = 0;
    for (i = 1; i < n; i++) {   /* sort the crossings along the ray (there are only a few) */
        ch_real tt = t->cross_t[i];
        uint8_t e = t->cross_exit[i];
        jj = i - 1;
        while (jj >= 0 && t->cross_t[jj] > tt) {
            t->cross_t[jj + 1] = t->cross_t[jj];
            t->cross_exit[jj + 1] = t->cross_exit[jj];
            jj--;
        }
        t->cross_t[jj + 1] = tt;
        t->cross_exit[jj + 1] = e;
    }
    i = 0;
    if (n > 0 && t->cross_exit[0]) {
        t_in = t->cross_t[0];
        i = 1;
    } else {
        ch_real back = R(2.5) * k, tb = back;
        int q, n_back = CH_FAR(ray_polyline)(o.x, o.y, -dir.x, -dir.y, &t->inner, 0, back, t->tmp);
        for (q = 0; q < n_back; q++)
            tb = mn(tb, t->tmp[q]);
        t_in = -tb;
    }
    while (i < n && t->cross_t[i] < t_out) {
        ch_real bb;
        if (t->cross_exit[i]) {
            i++;
            continue;
        }
        bb = i + 1 < n ? t->cross_t[i + 1] : t_out + R(1.5) * k;
        tissue += mn(bb, t_out) - t->cross_t[i];
        i += 2;
    }
    return t_in + tissue;
}

/* walk from o along dir: the last inner crossing before the first outer crossing */
static int measure(ch_tract_t *t, ch_v2 o, ch_v2 dir, ch_real max_len, int fan, measured_t *m)
{
    const ch_real k = t->k;
    int n_out = CH_FAR(ray_polyline)(o.x, o.y, dir.x, dir.y, &t->outer, 0, max_len, t->tmp), i;
    ch_real t_out = INF, t_in, d;
    if (n_out == 0)
        return 0;
    for (i = 0; i < n_out; i++)
        t_out = mn(t_out, t->tmp[i]);
    if (fan) {
        t_in = fan_inner(t, o, dir, t_out);
    } else {
        int n_in = CH_FAR(ray_polyline)(o.x, o.y, dir.x, dir.y, &t->inner, 0, t_out + R(1.5) * k, t->tmp);
        int found = 0;
        t_in = -INF;
        for (i = 0; i < n_in; i++)
            if (t->tmp[i] > t_in && t->tmp[i] < t_out + R(1.5) * k) {
                t_in = t->tmp[i];
                found = 1;
            }
        if (!found)
            t_in = mx(0, t_out - R(3.5) * k);
    }
    d = mx(0, t_out - t_in);
    m->inner = add(o, scl(dir, mn(t_in, t_out)));
    m->outer = add(o, scl(dir, t_out));
    m->d = d;
    return 1;
}

static void grid_push(ch_tract_t *t, const measured_t *m, uint8_t region)
{
    ch_grid_t *g;
    if (t->n_grid >= CH_MAXGRID)
        return;
    g = &t->grid[t->n_grid++];
    g->inner = m->inner;
    g->outer = m->outer;
    g->mid = lerpv(m->inner, m->outer, R(0.5));
    g->d = m->d;
    g->area = 0;
    g->s = 0;
    g->region = region;
}

/* HumanVocalTract::compute, the acoustic parts */
#ifndef CH_PROF
#define CH_PROF(stage)                        /* (lab/port/tract_prof.c: the anatomy's stages timed) */
#endif
static void compute(ch_tract_t *t, const ch_real *a)
{
    const ch_real k = t->k;
    const ch_real lar = a[CH_LARYNX] * k;
    ch_v2 glottis = add(P(t, 0, 0), v2(0, lar));
    ch_v2 jaw_ref, ul_edge, ll_edge, ul_base, b, b0, vallecula, epi_base, epi_tip, p_b, tan_b, tip, p_f, tan_f, ctrl;
    ch_v2 fren, floor_[3], lower_teeth[4], ll_base;
    ch_real jaw_r, phi, v_open, v_ang, v_len, uv_y, prot, spread_w, y_mid, lab, ap, lip_front_x, r, h_r, d_t, th_b, th_f;
    ch_real bl, curl, y_root, y_epi_top, y_phar_top, lip_w;
    int i, roof_start, roof_end, root_len = 9, n_back, start, n_ph = 12;
    measured_t m;
    ch_v2 nrm;

#define J(x, y) rotate_about(P(t, R(x), R(y)), t->condyle, phi)
    CH_PROF(0);
    /* ---- jaw (rotation about the condyle) */
    jaw_ref = P(t, R(6.95), R(7.75));
    jaw_r = dist(t->condyle, jaw_ref);
    phi = -clampr(a[CH_JAW], R(-0.2), R(3.0)) * k / jaw_r;
    t->jaw_phi = phi;

    /* ---- velum flap from PNS; angle from port area */
    v_open = clampr(a[CH_VELUM] / R(1.2), 0, 1);
    v_ang = (R(194.0) + R(46.0) * v_open) * DEG;
    v_len = R(2.8) * k;
    nrm = v2(-SIN(v_ang), COS(v_ang));
    for (i = 0; i <= 10; i++) {
        ch_real s = (ch_real)i / R(10.0);
        ch_real sag = SIN(s * CH_PI) * k * (R(0.3) * v_open - R(0.12));
        ch_v2 c = add(polar(t->pns, v_len * s, v_ang), v2(0, -sag));
        ch_real th = (R(0.48) - R(0.2) * s) * k;
        t->vel_oral[i] = add(c, scl(nrm, th));
    }
    /* the velum's oral surface runs into the hard palate's */
    for (i = 0; i < 11; i++) {
        ch_real u = (ch_real)i / R(10.0) / R(0.35);
        if (u < 1)
            t->vel_oral[i] = lerpv(t->vel_oral[i], t->palate[0], (R(1.0) - u) * (R(1.0) - u));
    }

    CH_PROF(1);
    /* ---- outer contour: pharyngeal wall to below the uvula, velum, palate, incisors, upper lip */
    t->outer.n = 0;
    uv_y = t->vel_oral[10].y - R(0.3) * k;
    for (i = 0; i < t->n_phar_wall; i++) {
        ch_v2 p = t->phar_wall[i];
        if (p.y < uv_y) {
            push(&t->outer, p);
            continue;
        }
        if (i > 0) {
            ch_v2 aa = t->phar_wall[i - 1];
            push(&t->outer, lerpv(aa, p, clampr((uv_y - aa.y) / mx(R(1e-9), p.y - aa.y), 0, 1)));
        }
        break;
    }
    roof_start = t->outer.n;
    for (i = 10; i >= 0; i--)
        push(&t->outer, t->vel_oral[i]);
    append(&t->outer, t->palate, t->n_palate, 1);
    roof_end = t->outer.n - 1;
    prot = clampr(a[CH_LIP_PROT], R(-0.2), R(1.6)) * k;
    spread_w = clampr(a[CH_LIP_WIDTH], 0, 1);
    y_mid = (R(7.85) - R(0.3) * a[CH_JAW]) * k;
    lab = clampr(a[CH_LABIODENTAL], 0, 1);
    ap = a[CH_LIP_AP] * k;
    lip_front_x = P(t, R(8.15) + R(0.12) * (R(1.0) - spread_w), 0).x + prot * R(0.95);
    ul_edge = v2(lip_front_x - R(0.15) * k, y_mid + ap * R(0.35) + lab * R(0.35) * k);
    ll_edge = v2(lip_front_x - R(0.25) * k, y_mid - ap * R(0.65));
    if (lab > 0) {
        /* lower lip tucks under the upper incisor edge for /f v/ */
        ch_v2 incisor = t->palate[t->n_palate - 1];
        ch_v2 target = v2(incisor.x + R(0.05) * k, incisor.y - mx(a[CH_LIP_AP] * k, R(-0.1)));
        ll_edge = lerpv(ll_edge, target, lab);
        ul_edge = lerpv(ul_edge, v2(incisor.x + R(0.55) * k, incisor.y + R(0.45) * k), lab * R(0.7));
    }
    ul_base = P(t, R(7.32), R(8.35));
    bezier3(ul_base, add(ul_base, v2(R(0.3) * k + prot * R(0.4), R(0.05) * k)), add(ul_edge, v2(R(-0.35) * k, R(0.12) * k)), ul_edge, 7, t->upper_lip_in);
    append(&t->outer, t->upper_lip_in, 7, 0);

    CH_PROF(2);
    /* ---- tongue */
    r = t->tongue_radius * k;
    b = P(t, a[CH_TB_X], a[CH_TB_Y]);
    b0 = P(t, R(2.9), R(6.85));   /* NEUTRAL's tongue body */
    vallecula = add(P(t, R(0.75), R(3.3)), v2(R(0.45) * (b.x - b0.x) + a[CH_ROOT] * k, R(0.3) * (b.y - b0.y) + R(0.55) * lar));
    epi_base = add(P(t, R(0.3), R(2.4)), v2(0, lar));
    epi_tip = add(P(t, 0, R(4.5)), v2(R(0.35) * (vallecula.x - R(0.75) * k), lar * R(0.8) + R(0.15) * (b.y - b0.y)));
    th_b = R(200.0) * DEG;
    p_b = polar(b, r, th_b);
    tan_b = v2(SIN(th_b), -COS(th_b));
    h_r = dist(vallecula, p_b) / R(3.0);
    bezier3(vallecula, add(vallecula, v2(R(0.08) * k, h_r)), sub(p_b, scl(tan_b, h_r)), p_b, 9, t->root);
    tip = P(t, a[CH_TT_X], a[CH_TT_Y]);
    d_t = dist(tip, b);
    if (d_t < r * R(1.05))   /* keep the tip outside the body circle */
        tip = add(b, scl(norm2(sub(tip, b)), r * R(1.05)));
    if (!tangent_angle(b, r, tip, &th_f))
        th_f = ATAN2(tip.y - b.y, tip.x - b.x);
    while (th_f > th_b)   /* the arc runs from thB (200°) decreasing to thF */
        th_f -= R(2.0) * CH_PI;
    while (th_f < th_b - R(2.0) * CH_PI)
        th_f += R(2.0) * CH_PI;
    arc(b, r, th_b, th_f, 32, t->arc_pts);
    p_f = t->arc_pts[31];
    tan_f = v2(SIN(th_f), -COS(th_f));
    /* blade: quadratic from the tangent point to the tip; curl lifts the tip and turns it back */
    bl = dist(p_f, tip);
    curl = clampr(a[CH_TT_CURL], 0, 1);
    ctrl = add(add(p_f, scl(tan_f, bl * R(0.5))), v2(0, (R(0.18) + R(0.5) * curl) * k));
    bezier2(p_f, ctrl, tip, 7, t->blade);
    if (a[CH_DORSUM] > 0) {   /* dorsum depression (clicks) */
        ch_real x0 = b.x, x1 = tip.x;
        int q;
        for (q = 0; q < 32 + 5; q++) {
            ch_v2 *pp = q < 32 ? &t->arc_pts[q] : &t->blade[q - 32 + 1];
            ch_real u = (pp->x - x0) / mx(R(1e-6), x1 - x0), w;
            if (u <= 0 || u >= 1)
                continue;
            w = SIN(CH_PI * POW(u, R(0.8)));
            pp->y += -a[CH_DORSUM] * R(0.9) * k * w * w;
        }
    }
    /* underside back to the frenulum / floor of mouth (jaw-carried) */
    fren = J(5.7, 6.15);
    bezier2(tip, add(tip, v2(R(-0.3) * k - curl * R(0.4) * k, R(-0.6) * k)), fren, 6, t->under);
    floor_[0] = fren;
    floor_[1] = J(6.2, 6.25);
    floor_[2] = J(6.45, 6.55);
    lower_teeth[0] = J(6.45, 6.55);
    lower_teeth[1] = J(6.72, 7.15);
    lower_teeth[2] = J(6.95, 7.75);
    lower_teeth[3] = J(7.12, 7.45);
    ll_base = J(7.12, 7.45);
    bezier3(ll_base, add(ll_base, v2(R(0.25) * k + prot * R(0.3), R(-0.05) * k)), add(ll_edge, v2(R(-0.3) * k, R(-0.12) * k)), ll_edge, 7, t->lower_lip_in);

    /* acoustic inner wall: epiglottis, then the tongue root above the epiglottis tip */
    t->inner.n = 0;
    push(&t->inner, epi_base);
    push(&t->inner, epi_tip);
    y_root = epi_tip.y + R(0.05) * k;
    for (i = 0; i < root_len; i++)
        t->back[i] = t->root[i];
    for (i = 1; i < 32; i++)
        t->back[root_len + i - 1] = t->arc_pts[i];
    n_back = root_len + 31;
    start = -1;
    for (i = 0; i < n_back; i++)
        if (t->back[i].y > y_root) {
            start = i;
            break;
        }
    if (start < 0) {
        start = n_back - 1;
    } else if (start > 0) {
        ch_v2 aa = t->back[start - 1], bb = t->back[start];
        push(&t->inner, lerpv(aa, bb, clampr((y_root - aa.y) / mx(R(1e-9), bb.y - aa.y), 0, 1)));
    }
    if (start < root_len) {
        for (i = start; i < root_len; i++)
            push(&t->inner, t->root[i]);
        append(&t->inner, t->arc_pts, 32, 1);
    } else {
        append(&t->inner, t->arc_pts + (start - root_len + 1), 32 - (start - root_len + 1), 1);
    }
    append(&t->inner, t->blade, 7, 1);
    append(&t->inner, t->under, 6, 1);
    append(&t->inner, floor_, 3, 1);
    append(&t->inner, lower_teeth, 4, 1);
    append(&t->inner, t->lower_lip_in, 7, 0);

    line_boxes(&t->outer);
    line_boxes(&t->inner);

    CH_PROF(3);
    /* ---- grid */
    t->n_grid = 0;
    y_epi_top = glottis.y + R(2.4) * k * t->pharynx;
    y_phar_top = t->polar_c.y;
    t->split_index = -1;
    /* pharyngeal horizontals (origin in front, looking back) */
    for (i = 0; i < n_ph; i++) {
        ch_real y = lerp(y_epi_top + R(0.12) * k, y_phar_top, (ch_real)i / (ch_real)n_ph);
        if (measure(t, v2(R(4.6) * k, y), v2(R(-1.0), 0), R(9.0) * k, 0, &m))
            grid_push(t, &m, 1);
    }
    /* oral family: rays from a centre that glides from C1 (mid-oral) to C2 (behind the incisors) */
    for (i = 0; i <= 30; i++) {
        ch_real tt = (ch_real)i / R(30.0), ang = lerp(R(180.0), R(36.0), tt) * DEG;
        ch_real w = tt < R(0.45) ? 0 : (tt - R(0.45)) / R(0.55);
        ch_v2 c = lerpv(t->polar_c, t->polar_c2, w * w * (R(3.0) - R(2.0) * w));
        if (!measure(t, c, v2(COS(ang), SIN(ang)), R(8.0) * k, 1, &m))
            continue;
        if (t->split_index < 0 && ang <= R(150.0) * DEG)
            t->split_index = t->n_grid;
        grid_push(t, &m, t->split_index < 0 ? 1 : 2);
    }
    /* front verticals (upper incisor edge → lip opening), looking up */
    {
        ch_v2 incisor_tip = t->palate[t->n_palate - 1];
        ch_real x_start = incisor_tip.x + R(0.03) * k;
        ch_real x_end = mx(x_start + R(0.3) * k, mn(ul_edge.x, ll_edge.x) - R(0.04) * k);
        t->teeth_idx = t->n_grid;
        for (i = 0; i < 6; i++) {
            ch_real x = lerp(x_start, x_end, (ch_real)i / R(5.0));
            if (measure(t, v2(x, R(4.0) * k), v2(0, R(1.0)), R(8.0) * k, 0, &m)) {
                if (x > ll_edge.x) {   /* in front of the lower lip's edge (tucked for /f v/) */
                    ch_real face = mn(ll_edge.y - (x - ll_edge.x) * R(4.0) / R(3.0), m.outer.y);
                    ch_real open = smoothstep(R(0.7), R(1.0), lab) * smoothstep(0, R(0.1) * k, x - ll_edge.x);
                    ch_real y_in = lerp(face, m.outer.y - R(3.5) * k, open);
                    m.inner = v2(x, y_in);
                    m.d = m.outer.y - y_in;
                }
                grid_push(t, &m, 3);
            }
        }
    }
    lip_w = (R(1.1) + (t->lip_width - R(1.1)) * spread_w) * k;

    CH_PROF(4);
    /* ---- soft-tissue contact model: near the oral roof the gap is the distance to the tongue surface */
    t->roof.n = 0;
    for (i = roof_start; i <= roof_end; i++)
        push(&t->roof, t->outer.p[i]);
    line_boxes(&t->roof);
    t->cum[0] = 0;
    for (i = 1; i < t->roof.n; i++)
        t->cum[i] = t->cum[i - 1] + HYPOT(t->roof.p[i].x - t->roof.p[i - 1].x, t->roof.p[i].y - t->roof.p[i - 1].y);
    {
        int n_tip = 0, q;
        ch_real tip_c, body_c, press, half, roof_len;
        for (q = 2; q < 7; q++)
            t->tip_pts[n_tip++] = t->blade[q];
        t->tip_pts[n_tip++] = t->under[1];
        t->tip_pts[n_tip++] = t->under[2];
        tip_c = closest_approach(&t->roof, t->cum, t->tip_pts, n_tip, &t->contact_tip_n);
        body_c = closest_approach(&t->roof, t->cum, t->arc_pts, 32, &t->contact_body_n);
        t->contact_tip = tip_c;
        t->contact_body = body_c;
        /* the whole tongue outline, closed */
        t->outline.n = 0;
        append(&t->outline, t->root, 9, 0);
        append(&t->outline, t->arc_pts, 32, 1);
        append(&t->outline, t->blade, 7, 1);
        append(&t->outline, t->under, 6, 1);
        append(&t->outline, floor_, 3, 1);
        push(&t->outline, t->root[0]);
        line_boxes(&t->outline);
        press = R(1.0) - smoothstep(R(-0.06) * k, 0, mn(tip_c, body_c));
        half = R(0.1) * k * (R(1.0) + press);
        roof_len = t->cum[t->roof.n - 1];
        for (q = 0; q < t->n_grid; q++) {
            ch_grid_t *g = &t->grid[q];
            proj_t pr;
            ch_real on_roof, dc, w, cap, dp, dm;
            if (g->region == 0)
                continue;
            pr = CH_FAR(project_on)(&t->roof, t->cum, g->outer.x, g->outer.y);
            on_roof = R(1.0) - smoothstep(R(0.15) * k, R(0.25) * k, pr.dist);
            if (on_roof <= 0)
                continue;
            dc = CH_FAR(signed_gap)(&t->outline, g->outer.x, g->outer.y);
            if (dc - half - pr.dist <= R(0.6) * k) {
                static const ch_real FR[4] = {-1.0, -0.5, 0.5, 1.0};
                int f;
                for (f = 0; f < 4; f++) {
                    ch_v2 qq = point_along(&t->roof, t->cum, t->roof.n, clampr(pr.s + FR[f] * half, 0, roof_len));
                    dc = mn(dc, CH_FAR(signed_gap)(&t->outline, qq.x, qq.y));
                }
            }
            w = (R(1.0) - smoothstep(R(0.15) * k, R(0.6) * k, dc)) * on_roof;
            cap = (R(1.0) - smoothstep(R(0.45) * k, R(0.6) * k, dc)) * on_roof;
            if (w <= 0 && cap <= 0)
                continue;
            dp = mx(0, dc);
            dm = g->d + (mn(g->d, dp) - g->d) * cap;
            g->d = dm + (dp - dm) * w;
        }
    }

    /* lip contact: between the lips the gap follows aperture + Δx²/2R back from the lip edge */
    {
        ch_real edge_x = mn(ul_edge.x, ll_edge.x), rl = R(0.6) * k;
        ch_real lab_open = smoothstep(0, R(0.5), lab);
        ch_real ap_eff = lab_open >= 1 ? INF : ap + R(0.3) * k * lab_open / (R(1.0) - lab_open);
        ch_real px = t->palate[t->n_palate - 1].x;
        int q;
        for (q = 0; q < t->n_grid; q++) {
            ch_grid_t *g = &t->grid[q];
            ch_real dxl;
            if (g->region != 3 || g->mid.x < px)
                continue;
            dxl = mx(0, edge_x - g->mid.x);
            g->d = mn(g->d, mx(0, ap_eff + (dxl * dxl) / (R(2.0) * rl)));
        }
    }

    CH_PROF(5);
    /* ---- areas */
    {
        ch_real teeth_x = t->palate[t->n_palate - 1].x;
        int q;
        for (q = 0; q < t->n_grid; q++) {
            ch_grid_t *g = &t->grid[q];
            if (g->region == 1) {
                g->area = t->law[0] * POW(g->d, t->law[1]);
            } else if (g->region == 2) {
                ch_real ahead = smoothstep(0, R(0.4) * k, g->mid.x - tip.x);
                g->area = lerp(t->law[2] * POW(g->d, t->law[3]), t->law[6] * POW(g->d, t->law[7]), ahead);
            } else {
                ch_real u = clampr((g->mid.x - teeth_x) / (R(0.45) * k), 0, 1);
                ch_real dental = t->law[4] * POW(g->d, t->law[5]);
                ch_real lip = (CH_PI / R(4.0)) * g->d * lip_w;
                g->area = lerp(dental, lip, u);
            }
        }
        /* suppress isolated spikes */
        for (q = 1; q < t->n_grid - 1; q++) {
            ch_real lim = R(1.35) * mx(t->grid[q - 1].area, t->grid[q + 1].area);
            if (t->grid[q].area > lim)
                t->grid[q].area = lim;
        }
        /* lateral channel (for /l/) */
        if (a[CH_LATERAL] > 0)
            for (q = 0; q < t->n_grid; q++) {
                ch_grid_t *g = &t->grid[q];
                if (g->region >= 2 && dist(g->mid, tip) < R(1.4) * k)
                    g->area = mx(g->area, a[CH_LATERAL] * R(0.35) * k * k);
            }
        /* ---- centreline s */
        {
            ch_real s = R(2.4) * k * t->pharynx;
            for (q = 0; q < t->n_grid; q++) {
                if (q > 0)
                    s += dist(t->grid[q].mid, t->grid[q - 1].mid);
                t->grid[q].s = s;
            }
        }
        q = t->n_grid - 1;
        t->teeth_s = t->teeth_idx < t->n_grid ? t->grid[t->teeth_idx].s : t->grid[q].s;
        t->lip_s = t->grid[q].s;
        t->pharynx_length = t->split_index > 0 ? t->grid[t->split_index].s : t->grid[n_ph].s;
        t->length = t->lip_s;
    }
    t->velum_port_area = mx(0, a[CH_VELUM]) * k * k;
    t->tip = tip;
    if (t->draw) {   /* the drawing parts (HumanVocalTract::compute's last lines) */
        int n = 0, q;
        for (q = 0; q < 9; q++)
            t->d_tongue[n++] = t->root[q];
        for (q = 1; q < 32; q++)
            t->d_tongue[n++] = t->arc_pts[q];
        for (q = 1; q < 7; q++)
            t->d_tongue[n++] = t->blade[q];
        for (q = 1; q < 6; q++)
            t->d_tongue[n++] = t->under[q];
        for (q = 1; q < 3; q++)
            t->d_tongue[n++] = floor_[q];
        t->n_tongue = n;
        bezier3(ul_edge, add(ul_edge, v2(R(0.25) * k, R(0.35) * k)), P(t, R(8.3), R(9.6)), P(t, R(7.9), R(10.4)), 6, t->d_ulip_out);
        bezier3(ll_edge, add(ll_edge, v2(R(0.3) * k, R(-0.4) * k)), J(8.1, 6.3), J(7.6, 5.6), 6, t->d_llip_out);
        t->d_jaw_bone[0] = J(7.25, 6.75);
        t->d_jaw_bone[1] = J(7.4, 5.4);
        t->d_jaw_bone[2] = J(6.4, 4.6);
        t->d_jaw_bone[3] = J(3.5, 4.4);
        t->d_jaw_bone[4] = J(1.2, 5.2);
        t->d_jaw_bone[5] = J(-0.6, 7.4);
        t->d_jaw_bone[6] = J(-1.9, 9.6);
        t->d_upper_teeth[0] = P(t, R(6.15), R(9.36));
        t->d_upper_teeth[1] = P(t, R(6.78), R(8.7));
        t->d_upper_teeth[2] = P(t, R(7.12), R(7.95));
        t->d_upper_teeth[3] = P(t, R(7.38), R(8.7));
        t->d_upper_teeth[4] = P(t, R(7.4), R(9.3));
        for (q = 0; q < 4; q++)
            t->d_lower_teeth[q] = lower_teeth[q];
        t->d_glottis = glottis;
        t->d_tongue_c = b;
        t->d_tongue_r = r;
    }
#undef J
}

/* -------------------------------------------------------------------------- resample --- */
static ch_real epilarynx_area(const ch_tract_t *t, const ch_real *a, ch_real s, ch_real epi_len)
{
    ch_real u = s / epi_len;
    ch_real vent = R(0.95) * (R(1.0) - R(0.75) * clampr(a[CH_VENTRICULAR], 0, 1));
    ch_real top = lerp(R(1.9), R(0.35), clampr(a[CH_EPILARYNX], 0, 1));
    ch_real r = R(0.03), area;
#define EPI_F(x) ((x) < R(0.3) ? R(1.3) : (x) < R(0.5) ? vent : lerp(R(1.2), top, ((x) - R(0.5)) / R(0.5)))
    area = EPI_F(u);
    if (FABS(u - R(0.3)) < r)
        area = lerp(EPI_F(R(0.3) - r), EPI_F(R(0.3) + r), (u - (R(0.3) - r)) / (R(2.0) * r));
    if (FABS(u - R(0.5)) < r)
        area = lerp(EPI_F(R(0.5) - r), EPI_F(R(0.5) + r), (u - (R(0.5) - r)) / (R(2.0) * r));
#undef EPI_F
    return area * t->k * t->k;
}

static ch_real area_at(const ch_tract_t *t, const ch_real *a, ch_real s, ch_real epi_len)
{
    const ch_grid_t *g = t->grid;
    ch_real flare = R(0.1) * epi_len, tt;
    int lo = 0, hi = t->n_grid - 1;
    COUNT(CN_AREA_AT);
    if (s <= epi_len - flare)
        return epilarynx_area(t, a, s, epi_len);
    if (s <= epi_len)
        return lerp(epilarynx_area(t, a, s, epi_len), g[0].area, (s - (epi_len - flare)) / flare);
    if (s <= g[0].s)
        return g[0].area;
    if (s >= g[hi].s)
        return g[hi].area;
    while (hi - lo > 1) {
        int m = (lo + hi) >> 1;
        if (g[m].s <= s)
            lo = m;
        else
            hi = m;
    }
    tt = (s - g[lo].s) / mx(R(1e-9), g[hi].s - g[lo].s);
    return lerp(g[lo].area, g[hi].area, tt);
}

/* harmonic mean of the area over each section (8 sub-samples), and the grid's own samples inside it */
static void fill(const ch_tract_t *t, const ch_real *a, ch_real s0, ch_real s1, ch_real *out, ch_real *mins, int n)
{
    const ch_real epi_len = R(2.4) * t->k * t->pharynx;
    ch_real d = (s1 - s0) / (ch_real)n;
    int i, gi = 0, sub = 8;
    for (i = 0; i < n; i++) {
        ch_real inv = 0, mn_ = INF, a0, a1;
        int closed = 0, jj, q;
        for (jj = 0; jj < sub; jj++) {
            ch_real area = area_at(t, a, s0 + d * ((ch_real)i + ((ch_real)jj + R(0.5)) / (ch_real)sub), epi_len);
            if (area < mn_)
                mn_ = area;
            if (area <= R(1e-5)) {
                closed = 1;
                break;
            }
            inv += R(1.0) / area;
        }
        a0 = s0 + d * (ch_real)i;
        a1 = s0 + d * ((ch_real)i + R(1.0)) + (i == n - 1 ? R(1e-6) : 0);
        while (gi < t->n_grid && t->grid[gi].s < a0)
            gi++;
        for (q = gi; q < t->n_grid && t->grid[q].s <= a1; q++) {
            if (t->grid[q].area < mn_)
                mn_ = t->grid[q].area;
            if (t->grid[q].area <= R(1e-5))
                closed = 1;
        }
        out[i] = closed ? 0 : (ch_real)sub / inv;
        mins[i] = closed ? 0 : mn(mn_, out[i]);
    }
}

/* ---------------------------------------------------------------- human_airway.rs commit --- */
static ch_real close_rate(const ch_tract_t *t, ch_real area)
{
    ch_real k2 = t->k * t->k, open_r = t->max_area_rate * k2, seal_r = t->seal_rate * k2;
    ch_real lo = t->seal_lo * k2, hi = t->seal_hi * k2, ease = t->seal_ease * k2, u;
    if (area < ease)
        return seal_r * SQRT(mx(0, area) / ease);
    u = area <= lo ? 0 : area >= hi ? 1 : (area - lo) / (hi - lo);
    return seal_r + (open_r - seal_r) * u * u * (R(3.0) - R(2.0) * u);
}

static void clamp_arr(const ch_tract_t *t, ch_real *cur, const ch_real *old, ch_real *mins, const ch_real *old_mins, int n, ch_real dt)
{
    const ch_real open_r = t->max_area_rate * t->k * t->k, gain = t->area_rate_gain;
    int i;
    for (i = 0; i < n; i++) {
        ch_real d = cur[i] - old[i], dm;
        if (d > 0) {
            ch_real lim = (open_r + gain * cur[i]) * dt;
            if (d > lim)
                cur[i] = old[i] + lim;
        } else {
            ch_real lim = (close_rate(t, old[i]) + gain * old[i]) * dt;
            if (d < -lim)
                cur[i] = old[i] - lim;
        }
        dm = mins[i] - old_mins[i];
        if (dm > 0) {
            ch_real lim_m = (open_r + gain * mins[i]) * dt;
            if (dm > lim_m)
                mins[i] = old_mins[i] + lim_m;
        } else {
            ch_real lim_m = (close_rate(t, old_mins[i]) + gain * old_mins[i]) * dt;
            if (dm < -lim_m)
                mins[i] = old_mins[i] - lim_m;
        }
        if (mins[i] > cur[i])
            mins[i] = cur[i];
        if (mins[i] < 0)
            mins[i] = 0;
    }
}

static uint16_t branch_of(uint16_t base, int n, int i)
{
    if (i < 0)
        i = 0;
    if (i > n - 2)
        i = n - 2;
    return (uint16_t)(base + i);
}

static void noise_spec(ch_tract_t *t, int src, int fwd, int bwd, ch_real gain, ch_real lowpass, ch_real highpass)
{
    ch_noise_t *s = &t->noise[t->n_noise++];
    s->src = (uint16_t)src;
    s->fwd = (uint16_t)fwd;
    s->bwd = (uint16_t)bwd;
    s->gain = gain;
    s->lp_coef = R(1.0) - EXP((R(-2.0) * CH_PI * lowpass) / t->fs);
    s->hp_coef = EXP((R(-2.0) * CH_PI * highpass) / t->fs);
    s->hp = s->lp1 = s->lp2 = s->level = 0;
}

/* register turbulence sources: glottis, the narrowest supraglottal constriction, the teeth obstacle */
static void update_noise(ch_tract_t *t)
{
    const int n_ph = t->n_ph, n_or = t->n_or, n_tot = n_ph + n_or;
    ch_real min_a = INF, k2 = t->k * t->k, w_area;
    int i, min_i = -1;
    const ch_real dxp = t->ph_len / (ch_real)n_ph, dxo = t->or_len / (ch_real)n_or;
    t->n_noise = 0;
    /* aspiration: the jet from the glottis hits the epilarynx/ventricular region */
    noise_spec(t, t->glottis_port, branch_of(t->ph_branch, n_ph, 1), branch_of(t->tr_branch, t->tr_n, t->tr_n - 2), t->gain_glottis,
               R(3500.0), R(300.0));
    t->noise_s[0][0] = 0;
    t->noise_s[0][1] = (ch_real)((n_ph - 2 < 1 ? n_ph - 2 : 1) + 1) * dxp;
    t->noise_s[0][2] = -1;
    for (i = 0; i < n_tot; i++) {
        ch_real area = i < n_ph ? t->ph_min[i] : t->or_min[i - n_ph];
        if (area > 0 && area < min_a) {
            min_a = area;
            min_i = i;
        }
    }
    w_area = min_i >= 0 ? R(1.0) - smoothstep(R(0.35) * k2, R(0.9) * k2, min_a) : 0;
    if (w_area > 0) {
        ch_real dx_ph = t->ph_len / (ch_real)n_ph, dx_or = t->or_len / (ch_real)n_or;
        ch_real dx = min_i < n_ph ? dx_ph : dx_or;
        int off = (int)mx(R(1.0), (ch_real)js_round_c((R(0.9) * t->k) / dx));
        int src, fwd, bwd;
#define TO_BRANCH(gi) ((gi) < n_ph ? (int)branch_of(t->ph_branch, n_ph, (gi)) : (gi) == n_ph ? -1 : (int)branch_of(t->or_branch, n_or, (gi) - n_ph))
/* a branch's place: the end of the node it leaves (its index clamped as branch_of does), cm from the glottis */
#define TO_S(gi) ((gi) < n_ph ? (ch_real)(((gi) > n_ph - 2 ? n_ph - 2 : (gi)) + 1) * dxp \
                              : t->ph_len + (ch_real)(((gi) - n_ph > n_or - 2 ? n_or - 2 : (gi) - n_ph) + 1) * dxo)
        src = TO_BRANCH(min_i < n_tot - 2 ? min_i : n_tot - 2);
        fwd = TO_BRANCH(n_tot - 2 < min_i + off ? n_tot - 2 : min_i + off);
        bwd = TO_BRANCH(0 > min_i - off ? 0 : min_i - off);
        if (src >= 0 && fwd >= 0 && bwd >= 0) {
            ch_real s_min, behind, w_dist, w_jet, wt;
            noise_spec(t, src, fwd, bwd, t->gain_constriction * w_area, R(2600.0), R(350.0));
            t->noise_s[t->n_noise - 1][0] = TO_S(min_i < n_tot - 2 ? min_i : n_tot - 2);
            t->noise_s[t->n_noise - 1][1] = TO_S(n_tot - 2 < min_i + off ? n_tot - 2 : min_i + off);
            t->noise_s[t->n_noise - 1][2] = TO_S(0 > min_i - off ? 0 : min_i - off);
            /* obstacle source: a narrow anterior jet impinging on the incisors (sibilants) */
            s_min = min_i < n_ph ? ((ch_real)min_i + R(0.5)) * dx_ph : t->ph_len + ((ch_real)(min_i - n_ph) + R(0.5)) * dx_or;
            behind = (t->teeth_s - s_min) / t->k;
            w_dist = smoothstep(R(0.15), R(0.5), behind) * (R(1.0) - smoothstep(R(2.6), R(4.0), behind));
            w_jet = R(1.0) - smoothstep(R(0.2) * k2, R(0.5) * k2, min_a);
            wt = w_dist * w_jet;
            if (wt > R(1e-3)) {
                int teeth_i = n_ph + (int)mn((ch_real)(n_or - 2), mx(0, (ch_real)js_round_c((t->teeth_s - t->ph_len) / dx_or)));
                int tb = TO_BRANCH(teeth_i);
                if (tb >= 0) {
                    noise_spec(t, src, tb, bwd, t->gain_teeth * wt, R(5000.0), R(1200.0));
                    t->noise_s[t->n_noise - 1][0] = t->noise_s[t->n_noise - 2][0];
                    t->noise_s[t->n_noise - 1][1] = TO_S(teeth_i);
                    t->noise_s[t->n_noise - 1][2] = t->noise_s[t->n_noise - 2][2];
                }
            }
        }
#undef TO_BRANCH
#undef TO_S
    }
}

/* HumanAirway::commit: the geometry, the tubes, the slew limits against the step's baseline, the noise */
static void commit(ch_tract_t *t, const ch_real *a, ch_real dt)
{
    COUNT(CN_STEPS);
    compute(t, a);
    CH_PROF(6);
    fill(t, a, 0, t->pharynx_length, t->ph_areas, t->ph_min, t->n_ph);
    fill(t, a, t->pharynx_length, t->lip_s, t->or_areas, t->or_min, t->n_or);
    if (dt > 0 && t->have_base) {
        clamp_arr(t, t->ph_areas, t->base[0], t->ph_min, t->base[2], t->n_ph, dt);
        clamp_arr(t, t->or_areas, t->base[1], t->or_min, t->base[3], t->n_or, dt);
    }
    t->ph_len = t->pharynx_length;
    t->or_len = t->lip_s - t->pharynx_length;
    CH_PROF(7);
    update_noise(t);
    CH_PROF(8);
}

void ch_tract_apply(ch_tract_t *t, const ch_real a[CH_NARTIC], ch_real dt)
{
    int i;
    if (dt > 0) {
        for (i = 0; i < t->n_ph; i++) {
            t->base[0][i] = t->ph_areas[i];
            t->base[2][i] = t->ph_min[i];
        }
        for (i = 0; i < t->n_or; i++) {
            t->base[1][i] = t->or_areas[i];
            t->base[3][i] = t->or_min[i];
        }
        t->have_base = 1;
    }
    t->base_dt = dt;
    commit(t, a, dt);
}

void ch_tract_reapply(ch_tract_t *t, const ch_real a[CH_NARTIC])
{
    commit(t, a, t->base_dt);
}

void ch_tract_targets(const ch_tract_t *t, ch_real *area_target, ch_real *amin_target, ch_real *dx_target, ch_real *port_area_target)
{
    ch_real ph_sim = mx(t->ph_len, (ch_real)t->n_ph * t->dx_stable), or_sim = mx(t->or_len, (ch_real)t->n_or * t->dx_stable);
    int i;
    for (i = 0; i < t->n_ph; i++) {
        ch_real a = mx(0, t->ph_areas[i]);
        area_target[t->ph_first + i] = a;
        amin_target[t->ph_first + i] = mx(0, mn(a, t->ph_min[i]));
        dx_target[t->ph_first + i] = ph_sim / (ch_real)t->n_ph;
    }
    for (i = 0; i < t->n_or; i++) {
        ch_real a = mx(0, t->or_areas[i]);
        area_target[t->or_first + i] = a;
        amin_target[t->or_first + i] = mx(0, mn(a, t->or_min[i]));
        dx_target[t->or_first + i] = or_sim / (ch_real)t->n_or;
    }
    port_area_target[t->velum_port] = mx(0, t->velum_port_area);
}

void ch_tract_shape(const ch_tract_t *t, ch_shape_t *s)
{
    int i, j;
    s->n_ph = t->n_ph;
    s->n_or = t->n_or;
    for (i = 0; i < t->n_ph; i++) {
        s->ph_areas[i] = t->ph_areas[i];
        s->ph_min[i] = t->ph_min[i];
    }
    for (i = 0; i < t->n_or; i++) {
        s->or_areas[i] = t->or_areas[i];
        s->or_min[i] = t->or_min[i];
    }
    s->ph_len = t->ph_len;
    s->or_len = t->or_len;
    s->velum_port_area = t->velum_port_area;
    s->n_noise = t->n_noise;
    for (i = 0; i < t->n_noise; i++) {
        s->noise[i] = t->noise[i];
        for (j = 0; j < 3; j++)
            s->noise_s[i][j] = t->noise_s[i][j];
    }
}
