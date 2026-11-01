// Set the compatibility preload after entering the isolated Linux root, so it
// is never loaded by Android's Bionic-linked chroot/setsid executables.
#define _POSIX_C_SOURCE 200809L
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc < 2 || setenv("LD_PRELOAD", "/lib/libairplay-legacy.so", 1) != 0)
        return 2;
    execv(argv[1], argv + 1);
    perror("airplay-launch");
    return 1;
}
