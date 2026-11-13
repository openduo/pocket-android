// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

#include <dlfcn.h>
#include <android/log.h>
#include <errno.h>
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <time.h>

/* App-local adapter for the exact 04319 tinyalsa ABI. The firmware's single PCM
 * stream still supplies every byte to its array processor. Only the size of each
 * blocking read changes, so the UI can observe real input before that processor's
 * output queue. No second PCM device or Java microphone is opened. */
#define API __attribute__((visibility("default")))
#define PCM_IN 0x10000000u

struct pcm;
struct pcm_params;
struct mixer;
struct mixer_ctl;
struct pcm_config {
    unsigned channels, rate, period_size, period_count;
    int format;
    unsigned start_threshold, stop_threshold, silence_threshold, avail_min;
};

static pthread_once_t load_once = PTHREAD_ONCE_INIT;
static int loaded;
static struct pcm *(*real_open)(unsigned, unsigned, unsigned, struct pcm_config *);
static int (*real_close)(struct pcm *);
static int (*real_read)(struct pcm *, void *, unsigned);
static int (*real_read_es)(struct pcm *, void *, unsigned);
static int (*real_write)(struct pcm *, const void *, unsigned);
static int (*real_is_ready)(const struct pcm *);
static const char *(*real_get_error)(const struct pcm *);
static struct pcm_params *(*real_params_get)(unsigned, unsigned, unsigned);
static unsigned (*real_params_min)(const struct pcm_params *, int);
static unsigned (*real_params_max)(const struct pcm_params *, int);
static void (*real_params_free)(struct pcm_params *);
static struct mixer *(*real_mixer_open)(unsigned);
static void (*real_mixer_close)(struct mixer *);
static struct mixer_ctl *(*real_mixer_get_ctl)(struct mixer *, unsigned);
static int (*real_mixer_ctl_get_type)(const struct mixer_ctl *);
static unsigned (*real_mixer_ctl_get_num_values)(const struct mixer_ctl *);
static int (*real_mixer_ctl_set_value)(struct mixer_ctl *, unsigned, int);

static _Atomic(struct pcm *) capture;
static _Atomic(uint64_t) meter;
static unsigned capture_chunk;
static unsigned capture_frame;
static int capture_format;

static void load_driver(void) {
    /* This is the unchanged firmware copy except for its app-local SONAME and
     * unused Android platform dependencies, checked by prepare-e2-audio.sh. */
    void *driver = dlopen("libe2alsa.so", RTLD_NOW | RTLD_LOCAL);
    if (!driver) return;
#define RESOLVE(field, name) *(void **) (&field) = dlsym(driver, name); if (!field) return
    RESOLVE(real_open, "pcm_open");
    RESOLVE(real_close, "pcm_close");
    RESOLVE(real_read, "pcm_read");
    RESOLVE(real_read_es, "pcm_read_es");
    RESOLVE(real_write, "pcm_write");
    RESOLVE(real_is_ready, "pcm_is_ready");
    RESOLVE(real_get_error, "pcm_get_error");
    RESOLVE(real_params_get, "pcm_params_get");
    RESOLVE(real_params_min, "pcm_params_get_min");
    RESOLVE(real_params_max, "pcm_params_get_max");
    RESOLVE(real_params_free, "pcm_params_free");
    RESOLVE(real_mixer_open, "mixer_open");
    RESOLVE(real_mixer_close, "mixer_close");
    RESOLVE(real_mixer_get_ctl, "mixer_get_ctl");
    RESOLVE(real_mixer_ctl_get_type, "mixer_ctl_get_type");
    RESOLVE(real_mixer_ctl_get_num_values, "mixer_ctl_get_num_values");
    RESOLVE(real_mixer_ctl_set_value, "mixer_ctl_set_value");
#undef RESOLVE
    loaded = 1;
}

static int driver_ready(void) {
    pthread_once(&load_once, load_driver);
    if (!loaded) errno = ENODEV;
    return loaded;
}

API struct pcm *pcm_open(unsigned card, unsigned device, unsigned flags, struct pcm_config *config) {
    if (!driver_ready()) return NULL;
    struct pcm *pcm = real_open(card, device, flags, config);
    if (pcm && config && (flags & PCM_IN) && real_is_ready(pcm)
            && config->rate && config->channels && (config->format == 0 || config->format == 1)) {
        capture_frame = config->channels * (config->format == 1 ? 4 : 2);
        capture_chunk = config->rate / 50 * capture_frame;
        capture_format = config->format;
        __android_log_print(ANDROID_LOG_INFO, "E2CaptureMeter", "PCM capture: rate=%u channels=%u format=%d period=%u/%u read=%u bytes",
                config->rate, config->channels, config->format, config->period_size, config->period_count, capture_chunk);
        atomic_store_explicit(&meter, 0, memory_order_release);
        atomic_store_explicit(&capture, pcm, memory_order_release);
    }
    return pcm;
}

