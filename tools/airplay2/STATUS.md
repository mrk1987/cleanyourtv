# AirPlay 2 audio receiver port

## Current handoff

The owner accepts the current synchronization; do not keep tuning it. Source is
now under the TV project at `repo/tools/airplay2`, with private artifacts under
the outer project's `scratchpad/airplay2`. The JBL checkout retains only a move
notice. The shared Windows companion is retained here but remains blocked by
firmware virtualization being disabled on the PC.

Standby diagnosis: after a real sleep/wake, all receiver processes were alive,
but AAudio's frame count stayed frozen and its successful timestamp was over
nine minutes old. The timestamp dated back to around the last standby event.
Manually suspending and resuming only `android_airplay` recreated the native
stream; timestamps advanced again and Shairport playback statistics resumed.
The wake watcher is now deployed/running. Its seven shell-loop tests pass; a new
physical standby/wake cycle has not yet been confirmed with the watcher active.

Update 2026-11-10. The receiver had been silent since 2026-11-05 23:44: PulseAudio
deadlocked while the watcher recreated the sink (main, `aaudio-sink` and `AudioTrack`
threads all in a futex wait). The process stayed alive, its socket refused clients,
and every later wake logged "recovery failed". Twelve earlier recreations had worked.
The deadlock itself is not fixed; it is in the sink or the timing bridge. Changes:

- The watcher now starts `airplay2-control.sh recover` (new: keep the log tails,
  stop, start) when three attempts fail. Tested live with PulseAudio frozen by
  SIGSTOP: the runtime was back about a minute after wake.
- `pactl` exits 0 on SIGTERM/SIGINT, so a timed-out call used to count as success.
  The timeouts now send SIGKILL.
- `status` probes PulseAudio and reports "running but not answering (hung)".
- Watcher log lines carry a timestamp.
- A normal standby/wake cycle with the watcher active is confirmed
  (KEYCODE_SLEEP / KEYCODE_WAKEUP over ADB, stream recreated on the first attempt).
- Boot start, at the owner's request: `philips-boot-start.sh`, installed as
  `/data/adb/service.d/90-airplay2.sh`. Verified across one reboot (runtime up 75 s
  after boot). This replaces the earlier "no boot hooks" rule; delete that file to
  go back to manual starts.
- Not changed: avahi runs with `--debug` and logs a hostname conflict with Android's
  own responder (`Android.local`) continuously, about 8 MB in nine days. Calibration
  files were not touched.

Goal: use Shairport Sync for audio reception on the rooted Philips Android TV and
the Windows PC. Use the rooted JBL as a working behavior reference. The owner's
iPhone can select the HomePod and JBL together; adding each new receiver to that
group and checking audible alignment/drift is the acceptance test.

## Source baseline

