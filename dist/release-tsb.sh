#!/bin/bash
# Publishes this fork's toolchain as a GitHub release — the build RapidFX and RapidJ pin to.
#
#   dist/release-tsb.sh 3.0.0-tsb.20260922
#
# What comes out:
#   tag      tsbmobile-<version>            on the current HEAD (must be pushed)
#   asset    robovm-dist-<version>.tar.gz   unpacks to robovm-<version>/
#   notes    the SHA-1 of the asset, to be written into Mobivm.java of both projects
#
# Why a release and not Sonatype: the fork is not published anywhere else, and RoboVM is GPL2 —
# whoever hosts the binary owes the corresponding source. A release tagged on the commit it was
# built from is that correspondence, in one place and at no cost.
#
# Prerequisites: JDK 21 as JAVA_HOME, the VM binaries under compiler/vm/target/binaries
# (see README "Building"), a clean working tree, gh logged in.
set -euo pipefail

VERSION=${1:?version, e.g. 3.0.0-tsb.20260922}
cd "$(dirname "$0")/.."

# The tree must correspond to a commit — except for what the build itself regenerates.
#
# compiler/swift/src/main/robopods holds the xcframework build-natives.sh produces. It is tracked
# on purpose, so that a clone without a Swift toolchain can still build, and it is therefore
# rewritten by every build. Two measurements from 27.09.2026, after ZERO_AR_DATE=1 and after
# sorting the xcframework's Info.plist: the archives still differ by **16 bytes** between two runs
# over identical source — a hash inside the Swift object, not a timestamp, and not worth chasing
# further.
#
# Excluding it does not weaken what this check is for. The correspondence GPL2 asks for is between
# the released binary and the *source* it was built from, and that source — src/main/swift/*.swift
# — is tracked and is covered here. A regenerated artefact differing in a hash is the same
# artefact. Anything else being dirty still stops the release.
DIRTY=$(git status --porcelain -- . ':(exclude)compiler/swift/src/main/robopods')
if [ -n "$DIRTY" ]; then
    echo "The working tree is not clean — the release must correspond to a commit." >&2
    echo "$DIRTY" >&2
    exit 1
fi
COMMIT=$(git rev-parse HEAD)
if ! git branch -r --contains "$COMMIT" | grep -q origin/; then
    echo "HEAD ($COMMIT) is not on origin — push first." >&2
    exit 1
fi

NAME="robovm-$VERSION"
TAG="tsbmobile-$VERSION"
ASSET="robovm-dist-$VERSION.tar.gz"

# iCloud copies, out of the way before anything is packaged.
#
# This repository sits under ~/Documents, which is mirrored, and the file service resolves what it
# thinks are conflicts by writing "Foo 2.class" beside "Foo.class" — including inside target/, where
# nobody looks. On 28.09.2026 the 27.0.0 release went out with **502 of them inside robovm-rt.jar**:
# stale duplicates of real classes, carrying an older API. They declare the same internal class name
# as the originals, so anything keyed by class name rather than by path reads whichever comes last
# in the zip. ApiDelta did exactly that and reported 59 methods missing from java.util.Arrays that
# have been there all along.
#
# Deleted rather than merely reported: a build that is one `rm` away from correct should take it.
DUPES=$(find . -name "* [0-9].*" -not -path "./.git/*" | wc -l | tr -d ' ')
if [ "$DUPES" != "0" ]; then
    echo "removing $DUPES iCloud duplicates before packaging"
    find . -name "* [0-9].*" -not -path "./.git/*" -delete
fi

echo "== packaging $NAME =="
mvn -q -pl dist/package clean package -DskipTests -Ddist.name="$NAME"
cp "dist/package/target/$NAME.tar.gz" "dist/package/target/$ASSET"

# And the same question asked of the result, because the copies can reappear during the build.
INSIDE=$(tar tzf "dist/package/target/$ASSET" | grep -c " [0-9]\." || true)
if [ "$INSIDE" != "0" ]; then
    echo "The archive holds $INSIDE duplicated paths — refusing to publish it." >&2
    exit 1
fi
# The runtime jar again from inside, because that is where it actually did the damage: a duplicated
# class sits at a path the archive listing above cannot see into.
UNPACKED=$(mktemp -d)
tar xzf "dist/package/target/$ASSET" -C "$UNPACKED" "$NAME/lib/robovm-rt.jar"
INSIDE=$(unzip -l "$UNPACKED/$NAME/lib/robovm-rt.jar" | grep -c " [0-9]\.class" || true)
rm -rf "$UNPACKED"
if [ "$INSIDE" != "0" ]; then
    echo "robovm-rt.jar holds $INSIDE duplicated classes — refusing to publish it." >&2
    exit 1
fi
SHA1=$(shasum -a 1 "dist/package/target/$ASSET" | cut -d' ' -f1)
SIZE=$(du -h "dist/package/target/$ASSET" | cut -f1)

echo "== release $TAG on $COMMIT =="
gh release create "$TAG" "dist/package/target/$ASSET" \
    --repo thorstenstueker/robovm \
    --target "$COMMIT" \
    --title "RoboVM $VERSION (tsbMobile toolchain)" \
    --notes "$(cat <<EOF
Toolchain build of this fork for RapidFX and RapidJ: class files up to version 61, a runtime library being filled in against Java 25, reduced CocoaTouch bindings, Swing on UIView.

    asset   $ASSET ($SIZE)
    sha1    $SHA1
    commit  $COMMIT

Unpacks to \`$NAME/\`. Pinned in \`Mobivm.java\` of both projects; the checksum there must match this one.
EOF
)"

echo
echo "Now write into Mobivm.java (RapidFX and RapidJ):"
echo "    MOBIVM_VERSION = \"$VERSION\""
echo "    SHA1           = \"$SHA1\""
