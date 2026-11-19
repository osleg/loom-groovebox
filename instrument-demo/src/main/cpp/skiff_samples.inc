/* Android sample ingestion into Skiff's existing FSMP user slots. */
#define SKIFF_CAPTURE_FRAMES (3u * FS)
static int16_t skiff_capture[SKIFF_CAPTURE_FRAMES];
static uint32_t skiff_capture_count;
static int skiff_capturing;

static int skiff_start_sample_capture(void)
{
    if (!runtime_booted || runtime_stopped || diagnostic_on || skiff_capturing) return 0;
    skiff_capture_count = 0;
    skiff_capturing = 1;
    return 1;
}

static void skiff_cancel_sample_capture(void)
{
    skiff_capturing = 0;
    skiff_capture_count = 0;
}

static void skiff_capture_audio(const int16_t *stereo, uint32_t frames)
{
    if (frames > SKIFF_CAPTURE_FRAMES - skiff_capture_count)
        frames = SKIFF_CAPTURE_FRAMES - skiff_capture_count;
    for (uint32_t i = 0; i < frames; i++)
        skiff_capture[skiff_capture_count++] = (int16_t)(((int32_t)stereo[2u * i] + stereo[2u * i + 1u]) / 2);
    if (skiff_capture_count == SKIFF_CAPTURE_FRAMES) skiff_capturing = 0;
}

static uint32_t skiff_encoded_sample_size(uint32_t frames)
{
    return frames && frames <= SKIFF_CAPTURE_FRAMES ? SMP_USER_DATA + (frames + 1u) / 2u : 0u;
}

/* This runs before taking the audio lock. The empty ADPCM state at frame zero
 * is the same state used by SAMPLE, SLICE and GRAIN. */
static int skiff_encode_sample(uint8_t *out, uint32_t size, const int16_t *pcm,
                               uint32_t frames, const char *name, uint32_t root)
{
    uint32_t expected = skiff_encoded_sample_size(frames);
    int32_t predictor = 0, index = 0;
    smp_user_hdr_t h = {0};
    if (!out || !pcm || !name || root > 127u || !expected || size != expected) return 0;
    memset(out, 0, size);
    for (uint32_t i = 0; i < frames; i++) {
        int32_t delta = pcm[i] - predictor, step = IMA_STEP[index], decoded = step >> 3;
        uint32_t code = delta < 0 ? 8u : 0u;
        if (delta < 0) delta = -delta;
        for (uint32_t bit = 4u; bit; bit >>= 1, step >>= 1) {
            if (delta >= step) {
                code |= bit;
                delta -= step;
                decoded += step;
            }
        }
        predictor = clamp(predictor + ((code & 8u) ? -decoded : decoded), -32768, 32767);
        index = clamp(index + IMA_IDX[code & 7u], 0, 88);
        out[SMP_USER_DATA + i / 2u] |= (uint8_t)(code << ((i & 1u) * 4u));
    }
    h.magic = SMP_USER_MAGIC;
    h.version = 1;
    h.nz = 1;
    for (uint32_t i = 0; i < sizeof h.name && name[i]; i++) {
        unsigned char c = (unsigned char)name[i];
        if (c >= 'a' && c <= 'z') c -= 'a' - 'A';
        h.name[i] = c >= 32u && c <= 126u ? (char)c : ' ';
    }
    if (!h.name[0]) memcpy(h.name, "SAMPLE", 6);
    h.data_len = size - SMP_USER_DATA;
    h.crc = st_crc32(out + SMP_USER_DATA, h.data_len);
    h.zone[0] = (smp_zone_t){.n = frames, .le = frames - 1u, .rate = 65536u,
                            .root16 = (int16_t)(root * 16u), .hi = 127, .looped = 1};
    memcpy(out, &h, sizeof h);
    return 1;
}

static int skiff_sample_blob_valid(const uint8_t *blob, uint32_t size)
{
    smp_user_hdr_t h;
    if (!blob || size < SMP_USER_DATA || size > SMP_USER_SIZE) return 0;
    memcpy(&h, blob, sizeof h);
    if (h.magic != SMP_USER_MAGIC || h.version != 1 || !h.nz || h.nz > NELEM(h.zone) ||
        !h.data_len || h.data_len > size - SMP_USER_DATA ||
        h.crc != st_crc32(blob + SMP_USER_DATA, h.data_len)) return 0;
    for (uint32_t i = 0; i < h.nz; i++) {
        const smp_zone_t *z = &h.zone[i];
        if (!z->n || z->n > 2u * SMP_USER_SIZE || z->off > h.data_len ||
            (z->n + 1u) / 2u > h.data_len - z->off || !z->rate || z->rate > 4u << 16 ||
            z->idx > 88u || z->ls > z->le || z->le >= z->n || z->lo > z->hi || z->hi > 127u)
            return 0;
    }
    return 1;
}

static int skiff_store_sample(uint32_t slot, const uint8_t *encoded, uint32_t size)
{
    if (slot >= SMP_USER_SLOTS || !skiff_sample_blob_valid(encoded, size)) return 1;
    if (!runtime_booted || runtime_stopped || diagnostic_on || !flash_ok) return 3;
    if (transport_busy()) return 2;
    /* Invalidate readers at the block boundary before changing the bytes. A
     * voice that was holding the previous asset must be retriggered. */
    for (uint32_t k = 0; k < NTRK; k++) {
        track_t *part = &trk[k];
        uint32_t engine = part->engine;
        if (engine != ENGI_SAMPLE && engine != ENGI_GRAIN && engine != ENGI_SLICE && engine != ENGI_DRUM
#if defined(ENGI_LOFI_SMP)
            && engine != ENGI_LOFI_SMP && engine != ENGI_CLOUD
#endif
        ) continue;
        for (uint32_t i = 0; i < NVOICE; i++) {
            voice_t *v = &part->v[i];
            v->active = v->gate = v->stage = 0;
            v->env = v->env_out = 0;
        }
    }
    uint8_t *target = nor + SMP_USER_BASE + slot * SMP_USER_SIZE;
    memcpy(target, encoded, size);
    memset(target + size, 0xff, SMP_USER_SIZE - size);
    smp_user_scan(slot);
    web_flash_writes++;
    return 0;
}
