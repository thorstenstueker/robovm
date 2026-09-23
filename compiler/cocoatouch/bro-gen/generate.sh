#!/bin/bash
# Regenerates the CocoaTouch bindings this fork keeps, from the SDK of the selected Xcode.
#
#   compiler/cocoatouch/bro-gen/generate.sh              all frameworks in src/main/bro-gen
#   compiler/cocoatouch/bro-gen/generate.sh uikit        one of them (plus what it includes)
#   compiler/cocoatouch/bro-gen/generate.sh --no-build   generate only, skip the Maven build
#
# Since 23.09.2026 the bindings are ours: no more merges from MobiVM. The generator is bro-gen —
# the fork thorstenstueker/robovm-bro-gen of dkimitsa's, pinned as a submodule beside this script —
# and the YAMLs in src/main/bro-gen are the maintenance surface: one file per framework, with the
# renames, exclusions and type overrides. bro-gen rewrites only what lies between the
# /*<key>*/ … /*</key>*/ markers of a Java file; hand-written code outside them survives.
#
# What the script does, in order:
#   1. checks Xcode, the iOS SDK, Ruby and the gems against ./versions
#   2. brings the submodule to the pinned commit
#   3. runs bro-gen for the requested YAMLs with BRO_IOS_VERSION = the SDK version
#   4. fails on "Failed to resolve type", "Err:" or skipped categories in the generator's log,
#      lists Java files the generator did not touch (orphans: the class left the SDK or the YAML)
#   5. builds compiler/cocoatouch-prune and compiler/cocoatouch (javac + the cut, no javadoc)
#   6. prints the source diff summary and the prune report diff — that is what to review
#
# The generator also prints ">>> POTENTIAL NEW ENTRIES <<<" per YAML: classes, methods and enums
# the SDK has and the YAML does not name. Nothing of that is generated until it is added to the
# YAML — that is how a new API comes in, see prune/UPDATE.md.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)                      # the fork
YAMLS="$HERE/../src/main/bro-gen"
JAVA="$HERE/../src/main/java"
BRO="$HERE/robovm-bro-gen"
LOG=${BRO_GEN_LOG:-$HERE/../target/bro-gen.log}
# shellcheck source=versions
source "$HERE/versions"

BUILD=true
FRAMEWORKS=()
for arg in "$@"; do
    case "$arg" in
        --no-build) BUILD=false ;;
        *) FRAMEWORKS+=("$arg") ;;
    esac
done

fail() { echo "generate.sh: $*" >&2; exit 1; }
vercmp() { [ "$(printf '%s\n%s\n' "$2" "$1" | sort -V | head -1)" = "$2" ]; }   # $1 >= $2

