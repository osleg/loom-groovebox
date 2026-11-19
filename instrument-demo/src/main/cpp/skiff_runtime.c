/* Stock Skiff's browser host supplies the firmware engine, UI and NOR device. */
#include <stdint.h>
#include <string.h>
#include <math.h>
#ifndef SKIFF_HOST_TEST
#include <jni.h>
#endif

/* The phone executes both rendering sections in order on one thread, while
 * retaining the stock two-core 16-voice allocation and exact mix summation. */
#define C1_ON() 1
#define C1_RUN(fn, arg) ((fn)(arg), 1)
#define C1_WAIT() 0
#define C1_SPLIT() 0
#include "felucca_web.c"

#define SKIFF_VIEW_CAP 3072u
#define KEY_EVENTS 256u
static uint32_t key_events[KEY_EVENTS], key_serials[KEY_EVENTS], key_read, key_write;
static uint32_t logical_keys;
static int runtime_booted, runtime_stopped;
static uint32_t skiff_master_q12;
static int skiff_master_ready;
static uint8_t skiff_device_screen;
static uint32_t host_buttons;
static uint32_t midi_frame[MQO], last_render_start, last_render_end;
static uint8_t diagnostic_on, diagnostic_probe_armed, diagnostic_marker_left;
static uint32_t diagnostic_probe_marks;
static uint32_t diagnostic_quiet_frames, diagnostic_probe_frame, diagnostic_key_state;
static uint32_t diagnostic_probe_deadline;
static uint32_t diagnostic_key_rises, diagnostic_arm_rejected;
static uint32_t diagnostic_probe_serial, diagnostic_onset_count;
static uint64_t diagnostic_onsets[64];
#include "skiff_samples.inc"

/* A full-size file can still be a torn or unrelated image. Stock NOR commits
 * have independent header and body CRCs; at least one must survive unless the
 * chip is completely blank. The firmware itself chooses the newest good copy. */
static int skiff_nor_size_supported(uint32_t size)
{
    return size == sizeof nor || size == 0x100000u;
}

static int skiff_saved_nor_valid(const uint8_t *saved, uint32_t size)
{
    uint32_t obj, copy, i;
    if (!saved || !skiff_nor_size_supported(size)) return 0;
    for (obj = 0; obj < OBJ_COUNT; obj++) for (copy = 0; copy < 2u; copy++) {
        uint32_t off = st_sector(obj, copy);
        st_hdr_t h;
        memcpy(&h, saved + off, sizeof h);
        if (h.magic == ST_MAGIC && h.type == obj && h.slot == copy &&
            h.len <= ST_PAYLOAD_MAX && h.hcrc == st_crc32(&h, sizeof h - 4u) &&
            h.crc == st_crc32(saved + off + ST_PAYLOAD_OFF, h.len)) return 1;
    }
    for (unsigned bank = 0; bank < 2u; bank++)
        for (unsigned sector = 0; sector < (bank ? 68u : 20u); sector++) {
            unsigned off = bank ? 0x100000u + sector * 0x1000u :
                           sector < 2u ? 0xE7000u + sector * 0x1000u : 0xEA000u + (sector - 2u) * 0x1000u;
            bk_hdr_t header;
            if (off + 4096u > size) break;
            memcpy(&header, saved + off, sizeof header);
            if (header.magic == (bank ? 0x32424B53u : 0x31424B53u) &&
                header.chunk < (bank ? 64u : 16u) &&
                header.len == (bank ? 2760u : 3552u) &&
                header.hsum == proj_hash(&header, sizeof header - 4u) &&
                header.sum == proj_hash(saved + off + 256u, header.len))
                return 1;
        }
    for (unsigned slot = 0; slot < SMP_USER_SLOTS; slot++) {
        unsigned off = SMP_USER_BASE + slot * SMP_USER_SIZE;
        if (off + SMP_USER_SIZE <= size && skiff_sample_blob_valid(saved + off, SMP_USER_SIZE)) return 1;
    }
    for (i = 0; i < size; i++) if (saved[i] != 0xFFu) return 0;
    return 1;
}

