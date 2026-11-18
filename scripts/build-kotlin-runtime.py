"""Rebuild LemonLib's bundled Kotlin runtime from Maven Central.

Writes jars/lib/Kotlin-Runtime.jar (kotlin-stdlib + kotlinx-coroutines-core-jvm, one flat jar) and
jars/lib/Kotlin-Runtime-sources.jar (IDE source attach only). No kotlin-reflect: the game's script
classloader blocks java.lang.reflect, which kotlin-reflect is built on.

Usage (from the LemonLib folder; Python 3.8+, standard library only):
    python scripts/build-kotlin-runtime.py --kotlin 2.4.21 --coroutines 1.11.0

Every download is checked against Maven's .sha1. Afterwards set the same Kotlin version in
`kotlin("jvm") version "..."` of every build that compiles against this jar: a Kotlin compiler can
read stdlib metadata at most one minor version newer than itself, and code compiled by a newer
compiler may call stdlib functions an older runtime lacks. Check coroutines' POM: the kotlin-stdlib
version it declares must not be newer than --kotlin.
"""
import argparse
import hashlib
import io
import os
import sys
import urllib.request
import zipfile

MAVEN = "https://repo1.maven.org/maven2"
HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.normpath(os.path.join(HERE, "..", "jars", "lib"))


def fetch(group, artifact, version, suffix):
    base = "%s/%s/%s/%s/%s-%s%s" % (MAVEN, group.replace(".", "/"), artifact, version, artifact, version, suffix)
    data = urllib.request.urlopen(base, timeout=60).read()
    want = urllib.request.urlopen(base + ".sha1", timeout=60).read().decode("ascii").split()[0].strip().lower()
    got = hashlib.sha1(data).hexdigest()
    if got != want:
        sys.exit("SHA1 mismatch for %s: got %s, Maven says %s" % (base, got, want))
    print("sha1 ok  %s  %s-%s%s" % (got, artifact, version, suffix))
    return data


def skip(entry, sources):
    if entry.endswith("/"):
        return True
    base = entry.rsplit("/", 1)[-1]
    if base == "module-info.class":
        return True
    if entry.startswith("META-INF/"):
        if sources or entry == "META-INF/MANIFEST.MF":
            return True
        if entry.startswith("META-INF/versions/") or entry.startswith("META-INF/maven/"):
            return True
        if base.upper().endswith((".SF", ".RSA", ".DSA", ".EC")):
            return True
    return False


def assemble(parts, target, sources, manifest):
    """Merge jars in order (first copy of an entry wins) into one flat jar."""
    seen, count = set(), 0
    tmp = target + ".tmp"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zo:
        zo.writestr("META-INF/MANIFEST.MF", manifest)
        for data in parts:
            with zipfile.ZipFile(io.BytesIO(data)) as zi:
                for info in zi.infolist():
                    if skip(info.filename, sources) or info.filename in seen:
                        continue
                    seen.add(info.filename)
                    zo.writestr(info, zi.read(info.filename))
                    count += 1
    os.replace(tmp, target)
    print("wrote %s (%d entries)" % (target, count))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--kotlin", required=True, help="kotlin-stdlib version, e.g. 2.4.21")
    ap.add_argument("--coroutines", required=True, help="kotlinx-coroutines-core-jvm version, e.g. 1.11.0")
    a = ap.parse_args()

    stdlib = ("org.jetbrains.kotlin", "kotlin-stdlib", a.kotlin)
    coroutines = ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", a.coroutines)
    title = "kotlin-stdlib %s, kotlinx-coroutines-core-jvm %s" % (a.kotlin, a.coroutines)

    def manifest(what):
        return ("Manifest-Version: 1.0\r\nImplementation-Title: LemonLib Kotlin runtime%s\r\n"
                "Implementation-Version: %s\r\n\r\n" % (what, title))

    assemble([fetch(*stdlib, ".jar"), fetch(*coroutines, ".jar")],
             os.path.join(OUT_DIR, "Kotlin-Runtime.jar"), False, manifest(""))
    assemble([fetch(*stdlib, "-sources.jar"), fetch(*coroutines, "-sources.jar")],
             os.path.join(OUT_DIR, "Kotlin-Runtime-sources.jar"), True, manifest(" sources"))


if __name__ == "__main__":
    main()
