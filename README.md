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

## Reduced CocoaTouch bindings

This fork ships a reduced `robovm-cocoatouch` with the frameworks needed by RapidFX/RapidJ apps —
Swing drawn onto a `UIView` through CoreGraphics and CoreText, plus UIKit for the window, the scene
life cycle, keyboard and touch input — and by camera, Bluetooth, GPS and motion sensors:

`foundation`, `corefoundation`, `dispatch`, `uikit`, `coregraphics`, `coreanimation`, `coretext`,
`coreimage`, `imageio`, `uniformtypeid`, `usernotifications`, `security`, `coreservices`,
`avfoundation`, `coremedia`, `corevideo`, `audiotoolbox`, `coreaudio`, `iosurface`,
`corelocation`, `coremotion`, `corebluetooth`.

All other framework bindings (Metal, OpenGL ES, SceneKit, SpriteKit, MapKit, Intents, CloudKit,
CoreData, HealthKit, HomeKit, ...) were removed together with the native `oslog` helper library,
so `cocoatouch` builds with plain `javac` and no longer needs `cmake`. Cross references from the
kept bindings into removed frameworks (e.g. `UIViewController` iAd/MediaPlayer extensions,
`NSValue` MapKit/SceneKit values, `CAMetalLayer`) were removed as well.

Note: apps linked against the iOS 26+ SDK must adopt the `UIScene` lifecycle (see the
`ios-single-view-no-ib` template), otherwise UIKit traps at launch.

## Using RoboVM

There are pre-built plugins for Eclipse and IntelliJ IDEA, for installation take a look at the [homepage](http://mobivm.github.io/).

For using the RoboVM Gradle plugin, follow the [README in the repository](https://github.com/MobiVM/robovm/tree/master/plugins/gradle)

## Communicating
[![Join the chat at https://gitter.im/MobiVM/robovm](https://badges.gitter.im/MobiVM/robovm.svg)](https://gitter.im/MobiVM/robovm?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

## License
See the LICENSE files in the various sub directories. Generally, RoboVM is GPL2,
with the runtime code being Apache 2 for distribution on iOS.
