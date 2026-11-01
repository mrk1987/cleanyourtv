#!/usr/bin/env python3
"""Exercise the real interposer on a host with fake Android/PulseAudio exports."""
from pathlib import Path
import os
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
HEADER = r'''
#ifndef FAKE_AAUDIO_H
#define FAKE_AAUDIO_H
#include <stdint.h>
#include <time.h>
typedef struct AAudioStream AAudioStream;
typedef struct AAudioStreamBuilder AAudioStreamBuilder;
typedef int32_t aaudio_result_t;
#define AAUDIO_OK 0
#define AAUDIO_ERROR_UNAVAILABLE (-889)
aaudio_result_t AAudioStreamBuilder_openStream(AAudioStreamBuilder *, AAudioStream **);
aaudio_result_t AAudioStream_close(AAudioStream *);
int64_t AAudioStream_getFramesWritten(AAudioStream *);
int32_t AAudioStream_getSampleRate(AAudioStream *);
aaudio_result_t AAudioStream_getTimestamp(AAudioStream *, clockid_t, int64_t *, int64_t *);
#endif
'''
FAKE = r'''
#define _GNU_SOURCE
#include <aaudio/AAudio.h>
#include <stddef.h>
struct AAudioStream { int unused; };
static struct AAudioStream stream;
static int objects[8], next_object;
unsigned fake_flags;
int fake_error;
aaudio_result_t AAudioStreamBuilder_openStream(AAudioStreamBuilder *b, AAudioStream **s) {
    (void)b; *s = &stream; return 0;
}
aaudio_result_t AAudioStream_close(AAudioStream *s) { (void)s; return 0; }
int64_t AAudioStream_getFramesWritten(AAudioStream *s) { (void)s; return 4800; }
int32_t AAudioStream_getSampleRate(AAudioStream *s) { (void)s; return 48000; }
aaudio_result_t AAudioStream_getTimestamp(AAudioStream *s, clockid_t c, int64_t *p, int64_t *t) {
    (void)s; struct timespec now; clock_gettime(c, &now);
    *p = 0; *t = (int64_t)now.tv_sec * 1000000000LL + now.tv_nsec;
    return fake_error;
}
void *pa_sink_new(void *c, void *d, unsigned flags) {
    (void)c; (void)d; fake_flags = flags; return &objects[next_object++];
}
void pa_sink_put(void *s) { (void)s; }
void pa_sink_unlink(void *s) { (void)s; }
int pa_sink_process_msg(void *o, int c, void *d, int64_t n, void *b) {
    (void)o; (void)c; (void)d; (void)n; (void)b; return 77;
}
'''
DECLARATIONS = r'''
#define _GNU_SOURCE
#include <aaudio/AAudio.h>
void *pa_sink_new(void *, void *, unsigned);
void pa_sink_put(void *);
void pa_sink_unlink(void *);
int pa_sink_process_msg(void *, int, void *, int64_t, void *);
'''
MODULE = DECLARATIONS + r'''
void *target_create(void) {
    void *s = pa_sink_new(0, 0, 0);
    pa_sink_put(s);
    return s;
}
'''
DRIVER = DECLARATIONS + r'''
#include <assert.h>
#include <stdio.h>
#include <string.h>
extern unsigned fake_flags;
extern int fake_error;
void *target_create(void);
int main(int argc, char **argv) {
    assert(argc == 2);
    int feedback = strcmp(argv[1], "feedback") == 0;
    AAudioStream *stream;
    assert(AAudioStreamBuilder_openStream(0, &stream) == 0);
    void *target = target_create();
    assert(fake_flags == 2);  // capability is required for any latency query
    uint64_t delay = 123;
    assert(pa_sink_process_msg(target, 9, &delay, 0, 0) == 0);
    assert(feedback ? (delay >= 90000 && delay <= 100000) : delay == 0);
    assert(pa_sink_process_msg(target, 11, &delay, 0, 0) == 77);  // other messages pass through
    assert(pa_sink_process_msg(target, 9, 0, 0, 0) == 77);
    void *other = pa_sink_new(0, 0, 0);
    assert(fake_flags == 0);  // never grant capabilities to unrelated sinks
    pa_sink_put(other);
    delay = 123;
    assert(pa_sink_process_msg(other, 9, &delay, 0, 0) == 77 && delay == 123);
    fake_error = -895;
    assert(pa_sink_process_msg(target, 9, &delay, 0, 0) == 0);
    assert(delay == (feedback ? 50000 : 0));
    fake_error = 0;
    assert(AAudioStream_close(stream) == 0);
    assert(AAudioStreamBuilder_openStream(0, &stream) == 0);
    assert(pa_sink_process_msg(target, 9, &delay, 0, 0) == 0);
    assert(feedback ? (delay >= 90000 && delay <= 100000) : delay == 0);
    pa_sink_unlink(target);
    assert(pa_sink_process_msg(target, 9, &delay, 0, 0) == 77);
    assert(AAudioStream_close(stream) == 0);
    puts("AAudio bridge routing/lifecycle checks passed.");
}
'''


def run(*args, **kwargs):
    subprocess.run(args, check=True, **kwargs)


with tempfile.TemporaryDirectory(prefix="airplay-aaudio-check-") as temporary:
    root = Path(temporary)
    (root / "aaudio").mkdir()
    (root / "aaudio/AAudio.h").write_text(HEADER)
    for name, content in (("fake.c", FAKE), ("module.c", MODULE), ("driver.c", DRIVER)):
        (root / name).write_text(content)
    flags = ("-std=c11", "-Wall", "-Wextra", "-Werror", "-fPIC", "-I", str(root))
    run("cc", *flags, "-shared", str(root / "fake.c"), "-o", str(root / "libfake.so"))
    run("cc", *flags, "-shared", str(HERE / "aaudio_timing.c"), "-L", str(root),
        "-lfake", "-ldl", "-pthread", "-Wl,-rpath,$ORIGIN", "-o", str(root / "bridge.so"))
    run("cc", *flags, "-shared", str(root / "module.c"), "-L", str(root), "-lfake",
        "-Wl,-rpath,$ORIGIN", "-o", str(root / "module-aaudio-sink.so"))
    run("cc", *flags, str(root / "driver.c"), "-L", str(root), "-Wl,--no-as-needed",
        "-l:module-aaudio-sink.so", "-lfake", "-Wl,-rpath,$ORIGIN", "-o", str(root / "check"))
    for mode in ("observe", "feedback"):
        run(str(root / "check"), mode,
            env={**os.environ, "LD_PRELOAD": str(root / "bridge.so"),
                 "AIRPLAY_AAUDIO_TIMING_MODE": mode})