API int pcm_close(struct pcm *pcm) {
    if (!driver_ready()) return -1;
    if (atomic_load_explicit(&capture, memory_order_acquire) == pcm) {
        atomic_store_explicit(&capture, NULL, memory_order_release);
        atomic_store_explicit(&meter, 0, memory_order_release);
    }
    return real_close(pcm);
}

static void publish_level(const void *buffer, unsigned bytes) {
    unsigned count = bytes / (capture_format == 1 ? 4 : 2);
    if (!count) return;
    int64_t sum = 0;
    uint64_t squares = 0;
    for (unsigned i = 0; i < count; i++) {
        /* E2's S32_LE input includes synchronization tags in its low byte.
         * The signed high 16 bits carry the audio, without those tags. */
        int sample = capture_format == 1 ? ((const int32_t *) buffer)[i] >> 16 : ((const int16_t *) buffer)[i];
        sum += sample;
        squares += (int64_t) sample * sample;
    }
    double mean = (double) sum / count;
    double variance = (double) squares / count - mean * mean;
    unsigned rms = variance > 0 ? (unsigned) sqrt(variance) : 0;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    uint64_t millis = (uint64_t) now.tv_sec * 1000 + now.tv_nsec / 1000000;
    atomic_store_explicit(&meter, millis << 16 | (rms > 32767 ? 32767 : rms), memory_order_release);
}

static int read_metered(struct pcm *pcm, void *buffer, unsigned bytes, int es) {
    if (!driver_ready()) return -1;
    int (*read_pcm)(struct pcm *, void *, unsigned) = es ? real_read_es : real_read;
    if (atomic_load_explicit(&capture, memory_order_acquire) != pcm
            || !capture_chunk || !capture_frame || bytes % capture_frame) return read_pcm(pcm, buffer, bytes);
    unsigned offset = 0;
    while (offset < bytes) {
        unsigned count = bytes - offset < capture_chunk ? bytes - offset : capture_chunk;
        int result = read_pcm(pcm, (char *) buffer + offset, count);
        if (result) return result;
        publish_level((char *) buffer + offset, count);
        offset += count;
    }
    return 0;
}

API int pcm_read(struct pcm *pcm, void *buffer, unsigned bytes) { return read_metered(pcm, buffer, bytes, 0); }
API int pcm_read_es(struct pcm *pcm, void *buffer, unsigned bytes) { return read_metered(pcm, buffer, bytes, 1); }
/* Packed monotonic milliseconds + unsigned PCM16 RMS, copied atomically across
 * the capture and UI threads. Zero means that there is no active capture. */
API uint64_t e2_capture_meter(void) { return atomic_load_explicit(&meter, memory_order_acquire); }
API int pcm_write(struct pcm *p, const void *b, unsigned n) { return driver_ready() ? real_write(p, b, n) : -1; }
API int pcm_is_ready(const struct pcm *p) { return driver_ready() ? real_is_ready(p) : 0; }
API const char *pcm_get_error(const struct pcm *p) { return driver_ready() ? real_get_error(p) : "E2 audio driver unavailable"; }
API struct pcm_params *pcm_params_get(unsigned c, unsigned d, unsigned f) { return driver_ready() ? real_params_get(c, d, f) : NULL; }
API unsigned pcm_params_get_min(const struct pcm_params *p, int n) { return driver_ready() ? real_params_min(p, n) : 0; }
API unsigned pcm_params_get_max(const struct pcm_params *p, int n) { return driver_ready() ? real_params_max(p, n) : 0; }
API void pcm_params_free(struct pcm_params *p) { if (driver_ready()) real_params_free(p); }
API struct mixer *mixer_open(unsigned c) { return driver_ready() ? real_mixer_open(c) : NULL; }
API void mixer_close(struct mixer *m) { if (driver_ready()) real_mixer_close(m); }
API struct mixer_ctl *mixer_get_ctl(struct mixer *m, unsigned n) { return driver_ready() ? real_mixer_get_ctl(m, n) : NULL; }
API int mixer_ctl_get_type(const struct mixer_ctl *c) { return driver_ready() ? real_mixer_ctl_get_type(c) : 0; }
API unsigned mixer_ctl_get_num_values(const struct mixer_ctl *c) { return driver_ready() ? real_mixer_ctl_get_num_values(c) : 0; }
API int mixer_ctl_set_value(struct mixer_ctl *c, unsigned n, int v) { return driver_ready() ? real_mixer_ctl_set_value(c, n, v) : -1; }