static void mark_midi(uint32_t first, uint32_t frame, uint32_t base_tick)
{
    uint32_t i;
    for (i = first; i != mo_w; i++) {
        int32_t ticks = (int32_t)(mo.due[i % MQO] - base_tick);
        int32_t offset = ticks / (int32_t)DAC_TICKS;
        if (offset < 0) offset = 0;
        if (offset >= (int32_t)HALF_FRAMES) offset = HALF_FRAMES - 1;
        midi_frame[i % MQO] = frame + (uint32_t)offset;
    }
}

static void skiff_boot(const uint8_t *saved, uint32_t size)
{
#if defined(ENGI_DX386)
    if (!runtime_booted || runtime_stopped) dx386_start_worker();
#endif
#if defined(ENGI_SID)
    if (!runtime_booted) sid_prepare_models();
#endif
    skiff_master_q12 = 0;
    skiff_master_ready = 0;             /* every attachment needs its first physical pot scan */
    song.master_q12 = 0;
    if (runtime_booted) {
        if (!runtime_stopped)
            return;                    /* attaching to the live audio session */
        diagnostic_on = diagnostic_probe_armed = diagnostic_marker_left = 0;
        diagnostic_probe_marks = 0;
        diagnostic_key_rises = diagnostic_arm_rejected = 0;
        runtime_stopped = 0;
        if (!saved || !skiff_nor_size_supported(size) || (size == sizeof nor && !memcmp(saved, nor, size)))
            return;                    /* the saved state is already resident */
        /* A disconnected service may reopen with a different validated NOR
         * image. Give the stock power-on path fresh volatile state and read
         * that image exactly as a new process would. */
        web_nor_erase();
        memcpy(nor, saved, size);
        web_flash_writes++;            /* revision remains monotonic */
        memset(&song, 0, sizeof song);
        memset(trk, 0, sizeof trk);
        memset(&ui, 0, sizeof ui);
        memset(&as, 0, sizeof as);
        memset(&mn, 0, sizeof mn);
        memset(&midi_clock, 0, sizeof midi_clock);
        memset(&fm1_in, 0, sizeof fm1_in);
        memset(kb_note, KB_SILENT, sizeof kb_note);
        memset(kb_chn, 0, sizeof kb_chn);
        memset(kb_ped, 0, sizeof kb_ped);
        kb_prev = kb_ped_trk = kb_ped_sol = 0;
        transport_req = panic_req = 0;
        mi_r = mi_w = mo_r = mo_w = 0;
        usb.config = 0;
        memset(&fx, 0, sizeof fx);
        memset(dly_buf, 0, sizeof dly_buf);
        memset(cho_buf, 0, sizeof cho_buf);
        rev_clear();
        memset(ins, 0, sizeof ins);
        memset(&pf, 0, sizeof pf);
        web_booted = web_boot_ms = web_last_frame = 0;
        fm1_ms = web_ticks = web_samples_due = web_half = 0;
        ring_w = ring_r = 0;
        key_read = key_write = logical_keys = 0;
        host_buttons = skiff_device_screen = 0;
        last_render_start = last_render_end = 0;
        memset(web_enc_steps, 0, sizeof web_enc_steps);
    } else {
        diagnostic_on = diagnostic_probe_armed = diagnostic_marker_left = 0;
        diagnostic_probe_marks = 0;
        diagnostic_key_rises = diagnostic_arm_rejected = 0;
        web_nor_erase();
        if (saved && skiff_nor_size_supported(size))
            memcpy(web_nor(), saved, size);
    }
    web_boot();
    /* The app's selected Android MIDI endpoint is the stock USB-MIDI host
     * for the firmware's output ring, even though no USB controller runs. */
    usb.config = 1;
    /* Complete the stock splash and enable the input loop before Android
     * grants an output route. Its generated audio is never played. */
    while (web_boot_ms)
        web_ms();
    ring_r = ring_w;
    mo_r = mo_w;                       /* no startup clock before the output route */
    last_render_start = last_render_end = ring_r;
    runtime_booted = 1;
    runtime_stopped = 0;
    song.master_q12 = 0;
}

/* The caller supplies a separate, memory-only NOR. This fixture uses the stock
 * voices, sequencer, mixer and screen; track 1 is the physical key probe. */
