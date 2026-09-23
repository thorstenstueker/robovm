# Updating the CocoaTouch bindings for a new iOS SDK

The routine, twice a year, written so that it can be handed to an assistant on any Mac. Nothing in
it is a secret of one machine: the sources are upstream's, the cut is two text files in this folder,
the toolchain goes out as a GitHub release, and the consumers pin that release by version and SHA-1.

## What the bindings are, and what we ship

`compiler/cocoatouch/src/main/java` is **identical to upstream MobiVM** (`upstream/dev/3.0.0`). The
bindings are generated there from Apple's SDK headers with bro-gen (a separate tool,
`github.com/MobiVM/robovm-bro-gen`, Ruby + libclang) plus hand-written parts outside the generator
markers; upstream refreshes them per iOS release, framework by framework. We do not run bro-gen.

What we ship is the **cut**: after javac, `compiler/cocoatouch-prune` keeps the closure of
`seeds.txt` (the classes the consumers name, plus Bluetooth, GPS, motion, camera), strips from kept
classes every member that names a package in `exclude.txt`, deletes the rest and writes
`report.txt`. The jar in the release is the pruned one; consumers never see the cut, they get a
finished, tested jar. Their forms compile only against `tsb.mobile`; only the consumers' own
`swing-ios` modules name binding classes, and those are in `seeds.txt`.

## Prerequisites

- JDK 21 as `JAVA_HOME`; Xcode with the new iOS SDK (`xcodebuild -version`)
- the VM binaries under `compiler/vm/target/binaries` (README "Building"; on a machine without
  cmake, the `-nocompiler` tarball of a previous release supplies `lib/vm`)
- `gh` logged in with push rights to `thorstenstueker/robovm`
- sibling checkouts `../rapidfx` (branch `java17` or later) and `../tsbRapidJ` (branch `swing-only`
  or later) for the consumer seeds and the smoke tests
- the iOS simulator for the new iOS, and an Android 14 emulator for the RapidFX/RapidJ regression

## The routine

1. **Merge upstream.** `git fetch upstream && git merge upstream/dev/3.0.0`. Conflicts can only
   appear in `compiler/cocoatouch/pom.xml` (we dropped the `src/main/robopods` resources and the
   `build-natives.sh` exec, and added the prune execution) and in the modules this fork changed for
   Java 17. Do not touch `src/main/java` by hand.
2. **Refresh the seeds** if a consumer gained a binding class:
   `compiler/cocoatouch/prune/consumer-seeds.sh` (reads both consumers' `swing-ios`).
3. **Build and cut.**
   `mvn install -pl compiler/cocoatouch-prune,compiler/cocoatouch -am -DskipTests -Dmaven.javadoc.skip=true`
   (javac over 11,500 files takes 2–3 minutes; javadoc would take much longer and is not needed).
   Then read `git diff compiler/cocoatouch/prune/report.txt`:
   - a **failed build** names a seed that no longer exists (upstream renamed or removed it — fix
     `seeds.txt` and the consumer if it still uses the class) or a kept class whose supertype is
     excluded (decide: keep that package or drop the class);
   - **new packages in the closure**: upstream added a type to a signature we keep. Either accept
     (it is a few classes) or add the package to `exclude.txt`, which strips the member instead;
   - **stripped members** and **phantom references** should only change where upstream changed code.
4. **Smoke test on the new simulator** with the toolchain from `dist/package/target` (unpack the
   tarball, point `RFXMOBILE_MOBIVM` / `RAPIDJMOBILE_MOBIVM` at it):
   - RapidFX `mobile/examples/ERPMobile` (`rfxmobile ios`), RapidJ `examples/rapidx-erp`
     (`rapidjmobile ios`): the sign-in form renders with FlatLaf, sign-in shows the article list;
   - the sensor smoke app (see `compiler/cocoatouch/prune/smoke/` once it exists, otherwise the
     RoboVM console/ios templates): `UIScene` start, `CLLocationManager`, `CMMotionManager`,
     `CBCentralManager`, `AVCaptureDevice` — this is what catches runtime-behaviour changes such as
     the iOS 27 scene-lifecycle trap, which no binding diff shows;
   - read Apple's UIKit release notes for the SDK and upstream's "iOS xx bindings" pull request.
5. **Release.** `dist/release-tsb.sh 3.0.0-tsb.<yyyymmdd>` (clean tree, HEAD pushed). It prints
   the two lines for the pin.
6. **Move the pins**: RapidFX `mobile/tsb-mobile/tooling/.../Toolchain.java` (`MOBIVM_VERSION`),
   `Mobivm.java` (`SHA1`), `mobile/build.gradle.kts` (`mobivmVersion`), `MobivmTest`; RapidJ the
   same in `mobile/tooling`, plus `mobile/pom.xml`, `dist/pom.xml`, `mobile/device-guards/pom.xml`,
   `thirdparty/pom.xml`, `tools/install-toolchain-jars.sh`, docs. Run both consumer builds (all
   tests), the device runs again against the *published* release (no override), rebuild both
   IntelliJ plugins, then commit and push. The old releases stay on GitHub; rolling back is one line.

## If upstream lags behind Apple

Apps build and run on a newer SDK with older bindings — a binding describes an API surface, and the
60 classes the Swing route uses are decades old. New bindings are only needed to *call* a new API.
If that cannot wait for upstream: clone `MobiVM/robovm-bro-gen`, run it for the one framework's
YAML (`src/main/bro-gen/<framework>.yaml`) against the local SDK, commit the regenerated files on a
short-lived branch `sdk-<xx>-pregen`, and cut as above. When upstream publishes, drop that branch and
merge upstream instead; regenerated code lies between generator markers, so a conflicting merge
resolves with "theirs".

## Effort

About half a day per SDK: merge, build, cut and release are scripted; reading the report and the
smoke tests are the manual part.
