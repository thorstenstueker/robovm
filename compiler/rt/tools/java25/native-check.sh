#!/bin/bash
#
# Runs a check against the real robovm-rt, ahead-of-time compiled and executed natively.
#
# check.sh next door tests the added members by transplanting their sources into a probe package and
# running them on the host JDK. That catches a wrong body, and nothing else: it proves a copy of
# OpenJDK agrees with OpenJDK. It cannot say whether the addition fits onto *our* classes, which are
# not the JDK's: compiler/rt/android/libcore is a fork of Android's libcore, and it has diverged from
# OpenJDK for fifteen years. Our String has no compact encoding. Our Properties still keeps its
# entries in Hashtable. Our collections are our own.
#
# None of that is a constraint Android imposes — we own this source and may change any of it. It is
# simply what the code already is, and a member copied out of OpenJDK has to fit onto it.
#
# So this compiles the check against robovm-rt as the boot class path, hands it to the AOT compiler
# for this machine's own architecture, and runs the binary. Everything below java.lang.Object is
# then the library a phone gets. It found nothing when SequencedCollection went in, which is the
# result worth having: List's new supertype reaches ArrayList, LinkedList, Arrays.asList, subList
# and Collections.unmodifiableList without disturbing any of them.
#
# Needs a built distribution. Either pass one, or let it build:
#   mvn -pl compiler/rt install -DskipTests
#   mvn -pl dist/package clean package -DskipTests -Ddist.name=robovm-test
#
# Usage: native-check.sh [/path/to/unpacked/dist]
set -e

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../../.." && pwd)

DIST="${1:-}"
if [ -z "$DIST" ]; then
    PACKED="$ROOT/dist/package/target/robovm-test.tar.gz"
    if [ ! -f "$PACKED" ]; then
        echo "No distribution. Build one:" >&2
        echo "  mvn -pl compiler/rt install -DskipTests" >&2
        echo "  mvn -pl dist/package clean package -DskipTests -Ddist.name=robovm-test" >&2
        exit 1
    fi
    DIST=$(mktemp -d)/dist
    mkdir -p "$DIST"
    tar xzf "$PACKED" -C "$DIST"
    DIST="$DIST/robovm-test"
fi
if [ ! -x "$DIST/bin/robovm" ]; then
    echo "$DIST does not look like an unpacked distribution." >&2
    exit 1
fi
echo "== against $DIST =="

# javac 8, because robovm-rt is the boot class path and only a Java 8 target accepts one.
JAVAC=$(/usr/libexec/java_home -v 1.8 2>/dev/null)/bin/javac
if [ ! -x "$JAVAC" ]; then
    echo "No JDK 8. It is needed for -bootclasspath, which later javac rejects." >&2
    exit 1
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/classes"
"$JAVAC" -nowarn -bootclasspath "$DIST/lib/robovm-rt.jar" -d "$WORK/classes" "$HERE"/native/*.java

# -target console, or the linker asks each target whether it matches and the framework one trips
# over a null. -os/-arch default from the host, but naming them keeps the failure legible on a
# machine where they would not.
ARCH=$([ "$(uname -m)" = "arm64" ] && echo arm64 || echo x86_64)
OS=$([ "$(uname -s)" = "Darwin" ] && echo macosx || echo linux)

for SRC in "$HERE"/native/*.java; do
    NAME=$(basename "$SRC" .java)
    echo "== $NAME =="
    "$DIST/bin/robovm" -target console -os "$OS" -arch "$ARCH" \
        -cp "$WORK/classes" -d "$WORK/$NAME" -o "$NAME" "$NAME" > "$WORK/$NAME.log" 2>&1 \
        || { echo "AOT compilation failed:"; tail -20 "$WORK/$NAME.log"; exit 1; }
    "$WORK/$NAME/$NAME"
done
