// Linux pre-3.17 compatibility for modern libraries on the Android 8 TV kernel.
// Use the kernel getrandom syscall where available. ENOSYS alone falls back to
// the operating system's random devices; never substitute predictable bytes.
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <sys/random.h>
#include <sys/syscall.h>
#include <unistd.h>

ssize_t getrandom(void *buffer, size_t length, unsigned int flags) {
    ssize_t result;
#ifndef AIRPLAY_TEST_OLD_KERNEL
    result = syscall(SYS_getrandom, buffer, length, flags);
    if (result >= 0 || errno != ENOSYS)
        return result;
#endif
    if (flags & ~(GRND_NONBLOCK | GRND_RANDOM)) {
        errno = EINVAL;
        return -1;
    }
    if (!length)
        return 0;
    const char *path = flags & GRND_RANDOM ? "/dev/random" : "/dev/urandom";
    int fd = open(path, O_RDONLY | O_CLOEXEC | (flags & GRND_NONBLOCK ? O_NONBLOCK : 0));
    if (fd < 0)
        return -1;
    do {
        result = read(fd, buffer, length);
    } while (result < 0 && errno == EINTR);
    int saved_errno = errno;
    close(fd);
    errno = saved_errno;
    return result;
}

int getentropy(void *buffer, size_t length) {
    if (length > 256) {
        errno = EIO;
        return -1;
    }
    unsigned char *position = buffer;
    while (length) {
        ssize_t count = getrandom(position, length, 0);
        if (count < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        if (!count) {
            errno = EIO;
            return -1;
        }
        position += count;
        length -= count;
    }
    return 0;
}
