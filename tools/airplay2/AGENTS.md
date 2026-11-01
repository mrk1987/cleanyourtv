# AirPlay 2 experiment

This source bundle is independent of the launcher and normal debloat workflow.
The owner has authorized the rooted Philips receiver experiment. Do not change
stock Android audio/input services, launcher behavior, SELinux, wake locks or
boot hooks as incidental setup. Do not modify the JBL reference receiver.

The current synchronization has owner acceptance. Preserve the private device
calibration; do not resume tuning unless asked. Source configuration is a generic
template, not a copy of the private device settings.

Use the existing `tools/tv.py` helper from the repository root with an explicit
TV endpoint. Stage uploads in `/data/local/tmp`, then copy through root into
root-owned directories. Do not make those directories world-writable.

Keep recordings, generated WAVs, vendor binaries, packages, runtime archives,
credentials, device settings and reports in the outer TV project's
`scratchpad/airplay2` directory. None belong in Git. Deliver generated audio on
the PC for the owner to transfer to their phone; do not host it on the TV.

Relevant checks: `test_sync_measurement.py` in the analysis environment;
`test_wake_watch.py` on a Linux host; `build-aaudio-timing.sh` for arithmetic,
interposer lifecycle checks and the Android cross-build. State actual wake/audio
acceptance separately from mocked tests and internal timestamp statistics.