- Shairport Sync: `0a34fbda83724cc06266aadcf7c88acc8fef4e7d`, reports version 5.6.2.
- NQPTP: `ab8fb67483411640fd7f1e193889482f2421c37c`, version 1.2.9 / shared-memory interface smi11.
- [Upstream build guide](https://github.com/mikebrady/shairport-sync/blob/master/BUILD.md).
- [PulseAudio output backend](https://github.com/mikebrady/shairport-sync/blob/master/audio_pa.c)
  provides `pa_stream_get_latency` feedback. A stdout PCM pipe is not the final
  synchronized-output solution.
- Android PulseAudio: Termux 17.0-5 ARM runtime. The repository Release signature,
  package index digest and every package SHA-256 were checked. Generated binaries,
  package downloads and user/device configuration remain in the private outer
  project's `scratchpad/airplay2` directory.

## Verified so far

- TV checked live: Android 8.0, ARMv7 32-bit, kernel 3.10.79. The existing
  `io.github.jqssun.airplay` 0.0.36 app remains installed.
- Native Linux build of Shairport Sync reports AirPlay2, OpenSSL, Avahi,
  PulseAudio, dummy and stdout support. NQPTP builds and reports smi11.
- Both programs cross-compile as ARM ELF executables targeting Linux 3.2+.
- Android-native Termux PulseAudio runs and prints its version on the TV.
- `package_arm_runtime.py` assembles the Linux ARM dependency closure, rejecting
  non-ARM libraries. Daemon Debian packages are extracted rather than installed
  as ARM services on the x86 build host.

- The phone connects and the owner confirms audible output through AAudio.
- Linux kernel 3.10 lacks the entropy syscall expected by modern crypto libraries.
  The initial connection failed with `getentropy is not supported`. The launcher
  now preloads an ENOSYS-only compatibility shim inside the GNU runtime. It uses
  the OS entropy devices; no generated identity or secret is bundled.
- The OpenSL ES sink accepted data but stalled, including silent test playback.
  Moving the live Shairport stream to `module-aaudio-sink` restored audible sound.
- The owner reported late audio. A private offset test was used; it is not a
  calibrated template default. A subsequent slow-motion video measurement
  reports TV audio 700 ms late relative to the phone/tablet picture. That is a
  lip-sync measurement, not a TV-versus-JBL audio measurement. The later coded
  recording below supplied the relative acoustic measurement; long-term drift
  remains unverified.
- The updated ARM packager passes host entropy API checks with warnings treated
  as errors and assembles six ARM programs plus 117 libraries. Shell syntax,
  Python compilation and the Windows setup parser/preflight checks pass. The
  complete fresh-build scripts have not yet been exercised end to end.

## JBL evidence and limits

Read-only inspection found a separate ARM receiver, `airplaydemo`, and its
`libairplayipc.so` bridge to the soundbar software. Native function names include
PCM writes, PCM delay reads, ALSA hardware timestamps and AirPlay audio-stream
callbacks. Those observations support prioritizing output timing feedback.

The executable also contains MFi signature/certificate function names, I2C
references and authentication-chip messages. This suggests a hardware dependency
for a direct transplant, but does not establish which live authentication path
the JBL uses. No credentials or certificate contents were extracted. Shairport
Sync is the implementation base; JBL identity material and proprietary code are
not included in the port.

## Target runtime design

TV: isolated Linux ARM runtime at `/data/local/tmp/airplay2-root`, with an
Android-native PulseAudio process under `/data/local/tmp/airplay-pulse` delivering
audio through Android's audio stack. The PulseAudio control socket is local Unix
IPC. Avoid raw ALSA takeover, stopping Android media services, boot hooks and
SELinux changes. Use a distinct test receiver name and RTSP port 7100 so the
existing mirroring app can remain installed.

The original AAudio sink does not advertise `PA_SINK_LATENCY` and does not answer
dynamic playback-latency queries. It therefore reported zero downstream latency.
Android shows a 4,104-frame client buffer at 48 kHz plus mixer/HAL buffering;
capacities alone do not measure acoustic latency.

An optional native bridge now enables latency queries and calculates the remaining
queue from `AAudioStream_getTimestamp`, frames written and the monotonic clock.
Observation mode yielded valid advancing timestamps with roughly 49-62 ms delay.
Feedback mode was initially deployed with the private manual offset reset to zero. The
first PulseAudio query returned 52,514 microseconds instead of zero. During actual
phone playback, 52 timestamp queries were valid and none rejected; sampled delays
were roughly 49-73 ms. Shairport playback statistics continued updating. These
checks establish working software feedback, not complete acoustic alignment.
The later acoustic comparison is described below; long-running drift remains unverified.
The bridge's arithmetic and host routing/lifecycle harness pass with warnings
treated as errors, and it cross-compiles for Android API 26 / ARMv7.

The owner reports TV audio is still late with timestamp feedback and zero manual
offset. During a subsequent live stream, the NQPTP shared-memory probe showed
version 11, coherent copies, an active master, sample ages of 74-100 ms and
mapped-offset changes around -0.08 to -0.12 ms per second. Shairport's reported
internal timing error was around 1 ms, with valid AAudio readings around 58-68 ms.
This shows an updating clock mapping, not a stopped clock or obvious large drift.
It does not exclude a fixed timing bias or unreported downstream output delay.
No system wall-clock, NTP or global Android audio settings were changed.

A later continuous iPad recording of the known coded source provided an acoustic
measurement. Fully stationary windows at recording seconds 4-13 and 25-35 matched
the original source unambiguously: 10 first-speaker and 11 reference-speaker
windows gave a median difference of 228.9 ms, with the Philips late relative to
the HomePod. Separate 500-1400, 1400-2400 and 2400-3400 Hz checks gave roughly
225.5, 230.6 and 228.9 ms respectively. This is an approximate room measurement,
not sample-accurate hardware calibration. Timestamp feedback remained enabled;
the private device offset was adjusted by the rounded acoustic residual. The
receiver restarted and logged the expected new setting. A new recording is
needed to verify the correction; the captured stream was buffered AirPlay 2,
and realtime playback still needs separate validation. Keep raw recordings,
source WAVs, graphs and detailed reports in ignored storage.

After an overnight idle, a new realtime phone session connected but produced no
periodic playback statistics; the unmuted Pulse input had an empty audio queue.
The owner reported silence. The complete temporary runtime was stopped and
started, preserving previous receiver/clock logs for inspection. The owner then
confirmed sound returned. The underlying stalled-session cause is not established.

The running test now has only AAudio named `android_airplay`, matching the
checked-in template. The private configuration's sink name was updated during
recovery, preserving its calibration. A silent raw-playback drain test completed
successfully after restart. `restart-receiver` preserves the live sink, discovery
and clock service during calibration; the complete stop/start also resets them.

PC: the owner approved WSL2. WSL 2.7.14 and WSLg are installed, and the existing
WSL configuration already selects mirrored networking. However, a live CPU query
reports firmware virtualization disabled and the WSL distro import fails with
`HCS_E_HYPERV_NOT_INSTALLED`. The process is not elevated, so Windows optional
features could not be inspected. Enable AMD SVM in firmware, then check/enable
Virtual Machine Platform and restart as needed. Do not reboot automatically.

An official Ubuntu 24.04.5 WSL image was downloaded and its published SHA-256
verified. No working distro exists yet. Once WSL starts, test Shairport's
PulseAudio backend against WSLg's audio server, including its reported latency,
LAN discovery, timing traffic and actual multiroom playback.

## Build notes

The isolated Linux build host required `build-essential` in addition to the
library development packages. ARM links must explicitly use the ARM C++ compiler;
using the native `g++` fails with EM_ARM object-format errors. The current
upstream PulseAudio flag is `--with-pulseaudio`, not `--with-pa`.

Normalize Unix shell build inputs to LF when transferring from Windows, and
generate `plists/get_info_response.h` before the parallel build. Keep generated
objects and binaries out of Git. Do not interpret `--version` success as an
end-to-end receiver test.