static void skiff_start_diagnostic_pattern(void)
{
    uint32_t k, s;
    static const uint8_t notes[5] = {48, 55, 36, 43, 38};
    if (!runtime_booted || runtime_stopped || diagnostic_on) return;
    seq_stop();
    lists_clear();
    song.rec = 0;
    song.g[G_BPM] = 128;
    song.g[G_CLOCK] = 0;
    song.g[G_SWING] = 0;
    for (k = 0; k < NTRK; k++) {
        track_t *t = &trk[k];
        track_defaults(t);
        set_engine_of(t, TRK_DEF[k][0]);
        apply_preset_to(t, k ? TRK_DEF[k][1] : 0u);
        t->engine = t->eng_req;
        track_defaults_steps(t);
        t->p[P_PAN] = k ? -64 : 63;
        t->p[P_CHOR] = t->p[P_DLY] = t->p[P_REV] = 0;
        t->p[P_SPRD] = 0;
        t->p[P_ATK] = 0;
        t->p[P_REL] = k ? 12 : 0;
        t->p[P_SLEN] = 16;
        if (!k) t->p[P_LEVEL] = 127;
        if (!k || k > sizeof notes) continue;
        t->p[P_DIST] = 12 + (int32_t)k * 4;
        for (s = 0; s < 16; s++) {
            step_t *p = &t->step[s];
            if ((s + k) % 4u == 3u) continue;
            p->time = ST_NOTE;
            p->n = 1;
            p->note[0] = (uint8_t)(notes[k - 1u] +
                (s % 4u == 2u ? (k == 3u || k == 5u ? 6u : 7u) : 0u));
            p->vel = (uint8_t)(82u + (s & 3u) * 9u);
            if (k == 2u || k == 4u) {
                p->n = 2;
                p->note[1] = (uint8_t)(p->note[0] + 7u);
            }
        }
        t->seq_active = 1;
    }
    /* EDIT 1's first knob is a numeric engine parameter. */
    sel_set(0);
    ui.home = ui.menu = ui.layer = 0;
    ui.page = (uint8_t)page_first(FAM_EDIT);
    ui.force = 1;
    fx_lowcut = 0;                /* BASS+ sums stereo channels into its bass path. */
    diagnostic_probe_armed = diagnostic_marker_left = 0;
    diagnostic_probe_marks = 0;
    diagnostic_quiet_frames = diagnostic_key_state = 0;
    diagnostic_key_rises = diagnostic_arm_rejected = 0;
    diagnostic_on = 1;
    ring_r = ring_w;
    transport_req = 1;
}

static void skiff_master_gain(int32_t q12)
{
    if (diagnostic_on && diagnostic_probe_armed &&
        (q12 < 0 || (uint32_t)q12 != skiff_master_q12))
        diagnostic_probe_armed = 0;    /* volume movement cannot date an old key press */
    if (q12 < 0 || !runtime_booted || runtime_stopped) {
        skiff_master_ready = 0;
        skiff_master_q12 = song.master_q12 = 0;
        return;
    }
    if (q12 > 4096) q12 = 4096;
    skiff_master_q12 = song.master_q12 = (uint32_t)q12;
    fx_usb_fixed = 0;
    if (!skiff_master_ready) ring_r = ring_w;
    skiff_master_ready = 1;
}

