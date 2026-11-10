"""Exercise the real shell watcher with mocked power snapshots and pactl."""
from pathlib import Path
import os
import subprocess
import tempfile
import time
import unittest

HERE = Path(__file__).resolve().parent


class WakeWatchChecks(unittest.TestCase):
    def run_watch(self, states, failed_resumes=0):
        with tempfile.TemporaryDirectory(prefix="airplay-wake-check-") as temporary:
            root = Path(temporary)
            (root / "run").mkdir()
            (root / "bin").mkdir()
            (root / "states").write_text("\n".join(f"{s} {w}" for s, w in states) + "\n")
            (root / "index").write_text("0\n")
            (root / "failures").write_text("0\n")
            scripts = {
                "id": "#!/bin/sh\nprintf '0\\n'\n",
                "busybox": '''#!/bin/sh
if [ "$1" = timeout ]; then
  shift
  if [ "$1" = -s ]; then shift 2; fi
  shift
fi
exec "$@"
''',
                "dumpsys": '''#!/bin/sh
i=$(cat "$TEST_RECOVERY_DIR/index"); i=$((i+1))
echo "$i" >"$TEST_RECOVERY_DIR/index"
line=$(sed -n "${i}p" "$TEST_RECOVERY_DIR/states")
set -- $line
printf '  mWakefulness=%s\n  mLastWakeTime=%s (10 ms ago)\n' "$1" "$2"
''',
                "sleep": '''#!/bin/sh
if [ "$1" = 5 ]; then
  i=$(cat "$TEST_RECOVERY_DIR/index")
  n=$(wc -l <"$TEST_RECOVERY_DIR/states")
  if [ "$i" -ge "$n" ]; then kill -TERM "$PPID"; fi
fi
''',
                "setsid": '#!/bin/sh\nexec "$@"\n',
                "control": '''#!/bin/sh
echo "$1" >>"$TEST_RECOVERY_DIR/recoveries"
''',
                "pactl": '''#!/bin/sh
printf '%s %s %s\n' "$1" "$2" "$3" >>"$TEST_RECOVERY_DIR/actions"
if [ "$3" = 0 ]; then
  n=$(cat "$TEST_RECOVERY_DIR/failures")
  if [ "$n" -lt "$TEST_FAILED_RESUMES" ]; then
    echo "$((n+1))" >"$TEST_RECOVERY_DIR/failures"; exit 1
  fi
fi
''',
            }
            for name, content in scripts.items():
                path = root / "bin" / name
                path.write_text(content)
                path.chmod(0o755)
            result = subprocess.run(["sh", str(HERE / "philips-wake-watch.sh")],
                env={**os.environ, "PATH": str(root / "bin") + ":" + os.environ["PATH"],
                     "AIRPLAY_RUNTIME_ROOT": str(root), "AIRPLAY_PULSE_ROOT": str(root),
                     "AIRPLAY_BUSYBOX": str(root / "bin/busybox"),
                     "AIRPLAY_DUMPSYS": str(root / "bin/dumpsys"),
                     "AIRPLAY_CONTROL": str(root / "bin/control"),
                     "TEST_RECOVERY_DIR": str(root), "TEST_FAILED_RESUMES": str(failed_resumes)},
                capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            actions = (root / "actions").read_text().splitlines() if (root / "actions").exists() else []
            if "restarting the test runtime" in result.stdout:
                deadline = time.monotonic() + 5  # the restart runs detached from the watcher
                while not (root / "recoveries").exists() and time.monotonic() < deadline:
                    time.sleep(0.05)
            self.recoveries = ((root / "recoveries").read_text().splitlines()
                               if (root / "recoveries").exists() else [])
            return actions, result.stdout

    def test_no_recovery_for_ordinary_awake_polling(self):
        self.assertEqual(self.run_watch([("Awake", 100)] * 5)[0], [])

    def test_sleep_then_wake_recovers_once(self):
        actions, _ = self.run_watch([("Awake", 100), ("Asleep", 100)] + [("Awake", 200)] * 5)
        self.assertEqual(actions, ["suspend-sink android_airplay 1", "suspend-sink android_airplay 0"])

    def test_changed_wake_counter_without_observed_sleep(self):
        actions, _ = self.run_watch([("Awake", 100)] + [("Awake", 200)] * 5)
        self.assertEqual(len(actions), 2)

    def test_bounce_is_debounced(self):
        actions, _ = self.run_watch([("Awake", 100), ("Asleep", 100), ("Awake", 200),
                                    ("Asleep", 200)] + [("Awake", 300)] * 5)
        self.assertEqual(len(actions), 2)

    def test_asleep_never_recreates_audio(self):
        self.assertEqual(self.run_watch([("Asleep", 100)] * 5)[0], [])

    def test_screensaver_is_not_standby(self):
        self.assertEqual(self.run_watch([("Awake", 100), ("Dreaming", 100)] + [("Awake", 100)] * 5)[0], [])

    def test_failed_recovery_is_bounded(self):
        actions, output = self.run_watch([("Awake", 100)] + [("Awake", 200)] * 10, 10)
        self.assertEqual(len(actions), 6)
        self.assertIn("failed after three attempts", output)
        self.assertEqual(self.recoveries, ["recover"])

    def test_successful_recovery_does_not_restart_the_runtime(self):
        self.run_watch([("Awake", 100), ("Asleep", 100)] + [("Awake", 200)] * 6, 1)
        self.assertEqual(self.recoveries, [])


if __name__ == "__main__":
    unittest.main()
