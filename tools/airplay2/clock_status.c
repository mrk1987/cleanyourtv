// Read-only NQPTP clock health; no clock IDs, peer addresses or absolute offsets printed.
#define _GNU_SOURCE
#include <fcntl.h>
#include <inttypes.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include "nqptp-shm-structures.h"
int main(int argc, char **argv) {
    if (argc != 2) return 2;
    int fd = open(argv[1], O_RDONLY);
    if (fd < 0) { perror("clock state"); return 1; }
    struct stat info;
    if (fstat(fd, &info) < 0 || info.st_size < (off_t)sizeof(struct shm_structure)) {
        close(fd); return 1;
    }
    const struct shm_structure *mapped = mmap(NULL, sizeof(*mapped), PROT_READ, MAP_SHARED, fd, 0);
    close(fd);
    if (mapped == MAP_FAILED) return 1;
    if (mapped->version != NQPTP_SHM_STRUCTURES_VERSION) {
        fputs("NQPTP shared-memory version mismatch.\n", stderr);
        munmap((void *)mapped, sizeof(*mapped)); return 1;
    }
    uint64_t previous_offset = 0;
    for (int i = 0; i < 8; ++i) {
        struct shm_structure snapshot;
        memcpy(&snapshot, mapped, sizeof(snapshot));
        __sync_synchronize();
        shm_structure_set secondary;
        memcpy(&secondary, &mapped->secondary, sizeof(secondary));
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC_RAW, &now);
        uint64_t now_ns = (uint64_t)now.tv_sec * 1000000000ULL + now.tv_nsec;
        int stable = memcmp(&snapshot.main, &secondary, sizeof(secondary)) == 0;
        int present = stable && snapshot.main.master_clock_id != 0;
        printf("version=%u stable=%d master=%d sample_age_ms=%.3f offset_change_ms=%.3f\n",
               snapshot.version, stable, present,
               present ? (int64_t)(now_ns - snapshot.main.local_time) / 1e6 : -1.0,
               present && previous_offset ? (int64_t)(snapshot.main.local_to_master_time_offset - previous_offset) / 1e6 : 0.0);
        if (stable && present) previous_offset = snapshot.main.local_to_master_time_offset;
        fflush(stdout);
        sleep(1);
    }
    munmap((void *)mapped, sizeof(*mapped));
    return 0;
}