static int skiff_input_serial(uint32_t buttons, uint32_t keys, const int32_t turns[7], uint32_t serial)
{
    uint32_t i, first = mo_w, raw_buttons = buttons;
    int key_only;
    if (!runtime_booted)
        return 0;
    if (skiff_device_screen) {
        host_buttons = buttons;
        return 0;                       /* app-owned device information screen */
    }
    if (ui.menu == 1 && (ui.menu_sel == MI_PANEL || ui.menu_sel == MI_ABOUT)) {
        if ((buttons & (1u << B_OCTUP)) && !(host_buttons & (1u << B_OCTUP))) {
            skiff_device_screen = ui.menu_sel == MI_PANEL ? 70u : 62u;
            host_buttons = raw_buttons;
            web_buttons(0);             /* stock panel_setup is a physical calibration loop */
            return 0;
        }
        buttons &= ~(1u << B_OCTUP);     /* held press after overlay exit is still consumed */
    }
    key_only = keys != logical_keys && buttons == host_buttons && skiff_master_ready;
    for (i = 0; i < 7; i++) if (turns[i]) key_only = 0;
    host_buttons = raw_buttons;
    if (keys != logical_keys) {
        logical_keys = keys;
        if (key_write - key_read == KEY_EVENTS)
            key_read++;                 /* release remains reachable if input floods */
        key_events[key_write % KEY_EVENTS] = keys;
        key_serials[key_write++ % KEY_EVENTS] = serial;
    }
    web_buttons(buttons);
    for (i = 0; i < 7; i++)
        if (turns[i])
            web_enc(i, turns[i]);
    /* A button/encoder edge must work while the audio route is unopened. */
    if (!key_only) ui_input();
    for (i = first; i != mo_w; i++)
        midi_frame[i % MQO] = ring_r;
    return 1;
}

static void skiff_input(uint32_t buttons, uint32_t keys, const int32_t turns[7])
{ skiff_input_serial(buttons, keys, turns, 0); }

static void skiff_render(int16_t *dst, uint32_t frames)
{
    uint32_t done = 0;
    if (!runtime_booted) {
        memset(dst, 0, frames * 2u * sizeof *dst);
        return;
    }
    last_render_start = ring_r;
    diagnostic_onset_count = 0;
    while (done < frames) {
        uint32_t count = frames - done, i;
        if (count > 1024u)
            count = 1024u;
        while (ring_w - ring_r < count) {
            uint32_t first = mo_w, frame = ring_w;
            if (web_samples_due + FS >= 1000u * HALF_FRAMES && key_read != key_write) {
                uint32_t keys = key_events[key_read % KEY_EVENTS];
                uint32_t serial = key_serials[key_read++ % KEY_EVENTS];
                if (diagnostic_on) {
                    /* A retrigger over the last note's tail has no isolated onset.
                     * Accept only samples generated after this physical input. */
                    if ((keys & 1u) && !(diagnostic_key_state & 1u)) {
                        diagnostic_key_rises++;
                        diagnostic_probe_armed = skiff_master_q12 > 0u &&
                            diagnostic_quiet_frames >= HALF_FRAMES;
                        if (skiff_master_q12 > 0u && !diagnostic_probe_armed)
                            diagnostic_arm_rejected++;
                        diagnostic_probe_frame = frame;
                        diagnostic_probe_serial = serial;
                        diagnostic_probe_deadline = frame + 512u;
                    } else if (!(keys & 1u) && (diagnostic_key_state & 1u) &&
                               diagnostic_probe_armed) {
                        /* A short tap can still have an audio half in flight. */
                        uint32_t limit = frame + HALF_FRAMES;
                        if ((int32_t)(limit - diagnostic_probe_deadline) < 0)
                            diagnostic_probe_deadline = limit;
                    }
                    diagnostic_key_state = keys;
                }
                web_keys(keys);
            }
            web_ms();
            song.master_q12 = skiff_master_ready ? skiff_master_q12 : 0u;
            fx_usb_fixed = 0;          /* the companion applies MASTER on its USB audio return */
            mark_midi(first, frame, web_ticks + HALF_FRAMES * DAC_TICKS);
        }
        for (i = 0; i < count; i++) {
            uint32_t at = (ring_r++ % RING) * 2u;
            float l = ring[at] * 32768.0f, r = ring[at + 1u] * 32768.0f;
            if (diagnostic_on) {
                /* The right stem is only the stock probe track: five running
                 * tracks are hard left, sends and stereo master bass are off.
                 * A marker needs a quantized contribution to the audible left
                 * mix, above the previous note's small release residue. */
                int32_t before = (int32_t)l;
                int32_t with_probe = (int32_t)(l + r * 0.5f);
                int above = skiff_master_ready && before != with_probe &&
                    (r >= 16.0f || r <= -16.0f);
                if (!above) {
                    if (diagnostic_quiet_frames < HALF_FRAMES) diagnostic_quiet_frames++;
                } else {
                    diagnostic_quiet_frames = 0;
                }
                if (diagnostic_probe_armed &&
                    (int32_t)(ring_r - 1u - diagnostic_probe_deadline) >= 0)
                    diagnostic_probe_armed = 0;
                if (diagnostic_probe_armed && above &&
                    (int32_t)(ring_r - 1u - diagnostic_probe_frame) >= 0) {
                    diagnostic_probe_armed = 0;
                    diagnostic_marker_left = 32;
                    diagnostic_probe_marks++;
                    if (diagnostic_onset_count < sizeof diagnostic_onsets / sizeof diagnostic_onsets[0])
                        diagnostic_onsets[diagnostic_onset_count++] =
                            ((uint64_t)diagnostic_probe_serial << 32) | (done + i);
                }
                l += r * 0.5f;     /* the physical note remains audible in the mix */
                r = 0.0f;
                if (diagnostic_marker_left) {
                    uint32_t phase = 32u - diagnostic_marker_left;
                    r = (phase & 8u) ? -24576.0f : 24576.0f;
                    diagnostic_marker_left--;
                }
            }
            dst[2u * (done + i)] = skiff_master_ready ?
                (int16_t)(l > 32767.0f ? 32767 : l < -32768.0f ? -32768 : (int32_t)l) : 0;
            dst[2u * (done + i) + 1u] = skiff_master_ready ?
                (int16_t)(r > 32767.0f ? 32767 : r < -32768.0f ? -32768 : (int32_t)r) : 0;
        }
        if (skiff_capturing) skiff_capture_audio(dst + 2u * done, count);
        done += count;
    }
    last_render_end = ring_r;
}

