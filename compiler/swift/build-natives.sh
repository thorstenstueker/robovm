#!/bin/bash
#
# Baut RvmSwiftBridge.xcframework aus src/main/swift.
#
# Analog zu compiler/cocoatouch/build-natives.sh, aber mit swiftc statt cmake:
# fuer Swift gibt es keinen Grund, den Umweg ueber CMake zu gehen.
#
# Ergebnis ist ein *statisches* xcframework. Der Weg ist der von MobiVM
# unterstuetzte (PR #474 "support for static libs with swift usage"); die
# Swift-Runtime-Abhaengigkeiten stehen als LC_LINKER_OPTION in der .a und
# werden von clang beim Linken der App automatisch aufgeloest -- RoboVMs
# AbstractTarget legt die noetigen -L-Pfade an, sofern <swiftSupport> aktiv ist.
#
set -euo pipefail

cd "$(dirname "$0")"

# SwiftUI braucht iOS 13, @Observable iOS 17. Ab iOS 15 entfaellt das
# Nachliefern der Concurrency-Back-Deployment-Libs.
MIN_IOS=17.0
MODULE=RvmSwiftBridge
OUT=src/main/robopods/META-INF/robovm/ios/libs/${MODULE}.xcframework

SOURCES=(src/main/swift/*.swift)

rm -rf build "$OUT"
mkdir -p build

# $1 = slice name, $2 = sdk, $3 = target triple
build_slice() {
  local name="$1" sdk="$2" triple="$3"
  local dir="build/$name"
  mkdir -p "$dir"
  echo ">>> $name ($triple, -sdk $sdk)"
  xcrun -sdk "$sdk" swiftc \
    -target "$triple" \
    -module-name "$MODULE" \
    -emit-library -static \
    -O -wmo \
    -swift-version 6 \
    -o "$dir/lib${MODULE}.a" \
    "${SOURCES[@]}"
}

build_slice ios-arm64    iphoneos        "arm64-apple-ios${MIN_IOS}"
build_slice ios-sim-arm64 iphonesimulator "arm64-apple-ios${MIN_IOS}-simulator"
build_slice ios-sim-x64   iphonesimulator "x86_64-apple-ios${MIN_IOS}-simulator"

# Simulator-Slices zu einer fat lib zusammenfassen -- ein xcframework darf
# pro Plattform/Variante nur einen Eintrag haben.
mkdir -p build/ios-sim
lipo -create \
  build/ios-sim-arm64/lib${MODULE}.a \
  build/ios-sim-x64/lib${MODULE}.a \
  -output build/ios-sim/lib${MODULE}.a

mkdir -p "$(dirname "$OUT")"
xcodebuild -create-xcframework \
  -library build/ios-arm64/lib${MODULE}.a \
  -library build/ios-sim/lib${MODULE}.a \
  -output "$OUT"

echo
echo ">>> fertig: $OUT"
lipo -info build/ios-arm64/lib${MODULE}.a build/ios-sim/lib${MODULE}.a
