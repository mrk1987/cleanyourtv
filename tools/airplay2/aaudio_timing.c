// Optional bridge for the dedicated, single-AAudio-sink Termux PulseAudio 17.
// No PulseAudio structure layouts are accessed. Non-AAudio messages pass through.
#define _GNU_SOURCE
#include <aaudio/AAudio.h>
#include <dlfcn.h>
#include <inttypes.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include "aaudio_delay.h"

// PulseAudio v17.0 src/pulsecore/sink.h: enum pa_sink_message, zero based.
// This is a private ABI: build/deploy only with the pinned 17.0 runtime.
enum { PULSE17_SINK_GET_LATENCY = 9 };
typedef int (*sink_message_fn)(void *, int, void *, int64_t, void *);
static sink_message_fn original_message;
static void (*original_put)(void *);
static void (*original_unlink)(void *);
static void *(*original_new)(void *, void *, unsigned);
static aaudio_result_t (*original_open)(AAudioStreamBuilder *, AAudioStream **);
static aaudio_result_t (*original_close)(AAudioStream *);
static pthread_mutex_t guard = PTHREAD_MUTEX_INITIALIZER;
static AAudioStream *active_stream;
static void *active_sink;
static unsigned open_count;
static bool feedback;
static uint64_t valid_count, invalid_count;
static int64_t last_log_ns;

__attribute__((constructor)) static void initialise(void) {
    original_message = (sink_message_fn)dlsym(RTLD_NEXT, "pa_sink_process_msg");
    original_put = (void (*)(void *))dlsym(RTLD_NEXT, "pa_sink_put");
    original_unlink = (void (*)(void *))dlsym(RTLD_NEXT, "pa_sink_unlink");
    original_new = (void *(*)(void *, void *, unsigned))dlsym(RTLD_NEXT, "pa_sink_new");
    original_open = (aaudio_result_t (*)(AAudioStreamBuilder *, AAudioStream **))
        dlsym(RTLD_NEXT, "AAudioStreamBuilder_openStream");
    original_close = (aaudio_result_t (*)(AAudioStream *))
        dlsym(RTLD_NEXT, "AAudioStream_close");
    if (!original_message || !original_put || !original_unlink || !original_new || !original_open || !original_close) {
        fputs("airplay-aaudio: required pinned-runtime symbols missing; refusing to start.\n", stderr);
        _exit(127);
    }
    const char *mode = getenv("AIRPLAY_AAUDIO_TIMING_MODE");
    feedback = mode && strcmp(mode, "feedback") == 0;
    fprintf(stderr, "airplay-aaudio: mode=%s, pulse_message=%s, open=%s, close=%s\n",
            feedback ? "feedback" : "observe", original_message ? "ok" : "missing",
            original_open ? "ok" : "missing", original_close ? "ok" : "missing");
}

void pa_sink_unlink(void *sink) {
    pthread_mutex_lock(&guard);
    if (active_sink == sink) active_sink = NULL;
    pthread_mutex_unlock(&guard);
    original_unlink(sink);
}

void *pa_sink_new(void *core, void *data, unsigned flags) {
    Dl_info caller = {0};
    if (dladdr(__builtin_return_address(0), &caller) && caller.dli_fname) {
        const char *base = strrchr(caller.dli_fname, '/');
        base = base ? base + 1 : caller.dli_fname;
        if (strcmp(base, "module-aaudio-sink.so") == 0) {
            // Public PA_SINK_LATENCY flag; the original module passes flags=0.
            // Without it PulseAudio never asks the module for playback latency.
            flags |= 0x0002U;
            fputs("airplay-aaudio: enabling AAudio sink latency queries\n", stderr);
        }
    }
    return original_new ? original_new(core, data, flags) : NULL;
}

