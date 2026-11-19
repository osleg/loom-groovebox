/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.protocol;

/** Bounded native Skiff presentation. The application owns every displayed value. */
public final class NativeUi {
    private NativeUi() {}
    public static final int KIND = 7, HEADER = 256, MAX_LENGTH = 3072;
    public static final int FLAGS = 1, CONTEXT = 2, SCREEN = 6, VARIANT = 7, FAMILY = 8;
    public static final int PAGE = 9, PAGE_COUNT = 11, PART = 13, PART_COUNT = 15;
    public static final int FIRST_STEP = 17, VISIBLE_STEPS = 19, CURSOR = 20, PLAYHEAD = 22;
    public static final int SELECTED_ROW = 24, FIRST_ROW = 26, ROW_COUNT = 28, TEMPO = 30;
    public static final int TRANSPORT = 32, TITLE = 33, SUBTITLE = 57, FOOTER = 81, CONTROLS = 112;
    public static final int ROWS = 1, NOTES = 2, CURVE = 3, MARKERS = 4,
            CELLS = 5, KEYBOARD = 6, TEXT = 7, SERIES = 8, EDGES = 9, LEDS = 10, PREFS = 11, ARP_GRID = 12,
            TRANSIENT_BOX = 13, CHROME = 14;
    public static final int FX = 0, SEL = 1, ENV = 2, LFO = 3, EDIT = 4, GLO = 5,
            HOME = 6, SAVE = 7, ARP = 8, SEQ = 9, PLAY = 10, REC = 11;
    public static final int NORMAL = 0, DRUM = 1;
    public static final int ANALOG = 2, FM_OPERATOR = 3, DRUM_SOUND = 4,
            SAMPLE_WAVE = 5, DRUM_LANES = 6;
    public static final int ICON_NONE = 0, ICON_NOTE = 1, ICON_STEP = 2, ICON_WAVE = 3,
            ICON_ENV = 4, ICON_LFO = 5, ICON_FX = 6, ICON_MIX = 7, ICON_SAVE = 8,
            ICON_PLAY = 9, ICON_REC = 10, ICON_LOCK = 11, ICON_MENU = 12;
    public static final int ENV_PAGE = 0, ENV2_PAGE = 1, LFO_PAGE = 2, LFO2_PAGE = 3,
            MOD_PAGE = 4, FX_PAGE = 5, FX_DELAY = 6, FX_REVERB = 7, DRIVE = 8,
            INSERT = 9, SLICER = 10, DUCK = 11, SCALE = 12, CHORD = 13,
            FOLLOW = 14, EDIT1 = 15, EDIT2 = 16, OPERATOR = 17, ALL_OPS = 18,
            LANES = 19, SOUND = 20, LANE_MIX = 21, SLICES = 22, SAMPLES = 23,
            OP1_ENV = 24, OP2_ENV = 25, OP3_ENV = 26, OP4_ENV = 27,
            OP_LEVEL = 28, VOICE = 29, VOICE2 = 30, MIXER = 31, DELAY = 32,
            REVERB = 33, CHORUS = 34, GLOBAL = 35, PRESETS = 36, USER = 37,
            PATTERNS = 38, PROJECT = 39, ARP_PAGE = 40, ARP2_PAGE = 41,
            NOTES_PAGE = 42, FEEL = 43, PATTERN = 44, AUTOMATION = 45,
            SONG = 46, HOME_PAGE = 47, SCOPE = 48, FX_LAYER = 49,
            GLO_LAYER = 50, SEL_LAYER = 51, EDIT_LAYER = 52, SEQ_LAYER = 53,
            REC_LAYER = 54, ARP_LAYER = 55, SHIFT = 56, MENU_DISPLAY = 57,
            MENU_CONTROL = 58, MENU_AUDIO = 59, MENU_SYSTEM = 60,
            MENU_MIDI = 61, ABOUT = 62, CONFIRM = 63, NAME = 64,
            UNDO = 65, TAKE = 66, TAKE_PICKER = 67, COUNT_IN = 68,
            UPDATE = 69, CALIBRATION = 70, RESET_SETTINGS = 71, APP_MENU = 72;

