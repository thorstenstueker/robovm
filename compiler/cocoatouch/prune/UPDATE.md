# Updating the CocoaTouch bindings for a new iOS SDK

The routine, twice a year, written so that it can be handed to an assistant on any Mac. Nothing in
it is a secret of one machine: the generator is pinned as a submodule, its inputs are the YAMLs in
this repository, the cut is a text file in this folder, the toolchain goes out as a GitHub release,
and the consumers pin that release by version and SHA-1.

## What the bindings are, and what we ship

Since 23.09.2026 the bindings are **ours**: no more merges from MobiVM. `compiler/cocoatouch/src/main/java`
holds the 22 frameworks the tsbMobile consumers can reach — Foundation, CoreFoundation, dispatch,
UIKit, CoreGraphics, CoreAnimation, CoreText, CoreImage, ImageIO, UniformTypeIdentifiers,
UserNotifications, Security, CoreServices, AVFoundation, CoreMedia, CoreVideo, AudioToolbox,
CoreAudio, IOSurface, CoreLocation, CoreMotion, CoreBluetooth — and nothing else. The sources are
generated from Apple's SDK headers by **bro-gen**, dkimitsa's generator forked to
`github.com/thorstenstueker/robovm-bro-gen` and pinned as the submodule
`compiler/cocoatouch/bro-gen/robovm-bro-gen`. The generator rewrites only what lies between the
`/*<key>*/ … /*</key>*/` markers of a Java file; the 96 hand-written files are listed in
`compiler/cocoatouch/bro-gen/handwritten.txt`.

The maintenance surface is `compiler/cocoatouch/src/main/bro-gen/*.yaml`, one file per framework:
which classes, enums and functions to generate, renames, type overrides, exclusions. Everything the
generator needs beyond that is in `compiler/cocoatouch/bro-gen/`:

- `versions` — Xcode, iOS SDK, the bro-gen commit, the Ruby and gem minimums. `generate.sh` refuses
  to run against anything else, so two machines produce the same Java from the same headers.
- `generate.sh [framework…] [--no-build]` — the whole routine: checks the toolchain, brings the
  submodule to the pinned commit, generates, checks the log, builds and cuts, prints what changed.
- `skipped.txt` — the members the generator left out because their type belongs to a framework the
  fork does not bind (Metal, CoreData, Intents, CloudKit, Symbols, Contacts, CoreMIDI, OpenGL ES …).
  Our bro-gen treats such a type as unresolved and skips the member with a `WARN: skipping` line
  instead of writing a bare class name that would not compile. The list is under version control:
  a diff there is a finding, not noise.
- `handwritten.txt` — the files the generator never touches; anything else it did not touch is an
  orphan (the class left the SDK or the YAML) and is listed.
- `dupkeys.rb` — duplicate keys in a YAML (Psych keeps the last one silently, which is how an
  exclusion goes missing).