void pa_sink_put(void *sink) {
    Dl_info caller = {0};
    if (dladdr(__builtin_return_address(0), &caller) && caller.dli_fname) {
        fprintf(stderr, "airplay-aaudio: registering sink from %s\n", caller.dli_fname);
        const char *base = strrchr(caller.dli_fname, '/');
        base = base ? base + 1 : caller.dli_fname;
        if (strcmp(base, "module-aaudio-sink.so") == 0) {
            pthread_mutex_lock(&guard);
            if (active_stream && open_count == 1) active_sink = sink;
            pthread_mutex_unlock(&guard);
        }
    }
    if (original_put) original_put(sink);
}

aaudio_result_t AAudioStreamBuilder_openStream(AAudioStreamBuilder *builder,
                                              AAudioStream **stream) {
    if (!original_open) return AAUDIO_ERROR_UNAVAILABLE;
    aaudio_result_t result = original_open(builder, stream);
    if (result == AAUDIO_OK && stream && *stream) {
        pthread_mutex_lock(&guard);
        ++open_count;
        active_stream = open_count == 1 ? *stream : NULL;
        fprintf(stderr, "airplay-aaudio: opened stream, active_count=%u, rate=%d\n",
                open_count, AAudioStream_getSampleRate(*stream));
        pthread_mutex_unlock(&guard);
    }
    return result;
}

aaudio_result_t AAudioStream_close(AAudioStream *stream) {
    if (!original_close) return AAUDIO_ERROR_UNAVAILABLE;
    pthread_mutex_lock(&guard);
    // Detach before closing so no concurrent latency query can use a freed stream.
    if (active_stream == stream) active_stream = NULL;
    if (open_count) --open_count;
    pthread_mutex_unlock(&guard);
    return original_close(stream);
}

int pa_sink_process_msg(void *object, int code, void *data, int64_t offset, void *chunk) {
    bool measured = false;
    bool eligible = false;
    uint64_t delay_us = 0;
    if (code == PULSE17_SINK_GET_LATENCY && data) {
        // Only the sink registered by the exact AAudio module is eligible.
        pthread_mutex_lock(&guard);
        eligible = active_sink == object;
        if (active_stream && open_count == 1 && active_sink == object) {
            int64_t position = 0, stamp = 0;
            int64_t written = AAudioStream_getFramesWritten(active_stream);
            int32_t rate = AAudioStream_getSampleRate(active_stream);
            aaudio_result_t status = AAudioStream_getTimestamp(
                active_stream, CLOCK_MONOTONIC, &position, &stamp);
            struct timespec now;
            int clock_status = clock_gettime(CLOCK_MONOTONIC, &now);
            int64_t now_ns = clock_status == 0 ?
                (int64_t)now.tv_sec * 1000000000LL + now.tv_nsec : 0;
            measured = status == AAUDIO_OK && clock_status == 0 &&
                airplay_aaudio_delay(written, position, stamp, now_ns, rate, &delay_us) == 0;
            if (measured) ++valid_count; else ++invalid_count;
            if (now_ns - last_log_ns >= 5000000000LL) {
                fprintf(stderr, "airplay-aaudio: timestamp_status=%d valid=%" PRIu64
                    " invalid=%" PRIu64 " delay_us=%" PRIu64 " written=%" PRId64
                    " presented=%" PRId64 " age_us=%" PRId64 " mode=%s\n",
                    status, valid_count, invalid_count, delay_us, written, position,
                    (now_ns - stamp) / 1000, feedback ? "feedback" : "observe");
                last_log_ns = now_ns;
            }
        }
        pthread_mutex_unlock(&guard);
    }
    if (eligible) {
        // Observe preserves the previous zero-latency behavior. During startup
        // or rejected timestamps, feedback uses an explicit 50 ms estimate.
        *(uint64_t *)data = feedback ? (measured ? delay_us : 50000) : 0;
        return 0;
    }
    // Messages for unrelated sinks retain their original behavior.
    return original_message ? original_message(object, code, data, offset, chunk) : -1;
}
