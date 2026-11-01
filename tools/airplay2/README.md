# Experimental AirPlay audio receivers

Project home: `tv-vibehack/repo/tools/airplay2`. Private build output, downloaded
packages, recordings and reports live in `tv-vibehack/scratchpad/airplay2`, outside
this Git checkout. This component is an independent owner-operated experiment;
it does not change the launcher or the normal CleanYourTV workflow.

This prototype uses [Shairport Sync](https://github.com/mikebrady/shairport-sync)
and [NQPTP](https://github.com/mikebrady/nqptp) for the rooted Philips Android TV
and a dedicated Windows WSL distro. See [STATUS.md](STATUS.md) for verified
results and remaining blockers. Audible TV playback works; synchronization is
still being calibrated. The PC receiver has not run because firmware CPU
virtualization is disabled.

## Philips TV

`build-philips.sh` builds the pinned GNU/Linux ARM programs on an isolated Debian
13 build host with root access. It installs cross-compilation dependencies and
adds the armhf package architecture. Use a dedicated build container. It produces
an uncompressed `receiver-arm-runtime.tar`; generated files belong outside Git.
The build script and a complete fresh deployment still need end-to-end testing.

The Android-native audio runtime is separate: official Termux ARM PulseAudio
17.0-5 and its package dependency closure, extracted from `.deb` packages without
installing them into Android or Debian. Verify the Termux signed `InRelease`,
the package-index digest and each downloaded package digest before extraction.
The tested runtime uses `module-aaudio-sink`; OpenSL ES stalled on this TV.
Do not substitute GNU/Linux PulseAudio for this Android-native process.

The temporary device layout is:

| Path | Contents |
|---|---|
| `/data/local/tmp/airplay2-root` | Extracted GNU runtime |
| `/data/local/tmp/airplay-pulse` | Extracted Android-native PulseAudio runtime |
| `/data/local/tmp/airplay2-control.sh` | `philips-control.sh`, LF line endings |
| `/data/local/tmp/airplay2-wake-watch.sh` | `philips-wake-watch.sh`, LF line endings |
| `airplay2-root/etc/airplay/shairport-sync.conf` | Private copy of `philips-shairport.conf` |
| `airplay2-root/etc/airplay/dbus.conf` | `dbus.conf` |
| `airplay2-root/etc/avahi/avahi-daemon.conf` | `avahi-daemon.conf` |

Stage uploads outside the root-only runtime, then copy them through `su -c`.
Use `tar -xf` on the TV: its compressed-tar path requires an unavailable external
gunzip. Never extract a new runtime over an active receiver. The control script
requires Magisk BusyBox and root; it creates only private runtime mounts and
processes, with no boot hooks or changes to Android media services.

Run on the TV through the project's authenticated ADB helper:

```sh
su -c 'sh /data/local/tmp/airplay2-control.sh start'
su -c 'sh /data/local/tmp/airplay2-control.sh status'
su -c 'sh /data/local/tmp/airplay2-control.sh start-watcher'
su -c 'sh /data/local/tmp/airplay2-control.sh restart-receiver'
su -c 'sh /data/local/tmp/airplay2-control.sh stop'
```

The test advertises `Philips AirPlay 2 (test)` on RTSP port 7100. Check the private
`run/receiver.log`, `run/nqptp.log` and `run/pulse.log` when troubleshooting.
Logs can contain device names and network details; keep them out of commits.
The PulseAudio cookie is generated on the TV; never copy its contents into Git.

### Standby recovery

On this firmware, standby can leave an AAudio stream reporting success while its
frame counter and presentation timestamp remain frozen after wake. Recreating
only the sink with PulseAudio suspend/resume restored advancing timestamps and
Shairport playback without restarting the receiver. The optional wake watcher
automates this after a changed Android wake counter or observed sleep/wake
transition, requiring two awake snapshots before acting. It polls every five
seconds and makes at most three bounded attempts. It neither wakes the TV nor
keeps it awake, and never touches stock services. Install it beside the control
script; normal `start` launches it and `stop` stops it before stopping audio.
`start-watcher` adds it to an already-running runtime. Check `run/watcher.log`.
The real shell loop passes `test_wake_watch.py` using mocked power and PulseAudio
commands, including missed-sleep detection, debounce and bounded retries. A full
physical standby/wake cycle remains an owner acceptance check.

For alignment, compare the TV and a known synchronized JBL/HomePod using the
same source and playback mode. Measure audio-to-audio alignment separately from
video lip sync. A negative `general.audio_backend_latency_offset_in_seconds`
advances delivery; change it in the private device config, restart only the
receiver, reconnect and measure again. The checked-in template defaults to zero.
A static offset cannot correct changing latency or unreliable clock feedback.
Test both buffered music and realtime video, then check long-running drift.

### AAudio timestamp feedback

The optional `aaudio_timing.c` bridge adds presentation-time feedback to the
tested, single-sink Termux PulseAudio 17.0 runtime. It enables the missing
`PA_SINK_LATENCY` capability and answers latency queries using the AAudio frames
written, presentation position/time and current monotonic time. No private
PulseAudio structures are accessed. The private sink-message number is pinned
to PulseAudio 17; do not load this bridge into other versions or arbitrary
multi-sink setups.

On a Linux build host with the Android NDK installed:

```sh
ANDROID_NDK_ROOT=/path/to/android-ndk-r27d sh build-aaudio-timing.sh /tmp/aaudio-build
```

The build runs the delay arithmetic checks and a host integration harness before
producing an ARMv7 Android API 26 shared library. The harness checks the latency
capability, routing, observation/feedback modes, invalid timestamp fallback,
stream reopen and sink removal. NDK r27d was downloaded from Google's SDK
repository and checked against its published archive digest for the live build.

With the temporary runtime stopped, install `libairplay-aaudio-timing.so` under
`airplay-pulse/lib/`. The controller loads it only into Android-native PulseAudio;
the GNU compatibility preload stays separate. The private file
`airplay2-root/etc/airplay/aaudio-timing-mode` selects `observe`, `feedback` or
`disabled`, defaulting to `observe`. Change modes with a complete temporary
runtime stop/start. In observation mode, queries preserve the original zero
latency while logging measurements. In feedback mode, valid measurements are
returned to PulseAudio and Shairport. Reset the manual Shairport offset to zero
when testing feedback to avoid counting a compensation twice; keep a private
backup of any previous calibration.

Startup, stale or implausible timestamps use an explicit 50 ms estimate in
feedback mode, not a claimed measurement. Logs distinguish valid/rejected
timestamps. Diagnostics contain counters and times, not audio samples. Successful
timestamp calls do not establish that a vendor driver accounts for every DSP or
acoustic delay; audible multiroom alignment remains a separate acceptance check.
To roll back, stop the runtime, set the mode to `disabled`, restore the private
Shairport calibration if appropriate, and start again.

Pass the pinned NQPTP checkout directory as a second build-script argument to
also build `airplay-clock-status`. This read-only diagnostic takes the path to
the receiver's NQPTP shared-memory file and samples its version, coherent-copy
status, master availability, sample age and changes in the mapped clock offset.
It prints no clock IDs, peer addresses or absolute offsets. Run it during active
playback; no master while disconnected is not by itself a fault. NQPTP and
Shairport use `CLOCK_MONOTONIC_RAW` on this Linux target. Setting the wall clock
does not replace that mapping. The AAudio bridge converts a timestamp and a
current reading within `CLOCK_MONOTONIC` into a duration, so it does not subtract
absolute times from different clock domains.

### Acoustic comparison recording

`make_sync_test.py` creates a 90-second stereo WAV and a source timing manifest
using NumPy. Every short band-limited burst has a distinct waveform and position,
avoiding the whole-beat ambiguity of a metronome or repeating drum pattern.
Generate into ignored build storage on the PC, then transfer the WAV to the
phone. Do not host it on the TV. Play it through both receivers together and
make one continuous recording: 8-10 seconds near the TV, walk, then 8-10 seconds
near the reference speaker. Preserve the walk and do not pause or seek playback.
The known source allows each stationary section to be matched to its source
position even when the two recorded sections contain different sounds. Distance,
room reflections, recording-clock drift and processing still affect uncertainty;
do not present a correlation peak as an exact acoustic calibration by itself.

`measure_sync_recording.py SOURCE.wav RECORDING.m4a --first START:END --second
START:END --output IGNORED_DIRECTORY --plot` matches windows against the full
known source and compares the median source-time offsets of the two stationary
sections. It requires PyAV, NumPy and SciPy; plotting additionally needs
Matplotlib. Positive delay means the first speaker is late. Weak or ambiguous
matches and inconsistent stationary sections are rejected. The sign/refusal
checks run with `test_sync_measurement.py`. Leave the microphone in similar
proximity to each speaker; unequal acoustic distances add approximately 3 ms per
metre. Verify a correction with a new recording and with the intended playback
mode rather than assuming buffered and realtime AirPlay behave identically.

## Windows PC

From PowerShell:

```powershell
./tools/airplay2/setup-windows.ps1 -CheckOnly
./tools/airplay2/setup-windows.ps1
```

Setup verifies the pinned official Ubuntu WSL image, imports a dedicated
`JBL-AirPlay` distro, builds the pinned receiver and installs its template config.
It requires CPU virtualization, working WSL2/Virtual Machine Platform, existing
mirrored networking and WSLg audio. It does not reboot the PC or change the
global WSL/firewall configuration. Firmware settings must be changed by the
owner before this machine can run the receiver.
The default cache follows the outer TV project's `scratchpad/airplay2` layout;
pass `-Cache` when using a standalone clone elsewhere.

After setup succeeds, run `start-windows-receiver.sh` as root inside that distro;
use `stop-windows-receiver.sh` to stop only its receiver processes. Resolve the
scripts' Windows checkout paths with `wslpath`. Playback uses
`unix:/mnt/wslg/PulseServer`. Discovery, sound, latency and drift all remain to
be tested on Windows.

## Source boundaries

No JBL executable, firmware library, MFi identity, private key or certificate is
part of this port. The old-kernel entropy shim uses kernel random syscalls when
available and OS random devices only when the syscall returns ENOSYS. Its API
checks cover fallback behavior, limits, invalid flags and distinct random output
without printing random bytes. The GNU preload is set after entering the chroot,
so it cannot accidentally preload into Android's Bionic launcher.
