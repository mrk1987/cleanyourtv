#define _GNU_SOURCE
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/random.h>
#include <unistd.h>

int main(void) {
    unsigned char first[256], second[256], oversized[257];
    if (getentropy(first, sizeof first) || getentropy(second, sizeof second)) {
        perror("getentropy");
        return 1;
    }
    if (!memcmp(first, second, sizeof first)) {
        fputs("Repeated random output\n", stderr);
        return 1;
    }
    // API bounds and flag handling; do not print any random bytes.
    errno = 0;
    if (getentropy(oversized, sizeof oversized) != -1 || errno != EIO)
        return 2;
    errno = 0;
    if (getrandom(first, 1, 0x80000000U) != -1 || errno != EINVAL)
        return 3;
    if (getentropy(first, 0) != 0)
        return 4;
    puts("Entropy API checks passed (bytes not displayed).");
    return 0;
}
