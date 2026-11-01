// Presentation-time conversion shared by the Android bridge and host checks.
#ifndef AIRPLAY_AAUDIO_DELAY_H
#define AIRPLAY_AAUDIO_DELAY_H
#include <stdint.h>

static inline int airplay_aaudio_delay(int64_t written, int64_t presented,
                                      int64_t stamp_ns, int64_t now_ns,
                                      int32_t rate, uint64_t *delay_us) {
    if (!delay_us || rate < 8000 || rate > 384000 || written < 0 || presented < 0 ||
        written < presented || stamp_ns < 0 || now_ns < 0)
        return -1;
    // Reject stale timestamps, implausible queues and overflow before arithmetic.
    int64_t age = now_ns - stamp_ns;
    int64_t frames = written - presented;
    if (age < -1000000000LL || age > 1000000000LL || frames > (int64_t)rate * 5)
        return -1;
    int64_t remaining_ns = frames * 1000000000LL / rate - age;
    if (remaining_ns < -20000000LL || remaining_ns > 2000000000LL)
        return -1;
    *delay_us = remaining_ns > 0 ? (uint64_t)remaining_ns / 1000 : 0;
    return 0;
}
#endif