After javac, `compiler/cocoatouch-prune` still cuts: it keeps the closure of `seeds.txt` (the
classes the consumers name, plus Bluetooth, GPS, motion, camera), deletes the rest of the 22
frameworks and writes `report.txt`. `exclude.txt` is empty by design: with only the 22 frameworks
in the sources there is nothing left to exclude, and the report's `stripped 0 members … 0 classes
with phantom references` line is the check that this stays true. The jar in the release is the
pruned one; consumers never see the cut. Their forms compile only against `tsb.mobile`; only the
consumers' own `swing-ios` modules name binding classes, and those are in `seeds.txt`.

## Prerequisites

- JDK 21 as `JAVA_HOME`; the Xcode named in `versions` selected (`xcode-select -p`)
- Homebrew Ruby 3.2+ (`brew install ruby`, the system Ruby is 2.6) with `gem install ffi ffi-clang`
- the VM binaries under `compiler/vm/target/binaries` (README "Building"; on a machine without
  cmake, the `-nocompiler` tarball of a previous release supplies `lib/vm`)
- `gh` logged in with push rights to `thorstenstueker/robovm` and `thorstenstueker/robovm-bro-gen`
- sibling checkouts `../rapidfx` (branch `java17` or later) and `../tsbRapidJ` (branch `swing-only`
  or later) for the consumer seeds and the smoke tests
- the iOS simulator for the new iOS, and an Android 14 emulator for the RapidFX/RapidJ regression

## The routine per SDK

1. **Install the Xcode**, then edit `compiler/cocoatouch/bro-gen/versions`: `XCODE`, `IOS_SDK`.
2. **Generate, build, cut:** `compiler/cocoatouch/bro-gen/generate.sh`. Twenty minutes; the log
   is `compiler/cocoatouch/target/bro-gen.log`. It stops on:
   - `Failed to resolve type` / `Err:` outside a `WARN: skipping` line — a type in a *bound*
     framework that no YAML names. Add the entry (the log's "POTENTIAL NEW ENTRIES" section has the
     line ready) or exclude the member (`'-selector:': {exclude: true}`), and rerun.
   - unmapped symbols — a `FIXME.java` or `Constant__`/`Value__` members: the SDK has a constant
     or function the YAML does not map. Map it (`values:`, `functions:`, `constants:`) or exclude
     it; delete the dump file; rerun.
   - a compile error. The usual causes and their fixes are in the YAML comments marked
     `tsbMobile, iOS 27 SDK`: two initializers with the same Java signature (exclude the
     deprecated one), a class property whose static getter clashes with an instance getter of the
     superclass (`properties: '+name': {exclude: true}`), a deprecated `+new` (Apple's deprecation
     message makes the generator emit a method named `new`).
3. **Read the diffs** the script names at the end:
   - `git diff -- compiler/cocoatouch/src/main/java`: appends and `@Deprecated` are normal. A
     removed, non-deprecated member means Apple removed it — check the consumers. Parameter names
     and member order change between SDKs, so do not read hunks:
     `python3 compiler/cocoatouch/bro-gen/removed-members.py` compares the member sets ignoring both.
   - `git diff -- compiler/cocoatouch/bro-gen/skipped.txt`: a new line is either a new API typed
     with an unbound framework (fine) or a YAML mistake (an include or a mapping missing).
   - `git diff -- compiler/cocoatouch/prune/report.txt`: kept classes should only go up;
     `stripped 0`, `phantom 0` must stay.
   - orphans the script lists: delete the file (class gone) or add it to `handwritten.txt`.
4. **Refresh the seeds** if a consumer gained a binding class:
   `compiler/cocoatouch/prune/consumer-seeds.sh` (reads both consumers' `swing-ios`), then
   `generate.sh --no-build` is not needed — just the Maven build of step 2.
5. **Consumers compile.** A new SDK adds members to protocols the consumers implement
   (iOS 27 added `grammarCheckingType` to `UITextInputTraits`, which `IosView` implements through
   `UIKeyInput`). Compile both `swing-ios` modules against the new jar before releasing:
   RapidFX `RFXMOBILE_MOBIVM=<unpacked dist> ./gradlew :swing-ios:compileJava` in `mobile/`,
   RapidJ `mvn -o -pl mobile/swing-ios compile -Drobovm.version=3.0.0-SNAPSHOT`. Add the missing
   overrides to `IosView` (both repositories carry the same file).
6. **Smoke test on the new simulator** with the toolchain from `dist/package/target`
   (`mvn -pl dist/package clean package -Ddist.name=robovm-3.0.0-SNAPSHOT`, unpack the tarball,
   point `RFXMOBILE_MOBIVM` / `RAPIDJMOBILE_MOBIVM` at it):
   - RapidFX `mobile/examples/ERPMobile` (`rfxmobile ios … --device <udid>`), RapidJ
     `examples/rapidx-erp` (`rapidjmobile ios … --device <udid>`): the sign-in form renders with
     FlatLaf; a copy of the example with `form = ArticleListForm` shows the article list from
     SQLite without a tap (there is no Simulator.app to click in on a Command-Line-Tools Mac);
   - the sensor classes are in the jar (`CLLocationManager`, `CMMotionManager`,
     `CBCentralManager`, `AVCaptureDevice`, `UIImagePickerController`) — a runtime sensor smoke
     needs a device;
   - read Apple's UIKit release notes for the SDK: runtime changes such as the iOS 26 scene
     life-cycle trap show in no diff.
7. **Release.** Commit (sources, YAMLs, `skipped.txt`, `report.txt`, `versions`, the submodule
   gitlink), push, `dist/release-tsb.sh 3.0.0-tsb.<yyyymmdd>` (clean tree, HEAD pushed). It prints
   the two lines for the pin.
8. **Move the pins**: RapidFX `mobile/tsb-mobile/tooling/.../Toolchain.java` (`MOBIVM_VERSION`),
   `Mobivm.java` (`SHA1`), `mobile/build.gradle.kts` (`mobivmVersion`), `MobivmTest`; RapidJ the
   same in `mobile/tooling`, plus `mobile/pom.xml`, `dist/pom.xml`, `mobile/device-guards/pom.xml`,
   `thirdparty/pom.xml`, `tools/install-toolchain-jars.sh`, docs. Run both consumer builds (all
   tests), the device runs again against the *published* release (no override), rebuild both
   IntelliJ plugins, then commit and push. The old releases stay on GitHub; rolling back is one line.

## A new API without an SDK change

Add the class, enum or function to the framework's YAML (`Foo: {}` is enough for most; the log's
"POTENTIAL NEW ENTRIES" section of the last run lists what the SDK has and the YAML does not),
run `generate.sh <framework>`, add the class to `seeds.txt` if a consumer names it, build, release.
A framework the fork does not bind yet needs its YAML back from upstream's history
(`git show upstream/dev/3.0.0:compiler/cocoatouch/src/main/bro-gen/<name>.yaml`), an `include:`
line in the YAMLs that reference its types, and a look at `skipped.txt`, which then shrinks.

## Upstream stays a reference

The `upstream` remote (MobiVM) is read-only for us: `git log upstream/dev/3.0.0 -- …/bro-gen/uikit.yaml`
shows how they mapped a new API, and a YAML hunk can be cherry-picked. Never `git merge` it again:
the sources are generated from different YAMLs now.

## Effort

Half a day to a day per SDK. Generation, build, cut and release are scripted; the YAML work for a
large UIKit SDK (new enums and classes that kept members name, renames, the odd clash) and the
smoke tests are the manual part. The small frameworks take minutes.
