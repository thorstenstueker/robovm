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

if [ -n "$(git status --porcelain)" ]; then
    echo "The working tree is not clean — the release must correspond to a commit." >&2
    git status --short >&2
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

echo "== packaging $NAME =="
mvn -q -pl dist/package clean package -DskipTests -Ddist.name="$NAME"
cp "dist/package/target/$NAME.tar.gz" "dist/package/target/$ASSET"
SHA1=$(shasum -a 1 "dist/package/target/$ASSET" | cut -d' ' -f1)
SIZE=$(du -h "dist/package/target/$ASSET" | cut -f1)

echo "== release $TAG on $COMMIT =="
gh release create "$TAG" "dist/package/target/$ASSET" \
    --repo thorstenstueker/robovm \
    --target "$COMMIT" \
    --title "RoboVM $VERSION (tsbMobile toolchain)" \
    --notes "$(cat <<EOF
Toolchain build of this fork for RapidFX and RapidJ (Java 17 class files and library, reduced CocoaTouch bindings, Swing on UIView).

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
