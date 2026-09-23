# RoboVM 

![Build Status](https://github.com/MobiVM/robovm/actions/workflows/snapshot.yml/badge.svg)


[**Website**](https://mobivm.github.io) -
[**Developer Guide**](https://github.com/MobiVM/robovm/wiki/Developer-Guide) -
[**Changelog**](https://github.com/MobiVM/robovm/wiki/Changelog) -
[**RoboPods**](https://github.com/MobiVM/robovm-robopods) -
[**dkimitsa's dev blog**](https://dkimitsa.github.io/)

RoboVM is an ahead-of-time compiler for Java bytecode, targeting Linux, Mac OS X and iOS.

This is a fork of the [last open-source release of RoboVM](https://github.com/robovm/robovm).

## Key Features

**iOS 16 and XCode 14** are fully supported.

**Interface Builder Integration** is also available, details in [this wiki article](https://github.com/MobiVM/robovm/wiki/Is-XCode-interface-builder-supported%3F).

**Debugging support** is finished, and stable thanks to @dkimitsa!

## Java version support

**Building RoboVM** requires JDK 21 (this is what CI uses); the toolchain itself is compiled for Java 17.
The runtime library (`robovm-rt`) is still compiled with `-source 8` on purpose: it *is* the boot class
path and javac only accepts an empty `-bootclasspath` for Java 8 targets.

**Application code** can be compiled with `javac --release 17` (or Kotlin `jvmTarget = 17`). This makes
it possible to share Java 17 code between Android (`d8`/AGP 8.x) and iOS without a lowered language level:

| Java 17 language feature | Android d8 | RoboVM |
|---|---|---|
| Lambdas, method references, string concat (`invokedynamic`) | yes | yes |
| Nest based access control (private access between nested classes, Java 11) | yes | yes |
| Private interface methods (Java 9) | yes | yes |
| Records: `equals`/`hashCode`/`toString`, `Class.isRecord()`, `getRecordComponents()` | yes | yes (desugared at compile time) |
| Sealed classes | yes | yes (`Class.isSealed()` reports `false`, the attribute is not retained) |
| Pattern matching `instanceof`, switch expressions, text blocks, `var` | yes | yes |
| `CONSTANT_Dynamic` (condy) | yes | no, reported as a compile error |
| Java 21 pattern switch / record patterns | yes | no, `NoSuchMethodError` at runtime (with a compile time warning) |

| API area | Android | RoboVM |
|---|---|---|
| `java.time`, streams, `Optional`, `java.util.function` | core library desugaring | yes (libcore 12) |
| Java 9 - 17 additions to `String`, `Optional`, `Objects`, `Math`, `Arrays`, `Collectors`, `Stream`, `Files`, `Path`, `InputStream`, `HexFormat`, `Runtime.version()` ... | D8 backported methods | yes |
| `java.lang.invoke.MethodHandle` invocation at runtime, `StackWalker`, `ProcessHandle`, hidden classes | partial | no |

Calls to APIs that are still missing in `robovm-rt` are reported as warnings at compile time
(`Unresolved method ...`) and throw `NoSuchMethodError` at runtime. `compiler/rt/tools/ApiDelta.java`
lists the remaining differences to the JDK's `java.base`.

### State on 23.09.2026 — what the runtime has and lacks

Added for the tsbMobile consumers (RapidFX, RapidJ) since the Java 17 commit: `LocalDate.EPOCH`,
`java.lang.ref.Cleaner`, `Method`/`Constructor.getParameterCount()`, the limited
`AccessController.doPrivileged(…, Permission...)` overloads, `MalformedParameterizedTypeException(String)`.

Known missing, and what it means:

| Missing | Effect |
|---|---|
| `MethodHandle` invocation, `MethodHandles.lookup()` | FlatLaf's reflective paths (`JavaCompatibility2`, `FlatPopupFactory`, `FlatStylingSupport`) print `Unresolved method` warnings at build time and would throw `NoSuchMethodError` if reached |
| `java.lang.Module`, `StackWalker`, `ProcessHandle`, hidden classes | absent; the java.desktop port replaces `Module` with `tsb.port.Modul` |
| `sun.net.util.URLUtil`, `sun.misc.SharedSecrets` | `NoSuchMethodError` if reached (URL image loading, old `PlatformLogger` caller info) |
| `java.io.tmpdir` | `/tmp`, not writable on a device — apps use the documents directory |
| class files newer than 61 | refused with a warning; Java 21 language features are not supported |

Consumer-facing documentation of the same facts: RapidFX `mobile/JAVA17.md`, RapidJ `docs/JAVA17.md`.

## Reduced CocoaTouch bindings — generated here, 22 frameworks

Since 23.09.2026 the bindings under `compiler/cocoatouch/src/main/java` are this fork's own: the
22 frameworks the tsbMobile consumers can reach (Foundation, UIKit, CoreGraphics, CoreText,
CoreAnimation, CoreImage, ImageIO, AVFoundation, CoreMedia, CoreVideo, AudioToolbox, CoreAudio,
IOSurface, CoreLocation, CoreMotion, CoreBluetooth, Security, CoreServices, UserNotifications,
UniformTypeIdentifiers, CoreFoundation, dispatch), generated from the iOS 27 SDK with bro-gen and
nothing else. Upstream MobiVM is no longer merged; its YAML history is read for reference.

- `compiler/cocoatouch/bro-gen/` — `generate.sh` (the whole routine), `versions` (Xcode, SDK,
  generator commit, Ruby), the generator itself as the submodule `robovm-bro-gen`
  (`thorstenstueker/robovm-bro-gen`, dkimitsa's bro-gen plus: `BRO_IOS_VERSION` from the
  environment, dictionary accessors merged from every value entry, and members whose type belongs
  to an unbound framework are skipped with a warning instead of written as a bare name),
  `skipped.txt` (those members, 95 today, under version control), `handwritten.txt` (the files the
  generator never touches).
- `compiler/cocoatouch/src/main/bro-gen/*.yaml` — the maintenance surface, one file per framework.
- `compiler/cocoatouch-prune`, in the `process-classes` phase of `compiler/cocoatouch`: keeps the
  closure of `prune/seeds.txt` (the 60 classes the consumers' `swing-ios` name — Swing drawn onto
  a `UIView` through CoreGraphics and CoreText, UIKit for window, scene life cycle, keyboard and
  touch — regenerated by `prune/consumer-seeds.sh`, plus CoreBluetooth, CoreLocation, CoreMotion
  and the capture side of AVFoundation) and deletes the rest. `prune/report.txt` is committed:
  3,083 of 7,084 classes kept, 0 members stripped, 0 phantom references — the last two must stay 0.

The jar in a release is the pruned one; consumers get it finished and tested, nothing of the cut
runs on their machines. Their forms compile against `tsb.mobile` only and never see a binding.
The AOT compiler links reachable classes only, so the cut changes build time and repository size,
not app size. `-Dcocoatouch.prune.skip=true` builds the jar with all 22 frameworks.

How the bindings are refreshed for a new iOS SDK — bump `versions`, `generate.sh`, read three
diffs, compile the consumers, smoke test, release, move the pins — is written down step by step in
`compiler/cocoatouch/prune/UPDATE.md`. Bindings need not be regenerated per iOS release: selectors
are sent by name at run time and globals are optional; regenerate when a new API is needed or Apple
removes a selector in use. Build the module with `-Dmaven.javadoc.skip=true`.

The oslog helper library (`build-natives.sh`, cmake, `RvmCocoaTouch.xcframework`) is not built by
this fork, so `cocoatouch` builds with plain `javac`.

Note: apps linked against the iOS 26+ SDK must adopt the `UIScene` lifecycle (see the
`ios-single-view-no-ib` template), otherwise UIKit traps at launch. A new SDK can add members to
protocols a consumer implements (iOS 27: `grammarCheckingType` on `UITextInputTraits`) — the
consumers' `swing-ios` must compile against the new jar before a release.

## Using RoboVM

There are pre-built plugins for Eclipse and IntelliJ IDEA, for installation take a look at the [homepage](http://mobivm.github.io/).

For using the RoboVM Gradle plugin, follow the [README in the repository](https://github.com/MobiVM/robovm/tree/master/plugins/gradle)

## Communicating
[![Join the chat at https://gitter.im/MobiVM/robovm](https://badges.gitter.im/MobiVM/robovm.svg)](https://gitter.im/MobiVM/robovm?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

## License
See the LICENSE files in the various sub directories. Generally, RoboVM is GPL2,
with the runtime code being Apache 2 for distribution on iOS.
