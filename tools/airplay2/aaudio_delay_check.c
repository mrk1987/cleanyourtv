#include <assert.h>
#include <stdio.h>
#include "aaudio_delay.h"

int main(void) {
    uint64_t us = 123;
    // 4800 queued frames at 48 kHz, timestamp already 20 ms old: 80 ms remain.
    assert(airplay_aaudio_delay(10000, 5200, 1000000000, 1020000000, 48000, &us) == 0);
    assert(us == 80000);
    assert(airplay_aaudio_delay(4410, 0, 1000000000, 1000000000, 44100, &us) == 0);
    assert(us == 100000);
    assert(airplay_aaudio_delay(0, 0, 1000000000, 1005000000, 48000, &us) == 0 && us == 0);
    assert(airplay_aaudio_delay(0, 1, 0, 0, 48000, &us) < 0);
    assert(airplay_aaudio_delay(-1, 0, 0, 0, 48000, &us) < 0);
    assert(airplay_aaudio_delay(INT64_MAX, 0, 0, 0, 48000, &us) < 0);
    assert(airplay_aaudio_delay(0, 0, 0, INT64_MAX, 48000, &us) < 0);
    assert(airplay_aaudio_delay(0, 0, 0, 0, 0, &us) < 0);
    assert(airplay_aaudio_delay(0, 0, 0, 30000000, 48000, &us) < 0);
    assert(airplay_aaudio_delay(48000 * 3, 0, 0, 0, 48000, &us) < 0);
    assert(airplay_aaudio_delay(0, 0, 0, 0, 48000, NULL) < 0);
    puts("AAudio presentation delay checks passed.");
    return 0;
}