static void skiff_stop(void)
{
    uint32_t t, v, first = mo_w;
    if (!runtime_booted)
        return;
    skiff_cancel_sample_capture();
#if defined(ENGI_DX386)
    dx386_stop_worker();
#endif
    runtime_stopped = 1;
    diagnostic_on = diagnostic_probe_armed = diagnostic_marker_left = 0;
    skiff_master_ready = 0;
    skiff_master_q12 = song.master_q12 = 0;
    key_read = key_write = 0;
    logical_keys = 0;
    web_buttons(0);
    web_keys(0);
    memset(web_enc_steps, 0, sizeof web_enc_steps);
    ui_input();
    seq_stop();
    transport_req = 0;
    panic_req = 0;
    kb_prev = 0;
    memset(kb_chn, 0, sizeof kb_chn);
    memset(kb_ped, 0, sizeof kb_ped);
    kb_ped_trk = kb_ped_sol = 0;
    for (t = 0; t < NTRK; t++) {
        trk_all_off(&trk[t]);
        trk[t].nheld = trk[t].arp_phys = 0;
        arp_forget(&trk[t]);
        for (v = 0; v < NVOICE; v++)
            trk[t].v[v].active = trk[t].v[v].gate = 0;
    }
    for (t = first; t != mo_w; t++)
        midi_frame[t % MQO] = ring_r;
    ring_r = ring_w;
    last_render_start = ring_r;
    last_render_end = ring_r + 1u;
    /* MIDI release/Stop packets were timestamped for the next audio half;
     * the service may close audio immediately after stop. */
    web_ticks += 2u * HALF_FRAMES * DAC_TICKS;
    /* Let the stock autosave finish after voices have stopped. The stop does
     * not restart the sequencer or create a new take. */
    for (t = 0; t < 32; t++) {
        fm1_ms += 250;
        web_ticks += 250u * 1000u * FM1_TICKS_PER_US;
        autosave_poll();
        if (!as.round && as.saved == autosave_sig())
            break;
    }
}

static uint32_t skiff_revision(void) { return web_flash_writes_count(); }

static int skiff_midi_in(uint32_t packet, uint32_t source)
{
    if (!runtime_booted || (source != 1u && source != 2u))
        return 0;
    return midi_enqueue(packet, source);
}