    private static int u14(byte[] b, int at) { return Wire6.u14(b, at); }
    private static boolean text(byte[] b, int at, int n) {
        for (int i = at; i < at + n; i++) if (b[i] < 32 || b[i] > 126) return false;
        return true;
    }
    private static boolean blank(byte[] b, int at, int n) {
        for (int i = at; i < at + n; i++) if (b[i] != ' ') return false;
        return true;
    }
    public static int familyFor(int s) {
        if (s <= 1) return ENV;
        if (s <= 4) return LFO;
        if (s <= 11) return FX;
        if (s <= 14) return SEL;
        if (s <= 30) return EDIT;
        if (s <= 35) return GLO;
        if (s <= 39) return SAVE;
        if (s <= 41) return ARP;
        if (s <= 46) return SEQ;
        if (s <= 48) return HOME;
        if (s == FX_LAYER) return FX;
        if (s == GLO_LAYER) return GLO;
        if (s == SEL_LAYER) return SEL;
        if (s == EDIT_LAYER) return EDIT;
        if (s == SEQ_LAYER) return SEQ;
        if (s == REC_LAYER) return REC;
        if (s == ARP_LAYER) return ARP;
        if (s >= MENU_DISPLAY && s <= ABOUT || s == CALIBRATION || s == RESET_SETTINGS || s == APP_MENU) return HOME;
        if (s == SHIFT || s >= CONFIRM && s <= UPDATE) return -1;
        return -2;
    }
    private static int tagsFor(int s) {
        if (s == APP_MENU) return 1 << ROWS;
        if (s >= FX_DELAY && s <= INSERT || s >= DELAY && s <= CHORUS)
            return (1 << ROWS) | (1 << CURVE) | (1 << MARKERS) | (1 << CELLS) |
                    (1 << TEXT) | (1 << SERIES);
        if (s >= EDIT1 && s <= ALL_OPS || s >= OP1_ENV && s <= OP_LEVEL)
            return (1 << ROWS) | (1 << CURVE) | (1 << MARKERS) | (1 << TEXT) |
                    (1 << SERIES) | (1 << EDGES);
        if (s == NOTES_PAGE || s == FEEL) return (1 << NOTES) | (1 << CELLS) | (1 << ROWS) | (1 << KEYBOARD);
        if (s == SLICER || s == PATTERN || s == SEQ_LAYER) return (1 << CELLS) | (1 << ROWS);
        if (s == SCALE || s == CHORD || s == FOLLOW) return (1 << KEYBOARD) | (1 << ROWS);
        if (s == ENV_PAGE || s == ENV2_PAGE || s >= OP1_ENV && s <= OP4_ENV) return (1 << CURVE) | (1 << MARKERS);
        if (s == LFO_PAGE || s == LFO2_PAGE) return (1 << CURVE) | (1 << MARKERS);
        if (s == SCOPE) return (1 << CURVE) | (1 << MARKERS) | (1 << ROWS);
        if (s == SLICES) return (1 << MARKERS) | (1 << ROWS) | (1 << CURVE);
        if (s == AUTOMATION || s == MOD_PAGE) return (1 << ROWS) | (1 << CURVE);
        if (s == FX_LAYER) return (1 << ROWS) | (1 << CELLS) | (1 << TEXT);
        if (s == GLO_LAYER || s == ARP_LAYER) return (1 << ROWS) | (1 << CELLS);
        if (s == SEL_LAYER) return (1 << ROWS) | (1 << KEYBOARD) | (1 << TEXT);
        if (s == SHIFT) return 1 << ROWS;
        if (s == REC_LAYER) return (1 << ROWS) | (1 << TEXT);
        if (s == COUNT_IN) return (1 << CELLS) | (1 << TEXT);
        if (s == EDIT_LAYER) return (1 << ROWS) | (1 << CELLS) | (1 << TEXT);
        if (s == NAME || s == TAKE || s == UNDO) return (1 << ROWS) | (1 << TEXT);
        if (s == TAKE_PICKER) return (1 << NOTES) | (1 << CURVE) | (1 << CELLS) | (1 << TEXT);
        if (s == ABOUT || s == CONFIRM || s == UPDATE) return 1 << TEXT;
        return (1 << ROWS) | (1 << CURVE) | (1 << MARKERS) | (1 << CELLS) | (1 << TEXT);
    }
    public static void validate(byte[] b) { validate(b, true); }
    static void validate(byte[] b, boolean panel) {
        if (b == null || b.length < HEADER || b.length > MAX_LENGTH || b[0] != KIND)
            throw new IllegalArgumentException("native length/kind");
        for (byte v : b) if ((v & 128) != 0) throw new IllegalArgumentException("native seven-bit");
        int s = b[SCREEN], family = b[FAMILY], selected = u14(b, PART), parts = u14(b, PART_COUNT);
        int first = u14(b, FIRST_ROW), rows = u14(b, ROW_COUNT), row = u14(b, SELECTED_ROW);
        if (s == APP_MENU && (!panel || b[FLAGS] >= 8 || u14(b, PAGE_COUNT) != 5) || (b[FLAGS] & ~15) != 0 ||
                (s >= EDIT1 && s <= VOICE2 ? b[VARIANT] > DRUM_LANES : b[VARIANT] > DRUM) ||
                familyFor(s) == -2 ||
                (familyFor(s) == -1 ? family > REC : family != familyFor(s)) ||
                (s >= MENU_DISPLAY && s <= ABOUT || s == CALIBRATION || s == RESET_SETTINGS) &&
                        ((b[FLAGS] & 8) == 0 || b.length != HEADER) ||
                u14(b, PAGE_COUNT) == 0 || u14(b, PAGE) >= u14(b, PAGE_COUNT) ||
                parts == 0 || selected >= parts || b[VISIBLE_STEPS] > 16 ||
                (b[VISIBLE_STEPS] != 0 && u14(b, FIRST_STEP) + b[VISIBLE_STEPS] > 16383) ||
                (rows == 0 ? first != 0 || row != 16383 : first >= rows ||
                        row != 16383 && row >= rows) || (b[TRANSPORT] & ~7) != 0 ||
                !text(b, TITLE, 79)) throw new IllegalArgumentException("native header");
        for (int at = CONTROLS; at < HEADER; at += 36) {
            int kind = b[at + 32], flags = b[at + 33], icon = b[at + 34];
            if (!text(b, at, 30) || kind > 4 || (flags & ~15) != 0 || icon > ICON_MENU || b[at + 35] != 0 ||
                    kind == 0 && (!blank(b, at, 30) || u14(b, at + 30) != 0 || flags != 0 || icon != 0))
                throw new IllegalArgumentException("native control");
        }
        int seen = 0;
        for (int at = HEADER; at < b.length;) {
            if (at + 3 > b.length) throw new IllegalArgumentException("section header");
            int tag = b[at], len = u14(b, at + 1), p = at + 3;
            if (tag < ROWS || tag > CHROME || (seen & (1 << tag)) != 0 ||
                    tag < LEDS && (tagsFor(s) & (1 << tag)) == 0 || p + len > b.length)
                throw new IllegalArgumentException("section tag/length");
            seen |= 1 << tag;
            if (tag == CHROME) {
                if (!panel || s > SCOPE || len != 56 || b[p+2] > 2 ||
                        !text(b,p+3,9) || b[p+12] > 1 || !text(b,p+13,40) ||
                        ((b[p+53] | b[p+54]) & ~27) != 0 || b[p+55] != 0)
                    throw new IllegalArgumentException("native chrome");
                at = p + len; continue;
            }
            if (tag == TRANSIENT_BOX) {
                if (!panel || s > SCOPE || s == MIXER || len != 67 ||
                        b[p] >= parts || b[p + 1] > 1 || b[p + 66] > 3 || !text(b, p + 2, 64))
                    throw new IllegalArgumentException("transient box");
                at = p + len; continue;
            }
            if (tag == ARP_GRID) {
                if (!panel || (s != 40 && s != 41) || len < 9 ||
                        b[p] > 14 || b[p+1] < 1 || b[p+1] > 4 || b[p+3] > 100 || b[p+5] > 1 ||
                        b[p+6] < 1 || b[p+6] > 64 || b[p+7] < 1 || b[p+7] > 65 || len != 8+b[p+7])
                    throw new IllegalArgumentException("arpeggio grid");
                at = p + len; continue;
            }
            if (tag == PREFS) {
                if (!panel || len < 2 || b[p] > 5 || b[p+1] > 15 || len != ((b[p+1] & 4) != 0 ? 35 : 2))
                    throw new IllegalArgumentException("panel look");
                for (int i = 4; i < len; i += 3) if (b[p+i] > 3) throw new IllegalArgumentException("RGB565");
                at = p + len; continue;
            }
            if (tag == LEDS) {
                if (!panel || p + len != b.length || len != 29 || b[p+28] > 1) throw new IllegalArgumentException("panel LED masks");
                for (int i = 0; i < 28; i += 7)
                    if (b[p+i+2] > 1 || b[p+i+6] > 63) throw new IllegalArgumentException("panel LED range");
                at = p + len; continue;
            }
            if (tag == SERIES) {
                if (len == 0 || b[p] > 6) throw new IllegalArgumentException("series count");
                int pos = p + 1, used = 0;
                for (int i = 0; i < b[p]; i++) {
                    if (pos + 3 > p + len) throw new IllegalArgumentException("series length");
                    int id = b[pos], points = b[pos + 2];
                    if (id > 5 || (used & (1 << id)) != 0 || b[pos + 1] > 7 ||
                            ((s >= OPERATOR && s <= ALL_OPS || s >= OP1_ENV && s <= OP_LEVEL ||
                            (s == EDIT1 || s == EDIT2) && b[VARIANT] == FM_OPERATOR) && points > 12) ||
                            pos + 3 + 6 * points > p + len)
                        throw new IllegalArgumentException("series record");
                    used |= 1 << id; pos += 3 + 6 * points;
                }
                if (pos != p + len) throw new IllegalArgumentException("series tail");
                at = p + len; continue;
            }
            if (tag == EDGES) {
                if (len == 0 || b[p] > 36 || len != 1 + 5 * b[p])
                    throw new IllegalArgumentException("edges count");
                for (int i = 0; i < b[p]; i++) {
                    int q = p + 1 + 5 * i;
                    if (b[q] > 5 || b[q + 1] > 5 || b[q + 2] > 1)
                        throw new IllegalArgumentException("edge");
                }
                at = p + len; continue;
            }
            if (len == 0) throw new IllegalArgumentException("section empty");
            int count = tag == ROWS || tag == MARKERS || tag == TEXT ? b[p] :
                    tag == KEYBOARD ? 0 : u14(b, p);
            int base = tag == KEYBOARD ? 20 : tag == ROWS || tag == MARKERS || tag == TEXT ? 1 : 2;
            int stride = tag == ROWS ? 48 : tag == NOTES ? 7 : tag == CURVE ? 4 :
                    tag == MARKERS ? 13 : tag == CELLS ? 7 : tag == TEXT ? 24 : 0;
            int limit = tag == ROWS || tag == TEXT ? 16 : tag == MARKERS ? 32 : 256;
            if (tag == KEYBOARD ? len != 20 || (b[p + 18] & 0x7c) != 0 :
                    len < base || count > limit || len != base + count * stride)
                throw new IllegalArgumentException("section count");
            if (s == APP_MENU && tag == ROWS &&
                    (count < 1 || count > 6 || first + count > rows || row < first || row >= first + count))
                throw new IllegalArgumentException("menu row window");
            if (tag == ROWS) for (int i = 0; i < count; i++) {
                int q = p + 1 + 48 * i;
                if (!text(b, q, 40) || b[q + 47] != 0) throw new IllegalArgumentException("row");
            }
            if (tag == NOTES) for (int i = 0; i < count; i++) {
                int q = p + 2 + 7 * i;
                if (u14(b, q + 3) == 0 || (b[q + 5] & ~63) != 0) throw new IllegalArgumentException("note");
            }
            if (tag == MARKERS) for (int i = 0; i < count; i++) {
                int q = p + 1 + 13 * i;
                if (!text(b, q + 5, 8)) throw new IllegalArgumentException("marker");
            }
            if (tag == CELLS) for (int i = 0; i < count; i++) {
                int q = p + 2 + 7 * i;
                if (!text(b, q + 3, 4)) throw new IllegalArgumentException("cell");
            }
            if (tag == TEXT && !text(b, p + 1, count * 24)) throw new IllegalArgumentException("text");
            at = p + len;
        }
        if (s == APP_MENU && (seen & (1 << ROWS)) == 0) throw new IllegalArgumentException("missing menu rows");
    }

    /** Apply session negotiation before computing either a full publication or a delta.
     * The caller has validated the application's complete view. */
    static byte[] withoutChrome(byte[] view) {
        for (int at = HEADER; at < view.length;) {
            int next = at + 3 + u14(view, at + 1);
            if (view[at] == CHROME) {
                byte[] compatible = java.util.Arrays.copyOf(view, view.length - (next - at));
                System.arraycopy(view, next, compatible, at, view.length - next);
                return compatible;
            }
            at = next;
        }
        return view;
    }
}