echo "== 1. toolchain"
XC=$(xcodebuild -version 2>/dev/null | awk 'NR==1{print $2}')
[ "$XC" = "$XCODE" ] || fail "Xcode $XC selected, versions says $XCODE — xcode-select or bump versions"
SDK=$(xcrun --sdk iphoneos --show-sdk-version)
[ "$SDK" = "$IOS_SDK" ] || fail "iOS SDK $SDK, versions says $IOS_SDK"
RUBY=${RUBY:-$( (brew --prefix ruby 2>/dev/null || echo /opt/homebrew/opt/ruby)/bin/ruby)}
[ -x "$RUBY" ] || fail "no Ruby at $RUBY — brew install ruby (system Ruby is 2.6, ffi-clang needs $RUBY_MIN+)"
RV=$("$RUBY" -e 'print RUBY_VERSION')
vercmp "$RV" "$RUBY_MIN" || fail "Ruby $RV, need $RUBY_MIN+"
GEM=$(dirname "$RUBY")/gem
for g in ffi:$FFI_MIN ffi-clang:$FFI_CLANG_MIN; do
    name=${g%%:*}; min=${g##*:}
    have=$("$GEM" list --exact "$name" 2>/dev/null | sed -n "s/^$name (\([0-9.]*\).*/\1/p" | head -1)
    [ -n "$have" ] || fail "gem $name missing — $GEM install ffi ffi-clang"
    vercmp "$have" "$min" || fail "gem $name $have, need $min+"
done
echo "   Xcode $XC, iOS SDK $SDK, Ruby $RV, gems ok"

echo "== 2. bro-gen at $BRO_GEN_COMMIT"
(cd "$ROOT" && git submodule update --init --recursive -- "$BRO" >/dev/null)
AT=$(git -C "$BRO" rev-parse --short HEAD)
[ "$AT" = "$BRO_GEN_COMMIT" ] || fail "submodule is at $AT, versions pins $BRO_GEN_COMMIT (git -C $BRO checkout $BRO_GEN_COMMIT, then commit the gitlink)"

if [ ${#FRAMEWORKS[@]} -eq 0 ]; then
    for y in "$YAMLS"/*.yaml; do
        n=$(basename "$y" .yaml)
        [ "$n" = "__template" ] && continue
        FRAMEWORKS+=("$n")
    done
fi

echo "== 3. generating: ${FRAMEWORKS[*]}"
mkdir -p "$(dirname "$LOG")"
STAMP=$(mktemp "${TMPDIR:-/tmp}/bro-gen-stamp.XXXXXX")
: > "$LOG"
for f in "${FRAMEWORKS[@]}"; do
    [ -f "$YAMLS/$f.yaml" ] || fail "no $YAMLS/$f.yaml"
    echo "===== $f" >> "$LOG"
    if ! (cd "$BRO" && BRO_IOS_VERSION="$SDK" "$RUBY" bro-gen.rb "$JAVA" "$YAMLS/$f.yaml" >> "$LOG" 2>&1); then
        tail -30 "$LOG" >&2
        fail "bro-gen failed on $f — see $LOG"
    fi
    printf '   %-20s %s new-entry lines suggested\n' "$f" "$(awk '/POTENTIAL NEW ENTRIES/{f=1;next} /END OF YAML FILE POTENTIAL NEW ENTRIES/{f=0} f' "$LOG" | grep -c "name:" || true)"
done

echo "== 4. generator log"
if grep -nE "Failed to resolve|Err:" "$LOG" | head -20; then
    fail "the generator reported problems — fix the YAML (exclude: true, name:, private_typedefs) and rerun"
fi
echo "   no unresolved types, no errors ($(grep -c 'WARN: Skipping category' "$LOG") skipped categories on foreign classes, as always)"
# What the generator could not place goes into FIXME.java and into Constant__/Value__ members of a
# framework's default class. Both mean: a symbol the SDK has and the YAML does not map — new since
# the last run. Map it or exclude it in the YAML; the file is the to-do list, not a result.
UNMAPPED=$( (find "$JAVA/org/robovm/apple" -name FIXME.java; grep -rlE "Constants?__?[A-Za-z]|Value__[A-Za-z]" "$JAVA/org/robovm/apple" --include='*.java') 2>/dev/null | sort -u | sed "s|$JAVA/||")
if [ -n "$UNMAPPED" ]; then
    echo "   unmapped symbols — map or exclude them in the YAML (values:/functions:/constants:), then delete these files and rerun:" >&2
    echo "$UNMAPPED" | sed 's/^/     /' >&2
    for f in $UNMAPPED; do grep -oE 'symbol="[^"]*"|Constants?__?[A-Za-z_0-9]+' "$JAVA/$f" | sed 's/symbol=//; s/"//g; s/^/       /' | head -40 >&2; done
    fail "unmapped symbols"
fi
ORPHANS=$(for f in "${FRAMEWORKS[@]}"; do
    pkg=$(sed -n 's/^package: *org\.robovm\.apple\.//p' "$YAMLS/$f.yaml" | head -1)
    [ -n "$pkg" ] && find "$JAVA/org/robovm/apple/$pkg" -name '*.java' ! -newer "$STAMP" 2>/dev/null
done | sed "s|$JAVA/||")
rm -f "$STAMP"
if [ -n "$ORPHANS" ]; then
    echo "   files the generator did not touch (class gone from the SDK or the YAML? hand-written? decide):"
    echo "$ORPHANS" | sed 's/^/     /'
fi

if [ "$BUILD" = true ]; then
    echo "== 5. build and cut"
    (cd "$ROOT" && mvn -q -B install -pl compiler/cocoatouch-prune,compiler/cocoatouch -am -DskipTests -Dmaven.javadoc.skip=true)
    sed -n 2p "$HERE/../prune/report.txt"
fi

echo "== 6. what changed"
(cd "$ROOT" && git diff --stat -- compiler/cocoatouch/src/main/java | tail -1)
(cd "$ROOT" && git diff --stat -- compiler/cocoatouch/prune/report.txt | tail -1)
echo "   review: git diff -- compiler/cocoatouch/src/main/java (appends and @Deprecated are normal;"
echo "           a removed, non-deprecated member means Apple removed it — check the consumers)"
echo "           git diff -- compiler/cocoatouch/prune/report.txt"
echo "   log:    $LOG (new-entry suggestions per YAML at the end of each section)"