static uint32_t skiff_midi_out(uint32_t *packets, uint32_t cap)
{
    uint8_t bytes[MQO * 4u];
    uint32_t i, size;
    if (!runtime_booted)
        return 0;
    if (cap > MQO)
        cap = MQO;
    mo.now = web_ticks;
    size = mo_take(bytes, cap * 4u, mo.now);
    for (i = 0; i < size / 4u; i++)
        packets[i] = (uint32_t)bytes[i * 4u] | (uint32_t)bytes[i * 4u + 1u] << 8 |
                     (uint32_t)bytes[i * 4u + 2u] << 16 | (uint32_t)bytes[i * 4u + 3u] << 24;
    return size / 4u;
}

static uint32_t skiff_midi_out_timed(uint64_t *events, uint32_t cap)
{
    uint32_t count = 0;
    if (!runtime_booted)
        return 0;
    while (mo_r != mo_w && count < cap) {
        uint32_t at = mo_r % MQO, frame = midi_frame[at];
        int32_t offset = (int32_t)(frame - last_render_start);
        if (!runtime_stopped && (int32_t)(frame - last_render_end) >= 0)
            break;
        events[count++] = ((uint64_t)(uint32_t)offset << 32) | midi_out_q[at];
        mo_r++;
    }
    return count;
}

static void skiff_exit_device_menu(void)
{
    if (!runtime_booted) return;
    skiff_device_screen = 0;
    web_buttons(0);
    ui.force = 1;
}

/* View adapters are intentionally included after the stock host, so they can
 * read its internal UI state without a second, diverging model. */
#include "skiff_view.inc"
#include "skiff_capture_cards.inc"
#include "skiff_led_view.inc"

#ifndef SKIFF_HOST_TEST
#define JNI_FN(name) Java_org_fm1_companion_demo_SkiffNative_##name

JNIEXPORT void JNICALL JNI_FN(boot)(JNIEnv *env, jobject self, jbyteArray saved)
{
    uint32_t size = saved ? (uint32_t)(*env)->GetArrayLength(env, saved) : 0;
    jbyte *p = saved && skiff_nor_size_supported(size) ? (*env)->GetByteArrayElements(env, saved, 0) : 0;
    (void)self;
    if (saved && (!p || !skiff_saved_nor_valid((const uint8_t *)p, size))) {
        jclass type = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
        if (p) (*env)->ReleaseByteArrayElements(env, saved, p, JNI_ABORT);
        if (type) (*env)->ThrowNew(env, type, "Saved Skiff NOR image is incomplete or corrupt");
        return;
    }
    skiff_boot((const uint8_t *)p, size);
    if (p)
        (*env)->ReleaseByteArrayElements(env, saved, p, JNI_ABORT);
}

JNIEXPORT void JNICALL JNI_FN(render)(JNIEnv *env, jobject self, jshortArray out)
{
    jsize n = (*env)->GetArrayLength(env, out);
    jshort *p = (*env)->GetShortArrayElements(env, out, 0);
    (void)self;
    if (!p)
        return;
    skiff_render((int16_t *)p, (uint32_t)(n / 2));
    if (n & 1)
        p[n - 1] = 0;
    (*env)->ReleaseShortArrayElements(env, out, p, 0);
}

JNIEXPORT void JNICALL JNI_FN(startDiagnosticPattern)(JNIEnv *env, jobject self)
{ (void)env; (void)self; skiff_start_diagnostic_pattern(); }

JNIEXPORT jboolean JNICALL JNI_FN(setDxText)(JNIEnv *env, jobject self, jstring text)
{
    (void)self;
#if defined(ENGI_DX386)
    if (!runtime_booted || runtime_stopped || diagnostic_on || !text ||
        (*env)->GetStringUTFLength(env, text) > 63)
        return JNI_FALSE;
    const char *line = (*env)->GetStringUTFChars(env, text, 0);
    if (!line)
        return JNI_FALSE;
    int result = dx386_set_text(line);
    (*env)->ReleaseStringUTFChars(env, text, line);
    return result == 0;
#else
    (void)env; (void)text;
    return JNI_FALSE;
#endif
}

