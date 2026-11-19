/* SPDX-License-Identifier: GPL-3.0-only
 * Filter DSP from CS-1 by twochannels as vendored by FM1-Dock.
 * Based on Felucca DSP, Copyright (C) 2026 Leo Kuroshita, Hügelton Instruments. */
static const int32_t cs1_SVF_G[137] = {
    2, 3, 3, 3, 3, 3, 3, 4,
    4, 4, 4, 5, 5, 5, 5, 6,
    6, 6, 7, 7, 8, 8, 9, 9,
    10, 10, 11, 11, 12, 13, 13, 14,
    15, 16, 17, 18, 19, 20, 21, 23,
    24, 25, 27, 29, 30, 32, 34, 36,
    38, 40, 43, 45, 48, 51, 54, 57,
    61, 64, 68, 72, 76, 81, 86, 91,
    96, 102, 108, 114, 121, 128, 136, 144,
    153, 162, 171, 182, 193, 204, 216, 229,
    243, 257, 272, 289, 306, 324, 344, 364,
    386, 409, 433, 459, 487, 516, 547, 580,
    615, 652, 692, 734, 779, 826, 877, 931,
    988, 1049, 1115, 1184, 1259, 1339, 1425, 1517,
    1616, 1722, 1838, 1962, 2098, 2246, 2407, 2585,
    2781, 2998, 3241, 3515, 3827, 4185, 4601, 5094,
    5688, 6422, 7355, 8590, 10315, 12918, 17344, 18554,
    18554,
};
static const uint16_t cs1_SVF_KT[128] = {
    8173, 7553, 7004, 6516, 6081, 5691, 5342, 5027,
    4744, 4488, 4255, 4044, 3852, 3676, 3516, 3368,
    3233, 3109, 2995, 2889, 2792, 2702, 2618, 2540,
    2468, 2401, 2338, 2280, 2225, 2174, 2127, 2082,
    2040, 2001, 1964, 1929, 1897, 1866, 1837, 1810,
    1784, 1760, 1737, 1715, 1695, 1675, 1657, 1640,
    1623, 1608, 1593, 1579, 1565, 1553, 1541, 1529,
    1519, 1508, 1498, 1489, 1480, 1472, 1464, 1456,
    1449, 1442, 1435, 1429, 1423, 1417, 1412, 1407,
    1402, 1397, 1392, 1388, 1384, 1380, 1376, 1373,
    1369, 1366, 1363, 1360, 1357, 1355, 1352, 1349,
    1347, 1345, 1343, 1341, 1339, 1337, 1335, 1333,
    1332, 1330, 1328, 1327, 1326, 1324, 1323, 1322,
    1321, 1320, 1319, 1318, 1317, 1316, 1315, 1314,
    1313, 1312, 1312, 1311, 1310, 1310, 1309, 1308,
    1308, 1307, 1307, 1306, 1306, 1305, 1305, 1305,
};

/* Trapezoidal SVF (A. Simper), unconditionally stable. Coefficients per control tick in Q13; the states stay
 * within +-150000 so the products fit in 32 bits. cut16: the cutoff as a pitch (1/16 semitone, MIDI 0..135),
 * k: the damping in Q12 (8192 = 2.0, no resonance; ~600 = 0.15, near self-oscillation). */
typedef struct { int32_t a1, a2, a3, k; } cs1_svf_t;
static inline void cs1_svf_coef(cs1_svf_t *c, int32_t cut16, int32_t k)
{
    int32_t i, g, den;
    cut16 = clamp(cut16, 0, 135 * 16);
    i = cut16 >> 4;
    g = cs1_SVF_G[i] + (((cs1_SVF_G[i + 1] - cs1_SVF_G[i]) * (cut16 & 15)) >> 4);
    den = 4096 + ((g * (g + k)) >> 12);
    c->a1 = (int32_t)((4096u << 13) / (uint32_t)den);
    c->a2 = (c->a1 * g) >> 12;
    c->a3 = (c->a2 * g) >> 12;
    c->k = k;
}
#define cs1_SVF_K(res) ((int32_t)cs1_SVF_KT[(res)])        /* resonance 0..127 -> damping Q12 (cs1_tables.h): 2.0 .. 0.32, the peak
                                                     * rising fast then levelling near +10 dB; the CS-80's filters never self-oscillate */
/* one sample: lp, bp and hp of in; the states ic1, ic2 */
static inline void cs1_svf_step(const cs1_svf_t *c, int32_t in, int32_t *ic1, int32_t *ic2, int32_t *lp, int32_t *hp)
{
    int32_t v3 = in - *ic2;
    int32_t v1 = (c->a1 * *ic1 + c->a2 * v3 + 4096) >> 13;       /* rounded: the state settles at 0, not a few LSB below */
    int32_t v2 = *ic2 + ((c->a2 * *ic1 + c->a3 * v3 + 4096) >> 13);
    *ic1 = clamp(2 * v1 - *ic1, -150000, 150000);
    *ic2 = clamp(2 * v2 - *ic2, -150000, 150000);
    *lp = v2;
    *hp = in - ((c->k * v1) >> 12) - v2;
}
