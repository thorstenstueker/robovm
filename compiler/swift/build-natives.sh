#!/bin/bash
#
# Baut RvmSwiftBridge.xcframework aus src/main/swift.
#
# Mit swiftc statt cmake: fuer Swift gibt es keinen Grund, den Umweg ueber CMake zu gehen.
# (Hier stand ein Verweis auf compiler/cocoatouch/build-natives.sh. Das Skript gibt es seit
# 21.09.2026 nicht mehr -- mit den 134 herausgeschnittenen Frameworks fiel auch rvm_oslog.m weg,
# die einzige native Quelle von cocoatouch.)
#
# Ergebnis ist ein *statisches* xcframework. Der Weg ist der von MobiVM
# unterstuetzte (PR #474 "support for static libs with swift usage"); die
# Swift-Runtime-Abhaengigkeiten stehen als LC_LINKER_OPTION in der .a und
# werden von clang beim Linken der App automatisch aufgeloest -- RoboVMs
# AbstractTarget legt die noetigen -L-Pfade an, sofern <swiftSupport> aktiv ist.
#
set -euo pipefail

# Zeitstempel aus den Archivkoepfen heraus -- sonst ist der Arbeitsbaum nach jedem Bau schmutzig.
#
# Das Ergebnis landet unter src/main/robopods/, und das ist versioniert: ein Klon ohne Swift-
# Werkzeugkette soll bauen koennen. `swiftc -emit-library -static` schreibt aber die Uhrzeit in
# den ar-Kopf jedes Mitglieds, also aendern sich bei gleichem Eingang trotzdem Bytes. Gemessen am
# 27.09.2026 an libRvmSwiftBridge.a: gleiche Groesse, gleiche Symbole, 32 abweichende Bytes.
#
# Das ist nicht Kosmetik. dist/release-tsb.sh verlangt einen sauberen Baum -- "the release must
# correspond to a commit", und zu Recht, denn GPL2 verlangt die Quelle zum Binaerteil. Ohne diese
# Zeile verweigert es nach jedem Bau, und der einzige Ausweg waere, zu jedem Release eine
# Rauschaenderung mitzucommitten.
#
# ZERO_AR_DATE ist Apples eigener Schalter dafuer; ld, libtool und ar lesen ihn, und swiftc ruft
# libtool auf.
export ZERO_AR_DATE=1

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

# Die Eintraege in Info.plist sortieren -- xcodebuild schreibt sie in wechselnder Reihenfolge.
#
# Nach ZERO_AR_DATE waren die .a-Dateien zwischen zwei Laeufen byteweise gleich und nur noch diese
# Datei nicht: mal steht ios-arm64 zuerst, mal ios-arm64_x86_64-simulator. Gemessen, nicht
# vermutet. Damit bliebe der Baum weiter nach jedem Bau schmutzig, und das Release-Skript
# weiterhin blockiert -- an einer Zeilenreihenfolge, die keine Bedeutung hat.
python3 - "$OUT/Info.plist" <<'PY'
import plistlib, sys

path = sys.argv[1]
with open(path, "rb") as f:
    plist = plistlib.load(f)

plist["AvailableLibraries"].sort(key=lambda entry: entry.get("LibraryIdentifier", ""))
with open(path, "wb") as f:
    plistlib.dump(plist, f, sort_keys=True)
PY

echo
echo ">>> fertig: $OUT"
lipo -info build/ios-arm64/lib${MODULE}.a build/ios-sim/lib${MODULE}.a