JNIEXPORT jbyteArray JNICALL JNI_FN(encodeSample)(JNIEnv *env, jobject self, jshortArray pcm,
                                                jstring name, jint root)
{
    uint32_t frames = pcm ? (uint32_t)(*env)->GetArrayLength(env, pcm) : 0;
    uint32_t size = skiff_encoded_sample_size(frames);
    (void)self;
    if (!size || !name || root < 0 || root > 127) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, (jsize)size);
    if (!result) return NULL;
    jshort *samples = (*env)->GetShortArrayElements(env, pcm, 0);
    if (!samples) return NULL;
    const char *label = (*env)->GetStringUTFChars(env, name, 0);
    jbyte *encoded = label ? (*env)->GetByteArrayElements(env, result, 0) : NULL;
    int success = encoded && skiff_encode_sample((uint8_t *)encoded, size, (const int16_t *)samples,
                                                  frames, label, (uint32_t)root);
    if (encoded) (*env)->ReleaseByteArrayElements(env, result, encoded, success ? 0 : JNI_ABORT);
    if (label) (*env)->ReleaseStringUTFChars(env, name, label);
    (*env)->ReleaseShortArrayElements(env, pcm, samples, JNI_ABORT);
    return success ? result : NULL;
}

JNIEXPORT jint JNICALL JNI_FN(storeSample)(JNIEnv *env, jobject self, jint slot, jbyteArray encoded)
{
    (void)self;
    uint32_t size = encoded ? (uint32_t)(*env)->GetArrayLength(env, encoded) : 0;
    if (slot < 0 || slot >= (jint)SMP_USER_SLOTS || size < SMP_USER_DATA || size > SMP_USER_SIZE) return 1;
    jbyte *bytes = (*env)->GetByteArrayElements(env, encoded, 0);
    if (!bytes) return 3;
    int result = skiff_store_sample((uint32_t)slot, (const uint8_t *)bytes, size);
    (*env)->ReleaseByteArrayElements(env, encoded, bytes, JNI_ABORT);
    return (jint)result;
}

JNIEXPORT jboolean JNICALL JNI_FN(startSampleCapture)(JNIEnv *env, jobject self)
{ (void)env; (void)self; return skiff_start_sample_capture() ? JNI_TRUE : JNI_FALSE; }

JNIEXPORT jshortArray JNICALL JNI_FN(finishSampleCapture)(JNIEnv *env, jobject self)
{
    (void)self;
    skiff_capturing = 0;
    if (!skiff_capture_count) return NULL;
    jshortArray result = (*env)->NewShortArray(env, (jsize)skiff_capture_count);
    if (result) {
        (*env)->SetShortArrayRegion(env, result, 0, (jsize)skiff_capture_count, (const jshort *)skiff_capture);
        skiff_capture_count = 0;
    }
    return result;
}

JNIEXPORT void JNICALL JNI_FN(cancelSampleCapture)(JNIEnv *env, jobject self)
{ (void)env; (void)self; skiff_cancel_sample_capture(); }

JNIEXPORT jintArray JNICALL JNI_FN(diagnosticProbeStats)(JNIEnv *env, jobject self)
{
    jint values[4] = {(jint)diagnostic_key_rises, (jint)diagnostic_arm_rejected,
                      (jint)diagnostic_probe_marks, (jint)skiff_master_q12};
    jintArray out = (*env)->NewIntArray(env, 4);
    (void)self;
    if (out) (*env)->SetIntArrayRegion(env, out, 0, 4, values);
    return out;
}

JNIEXPORT jlongArray JNICALL JNI_FN(diagnosticOnsets)(JNIEnv *env, jobject self)
{
    if (!diagnostic_onset_count) return NULL;
    jlongArray out = (*env)->NewLongArray(env, (jsize)diagnostic_onset_count);
    (void)self;
    if (out && diagnostic_onset_count)
        (*env)->SetLongArrayRegion(env, out, 0, (jsize)diagnostic_onset_count,
                                   (const jlong *)diagnostic_onsets);
    diagnostic_onset_count = 0;
    return out;
}

JNIEXPORT void JNICALL JNI_FN(masterGain)(JNIEnv *env, jobject self, jint q12)
{ (void)env; (void)self; skiff_master_gain(q12); }

