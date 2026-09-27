#!/bin/bash
#
# Holds the Java 25 additions to robovm-rt against a real Java 25.
#
# Bringing robovm-rt up to Java 25 means copying member bodies out of OpenJDK into libcore's
# sources. That they compile says very little: these are arithmetic and array routines whose whole
# difficulty is at the boundaries — ceilDiv at Integer.MIN_VALUE, the *Exact family one step either
# side of overflow, mismatch when the arrays hold NaN or a negative zero. So each added member is
# called here beside the JDK's own implementation of the same thing and the two answers compared.
#
# The classes cannot be tested where they live. java.lang.StrictMath and jdk.internal.util live in
# java.base, and a class loader will always answer with the platform's copy rather than ours. So the
# sources are transplanted into a `probe` package first: StrictMath's added block becomes
# StrictMathAdditions, ArraysSupport becomes ArraysSupportCopy, and both are compiled and run as
# ordinary classpath code against the JDK's originals.
#
# Needs a JDK 25 — earlier ones lack Math.powExact, which the added StrictMath.powExact delegates to.
#
# Usage: compiler/rt/tools/java25/check.sh [/path/to/jdk25]
set -e

HERE=$(cd "$(dirname "$0")" && pwd)
RT="$HERE/../../android/libcore/ojluni/src/main/java"

JDK="${1:-}"
if [ -z "$JDK" ]; then
    JDK=$(/usr/libexec/java_home -v 25 2>/dev/null \
        || ls -d "$HOME"/Library/Java/JavaVirtualMachines/*25*/Contents/Home 2>/dev/null | head -1)
fi
if [ ! -x "$JDK/bin/javac" ]; then
    echo "No JDK 25 found. Pass one: check.sh /path/to/jdk25" >&2
    exit 1
fi
echo "== against $("$JDK/bin/java" -version 2>&1 | head -1) =="

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/probe"

python3 - "$RT" "$WORK" <<'PY'
import pathlib
import sys

rt, work = (pathlib.Path(a) for a in sys.argv[1:3])

# StrictMath: only the block that was added, so the file's native declarations and its static
# initialiser — neither of which would resolve off the boot class path — stay out of the way.
text = (rt / 'java/lang/StrictMath.java').read_text(errors='replace')
marker = '// ---- added from OpenJDK 25 ----'
if marker not in text:
    sys.exit('StrictMath.java carries no added block — nothing to check.')
added = text[text.index(marker):text.rstrip().rfind('}')]
(work / 'probe/StrictMathAdditions.java').write_text(
    'package probe;\n\npublic final class StrictMathAdditions {\n'
    '    static final double PI = Math.PI;\n' + added + '\n}\n')

# ArraysSupport transplants whole; nothing in it reaches outside java.lang and java.util.
support = (rt / 'jdk/internal/util/ArraysSupport.java').read_text(errors='replace')
support = support.replace('package jdk.internal.util;', 'package probe;')
support = support.replace('class ArraysSupport', 'class ArraysSupportCopy')
support = support.replace('ArraysSupport()', 'ArraysSupportCopy()')
(work / 'probe/ArraysSupportCopy.java').write_text(support)
PY

cp "$HERE"/*.java "$WORK/probe/"
"$JDK/bin/javac" -nowarn -d "$WORK/out" "$WORK"/probe/*.java

"$JDK/bin/java" -cp "$WORK/out" probe.StrictMathCheck
"$JDK/bin/java" -cp "$WORK/out" probe.ArraysSupportCheck
