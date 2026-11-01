#!/usr/bin/env python3
"""Assemble a private ARM Linux receiver runtime; run on the Linux build host.

Input executables come from pinned Shairport Sync/NQPTP builds. Downloaded Debian
daemon packages are extracted, never installed as foreign-architecture services.
No JBL executables, identity material or firmware libraries are included.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tarfile


def arm_elf(path):
    with path.open("rb") as stream:
        data = stream.read(20)
    return data[:7] == b"\x7fELF\x01\x01\x01" and struct.unpack_from("<H", data, 18)[0] == 40


def dependencies(path):
    result = subprocess.check_output(["arm-linux-gnueabihf-readelf", "-d", str(path)], text=True)
    return re.findall(r"\(NEEDED\).*?\[([^]]+)\]", result)


def package(build_root, output):
    source_dir = Path(__file__).resolve().parent
    compiler = "arm-linux-gnueabihf-gcc"
    subprocess.run(["gcc", "-Wall", "-Wextra", "-Werror", "-DAIRPLAY_TEST_OLD_KERNEL",
                    str(source_dir / "legacy_entropy.c"), str(source_dir / "entropy_check.c"),
                    "-o", str(build_root / "entropy-check-host")], check=True)
    subprocess.run([str(build_root / "entropy-check-host")], check=True)
    for sources, flags, name in [
        (["legacy_entropy.c"], ["-shared", "-fPIC"], "libairplay-legacy.so"),
        (["legacy_launch.c"], [], "airplay-launch"),
        (["entropy_check.c"], [], "entropy-check"),
    ]:
        subprocess.run([compiler, "-Wall", "-Wextra", "-Werror", "-O2", *flags,
                        *(str(source_dir / item) for item in sources),
                        "-o", str(build_root / name)], check=True)
    root = build_root / "runtime"
    extracted = build_root / "daemon-files"
    root.mkdir(parents=True, exist_ok=True)
    extracted.mkdir(parents=True, exist_ok=True)
    for deb in sorted((build_root / "daemon-debs").glob("*.deb")):
        subprocess.run(["dpkg-deb", "-x", str(deb), str(extracted)], check=True)
    search = [extracted / "usr/lib/arm-linux-gnueabihf", extracted / "lib/arm-linux-gnueabihf",
              Path("/usr/lib/arm-linux-gnueabihf"), Path("/usr/arm-linux-gnueabihf/lib")]
    programs = {
        "usr/bin/shairport-sync": build_root / "shairport-sync/shairport-sync",
        "usr/bin/nqptp": build_root / "nqptp/nqptp",
        "usr/bin/airplay-launch": build_root / "airplay-launch",
        "usr/bin/entropy-check": build_root / "entropy-check",
        "usr/bin/dbus-daemon": extracted / "usr/bin/dbus-daemon",
        "usr/sbin/avahi-daemon": extracted / "usr/sbin/avahi-daemon",
    }
    queue = []
    for target, source in programs.items():
        if not arm_elf(source):
            raise ValueError(f"Not an ARM ELF: {source}")
        dest = root / target
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, dest)
        dest.chmod(0o755)
        queue.extend(dependencies(source))
    queue.append("ld-linux-armhf.so.3")
    (root / "lib").mkdir(exist_ok=True)
    shutil.copyfile(build_root / "libairplay-legacy.so", root / "lib/libairplay-legacy.so")
    queue.extend(dependencies(build_root / "libairplay-legacy.so"))
    copied = set()
    while queue:
        name = queue.pop()
        if name in copied:
            continue
        if "/" in name or name in (".", ".."):
            raise ValueError(f"Unsafe library name: {name}")
        matches = [p for directory in search if directory.exists() for p in directory.rglob(name)
                   if p.is_file() and arm_elf(p)]
        if not matches:
            raise FileNotFoundError(f"Missing ARM dependency: {name}")
        source = matches[0].resolve()
        dest = root / "lib" / name
        dest.parent.mkdir(exist_ok=True)
        shutil.copyfile(source, dest)
        dest.chmod(0o755)
        copied.add(name)
        queue.extend(dependencies(source))
    for directory in ("dev", "dev/shm", "proc", "run", "run/dbus", "run/avahi-daemon",
                      "run/pulse", "tmp", "etc", "etc/avahi", "etc/airplay", "var", "var/run",
                      "var/lib", "var/lib/dbus", "root"):
        (root / directory).mkdir(parents=True, exist_ok=True)
    (root / "tmp").chmod(0o1777)
    (root / "etc/passwd").write_text("root:x:0:0:root:/root:/bin/sh\navahi:x:995:995:Avahi:/run/avahi-daemon:/bin/false\n")
    (root / "etc/group").write_text("root:x:0:\navahi:x:995:\n")
    (root / "etc/nsswitch.conf").write_text("passwd: files\ngroup: files\nhosts: files dns\n")
    (root / "etc/hosts").write_text("127.0.0.1 localhost\n")
    (root / "etc/pulse").mkdir(exist_ok=True)
    (root / "etc/pulse/client.conf").write_text("autospawn = no\nenable-shm = no\nenable-memfd = no\n")
    manifest = {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                for p in root.rglob("*") if p.is_file()}
    (root / "MANIFEST.json").write_text(json.dumps(manifest, indent=2) + "\n")
    with tarfile.open(output, "w") as archive:
        for p in sorted(root.rglob("*")):
            archive.add(p, arcname=p.relative_to(root).as_posix(), recursive=False)
    print(f"Packaged {len(programs)} programs, {len(copied)} libraries: {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build_root", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    package(args.build_root.resolve(), args.output.resolve())