JNIEXPORT void JNICALL JNI_FN(input)(JNIEnv *env, jobject self, jint buttons, jint keys, jintArray turns)
{
    int32_t n[7] = {0};
    jsize len = turns ? (*env)->GetArrayLength(env, turns) : 0;
    (void)self;
    if (len > 7) len = 7;
    if (len)
        (*env)->GetIntArrayRegion(env, turns, 0, len, (jint *)n);
    skiff_input((uint32_t)buttons, (uint32_t)keys, n);
}

JNIEXPORT jboolean JNICALL JNI_FN(inputSerial)(JNIEnv *env, jobject self, jint buttons, jint keys,
                                           jintArray turns, jint serial)
{
    int32_t n[7] = {0};
    jsize len = turns ? (*env)->GetArrayLength(env, turns) : 0;
    (void)self;
    if (len > 7) len = 7;
    if (len) (*env)->GetIntArrayRegion(env, turns, 0, len, (jint *)n);
    return skiff_input_serial((uint32_t)buttons, (uint32_t)keys, n, (uint32_t)serial) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL JNI_FN(editContext)(JNIEnv *env, jobject self)
{ (void)env; (void)self; return runtime_booted ? (jint)skiff_edit_context() : -1; }

JNIEXPORT jbyteArray JNICALL JNI_FN(snapshot)(JNIEnv *env, jobject self)
{
    uint8_t out[SKIFF_VIEW_CAP] = {0};
    uint32_t length;
    jbyteArray result;
    (void)self;
    if (!runtime_booted)
        return (*env)->NewByteArray(env, 0);
    length = skiff_make_view(out);
    if (length > SKIFF_VIEW_CAP)
        length = SKIFF_VIEW_CAP;
    skiff_capture_cards(out, length);
    length = skiff_append_led_view(out, length);
    if (length > SKIFF_VIEW_CAP)
        length = SKIFF_VIEW_CAP;
    result = (*env)->NewByteArray(env, (jsize)length);
    if (result)
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)length, (jbyte *)out);
    return result;
}

JNIEXPORT void JNICALL JNI_FN(stop)(JNIEnv *env, jobject self)
{ (void)env; (void)self; skiff_stop(); }

JNIEXPORT jint JNICALL JNI_FN(storageRevision)(JNIEnv *env, jobject self)
{ (void)env; (void)self; return (jint)skiff_revision(); }

JNIEXPORT jbyteArray JNICALL JNI_FN(storage)(JNIEnv *env, jobject self, jint lastRevision)
{
    jbyteArray result;
    (void)self;
    if (!runtime_booted || (uint32_t)lastRevision == skiff_revision())
        return 0;
    result = (*env)->NewByteArray(env, (jsize)sizeof nor);
    if (result)
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)sizeof nor, (jbyte *)nor);
    return result;
}

JNIEXPORT void JNICALL JNI_FN(exitDeviceMenu)(JNIEnv *env, jobject self)
{
    (void)env; (void)self;
    skiff_exit_device_menu();
}

JNIEXPORT jboolean JNICALL JNI_FN(midiIn)(JNIEnv *env, jobject self, jint packet, jint source)
{ (void)env; (void)self; return skiff_midi_in((uint32_t)packet, (uint32_t)source) ? JNI_TRUE : JNI_FALSE; }

JNIEXPORT jintArray JNICALL JNI_FN(midiOut)(JNIEnv *env, jobject self)
{
    uint32_t packets[MQO];
    uint32_t count = skiff_midi_out(packets, MQO);
    jintArray result;
    (void)self;
    result = (*env)->NewIntArray(env, (jsize)count);
    if (result)
        (*env)->SetIntArrayRegion(env, result, 0, (jsize)count, (jint *)packets);
    return result;
}

JNIEXPORT jlongArray JNICALL JNI_FN(midiOutTimed)(JNIEnv *env, jobject self)
{
    uint64_t events[MQO];
    uint32_t count = skiff_midi_out_timed(events, MQO);
    jlongArray result;
    (void)self;
    if (!count) return NULL;
    result = (*env)->NewLongArray(env, (jsize)count);
    if (result)
        (*env)->SetLongArrayRegion(env, result, 0, (jsize)count, (jlong *)events);
    return result;
}
#endif
